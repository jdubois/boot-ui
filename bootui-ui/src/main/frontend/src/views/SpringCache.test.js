import {flushPromises, mount} from '@vue/test-utils'
import {ref} from 'vue'
import {afterEach, describe, expect, it, vi} from 'vitest'

import SpringCache from './SpringCache.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function report(overrides = {}) {
  return {
    cacheAvailable: true,
    clearEnabled: true,
    managerCount: 1,
    cacheCount: 1,
    operationCount: 0,
    managers: [
      {
        name: 'cacheManager',
        type: 'CaffeineCacheManager',
        noOp: false,
        caches: [{managerName: 'cacheManager', name: 'orders', nativeType: 'CaffeineCache', size: 2, metrics: null}]
      }
    ],
    operations: [],
    warnings: [],
    ...overrides
  }
}

function mountWithPlatform(platform, body = report()) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(body)))
  return mount(SpringCache, {
    global: {provide: {panels: ref({platform, panels: []})}}
  })
}

describe('SpringCache operations section', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('shows the Spring annotation-operations section on Spring Boot', async () => {
    wrapper = mountWithPlatform('spring-boot')
    await flushPromises()
    const text = wrapper.text()

    expect(text).toContain('Annotation operations')
    expect(text).toContain('@Cacheable')
    expect(text).not.toContain('@CacheResult')
  })

  it('defaults to the Spring section when no platform is provided', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report())))
    wrapper = mount(SpringCache)
    await flushPromises()

    expect(wrapper.text()).toContain('Annotation operations')
  })

  it('describes build-time cached operations on Quarkus', async () => {
    wrapper = mountWithPlatform('quarkus')
    await flushPromises()
    const text = wrapper.text()

    expect(text).toContain('Cached operations')
    expect(text).toContain('@CacheResult')
    expect(text).toContain('build-time')
    expect(text).not.toContain('Annotation operations')
    expect(text).not.toContain('@Cacheable')
  })
})
