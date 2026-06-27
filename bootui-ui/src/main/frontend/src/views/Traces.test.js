import {flushPromises, mount} from '@vue/test-utils'
import {ref} from 'vue'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Traces from './Traces.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function emptyEnabledReport() {
  return {enabled: true, retained: 0, capacity: 1000}
}

function mountWithPlatform(platform) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(emptyEnabledReport())))
  return mount(Traces, {
    global: {provide: {panels: ref({platform, panels: []})}}
  })
}

describe('Traces empty state', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('points cooperating services to the embedded OTLP receiver on Spring Boot', async () => {
    wrapper = mountWithPlatform('spring-boot')
    await flushPromises()
    const text = wrapper.text()

    expect(text).toContain('No traces received yet')
    expect(text).toContain('BootUI starter')
    expect(text).toContain('/bootui/api/otlp/v1/traces')
  })

  it('describes in-process capture with no OTLP endpoint on Quarkus', async () => {
    wrapper = mountWithPlatform('quarkus')
    await flushPromises()
    const text = wrapper.text()

    expect(text).toContain('No traces received yet')
    expect(text).toContain('quarkus-opentelemetry')
    expect(text).toContain('in-process')
    expect(text).not.toContain('/bootui/api/otlp/v1/traces')
    expect(text).not.toContain('BootUI starter')
  })
})
