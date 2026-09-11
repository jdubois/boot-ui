import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import Explorer from './Explorer.vue'
import {detail, event, report} from '../test/explorerFixtures.js'

let wrapper
function start(props = {}, stubScene = true) {
  wrapper = mount(Explorer, {
    props,
    attachTo: document.body,
    global: {
      stubs: {
        RouterLink: {template: '<a><slot /></a>'},
        ...(stubScene ? {ExplorerScene: {template: '<div data-testid="scene-stub" />'}} : {})
      }
    }
  })
  return wrapper
}
function stubFetch(payload = detail()) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url) =>
      Promise.resolve({
        ok: true,
        json: async () => (url.includes('/events/') ? payload : report([event(), event('future', {type: 'FUTURE'})]))
      })
    )
  )
}
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  vi.unstubAllGlobals()
})

describe('Explorer view', () => {
  it('uses the explicit unsupported message and makes no Explorer request', async () => {
    const fetch = vi.fn()
    vi.stubGlobal('fetch', fetch)
    start({panel: {id: 'explorer', available: false, unavailableReason: 'Spring MVC JVM only'}})
    await flushPromises()
    expect(wrapper.text()).toContain('Spring MVC JVM only')
    expect(fetch).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="scene-stub"]').exists()).toBe(false)
  })
  it('selects every event including unknown types, renders exact tree, and offers canonical source navigation', async () => {
    stubFetch()
    start()
    await flushPromises()
    expect(wrapper.findAll('.explorer-event')).toHaveLength(2)
    expect(wrapper.find('#explorer-type').text()).toContain('FUTURE')
    await wrapper.find('[data-event-id="request-1"]').trigger('click')
    await flushPromises()
    const sql = wrapper.find('[data-row-id="event:sql-1"]')
    expect(sql.attributes('aria-level')).toBe('5')
    await sql.trigger('click')
    expect(wrapper.find('.explorer-inspector').text()).toContain('Exact capture-time invocation')
    expect(wrapper.find('.explorer-inspector').text()).toContain('Open in SQL Trace')
    const root = wrapper.find('[role="treeitem"]')
    root.element.focus()
    await root.trigger('keydown', {key: 'ArrowDown'})
    expect(document.activeElement).toBe(wrapper.findAll('[role="treeitem"]')[1].element)
    expect(wrapper.findAll('[role="treeitem"][tabindex="0"]')).toHaveLength(1)
  })
  it('retains canonical evidence and a meaningful explanation for expired historical detail', async () => {
    stubFetch({
      found: false,
      event: null,
      related: [],
      invocations: [],
      warnings: ['Bean/detail evidence expired'],
      partial: true
    })
    start()
    await flushPromises()
    await wrapper.find('[data-event-id="request-1"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Detail expired or unavailable')
    expect(wrapper.find('[role="tree"]').text()).toContain('GET /orders/{id}')
    expect(wrapper.find('.explorer-inspector').text()).toContain('80 ms')
  })
  it('renders and selects timestamped versions independently when a source id is reused', async () => {
    const newest = event('reused', {timestamp: 2000, summary: 'new process'})
    const oldest = event('reused', {timestamp: 1000, summary: 'old process'})
    const fetch = vi.fn((url) =>
      Promise.resolve({
        ok: true,
        json: async () =>
          url.includes('/events/')
            ? detail({event: url.includes('timestamp=1000') ? oldest : newest, related: []})
            : report([newest, oldest])
      })
    )
    vi.stubGlobal('fetch', fetch)
    start()
    await flushPromises()
    expect(wrapper.findAll('[data-event-id="reused"]')).toHaveLength(2)
    expect(wrapper.findAll('[data-row-id^="event:"]')).toHaveLength(2)
    await wrapper.findAll('[data-event-id="reused"]')[1].trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[data-event-id="reused"]').map((button) => button.attributes('aria-pressed'))).toEqual([
      'false',
      'true'
    ])
    expect(fetch.mock.calls.map(([url]) => url)).toContain('api/explorer/events/reused?timestamp=1000')
  })
  it('opens captured detail when an overview event is selected through the keyboard tree', async () => {
    stubFetch()
    start()
    await flushPromises()
    await wrapper.find('[data-row-id="event:request-1"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-event-id="request-1"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.find('[data-row-id="invocation:repository"]').exists()).toBe(true)
  })
  it('makes the tree primary on mobile without loading a renderer until explicitly requested', async () => {
    vi.stubGlobal(
      'matchMedia',
      vi.fn((query) => ({
        matches: query.includes('max-width'),
        addEventListener: vi.fn(),
        removeEventListener: vi.fn()
      }))
    )
    stubFetch()
    start()
    await flushPromises()
    expect(wrapper.find('[data-testid="scene-stub"]').exists()).toBe(false)
    expect(wrapper.find('[role="tree"]').exists()).toBe(true)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === 'Show 3D')
      .trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="scene-stub"]').exists()).toBe(true)
  })
  it('keeps the same keyboard tree on actual WebGL2 initialization failure', async () => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null)
    stubFetch()
    start({}, false)
    await vi.waitFor(() => expect(wrapper.text()).toContain('Execution tree available'))
    expect(wrapper.find('[role="tree"]').exists()).toBe(true)
    expect(wrapper.findAll('canvas')).toHaveLength(0)
    expect(wrapper.findAll('button').some((button) => button.text() === 'Retry 3D')).toBe(true)
  })
})
