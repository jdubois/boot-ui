import {mount} from '@vue/test-utils'
import {defineComponent, ref, watch} from 'vue'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {call, detail} from '../test/explorerFixtures.js'
import {buildExplorerModel, buildReplay, layoutExplorer} from './explorerModel.js'
import {useExplorerMotion} from './useExplorerMotion.js'

let wrapper, frames, sequence, preference
function start(reduced = false, payload = detail()) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches: reduced,
      addEventListener: (_, callback) => {
        preference = callback
      },
      removeEventListener: vi.fn()
    }))
  )
  const model = ref(buildExplorerModel([], payload))
  const layout = ref(layoutExplorer(model.value)),
    paused = ref(false)
  let motion
  wrapper = mount(
    defineComponent({
      setup() {
        motion = useExplorerMotion(model, layout, paused)
        return () => null
      }
    })
  )
  return {motion, model, paused}
}
function frame(at) {
  const callbacks = [...frames.values()]
  frames.clear()
  callbacks.forEach((callback) => callback(at))
}
beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(0)
  frames = new Map()
  sequence = 0
  vi.stubGlobal(
    'requestAnimationFrame',
    vi.fn((callback) => {
      frames.set(++sequence, callback)
      return sequence
    })
  )
  vi.stubGlobal(
    'cancelAnimationFrame',
    vi.fn((id) => frames.delete(id))
  )
  vi.spyOn(performance, 'now').mockImplementation(() => Date.now())
})
afterEach(() => {
  wrapper?.unmount()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('Explorer replay and motion', () => {
  it('does not animate on mount and finishes every tree step before starting the next', async () => {
    const {motion, model} = start()
    expect(frames.size).toBe(0)
    motion.replay()
    expect(frames.size).toBe(1)
    const timeline = buildReplay(model.value)
    for (const action of timeline.actions) {
      expect(motion.running.value).toBe(true)
      expect(motion.activeRows.value).toEqual([action.rowId])
      expect(motion.effects.value).toHaveLength(1)
      expect(motion.effects.value[0].rowId).toBe(action.rowId)
      expect(motion.effects.value[0].startedAt).toBe(performance.now())
      await vi.advanceTimersByTimeAsync(action.durationMs - 1)
      frame(performance.now())
      expect(motion.effects.value[0].rowId).toBe(action.rowId)
      await vi.advanceTimersByTimeAsync(1)
    }
    expect(motion.running.value).toBe(false)
    expect(frames.size).toBe(0)
    expect(motion.effects.value).toEqual([])
    expect(motion.message.value).toContain('Replay complete')
  })
  it('cancels animation frames, timers, active highlights and announcements on pause/selection cancellation', () => {
    const {motion, paused} = start()
    motion.replay()
    frame(20)
    expect(motion.effects.value.length).toBeGreaterThan(0)
    paused.value = true
    expect(motion.effects.value).toEqual([])
    expect(motion.activeRows.value).toEqual([])
    expect(motion.message.value).toBe('')
    expect(frames.size).toBe(0)
    expect(vi.getTimerCount()).toBe(0)
    paused.value = false
    expect(frames.size).toBe(0)
  })
  it('scrubs to a static recorded observation and does not restart playback', () => {
    const {motion, model} = start()
    motion.replay()
    const position = buildReplay(model.value).actions.find((action) => action.rowId === 'invocation:repository').at + 20
    motion.scrub(position)
    expect(motion.position.value).toBe(position)
    expect(motion.running.value).toBe(false)
    expect(motion.activeRows.value).toEqual(['invocation:repository'])
    expect(motion.effects.value).toEqual([])
    expect(frames.size).toBe(0)
  })
  it('has a static reduced-motion equivalent and cancels on OS preference changes', () => {
    const {motion} = start(true)
    motion.replay()
    expect(frames.size).toBe(0)
    expect(motion.activeRows.value.length).toBeGreaterThan(0)
    expect(motion.message.value).toContain('without motion')
    motion.burst(['event:cache-1'])
    expect(motion.effects.value[0].static).toBe(true)
    preference({matches: false})
    expect(motion.effects.value).toEqual([])
  })
  it('disposes every pending replay and effect on unmount', () => {
    const {motion} = start()
    motion.replay()
    frame(40)
    wrapper.unmount()
    wrapper = null
    expect(frames.size).toBe(0)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('does not drop repeated shared-edge calls when one render frame arrives late', async () => {
    const {motion, model} = start(
      false,
      detail({
        invocations: [
          call('controller', 'request-1', {role: 'CONTROLLER'}),
          ...Array.from({length: 4}, (_, index) =>
            call(`repeat-${index}`, 'controller', {beanName: 'shared', typeName: 'Shared'})
          )
        ],
        related: [],
        links: [],
        sqlReferences: [],
        cacheOperations: []
      })
    )
    const visited = []
    const stop = watch(
      motion.effects,
      (effects) => {
        expect(effects.length).toBeLessThanOrEqual(1)
        if (effects.length) visited.push(effects[0].rowId)
      },
      {flush: 'sync'}
    )
    motion.replay()
    frame(4000)
    expect(visited).toEqual(['event:request-1'])
    await vi.runAllTimersAsync()
    expect(visited).toEqual(buildReplay(model.value).actions.map((action) => action.rowId))
    expect(motion.running.value).toBe(false)
    stop()
  })

  it('sequences Follow live in tree order and never accumulates a backlog from later bursts', async () => {
    const {motion} = start()
    motion.burst(['event:sql-1', 'invocation:repository', 'invocation:controller'])
    expect(motion.effects.value).toHaveLength(1)
    expect(motion.effects.value[0].rowId).toBe('invocation:controller')
    motion.burst(['event:cache-1'])
    await vi.advanceTimersByTimeAsync(750)
    expect(motion.effects.value).toHaveLength(1)
    expect(motion.effects.value[0].rowId).toBe('invocation:repository')
    await vi.advanceTimersByTimeAsync(750)
    expect(motion.effects.value[0].rowId).toBe('event:sql-1')
    await vi.advanceTimersByTimeAsync(750)
    expect(motion.effects.value).toEqual([])
    expect(motion.running.value).toBe(false)
    expect(vi.getTimerCount()).toBe(0)
  })

  it('cancels pending steps if retained evidence disappears during playback', () => {
    const {motion, model} = start()
    motion.replay()
    model.value = buildExplorerModel([], detail({invocations: []}))
    expect(motion.running.value).toBe(false)
    expect(motion.effects.value).toEqual([])
    expect(vi.getTimerCount()).toBe(0)
    expect(motion.message.value).toContain('no longer available')
  })
})
