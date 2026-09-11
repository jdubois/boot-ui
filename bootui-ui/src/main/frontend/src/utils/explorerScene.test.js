import * as THREE from 'three'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {createExplorerScene, MAX_PIXEL_RATIO} from './explorerScene.js'
import {buildExplorerModel, layoutExplorer} from './explorerModel.js'
import {detail, faultToleranceDetail} from '../test/explorerFixtures.js'

let host, view, renderer, frames, nextFrame, observers
function flushFrame(now = 0) {
  const callbacks = [...frames.values()]
  frames.clear()
  callbacks.forEach((callback) => callback(now))
}
function initialize(options = {}) {
  renderer = {
    setPixelRatio: vi.fn(),
    setClearColor: vi.fn(),
    setSize: vi.fn(),
    render: vi.fn(),
    dispose: vi.fn(),
    forceContextLoss: vi.fn()
  }
  view = createExplorerScene(host, {rendererFactory: () => renderer, ...options})
  view.update(layoutExplorer(buildExplorerModel([], detail())))
  return view
}
beforeEach(() => {
  host = document.createElement('div')
  host.tabIndex = 0
  document.body.appendChild(host)
  Object.defineProperty(host, 'clientWidth', {value: 1000})
  Object.defineProperty(host, 'clientHeight', {value: 500})
  Object.defineProperty(document, 'visibilityState', {configurable: true, value: 'visible'})
  vi.stubGlobal('devicePixelRatio', 4)
  frames = new Map()
  nextFrame = 0
  observers = []
  vi.stubGlobal(
    'requestAnimationFrame',
    vi.fn((callback) => {
      frames.set(++nextFrame, callback)
      return nextFrame
    })
  )
  vi.stubGlobal(
    'cancelAnimationFrame',
    vi.fn((id) => frames.delete(id))
  )
  vi.stubGlobal(
    'ResizeObserver',
    class {
      constructor(callback) {
        this.callback = callback
        this.disconnect = vi.fn()
        observers.push(this)
      }
      observe() {}
    }
  )
})
afterEach(() => {
  view?.dispose()
  view = null
  host.remove()
  vi.unstubAllGlobals()
  Object.defineProperty(document, 'visibilityState', {configurable: true, value: 'visible'})
})

describe('Three.js scene lifecycle', () => {
  it('selects from direct scene focus and preserves camera and journey when selection/layout refresh', () => {
    const onSelect = vi.fn()
    initialize({onSelect})
    flushFrame()
    const camera = renderer.render.mock.calls[0][1]
    const position = camera.position.clone()
    const layout = layoutExplorer(buildExplorerModel([], detail()))
    view.select('event:request-1')
    host.focus()
    const right = new KeyboardEvent('keydown', {key: 'ArrowRight', bubbles: true, cancelable: true})
    host.dispatchEvent(right)
    expect(right.defaultPrevented).toBe(true)
    expect(onSelect.mock.lastCall[0].role).toBe('CONTROLLER')
    const selected = onSelect.mock.lastCall[0]
    view.update({...layout, nodes: [...layout.nodes].reverse()}, selected.rowIds[0])
    flushFrame()
    expect(camera.position.equals(position)).toBe(true)
    expect(host.querySelector(`[data-node-id='${selected.id}']`).dataset.selected).toBe('true')
    host.dispatchEvent(new KeyboardEvent('keydown', {key: 'ArrowRight', bubbles: true}))
    expect(onSelect.mock.lastCall[0].role).toBe('SERVICE')
    flushFrame()
    expect(frames.size).toBe(0)
  })
  it('orbits, zooms and resets without selecting, ignores descendant inputs, and removes keyboard handlers', () => {
    const onSelect = vi.fn()
    initialize({onSelect})
    flushFrame()
    host.focus()
    const camera = renderer.render.mock.calls[0][1]
    const initial = camera.position.clone()
    host.dispatchEvent(new KeyboardEvent('keydown', {key: 'ArrowLeft', shiftKey: true, bubbles: true}))
    expect(camera.position.equals(initial)).toBe(false)
    const orbited = camera.position.clone()
    host.dispatchEvent(new KeyboardEvent('keydown', {key: '-', bubbles: true}))
    expect(camera.position.equals(orbited)).toBe(false)
    host.dispatchEvent(new KeyboardEvent('keydown', {key: 'Home', bubbles: true}))
    expect(camera.position.distanceTo(initial)).toBeLessThan(0.0001)
    const input = document.createElement('input')
    host.appendChild(input)
    input.dispatchEvent(new KeyboardEvent('keydown', {key: 'ArrowRight', bubbles: true}))
    expect(onSelect).not.toHaveBeenCalled()
    host.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}))
    expect(document.activeElement).not.toBe(host)
    view.dispose()
    host.dispatchEvent(new KeyboardEvent('keydown', {key: 'ArrowRight', bubbles: true}))
    expect(onSelect).not.toHaveBeenCalled()
    expect(frames.size).toBe(0)
  })
  it('keeps complete selected label text, treats event markup as text, and moves colliding labels into free lanes', () => {
    initialize()
    const name = '<img src=x onerror=alert(1)>longMethodName'.repeat(3)
    view.update(
      {
        nodes: [
          {id: 'a', rowIds: ['a'], label: name, kind: 'FUTURE', stage: 0, x: 0, y: 0, z: 0},
          {id: 'b', rowIds: ['b'], label: 'Neighbor', kind: 'MAIL', stage: 0, x: 0, y: 0, z: 0}
        ],
        edges: []
      },
      'a'
    )
    view.reset()
    flushFrame()
    expect(host.querySelector('img')).toBeNull()
    const label = host.querySelector('[data-node-id="a"]')
    expect(label.textContent.replaceAll('\u200b', '')).toContain(name)
    expect(label.hidden).toBe(false)
    const neighbor = host.querySelector('[data-node-id="b"]')
    expect(neighbor.hidden).toBe(false)
    expect(neighbor.style.transform).not.toBe(label.style.transform)
    expect([...host.querySelectorAll('.explorer-label-leader')].some((leader) => !leader.hidden)).toBe(true)
  })
  it('frames every front and back model in a dense circuit-breaker journey', () => {
    initialize()
    const layout = layoutExplorer(buildExplorerModel([], faultToleranceDetail()))
    view.update(layout, 'event:request-1')
    view.reset()
    flushFrame()
    const camera = renderer.render.mock.lastCall[1]
    for (const node of layout.nodes) {
      for (const dx of [-1.5, 1.5]) {
        for (const dz of [-1, 1]) {
          const point = new THREE.Vector3(node.x + dx, node.y, node.z + dz).project(camera)
          expect(Math.abs(point.x)).toBeLessThan(0.85)
          expect(Math.abs(point.y)).toBeLessThan(0.85)
        }
      }
      expect(
        [...host.querySelectorAll('[data-node-id]')].find((label) => label.dataset.nodeId === node.id).hidden
      ).toBe(false)
    }
  })

  it('creates a real Three scene with bounded DPR and demand-renders once, never idles in an animation loop', () => {
    initialize()
    flushFrame()
    expect(renderer.setPixelRatio).toHaveBeenCalledWith(MAX_PIXEL_RATIO)
    expect(renderer.render.mock.calls[0][0]).toBeInstanceOf(THREE.Scene)
    expect(renderer.render.mock.calls[0][1]).toBeInstanceOf(THREE.PerspectiveCamera)
    expect(host.querySelectorAll('canvas')).toHaveLength(1)
    expect(frames.size).toBe(0)
    view.reset()
    expect(frames.size).toBe(1)
    flushFrame()
    expect(frames.size).toBe(0)
  })
  it('disposes all GPU geometry/materials, controls, observers and pending frames exactly once', () => {
    const geometryDispose = vi.spyOn(THREE.BufferGeometry.prototype, 'dispose')
    const materialDispose = vi.spyOn(THREE.Material.prototype, 'dispose')
    initialize()
    view.dispose()
    view.dispose()
    expect(geometryDispose.mock.calls.length).toBeGreaterThanOrEqual(6)
    expect(materialDispose.mock.calls.length).toBeGreaterThan(3)
    expect(renderer.dispose).toHaveBeenCalledTimes(1)
    expect(renderer.forceContextLoss).toHaveBeenCalledTimes(1)
    expect(observers[0].disconnect).toHaveBeenCalledTimes(1)
    expect(frames.size).toBe(0)
    expect(host.children).toHaveLength(0)
  })
  it('cleans up on context loss and reports a usable fallback instead of rendering into a dead context', () => {
    const onFailure = vi.fn()
    initialize({onFailure})
    const lost = new Event('webglcontextlost', {cancelable: true})
    host.querySelector('canvas').dispatchEvent(lost)
    expect(lost.defaultPrevented).toBe(true)
    expect(onFailure).toHaveBeenCalledWith(expect.stringContaining('context was lost'))
    expect(renderer.dispose).toHaveBeenCalledTimes(1)
    expect(host.querySelector('canvas')).toBeNull()
    expect(frames.size).toBe(0)
  })
  it('fails before allocating GPU resources when WebGL2 is unavailable', () => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null)
    expect(() => createExplorerScene(host)).toThrow('WebGL2 is unavailable')
    expect(host.children).toHaveLength(0)
  })
  it('renders moving effects only until their bound, while reduced motion uses one static frame', () => {
    initialize()
    flushFrame()
    const nodeId = layoutExplorer(buildExplorerModel([], detail())).nodes[0].id
    view.setEffects([{fromId: nodeId, toId: nodeId, tone: 'slow', mode: 'forward', startedAt: 0, durationMs: 1350}])
    flushFrame(10)
    expect(frames.size).toBe(1)
    flushFrame(1400)
    expect(frames.size).toBe(0)
    view.setEffects([{fromId: nodeId, toId: nodeId, tone: 'ok', mode: 'forward', static: true}])
    flushFrame(1500)
    expect(frames.size).toBe(0)
    view.reset()
    Object.defineProperty(document, 'visibilityState', {configurable: true, value: 'hidden'})
    document.dispatchEvent(new Event('visibilitychange'))
    expect(frames.size).toBe(0)
  })
})
