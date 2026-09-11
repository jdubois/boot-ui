import {onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {createFlowQueue, MAX_CONCURRENT_PULSES} from './serviceMap.js'
import {buildReplay, effectsForRows} from './explorerModel.js'

export function useExplorerMotion(model, layout, paused) {
  const effects = ref([]),
    activeRows = ref([]),
    running = ref(false),
    position = ref(0)
  const message = ref(''),
    reduced = ref(false)
  let frame = null,
    started = 0,
    timeline = null,
    currentAction = null,
    actionIndex = 0,
    media,
    live = false
  const queue = createFlowQueue({maxConcurrent: 1})
  const unsubscribe = queue.subscribe((value) => {
    effects.value = [...value]
    if (!value.length) {
      if (running.value) advance()
      else activeRows.value = []
    }
  })

  function complete() {
    if (frame != null) cancelAnimationFrame(frame)
    frame = null
    position.value = timeline.durationMs
    timeline = null
    currentAction = null
    running.value = false
    activeRows.value = []
    message.value = live ? '' : 'Replay complete. Recorded evidence remains available in the execution tree.'
  }

  function cancel() {
    if (frame != null) cancelAnimationFrame(frame)
    frame = null
    timeline = null
    currentAction = null
    running.value = false
    queue.clear()
    activeRows.value = []
    message.value = ''
  }
  function burst(ids) {
    if (paused.value || running.value) return
    cancel()
    const wanted = new Set(ids)
    const fresh = model.value.rows.filter((row) => wanted.has(row.id)).slice(0, MAX_CONCURRENT_PULSES)
    begin(buildReplay(model.value, {rowIds: fresh.map((row) => row.id)}), true)
  }
  function advance() {
    if (actionIndex >= timeline.actions.length) {
      complete()
      return
    }
    currentAction = timeline.actions[actionIndex++]
    const [effect] = effectsForRows(model.value, [currentAction.rowId], layout.value, currentAction.phase)
    if (!effect) {
      cancel()
      message.value = 'Playback stopped because its evidence is no longer available.'
      return
    }
    started = performance.now()
    position.value = currentAction.at
    activeRows.value = [currentAction.rowId]
    queue.enqueue([
      {
        ...effect,
        id: `${effect.id}:${currentAction.phase}`,
        durationMs: currentAction.durationMs,
        startedAt: started
      }
    ])
    if (frame == null) frame = requestAnimationFrame(tick)
  }
  function tick(now) {
    frame = null
    if (!running.value || !currentAction) return
    position.value = Math.min(timeline.durationMs, currentAction.at + Math.min(currentAction.durationMs, now - started))
    frame = requestAnimationFrame(tick)
  }
  function replay() {
    cancel()
    if (paused.value || !model.value.rows.length) return
    begin(buildReplay(model.value), false)
  }
  function begin(next, follow) {
    if (!next.actions.length) return
    live = follow
    position.value = 0
    actionIndex = 0
    if (reduced.value) {
      position.value = next.durationMs
      activeRows.value = [...new Set(next.actions.map((action) => action.rowId))]
      if (live) {
        const first = next.actions[0]
        const [effect] = effectsForRows(model.value, [first.rowId], layout.value, first.phase)
        if (effect) queue.enqueue([{...effect, startedAt: performance.now(), static: true}])
      }
      message.value =
        'Recorded journey highlighted without motion. The tree describes operation directions and observed failures.'
      return
    }
    timeline = next
    running.value = true
    message.value = live
      ? 'Following new evidence in execution-tree order.'
      : 'Replaying the execution tree one step at a time. Animation pacing is not recorded latency.'
    advance()
  }
  function scrub(value) {
    cancel()
    const next = buildReplay(model.value)
    position.value = Math.max(0, Math.min(next.durationMs, Number(value) || 0))
    const before = next.actions.filter((action) => action.at <= position.value)
    activeRows.value = before.length ? [before[before.length - 1].rowId] : []
  }
  function preference(event) {
    reduced.value = event.matches
    cancel()
  }
  watch(
    paused,
    (value) => {
      if (value) cancel()
    },
    {flush: 'sync'}
  )
  watch(
    model,
    (next) => {
      if (timeline && timeline.actions.some((action) => !next.byId.has(action.rowId))) {
        cancel()
        message.value = 'Playback stopped because some evidence is no longer available.'
      }
    },
    {flush: 'sync'}
  )
  onMounted(() => {
    media = window.matchMedia?.('(prefers-reduced-motion: reduce)')
    reduced.value = media?.matches ?? false
    media?.addEventListener('change', preference)
  })
  onBeforeUnmount(() => {
    cancel()
    unsubscribe()
    media?.removeEventListener('change', preference)
  })
  return {effects, activeRows, running, position, message, reduced, cancel, burst, replay, scrub}
}
