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

  it('keeps the selected repository details when an older response arrives last', async () => {
    const user = repositoriesReport().repositories[0]
    const order = {...user, beanName: 'orderRepository', repositoryInterface: 'com.example.OrderRepository'}
    let finishUser
    const slowUser = new Promise((resolve) => {
      finishUser = resolve
    })
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(jsonResponse({total: 2, repositories: [user, order]}))
        .mockReturnValueOnce(slowUser)
        .mockResolvedValueOnce(jsonResponse({...repositoryDetail(), ...order, methods: []}))
    )

    wrapper = mount(Data)
    await flushPromises()
    const [userButton, orderButton] = wrapper.findAll('.list-group-item-action')
    await userButton.trigger('click')
    await orderButton.trigger('click')
    await flushPromises()
    expect(wrapper.get('.card-title').text()).toBe('OrderRepository')

    finishUser(jsonResponse(repositoryDetail()))
    await flushPromises()
    expect(orderButton.classes()).toContain('active')
    expect(wrapper.get('.card-title').text()).toBe('OrderRepository')
    expect(wrapper.findAll('.data-methods-table tbody tr')).toHaveLength(0)
  })

  it('ignores a slow body from an earlier request for the same repository', async () => {
    const user = repositoriesReport().repositories[0]
    const order = {...user, beanName: 'orderRepository', repositoryInterface: 'com.example.OrderRepository'}
    const {promise, resolve} = Promise.withResolvers()
    const readBody = vi.fn(() => promise)
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(jsonResponse({total: 2, repositories: [user, order]}))
        .mockResolvedValueOnce({ok: true, json: readBody})
        .mockResolvedValueOnce(jsonResponse({...repositoryDetail(), ...order, methods: []}))
        .mockResolvedValueOnce(jsonResponse(repositoryDetail()))
    )

    wrapper = mount(Data)
    await flushPromises()
    const [userButton, orderButton] = wrapper.findAll('.list-group-item-action')
    await userButton.trigger('click')
    await flushPromises()
    expect(readBody).toHaveBeenCalledOnce()

    await orderButton.trigger('click')
    await flushPromises()
    expect(wrapper.get('.card-title').text()).toBe('OrderRepository')
    await userButton.trigger('click')
    await flushPromises()
    expect(wrapper.get('.data-methods-table').text()).toContain('findUsersWithAnIntentionallyLongMethodSignature')

    resolve({...repositoryDetail(), methods: []})
    await flushPromises()
    expect(wrapper.get('.data-methods-table').text()).toContain('findUsersWithAnIntentionallyLongMethodSignature')
  })

  it('keeps loading through an older failure and allows retry after the latest failure', async () => {
    const {promise: older, reject} = Promise.withResolvers()
    const {promise: latest, resolve} = Promise.withResolvers()
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(repositoriesReport()))
        .mockReturnValueOnce(older)
        .mockReturnValueOnce(latest)
        .mockResolvedValueOnce(jsonResponse(repositoryDetail()))
    )

    wrapper = mount(Data)
    await flushPromises()
    const button = wrapper.get('.list-group-item-action')
    await button.trigger('click')
    await button.trigger('click')
    reject(new TypeError('Failed to fetch'))
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toBe('Loading repository details…')
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('Select a repository to see its methods')

    resolve(jsonResponse(null, false, 503))
    await flushPromises()
    expect(wrapper.get('[role="status"]').text()).toBe('')
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(1)
    expect(wrapper.get('[role="alert"]').text()).toContain('Unable to load repository details: HTTP 503')
    expect(wrapper.get('[role="alert"]').text()).toContain('Select the repository again to retry.')
    expect(wrapper.find('.card-title').exists()).toBe(false)

    await button.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[role="alert"]')).toHaveLength(0)
    expect(wrapper.get('.card-title').text()).toBe('UserRepository')
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
