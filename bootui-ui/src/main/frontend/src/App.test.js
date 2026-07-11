import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {createMemoryHistory, createRouter} from 'vue-router'

import {routes} from './routes.js'

const TestPanel = {template: '<section />'}
const namedRoutes = routes.filter((route) => route.name && route.meta?.title)

function shellRoutes() {
  return routes.map((route) => (route.redirect ? route : {...route, component: TestPanel}))
}

function jsonResponse(body) {
  return {
    ok: true,
    json: () => Promise.resolve(body)
  }
}

function mockShellFetch(platform = 'spring-boot') {
  vi.stubGlobal(
    'fetch',
    vi.fn((url) => {
      const requestUrl = String(url)
      if (requestUrl === 'api/overview') {
        return Promise.resolve(
          jsonResponse({
            applicationName: 'bootui-sample',
            frameworkName: 'Spring Boot',
            frameworkVersion: '4.0.6',
            javaVersion: '17',
            activeProfiles: ['dev'],
            activation: {enabled: true}
          })
        )
      }

      if (requestUrl === 'api/panels') {
        return Promise.resolve(
          jsonResponse({
            platform,
            panels: namedRoutes.map((route) => ({
              id: route.name,
              title: route.meta.title,
              available: true,
              enabled: true
            }))
          })
        )
      }

      return Promise.reject(new Error(`Unexpected fetch URL: ${requestUrl}`))
    })
  )
}

function stubLocalStorage() {
  const storage = new Map()
  const localStorageStub = {
    clear: () => storage.clear(),
    getItem: (key) => storage.get(key) ?? null,
    removeItem: (key) => storage.delete(key),
    setItem: (key, value) => storage.set(key, String(value))
  }

  Object.defineProperty(window, 'localStorage', {
    configurable: true,
    value: localStorageStub
  })
  if (globalThis !== window) {
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: localStorageStub
    })
  }
}

function restoreLocalStorage() {
  Reflect.deleteProperty(window, 'localStorage')
  if (globalThis !== window) {
    Reflect.deleteProperty(globalThis, 'localStorage')
  }
}

async function mountApp(initialPath = '/overview') {
  const {default: App} = await import('./App.vue')
  const router = createRouter({
    history: createMemoryHistory(),
    routes: shellRoutes()
  })

  await router.push(initialPath)
  await router.isReady()

  const wrapper = mount(App, {
    attachTo: document.body,
    global: {
      plugins: [router],
      stubs: {
        CommandPalette: true,
        RouterView: {template: '<div />'}
      }
    }
  })
  await flushPromises()

  return {router, wrapper}
}

function groupToggle(wrapper, title) {
  const toggle = wrapper.findAll('.bootui-nav-group__toggle').find((button) => button.text().includes(title))
  if (!toggle) {
    throw new Error(`Could not find ${title} navigation group toggle`)
  }
  return toggle
}

describe('App sidebar navigation', () => {
  beforeEach(() => {
    stubLocalStorage()
    mockShellFetch()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    restoreLocalStorage()
    document.body.innerHTML = ''
  })

  it('moves the active group away from Security after navigating to another group', async () => {
    const {router, wrapper} = await mountApp('/spring-security')

    expect(groupToggle(wrapper, 'Security').classes()).toContain('active')

    await router.push('/scheduled')
    await flushPromises()

    expect(groupToggle(wrapper, 'Security').classes()).not.toContain('active')
    expect(groupToggle(wrapper, 'Services').classes()).toContain('active')
  })

  it('releases pointer focus from group toggles after mouse or touch activation', async () => {
    const {wrapper} = await mountApp()
    const securityToggle = groupToggle(wrapper, 'Security')
    securityToggle.element.focus()

    securityToggle.element.dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true, detail: 1}))
    await flushPromises()

    expect(securityToggle.attributes('aria-expanded')).toBe('true')
    expect(document.activeElement).not.toBe(securityToggle.element)
  })

  it('keeps keyboard focus on group toggles after keyboard activation', async () => {
    const {wrapper} = await mountApp()
    const securityToggle = groupToggle(wrapper, 'Security')
    securityToggle.element.focus()

    securityToggle.element.dispatchEvent(new MouseEvent('click', {bubbles: true, cancelable: true, detail: 0}))
    await flushPromises()

    expect(securityToggle.attributes('aria-expanded')).toBe('true')
    expect(document.activeElement).toBe(securityToggle.element)
  })
})

describe('App remote authentication', () => {
  beforeEach(() => {
    stubLocalStorage()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    restoreLocalStorage()
    document.body.innerHTML = ''
  })

  it('unlocks the API with the token from the startup log', async () => {
    let authenticated = false
    vi.stubGlobal(
      'fetch',
      vi.fn((url, options = {}) => {
        const requestUrl = String(url)
        if (requestUrl === 'api/auth/session') {
          expect(options.method).toBe('POST')
          expect(options.headers.Authorization.split(' ')).toEqual(['Bearer', 'startup-token'])
          authenticated = true
          return Promise.resolve({ok: true, status: 204})
        }
        if (!authenticated) {
          return Promise.resolve({ok: false, status: 401})
        }
        if (requestUrl === 'api/overview') {
          return Promise.resolve(
            jsonResponse({
              applicationName: 'bootui-sample',
              javaVersion: '17',
              activeProfiles: ['dev'],
              activation: {enabled: true}
            })
          )
        }
        if (requestUrl === 'api/panels') {
          return Promise.resolve(jsonResponse({platform: 'spring-boot', panels: []}))
        }
        return Promise.reject(new Error(`Unexpected fetch URL: ${requestUrl}`))
      })
    )

    const {wrapper} = await mountApp()
    expect(wrapper.find('#authentication-title').text()).toBe('Unlock BootUI')

    await wrapper.find('#bootui-authentication-token').setValue('startup-token')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('#authentication-title').exists()).toBe(false)
    expect(wrapper.text()).toContain('bootui-sample')
  })
})

describe('App shell footer', () => {
  beforeEach(() => {
    stubLocalStorage()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    restoreLocalStorage()
    document.body.innerHTML = ''
  })

  it('labels the footer for Spring Boot when the manifest platform is spring-boot', async () => {
    mockShellFetch('spring-boot')
    const {wrapper} = await mountApp()

    expect(wrapper.find('.bootui-footer a').text()).toBe('BootUI - The missing developer UI for Spring Boot!')
  })

  it('labels the footer for Quarkus when the manifest platform is quarkus', async () => {
    mockShellFetch('quarkus')
    const {wrapper} = await mountApp()

    expect(wrapper.find('.bootui-footer a').text()).toBe('BootUI - The missing developer UI for Quarkus!')
  })
})
