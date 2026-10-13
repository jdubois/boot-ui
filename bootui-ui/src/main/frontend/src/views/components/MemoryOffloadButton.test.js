import {flushPromises, mount} from '@vue/test-utils'
import {ref} from 'vue'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

const confirm = vi.fn()
vi.mock('../../utils/useConfirm.js', () => ({useConfirm: () => ({confirm})}))

import MemoryOffloadButton from './MemoryOffloadButton.vue'
import {resetLowFootprintForTests} from '../../utils/useLowFootprint.js'

const REPORT = {
  heapUsedBeforeBytes: 200 * 1024 * 1024,
  heapUsedAfterBytes: 120 * 1024 * 1024,
  reclaimedBytes: 80 * 1024 * 1024,
  gcRequested: true,
  explicitGcDisabled: false,
  entriesCleared: 1234,
  stores: [
    {id: 'runtime-journal', label: 'Runtime journal events', cleared: true, entriesCleared: 1200, failure: null},
    {id: 'sql-trace', label: 'SQL Trace statements', cleared: true, entriesCleared: 34, failure: null}
  ],
  durationMillis: 42
}

const OFF = {active: false, sinceEpochMillis: null, agentAttached: false, components: [], warnings: [], offload: null}
const COMPONENTS = [
  {id: 'runtime-journal', label: 'Runtime journal recording', suspended: true, failure: null},
  {id: 'log-tail', label: 'Log Tail capture', suspended: true, failure: null}
]
const ON = {...OFF, active: true, sinceEpochMillis: 1, components: COMPONENTS, offload: REPORT}

function manifest(livePanel) {
  return ref({platform: 'spring-boot', panels: livePanel ? [livePanel] : []})
}

function livePanel(overrides = {}) {
  return {id: 'live-memory', enabled: true, available: true, readOnly: false, readOnlyReason: null, ...overrides}
}

function respond(body, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(body), {status, headers: {'content-type': 'application/json'}}))
}

function mountButton({panels = manifest(livePanel()), props = {}} = {}) {
  return mount(MemoryOffloadButton, {props, global: {provide: {panels}}, attachTo: document.body})
}

describe('MemoryOffloadButton', () => {
  let fetchMock

  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=test-token'
    resetLowFootprintForTests()
    fetchMock = vi.fn((url, init) => respond(init?.method === 'POST' ? ON : OFF))
    vi.stubGlobal('fetch', fetchMock)
    confirm.mockReset()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    document.body.innerHTML = ''
  })

  it('only reads the status on mount and keeps the explanation collapsed', async () => {
    const wrapper = mountButton()
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][1]?.method).toBeUndefined()
    expect(String(fetchMock.mock.calls[0][0])).toContain('api/live-memory/footprint')
    const toggle = wrapper.get('[data-testid="memory-offload"]')
    expect(toggle.text()).toContain('Low-footprint mode')
    expect(toggle.attributes('aria-pressed')).toBe('false')
    const info = wrapper.get('[data-testid="memory-offload-info"]')
    expect(info.attributes('aria-expanded')).toBe('false')
    expect(info.attributes('aria-controls')).toBe(wrapper.get('[data-testid="memory-offload-panel"]').attributes('id'))
    expect(wrapper.get('[data-testid="memory-offload-panel"]').isVisible()).toBe(false)
  })

  it('expands an explanation of what is cleared, what is kept, and that GC is only a hint', async () => {
    const wrapper = mountButton()

    await wrapper.get('[data-testid="memory-offload-info"]').trigger('click')

    const panel = wrapper.get('[data-testid="memory-offload-panel"]')
    expect(panel.isVisible()).toBe(true)
    expect(wrapper.get('[data-testid="memory-offload-info"]').attributes('aria-expanded')).toBe('true')
    expect(panel.text()).toContain('Runtime journal')
    expect(panel.text()).toContain('pauses BootUI')
    expect(panel.text()).toContain('Kept: settings, configuration overrides')
    expect(panel.text()).toContain('only a hint')
    expect(panel.text()).toContain('-XX:+DisableExplicitGC')

    await panel.trigger('keydown', {key: 'Escape'})
    expect(wrapper.get('[data-testid="memory-offload-panel"]').isVisible()).toBe(false)
  })

  it('does nothing when the confirmation is cancelled', async () => {
    confirm.mockResolvedValue(false)
    const wrapper = mountButton()

    await wrapper.get('[data-testid="memory-offload"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith(expect.objectContaining({danger: true, irreversible: true}))
    expect(fetchMock.mock.calls.every(([, init]) => init?.method === undefined)).toBe(true)
    expect(wrapper.emitted('offloaded')).toBeUndefined()
  })

  it('posts the toggle, shows the heap result and paused services, and lets the host refresh', async () => {
    confirm.mockResolvedValue(true)
    const wrapper = mountButton({props: {followUp: 'Run memory checks again.'}})

    await wrapper.get('[data-testid="memory-offload"]').trigger('click')
    await flushPromises()

    const [url, init] = fetchMock.mock.calls.find(([, i]) => i?.method === 'POST')
    expect(String(url)).toContain('api/live-memory/footprint')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body)).toEqual({enabled: true})
    expect(new Headers(init.headers).get('X-XSRF-TOKEN')).toBe('test-token')
    const result = wrapper.get('[data-testid="memory-offload-result"]')
    expect(result.attributes('role')).toBe('status')
    expect(result.text()).toContain('200.0 MB')
    expect(result.text()).toContain('120.0 MB')
    expect(result.text()).toContain('80.0 MB reclaimed')
    expect(result.text()).toContain('cleared from 2 stores')
    expect(result.text()).toContain('Run memory checks again.')
    expect(wrapper.emitted('offloaded')[0][0]).toEqual(REPORT)
    expect(wrapper.get('[data-testid="memory-offload"]').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('[data-testid="memory-offload"]').text()).toContain('Low-footprint: on')
    expect(wrapper.get('[data-testid="memory-offload-components"]').text()).toContain('Log Tail capture')
  })

  it('resumes without confirmation when the mode is already on', async () => {
    fetchMock.mockImplementation((url, init) => respond(init?.method === 'POST' ? OFF : ON))
    const wrapper = mountButton()
    await flushPromises()
    expect(wrapper.get('[data-testid="memory-offload"]').attributes('aria-pressed')).toBe('true')

    await wrapper.get('[data-testid="memory-offload"]').trigger('click')
    await flushPromises()

    expect(confirm).not.toHaveBeenCalled()
    const post = fetchMock.mock.calls.find(([, i]) => i?.method === 'POST')
    expect(JSON.parse(post[1].body)).toEqual({enabled: false})
    expect(wrapper.get('[data-testid="memory-offload"]').attributes('aria-pressed')).toBe('false')
  })

  it('warns about the Java agent before and after turning the mode on', async () => {
    const warning = 'The BootUI Java agent is attached. For better results, run these panels without the agent.'
    fetchMock.mockImplementation((url, init) =>
      respond(
        init?.method === 'POST' ? {...ON, agentAttached: true, warnings: [warning]} : {...OFF, agentAttached: true}
      )
    )
    confirm.mockResolvedValue(true)
    const wrapper = mountButton()
    await flushPromises()

    await wrapper.get('[data-testid="memory-offload"]').trigger('click')
    await flushPromises()

    expect(confirm.mock.calls[0][0].message).toContain('without the agent')
    expect(wrapper.get('[data-testid="memory-offload-agent"]').text()).toContain('without the agent')
  })

  it('warns when explicit GC is disabled and names stores that could not be cleared', async () => {
    confirm.mockResolvedValue(true)
    fetchMock.mockImplementation((url, init) =>
      respond(
        init?.method === 'POST'
          ? {
              ...ON,
              offload: {
                ...REPORT,
                reclaimedBytes: 0,
                explicitGcDisabled: true,
                stores: [
                  ...REPORT.stores,
                  {id: 'broken', label: 'Broken store', cleared: false, entriesCleared: 0, failure: 'boom'}
                ]
              }
            }
          : OFF
      )
    )
    const wrapper = mountButton()

    await wrapper.get('[data-testid="memory-offload"]').trigger('click')
    await flushPromises()

    const text = wrapper.get('[data-testid="memory-offload-result"]').text()
    expect(text).toContain('no measurable reduction yet')
    expect(text).toContain('collection request was ignored')
    expect(text).toContain('Could not clear: Broken store')
  })

  it('surfaces the access-filter reason when the server refuses the action', async () => {
    confirm.mockResolvedValue(true)
    fetchMock.mockImplementation((url, init) =>
      init?.method === 'POST'
        ? respond(
            {error: 'BootUI panel access denied', panel: 'live-memory', reason: 'Disabled by bootui.read-only=true'},
            403
          )
        : respond(OFF)
    )
    const wrapper = mountButton()

    await wrapper.get('[data-testid="memory-offload"]').trigger('click')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('bootui.read-only=true')
    expect(wrapper.emitted('offloaded')).toBeUndefined()
  })

  it('is disabled with the read-only reason when the Live Memory panel is read-only', async () => {
    const wrapper = mountButton({
      panels: manifest(livePanel({readOnly: true, readOnlyReason: 'Disabled by bootui.read-only=true'}))
    })

    const button = wrapper.get('[data-testid="memory-offload"]')
    expect(button.attributes('disabled')).toBeDefined()
    expect(button.attributes('title')).toBe('Disabled by bootui.read-only=true')
    await wrapper.get('[data-testid="memory-offload-info"]').trigger('click')
    expect(wrapper.get('[data-testid="memory-offload-panel"]').text()).toContain('bootui.read-only=true')
    expect(confirm).not.toHaveBeenCalled()
  })

  it.each([
    ['disabled', {enabled: false}],
    ['unavailable', {available: false}]
  ])('is hidden when the Live Memory panel is %s', (_, overrides) => {
    const wrapper = mountButton({panels: manifest(livePanel(overrides))})

    expect(wrapper.find('[data-testid="memory-offload"]').exists()).toBe(false)
  })

  it.each([
    ['still loading', ref(null)],
    ['missing the Live Memory panel', manifest(null)]
  ])('fails closed while the panel manifest is %s', async (_, panels) => {
    const wrapper = mountButton({panels})

    expect(wrapper.find('[data-testid="memory-offload"]').exists()).toBe(false)

    panels.value = {platform: 'spring-boot', panels: [livePanel()]}
    await flushPromises()
    expect(wrapper.find('[data-testid="memory-offload"]').exists()).toBe(true)
  })
})
