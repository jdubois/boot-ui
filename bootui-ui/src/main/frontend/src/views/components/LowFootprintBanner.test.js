import {flushPromises, mount} from '@vue/test-utils'
import {ref} from 'vue'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import LowFootprintBanner from './LowFootprintBanner.vue'
import {resetLowFootprintForTests} from '../../utils/useLowFootprint.js'

function respond(body, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(body), {status, headers: {'content-type': 'application/json'}}))
}

function liveMemoryPanel(overrides = {}) {
  return {id: 'live-memory', enabled: true, available: true, ...overrides}
}

describe('LowFootprintBanner', () => {
  beforeEach(() => resetLowFootprintForTests())
  afterEach(() => vi.unstubAllGlobals())

  it('renders when the mode is active', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => respond({active: true, components: [], warnings: []}))
    )
    const wrapper = mount(LowFootprintBanner, {
      global: {provide: {panels: ref({panels: [liveMemoryPanel()]})}}
    })
    await flushPromises()
    expect(wrapper.get('[data-testid="low-footprint-banner"]').text()).toContain('Paused by low-footprint mode')
  })

  it('renders nothing when the mode is off', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => respond({active: false, components: [], warnings: []}))
    )
    const wrapper = mount(LowFootprintBanner, {
      global: {provide: {panels: ref({panels: [liveMemoryPanel()]})}}
    })
    await flushPromises()
    expect(wrapper.find('[data-testid="low-footprint-banner"]').exists()).toBe(false)
  })

  it('loads the mode after the panel manifest arrives', async () => {
    const fetchMock = vi.fn(() => respond({active: true, components: [], warnings: []}))
    vi.stubGlobal('fetch', fetchMock)
    const panels = ref(null)
    const wrapper = mount(LowFootprintBanner, {global: {provide: {panels}}})

    await flushPromises()
    expect(fetchMock).not.toHaveBeenCalled()

    panels.value = {panels: [liveMemoryPanel()]}
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledOnce()
    expect(wrapper.find('[data-testid="low-footprint-banner"]').exists()).toBe(true)
  })

  it.each([
    ['disabled', {enabled: false}],
    ['unavailable', {available: false}]
  ])('does not fetch status when Live Memory is %s', async (_, panel) => {
    const fetchMock = vi.fn(() => respond({active: true, components: [], warnings: []}))
    vi.stubGlobal('fetch', fetchMock)
    mount(LowFootprintBanner, {
      global: {provide: {panels: ref({panels: [liveMemoryPanel(panel)]})}}
    })

    await flushPromises()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('uses the Live Memory read-only reason and prevents a resume request', async () => {
    const fetchMock = vi.fn(() => respond({active: true, components: [], warnings: []}))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(LowFootprintBanner, {
      global: {
        provide: {
          panels: ref({panels: [liveMemoryPanel({readOnly: true, readOnlyReason: 'Disabled by local policy.'})]})
        }
      }
    })

    await flushPromises()
    const button = wrapper.get('[data-testid="low-footprint-banner-off"]')
    expect(button.element.disabled).toBe(true)
    expect(button.attributes('title')).toBe('Disabled by local policy.')
    expect(wrapper.text()).toContain('Disabled by local policy.')
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('turns the mode off and announces success from the banner', async () => {
    const fetchMock = vi.fn((_, init) => respond({active: init?.method !== 'POST', components: [], warnings: []}))
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(LowFootprintBanner, {
      attachTo: document.body,
      global: {provide: {panels: ref({panels: [liveMemoryPanel()]})}}
    })
    await flushPromises()
    await wrapper.get('[data-testid="low-footprint-banner-off"]').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      'api/live-memory/footprint',
      expect.objectContaining({method: 'POST', body: JSON.stringify({enabled: false})})
    )
    expect(wrapper.find('[data-testid="low-footprint-banner"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="low-footprint-banner-off"]').exists()).toBe(false)
    const success = wrapper.get('[data-testid="low-footprint-banner-success"]')
    expect(success.text()).toContain('BootUI recording has resumed')
    expect(document.activeElement).toBe(success.element)
    expect(wrapper.findAll('[role="status"]')).toHaveLength(1)
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(0)
    wrapper.unmount()
  })

  it('shows failed component resumes after the mode turns off', async () => {
    const fetchMock = vi.fn((_, init) =>
      respond(
        init?.method === 'POST'
          ? {active: false, components: [], warnings: ['Could not resume SQL recording.']}
          : {active: true, components: [], warnings: []}
      )
    )
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(LowFootprintBanner, {
      attachTo: document.body,
      global: {provide: {panels: ref({panels: [liveMemoryPanel()]})}}
    })
    await flushPromises()
    await wrapper.get('[data-testid="low-footprint-banner-off"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="low-footprint-banner"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="low-footprint-banner-off"]').exists()).toBe(false)
    const error = wrapper.get('[data-testid="low-footprint-banner-error"]')
    expect(error.text()).toContain('Could not resume SQL recording.')
    expect(document.activeElement).toBe(error.element)
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(1)
    expect(wrapper.findAll('[role="status"]')).toHaveLength(0)
    wrapper.unmount()
  })

  it('keeps the active banner and displays the server reason when resume fails', async () => {
    const fetchMock = vi.fn((_, init) =>
      init?.method === 'POST'
        ? respond({reason: 'Resume is blocked by policy.'}, 403)
        : respond({active: true, components: [], warnings: []})
    )
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(LowFootprintBanner, {
      global: {provide: {panels: ref({panels: [liveMemoryPanel()]})}}
    })
    await flushPromises()
    await wrapper.get('[data-testid="low-footprint-banner-off"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="low-footprint-banner"]').exists()).toBe(true)
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(1)
    expect(wrapper.findAll('[role="status"]')).toHaveLength(1)
    expect(wrapper.get('[role="alert"]').text()).toContain('Resume is blocked by policy.')
    expect(wrapper.find('[role="status"] [role="alert"]').exists()).toBe(false)
  })
})
