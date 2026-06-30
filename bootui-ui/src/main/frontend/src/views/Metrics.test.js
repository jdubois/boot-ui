import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {ref} from 'vue'

import Metrics from './Metrics.vue'

function stubFetch(body) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve(new Response(JSON.stringify(body), {status: 200})))
  )
}

function mountMetrics(platform) {
  const global = platform ? {provide: {panels: ref({platform})}} : {}
  return mount(Metrics, {global})
}

describe('Metrics empty state', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('points Spring Boot users at Actuator or a MeterRegistry by default', async () => {
    stubFetch({metricsAvailable: false})
    const wrapper = mountMetrics()
    await flushPromises()
    expect(wrapper.text()).toContain('Add Actuator or a MeterRegistry')
    expect(wrapper.text()).not.toContain('quarkus-micrometer')
  })

  it('points Quarkus users at a quarkus-micrometer registry', async () => {
    stubFetch({metricsAvailable: false})
    const wrapper = mountMetrics('quarkus')
    await flushPromises()
    expect(wrapper.text()).toContain('quarkus-micrometer')
    expect(wrapper.text()).not.toContain('Add Actuator')
  })
})
