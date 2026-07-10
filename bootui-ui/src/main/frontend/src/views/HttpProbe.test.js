import {flushPromises, mount} from '@vue/test-utils'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {ref} from 'vue'

import HttpProbe from './HttpProbe.vue'

const {apiFetch} = vi.hoisted(() => ({apiFetch: vi.fn()}))

vi.mock('../api.js', () => ({apiFetch}))

describe('HttpProbe', () => {
  beforeEach(() => {
    apiFetch.mockReset()
  })

  it('describes the Spring Boot app in the subtitle by default', () => {
    const wrapper = mount(HttpProbe)
    expect(wrapper.text()).toContain('running Spring Boot app')
  })

  it('describes the Quarkus app when the platform is quarkus', () => {
    const wrapper = mount(HttpProbe, {
      global: {provide: {panels: ref({platform: 'quarkus'})}}
    })
    expect(wrapper.text()).toContain('running Quarkus app')
    expect(wrapper.text()).not.toContain('Spring Boot')
  })

  it('warns when the response body was truncated', async () => {
    apiFetch.mockResolvedValue({
      json: async () => ({
        status: 200,
        statusText: 'OK',
        headers: {},
        body: 'partial response',
        durationMs: 12,
        error: null,
        truncated: true
      })
    })
    const wrapper = mount(HttpProbe)

    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Response body was truncated at the configured byte limit.')
    expect(wrapper.text()).toContain('partial response')
  })
})
