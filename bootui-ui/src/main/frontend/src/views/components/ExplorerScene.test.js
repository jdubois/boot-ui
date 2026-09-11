import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import ExplorerScene from './ExplorerScene.vue'
import {createExplorerScene} from '../../utils/explorerScene.js'

vi.mock('../../utils/explorerScene.js', () => ({createExplorerScene: vi.fn()}))
let wrapper
const node = {id: 'bean', rowIds: ['call:1'], kind: 'CALL', role: 'SERVICE', label: 'OrderService', stage: 2}
afterEach(() => {
  wrapper?.unmount()
  delete HTMLElement.prototype.requestFullscreen
  delete document.exitFullscreen
  delete document.fullscreenElement
})
function mockFullscreen() {
  Object.defineProperty(document, 'fullscreenElement', {configurable: true, writable: true, value: null})
  HTMLElement.prototype.requestFullscreen = vi.fn(async function () {
    document.fullscreenElement = this
    document.dispatchEvent(new Event('fullscreenchange'))
  })
  document.exitFullscreen = vi.fn(async () => {
    document.fullscreenElement = null
    document.dispatchEvent(new Event('fullscreenchange'))
  })
  vi.mocked(createExplorerScene).mockReturnValue({
    update: vi.fn(),
    select: vi.fn(),
    setEffects: vi.fn(),
    reset: vi.fn(),
    dispose: vi.fn()
  })
}
describe('accessible 3D scene container', () => {
  it('exposes one focusable canvas control and one full selected description, with no reset on inspector updates', async () => {
    const scene = {update: vi.fn(), select: vi.fn(), setEffects: vi.fn(), reset: vi.fn(), dispose: vi.fn()}
    vi.mocked(createExplorerScene).mockReturnValue(scene)
    wrapper = mount(ExplorerScene, {props: {layout: {nodes: [node], edges: []}}, attachTo: document.body})
    const host = wrapper.find('[role="application"]')
    expect(host.attributes('tabindex')).toBe('0')
    expect(host.attributes('aria-label')).toBe('3D captured journey')
    expect(wrapper.findAll('[aria-live="polite"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('Shift + arrows orbit')
    await wrapper.setProps({selectedId: 'call:1'})
    expect(scene.select).toHaveBeenCalledWith('call:1', [])
    expect(scene.reset).not.toHaveBeenCalled()
    expect(wrapper.find('[aria-live="polite"]').text()).toContain('Service: OrderService. 1 observation.')
    const options = vi.mocked(createExplorerScene).mock.calls[0][1]
    options.onSelect(node)
    expect(wrapper.emitted('select')[0]).toEqual([node])
    await wrapper.setProps({frameKey: 'another-journey'})
    expect(scene.reset).toHaveBeenCalledOnce()
  })
  it('removes the failed canvas from tab order and retains retry and complete selection text', async () => {
    vi.mocked(createExplorerScene).mockImplementation(() => {
      throw new Error('WebGL2 unavailable')
    })
    wrapper = mount(ExplorerScene, {props: {layout: {nodes: [node], edges: []}, selectedId: 'call:1'}})
    await flushPromises()
    expect(wrapper.find('[role="application"]').attributes('tabindex')).toBe('-1')
    expect(wrapper.find('[aria-live="polite"]').text()).toContain('OrderService')
    expect(wrapper.find('button').text()).toBe('Retry 3D')
    expect(wrapper.emitted('unavailable')).toHaveLength(1)
  })
  it('enters full screen, contains keyboard focus, and restores the button after Escape', async () => {
    mockFullscreen()
    wrapper = mount(ExplorerScene, {props: {layout: {nodes: [node], edges: []}}, attachTo: document.body})
    await flushPromises()
    const button = wrapper.findAll('button').find((button) => button.text() === 'Full screen')
    await button.trigger('click')
    await flushPromises()
    expect(document.fullscreenElement).toBe(wrapper.element)
    expect(button.text()).toBe('Exit full screen')
    expect(button.attributes('aria-pressed')).toBe('true')
    const host = wrapper.find('[role="application"]')
    expect(document.activeElement).toBe(host.element)
    const previous = new KeyboardEvent('keydown', {key: 'Tab', shiftKey: true, bubbles: true, cancelable: true})
    host.element.dispatchEvent(previous)
    expect(previous.defaultPrevented).toBe(true)
    expect(document.activeElement).toBe(button.element)
    await button.trigger('keydown', {key: 'Tab'})
    expect(document.activeElement).toBe(host.element)
    await host.trigger('keydown', {key: 'Escape'})
    await flushPromises()
    expect(document.exitFullscreen).toHaveBeenCalledOnce()
    expect(button.text()).toBe('Full screen')
    expect(document.activeElement).toBe(button.element)
  })
  it('surfaces fullscreen denial without hiding the scene or claiming success', async () => {
    mockFullscreen()
    HTMLElement.prototype.requestFullscreen.mockRejectedValue(new Error('Permission denied'))
    wrapper = mount(ExplorerScene, {props: {layout: {nodes: [node], edges: []}}})
    await flushPromises()
    const button = wrapper.findAll('button').find((button) => button.text() === 'Full screen')
    await button.trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').text()).toContain('Permission denied')
    expect(button.attributes('aria-pressed')).toBe('false')
    expect(wrapper.find('[role="application"]').exists()).toBe(true)
  })
  it('keeps an exit control available if WebGL fails while fullscreen', async () => {
    mockFullscreen()
    wrapper = mount(ExplorerScene, {props: {layout: {nodes: [node], edges: []}}})
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Full screen')
      .trigger('click')
    await flushPromises()
    vi.mocked(createExplorerScene).mock.lastCall[1].onFailure('WebGL context lost')
    await flushPromises()
    expect(wrapper.text()).toContain('Exit full screen')
    expect(wrapper.text()).toContain('Execution tree available')
    expect(wrapper.find('[role="application"]').attributes('tabindex')).toBe('-1')
  })
})
