import {flushPromises, mount} from '@vue/test-utils'
import {ref} from 'vue'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import AgentSensorToggle from './AgentSensorToggle.vue'

function toggle(overrides = {}) {
  return {
    id: 'environment',
    configured: false,
    enabled: false,
    overridden: false,
    state: 'off',
    optInReason: 'Off by default: it advises System.getProperty.',
    available: true,
    unavailableReason: null,
    ...overrides
  }
}

function manifest(overrides = {}) {
  return ref({
    platform: 'spring-boot',
    panels: [{id: 'java-agent', enabled: true, available: true, readOnly: false, readOnlyReason: null, ...overrides}]
  })
}

function respond(body, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(body), {status, headers: {'content-type': 'application/json'}}))
}

function mountToggle(props = toggle(), panels = manifest()) {
  return mount(AgentSensorToggle, {props: {toggle: props}, global: {provide: {panels}}, attachTo: document.body})
}

describe('AgentSensorToggle', () => {
  let fetchMock

  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=test-token'
    fetchMock = vi.fn(() => respond({toggles: []}))
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    document.body.innerHTML = ''
  })

  it('shows the switch, the configured default, and why the sensor is on or off by default, without any request', () => {
    const wrapper = mountToggle()

    const input = wrapper.get('input[role="switch"]')
    expect(input.element.checked).toBe(false)
    expect(input.element.disabled).toBe(false)
    expect(wrapper.get('label').text()).toContain('environment')
    const details = wrapper.get(`#${input.attributes('aria-describedby')}`)
    expect(details.text()).toContain('Off by default: it advises System.getProperty.')
    expect(details.text()).toContain('Configured: off in bootui.agent.sensors')
    expect(wrapper.text()).toContain('Off')
    expect(wrapper.find('[data-testid="agent-sensor-overridden"]').exists()).toBe(false)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('posts the switch and emits the new report', async () => {
    const report = {toggles: [toggle({enabled: true, overridden: true, state: 'installing'})]}
    fetchMock.mockImplementation(() => respond(report))
    const wrapper = mountToggle()

    await wrapper.get('input[role="switch"]').setValue(true)
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(String(url)).toContain('api/java-agent/sensors/environment')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body)).toEqual({enabled: true})
    expect(wrapper.emitted('switched')).toEqual([[report]])
  })

  it('marks an override and says it lasts until the JVM ends', () => {
    const wrapper = mountToggle(toggle({enabled: true, overridden: true, state: 'installed'}))

    expect(wrapper.get('input[role="switch"]').element.checked).toBe(true)
    expect(wrapper.get('[data-testid="agent-sensor-overridden"]').text()).toBe('Overridden')
    expect(wrapper.text()).toContain('Recording')
    expect(wrapper.text()).toContain('until this JVM ends')
  })

  it('shows a refused switch with the canonical error and keeps the switch as it was', async () => {
    fetchMock.mockImplementation(() =>
      respond({error: 'The BootUI agent could not switch threads: it failed in this run'}, 409)
    )
    const wrapper = mountToggle(toggle({id: 'threads'}))

    await wrapper.get('input[role="switch"]').setValue(true)
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('could not switch threads')
    expect(wrapper.get('input[role="switch"]').element.checked).toBe(false)
    expect(wrapper.emitted('switched')).toBeUndefined()
    expect(wrapper.emitted('stale')).toHaveLength(1)
  })

  it('is disabled with the reason when the Java Agent panel is read-only or the agent predates switches', async () => {
    const readOnly = mountToggle(
      toggle(),
      manifest({readOnly: true, readOnlyReason: 'Panel is read-only via bootui.panels.java-agent.read-only=true'})
    )
    expect(readOnly.get('input[role="switch"]').element.disabled).toBe(true)
    expect(readOnly.text()).toContain('bootui.panels.java-agent.read-only=true')
    await readOnly.get('input[role="switch"]').trigger('change')
    expect(fetchMock).not.toHaveBeenCalled()

    const older = mountToggle(toggle({available: false, unavailableReason: 'The attached BootUI agent predates it.'}))
    expect(older.get('input[role="switch"]').element.disabled).toBe(true)
    expect(older.text()).toContain('predates it')
  })

  it('shows the agent’s failure of a switch it kept, with a failed state', () => {
    const wrapper = mountToggle(
      toggle({enabled: true, overridden: true, state: 'failed', failure: 'The agent failed this switch: boom'})
    )

    expect(wrapper.text()).toContain('Failed')
    expect(wrapper.text()).toContain('The agent failed this switch: boom')
    expect(wrapper.get('input[role="switch"]').element.disabled).toBe(false)
  })

  it('is hidden while the manifest does not list an enabled, available Java Agent panel', () => {
    expect(mountToggle(toggle(), ref(null)).find('input').exists()).toBe(false)
    expect(
      mountToggle(toggle(), manifest({enabled: false}))
        .find('input')
        .exists()
    ).toBe(false)
    expect(
      mountToggle(toggle(), manifest({available: false}))
        .find('input')
        .exists()
    ).toBe(false)
  })
})
