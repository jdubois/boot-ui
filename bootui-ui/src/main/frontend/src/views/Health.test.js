import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import Health from './Health.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'

function healthRoot(overrides = {}) {
  return {
    name: 'application',
    status: 'UP',
    details: null,
    components: [],
    available: true,
    setup: [],
    ...overrides
  }
}

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

describe('Health', () => {
  let wrapper

  beforeEach(() => {
    vi.useFakeTimers()
    Object.defineProperty(document, 'visibilityState', {configurable: true, value: 'visible'})
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  async function mountWithHealth(body) {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(body)))
    wrapper = mount(Health)
    await flushPromises()
    return wrapper
  }

  it('shows the skeleton on the first load only', async () => {
    let resolveFirst
    const fetchMock = vi.fn().mockImplementationOnce(() => new Promise((resolve) => (resolveFirst = resolve)))
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(Health)

    expect(wrapper.findComponent(PanelSkeleton).exists()).toBe(true)

    resolveFirst(jsonResponse(healthRoot()))
    await flushPromises()

    expect(wrapper.findComponent(PanelSkeleton).exists()).toBe(false)
    expect(wrapper.text()).toContain('Overall status')

    let resolveRefresh
    fetchMock.mockImplementationOnce(() => new Promise((resolve) => (resolveRefresh = resolve)))
    wrapper.findComponent(PanelHeader).vm.$emit('refresh')
    await flushPromises()

    expect(wrapper.findComponent(PanelSkeleton).exists()).toBe(false)
    expect(wrapper.text()).toContain('Overall status')

    resolveRefresh(jsonResponse(healthRoot()))
    await flushPromises()

    expect(wrapper.findComponent(PanelSkeleton).exists()).toBe(false)
  })

  it('keeps the last good data visible when a refresh fails', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse(healthRoot()))
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(Health)
    await flushPromises()
    expect(wrapper.text()).toContain('Overall status')

    fetchMock.mockResolvedValueOnce(jsonResponse({}, false, 503))
    wrapper.findComponent(PanelHeader).vm.$emit('refresh')
    await flushPromises()

    expect(wrapper.findComponent(PanelSkeleton).exists()).toBe(false)
    expect(wrapper.text()).toContain('Overall status')
  })

  it('shows setup guidance when Actuator health is unavailable', async () => {
    await mountWithHealth(
      healthRoot({
        status: 'DISABLED',
        available: false,
        unavailableReason: 'Spring Boot Actuator health endpoint is not available',
        setup: [
          {
            title: 'Add Spring Boot Actuator',
            description: 'Add the Actuator starter.',
            snippets: ['org.springframework.boot:spring-boot-starter-actuator']
          },
          {
            title: 'Enable availability probes when you need them',
            description: 'Expose liveness and readiness groups.',
            snippets: ['management.endpoint.health.probes.enabled=true']
          }
        ]
      })
    )

    expect(fetch).toHaveBeenCalledWith('api/health', expect.anything())
    expect(wrapper.text()).toContain('DISABLED')
    expect(wrapper.text()).toContain('Spring Boot Actuator health endpoint is not available')
    expect(wrapper.text()).toContain('Set up health monitoring')
    expect(wrapper.text()).toContain('org.springframework.boot:spring-boot-starter-actuator')
    expect(wrapper.text()).toContain('management.endpoint.health.probes.enabled=true')
    expect(wrapper.text()).not.toContain('Component tree')
  })

  it('renders Quarkus SmallRye setup guidance from backend fields when health is unavailable', async () => {
    await mountWithHealth(
      healthRoot({
        status: 'DISABLED',
        available: false,
        unavailableReason: 'Quarkus SmallRye Health is not available',
        setup: [
          {
            title: 'Add Quarkus SmallRye Health',
            description: 'Add the quarkus-smallrye-health extension.',
            snippets: ['io.quarkus:quarkus-smallrye-health']
          }
        ]
      })
    )

    expect(wrapper.text()).toContain('DISABLED')
    expect(wrapper.text()).toContain('Quarkus SmallRye Health is not available')
    expect(wrapper.text()).toContain('Set up health monitoring')
    expect(wrapper.text()).toContain('io.quarkus:quarkus-smallrye-health')
    // The panel chrome carries no hardcoded Spring/Actuator copy — all platform specifics come from the backend.
    expect(wrapper.text()).not.toContain('Spring')
    expect(wrapper.text()).not.toContain('Actuator')
    expect(wrapper.text()).not.toContain('Component tree')
  })

  it('shows default health contributors as live with setup guidance', async () => {
    await mountWithHealth(
      healthRoot({
        guidanceReason: 'Only Spring Boot default health indicators are available',
        setup: [
          {
            title: 'Add application health contributors',
            description: 'Create a HealthIndicator bean.',
            snippets: ['class MyHealthIndicator implements HealthIndicator']
          }
        ],
        components: [
          {
            name: 'livenessState',
            status: 'UP',
            details: {livenessState: 'CORRECT'},
            components: [],
            available: true
          },
          {
            name: 'readinessState',
            status: 'UP',
            details: {readinessState: 'ACCEPTING_TRAFFIC'},
            components: [],
            available: true
          },
          {
            name: 'ssl',
            status: 'UP',
            details: {validChains: []},
            components: [],
            available: true
          }
        ]
      })
    )

    expect(wrapper.text()).toContain('UP')
    expect(wrapper.text()).toContain('Only Spring Boot default health indicators are available')
    expect(wrapper.text()).toContain('Add application health contributors')
    expect(wrapper.text()).toContain("only the framework's built-in health indicators")
    expect(wrapper.text()).toContain('built-in health indicators: livenessState, readinessState, ssl')
    expect(wrapper.text()).toContain('Component tree')
    expect(wrapper.text()).toContain('livenessState')
    expect(wrapper.text()).toContain('readinessState')
    expect(wrapper.text()).toContain('ssl')
  })

  it('does not render empty detail sections for contributors', async () => {
    await mountWithHealth(
      healthRoot({
        guidanceReason: 'Only Spring Boot default health indicators are available',
        components: [
          {name: 'livenessState', status: 'UP', details: {}, components: [], available: true},
          {name: 'ping', status: 'UP', details: {}, components: [], available: true},
          {name: 'readinessState', status: 'UP', details: {}, components: [], available: true}
        ]
      })
    )

    expect(wrapper.text()).toContain('livenessState')
    expect(wrapper.text()).toContain('ping')
    expect(wrapper.text()).toContain('readinessState')
    expect(wrapper.findAll('h6').filter((heading) => heading.text() === 'Details')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('0 details')
  })

  it('renders the component tree when health data is available', async () => {
    await mountWithHealth(
      healthRoot({
        components: [{name: 'diskSpace', status: 'UP', details: {free: '128 GB'}, components: [], available: true}]
      })
    )

    expect(wrapper.text()).toContain('All reported components are healthy')
    expect(wrapper.text()).toContain('Component tree')
    expect(wrapper.text()).toContain('diskSpace')
    expect(wrapper.text()).not.toContain('Set up health monitoring')
  })
})
