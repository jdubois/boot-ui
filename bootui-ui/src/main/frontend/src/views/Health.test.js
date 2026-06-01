import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Health from './Health.vue'

function jsonResponse(body) {
  return Promise.resolve(new Response(JSON.stringify(body), {status: 200}))
}

async function mountWithHealth(body) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => jsonResponse(body))
  )
  const wrapper = mount(Health)
  await flushPromises()
  return wrapper
}

describe('Health', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows setup guidance when Actuator health is unavailable', async () => {
    const wrapper = await mountWithHealth({
      name: 'application',
      status: 'DISABLED',
      details: null,
      components: [],
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

    expect(fetch).toHaveBeenCalledWith('api/health')
    expect(wrapper.text()).toContain('DISABLED')
    expect(wrapper.text()).toContain('Spring Boot Actuator health endpoint is not available')
    expect(wrapper.text()).toContain('Set up Spring Boot Actuator health')
    expect(wrapper.text()).toContain('org.springframework.boot:spring-boot-starter-actuator')
    expect(wrapper.text()).toContain('management.endpoint.health.probes.enabled=true')
    expect(wrapper.text()).not.toContain('Component tree')
  })

  it('shows default health contributors as disabled while setup guidance is visible', async () => {
    const wrapper = await mountWithHealth({
      name: 'application',
      status: 'DISABLED',
      details: null,
      available: false,
      unavailableReason: 'Only Spring Boot default health indicators are available',
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
          status: 'DISABLED',
          details: {livenessState: 'CORRECT'},
          components: [],
          available: false
        },
        {
          name: 'readinessState',
          status: 'DISABLED',
          details: {readinessState: 'ACCEPTING_TRAFFIC'},
          components: [],
          available: false
        },
        {
          name: 'ssl',
          status: 'DISABLED',
          details: {validChains: []},
          components: [],
          available: false
        }
      ]
    })

    expect(wrapper.text()).toContain('Only Spring Boot default health indicators are available')
    expect(wrapper.text()).toContain('Add application health contributors')
    expect(wrapper.text()).toContain('Actuator health is present')
    expect(wrapper.text()).toContain('Spring Boot default health indicators: livenessState, readinessState, ssl')
    expect(wrapper.text()).toContain('The SSL indicator only appears when Spring has SSL bundles to validate.')
    expect(wrapper.text()).toContain('Component tree')
    expect(wrapper.text()).toContain('livenessState')
    expect(wrapper.text()).toContain('readinessState')
    expect(wrapper.text()).toContain('ssl')
  })

  it('does not render empty detail sections for contributors', async () => {
    const wrapper = await mountWithHealth({
      name: 'application',
      status: 'DISABLED',
      details: null,
      available: false,
      unavailableReason: 'Only Spring Boot default health indicators are available',
      setup: [],
      components: [
        {name: 'livenessState', status: 'DISABLED', details: {}, components: [], available: false},
        {name: 'ping', status: 'DISABLED', details: {}, components: [], available: false},
        {name: 'readinessState', status: 'DISABLED', details: {}, components: [], available: false}
      ]
    })

    expect(wrapper.text()).toContain('livenessState')
    expect(wrapper.text()).toContain('ping')
    expect(wrapper.text()).toContain('readinessState')
    expect(wrapper.findAll('h6').filter((heading) => heading.text() === 'Details')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('0 details')
  })

  it('renders the component tree when health data is available', async () => {
    const wrapper = await mountWithHealth({
      name: 'application',
      status: 'UP',
      details: null,
      components: [{name: 'diskSpace', status: 'UP', details: {free: '128 GB'}, components: [], available: true}],
      available: true,
      setup: []
    })

    expect(wrapper.text()).toContain('All reported components are healthy')
    expect(wrapper.text()).toContain('Component tree')
    expect(wrapper.text()).toContain('diskSpace')
    expect(wrapper.text()).not.toContain('Set up Spring Boot Actuator health')
  })
})
