import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Data from './Data.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function repositoriesReport() {
  return {
    total: 1,
    repositories: [
      {
        beanName: 'userRepository',
        repositoryInterface: 'com.example.UserRepository',
        domainType: 'com.example.User',
        idType: 'java.lang.Long',
        storeModule: 'JPA',
        queryMethodCount: 2,
        fragmentCount: 0
      }
    ]
  }
}

describe('Data', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('shows a shared unavailable reason when Spring Data is not on the classpath', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(null, false, 404)))

    wrapper = mount(Data)
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.classes()).toContain('alert-info')
    expect(alert.text()).toContain('Spring Data is not on the classpath')
    expect(alert.find('code').text()).toBe('spring-boot-starter-data-jpa')
  })

  it('reports when Spring Data is present but no repositories are detected', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({total: 0, repositories: []})))

    wrapper = mount(Data)
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.classes()).toContain('alert-secondary')
    expect(alert.text()).toContain('no repository beans were detected')
  })

  it('renders repositories when repository beans are present', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(repositoriesReport())))

    wrapper = mount(Data)
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/data/repositories', expect.anything())
    expect(wrapper.text()).not.toContain('not on the classpath')
    expect(wrapper.text()).toContain('UserRepository')
    expect(wrapper.text()).toContain('1 / 1 repositories')
  })
})
