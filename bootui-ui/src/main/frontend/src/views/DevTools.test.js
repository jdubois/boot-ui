import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import DevTools from './DevTools.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function devToolsStatus() {
  return {
    restartAvailable: false,
    restartPending: false,
    restartUnavailableReason: 'Spring Boot DevTools is not on the classpath.',
    liveReloadAvailable: false,
    liveReloadPort: null,
    liveReloadConnections: 0,
    liveReloadUnavailableReason: 'Spring Boot DevTools is not on the classpath.'
  }
}

describe('DevTools', () => {
  let wrapper

  beforeEach(() => {
    vi.useFakeTimers()
    Object.defineProperty(document, 'visibilityState', {configurable: true, value: 'visible'})
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('shows a shared unavailable state when the status cannot be loaded', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Failed to fetch')))

    wrapper = mount(DevTools)
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.classes()).toContain('alert-secondary')
    expect(alert.text()).toContain('DevTools status is unavailable')
  })

  it('renders the action cards when the status loads', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(devToolsStatus())))

    wrapper = mount(DevTools)
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/devtools', expect.anything())
    expect(wrapper.text()).toContain('Trigger LiveReload')
    expect(wrapper.text()).toContain('Restart application')
    expect(wrapper.text()).not.toContain('DevTools status is unavailable')
  })

  it('shows the enable tip when LiveReload is disabled but DevTools is on the classpath', async () => {
    const status = {
      ...devToolsStatus(),
      restartAvailable: true,
      restartUnavailableReason: null,
      liveReloadUnavailableReason: 'Spring Boot DevTools LiveReload server is not available.'
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(status)))

    wrapper = mount(DevTools)
    await flushPromises()

    expect(wrapper.text()).toContain('spring.devtools.livereload.enabled=true')
  })

  it('hides the enable tip when DevTools is not on the classpath', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(devToolsStatus())))

    wrapper = mount(DevTools)
    await flushPromises()

    expect(wrapper.text()).not.toContain('spring.devtools.livereload.enabled=true')
  })

  it('warns when LiveReload is available but no browsers are connected', async () => {
    const status = {
      ...devToolsStatus(),
      liveReloadAvailable: true,
      liveReloadPort: 35729,
      liveReloadConnections: 0,
      liveReloadUnavailableReason: null
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(status)))

    wrapper = mount(DevTools)
    await flushPromises()

    expect(wrapper.text()).toContain('Connected clients:')
    expect(wrapper.text()).toContain('No browsers are connected to the LiveReload server')
  })

  it('does not warn when LiveReload clients are connected', async () => {
    const status = {
      ...devToolsStatus(),
      liveReloadAvailable: true,
      liveReloadPort: 35729,
      liveReloadConnections: 2,
      liveReloadUnavailableReason: null
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(status)))

    wrapper = mount(DevTools)
    await flushPromises()

    expect(wrapper.text()).toContain('Connected clients:')
    expect(wrapper.text()).not.toContain('No browsers are connected to the LiveReload server')
  })

  it('flashes a warning when triggering LiveReload reaches no connected clients', async () => {
    const status = {
      ...devToolsStatus(),
      liveReloadAvailable: true,
      liveReloadPort: 35729,
      liveReloadConnections: 0,
      liveReloadUnavailableReason: null
    }
    const fetchMock = vi.fn().mockImplementation((url) => {
      if (url === 'api/devtools/livereload') {
        return Promise.resolve(
          jsonResponse({action: 'livereload', status: 'no_clients', message: 'no browsers are connected'})
        )
      }
      return Promise.resolve(jsonResponse(status))
    })
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(DevTools)
    await flushPromises()

    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()

    const banner = wrapper.findAll('.alert').find((alert) => alert.find('.btn-close').exists())
    expect(banner.classes()).toContain('alert-warning')
    expect(banner.text()).toContain('no browsers are connected')
  })
})
