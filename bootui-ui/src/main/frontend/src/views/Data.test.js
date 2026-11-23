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

function repositoryDetail() {
  return {
    repositoryInterface: 'com.example.UserRepository',
    storeModule: 'JPA',
    domainType: 'com.example.User',
    idType: 'java.lang.Long',
    beanName: 'userRepository',
    customImplementation: null,
    methods: [
      {
        origin: 'ANNOTATED',
        signature: 'findUsersWithAnIntentionallyLongMethodSignature(java.lang.String)',
        query: 'select user from User user where user.displayName = :displayName',
        namedQuery: null,
        nativeQuery: false
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

  it('distinguishes unavailable repository metadata from an absence of repositories', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({total: 0, repositories: []})))

    wrapper = mount(Data)
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.classes()).toContain('alert-secondary')
    expect(alert.text()).toContain('No initialized repository metadata is available.')
    expect(alert.text()).toContain('BootUI does not initialize lazy repositories.')
  })

  it('renders repositories when repository beans are present', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(repositoriesReport())))

    wrapper = mount(Data)
    await flushPromises()

    expect(wrapper.text()).toContain('Only initialized repositories with available metadata are listed.')
    expect(fetch).toHaveBeenCalledWith('api/data/repositories', expect.anything())
    expect(wrapper.text()).not.toContain('not on the classpath')
    expect(wrapper.text()).toContain('UserRepository')
    expect(wrapper.text()).toContain('1 / 1 repositories')
  })

  it('contains long repository method values in a responsive table', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(repositoriesReport()))
        .mockResolvedValueOnce(jsonResponse(repositoryDetail()))
    )

    wrapper = mount(Data)
    await flushPromises()
    await wrapper.get('.list-group-item-action').trigger('click')
    await flushPromises()

    expect(wrapper.get('.table-responsive.bootui-table-scroll .data-methods-table').exists()).toBe(true)
    expect(wrapper.findAll('.data-methods-table .bootui-break-anywhere')).toHaveLength(2)
  })
})
