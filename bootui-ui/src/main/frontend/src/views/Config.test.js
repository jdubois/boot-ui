import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {ref} from 'vue'

import Config from './Config.vue'

const EMPTY_CONFIG = {
  properties: [],
  page: {matched: 0, total: 0},
  sources: [],
  activeProfiles: [],
  propertySuggestions: []
}

async function mountConfig(provide) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve(new Response(JSON.stringify(EMPTY_CONFIG), {status: 200})))
  )

  const wrapper = mount(Config, provide ? {global: {provide}} : undefined)
  await flushPromises()
  return wrapper
}

describe('Config', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('describes Spring Boot configuration keys and placeholder by default', async () => {
    const wrapper = await mountConfig()

    expect(wrapper.text()).toContain('known Spring Boot')
    expect(wrapper.text()).not.toContain('known Quarkus')

    await wrapper.find('button.btn-success').trigger('click')
    await flushPromises()
    expect(wrapper.find('input[list="bootPropertySuggestions"]').attributes('placeholder')).toBe(
      'spring.application.name'
    )
  })

  it('describes Quarkus configuration keys and placeholder when the platform is quarkus', async () => {
    const wrapper = await mountConfig({panels: ref({platform: 'quarkus'})})

    expect(wrapper.text()).toContain('known Quarkus')
    expect(wrapper.text()).not.toContain('known Spring Boot')

    await wrapper.find('button.btn-success').trigger('click')
    await flushPromises()
    expect(wrapper.find('input[list="bootPropertySuggestions"]').attributes('placeholder')).toBe(
      'quarkus.application.name'
    )
  })
})
