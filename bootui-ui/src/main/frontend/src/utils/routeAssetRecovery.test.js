import {beforeEach, describe, expect, it, vi} from 'vitest'
import {createMemoryHistory, createRouter} from 'vue-router'
import {createRouteAssetRecovery, isRouteAssetError} from './routeAssetRecovery.js'

const assetError = () => new TypeError('Failed to fetch dynamically imported module: http://localhost/assets/old.js')

function setup(href = 'http://localhost/bootui/#/overview') {
  const loadPanel = vi.fn(() => Promise.reject(assetError()))
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {path: '/overview', component: {template: '<div />'}},
      {path: '/hibernate', meta: {title: 'Hibernate'}, component: loadPanel},
      {path: '/health', component: {template: '<div />'}}
    ]
  })
  const browser = {
    location: {href, reload: vi.fn()},
    history: {state: {position: 1}, replaceState: vi.fn()}
  }
  const recovery = createRouteAssetRecovery(router, browser)
  return {router, loadPanel, browser, recovery}
}

describe('route asset recovery', () => {
  beforeEach(() => vi.spyOn(console, 'error').mockImplementation(() => {}))

  it.each([
    assetError(),
    new TypeError('error loading dynamically imported module: http://localhost/assets/old.js'),
    new TypeError('Importing a module script failed.'),
    new Error('Unable to preload CSS for http://localhost/assets/old.css')
  ])('recognizes a browser import or Vite CSS failure: %s', (error) => {
    expect(isRouteAssetError(error)).toBe(true)
  })

  it.each([
    new TypeError('Failed to fetch'),
    new Error('HTTP 500'),
    new Error('Network Error'),
    new SyntaxError("Unexpected token '<'"),
    new ReferenceError('missing is not defined'),
    new DOMException('Aborted', 'AbortError'),
    'Failed to fetch dynamically imported module',
    null
  ])('does not classify an application or API error as a stale asset: %s', (error) => {
    expect(isRouteAssetError(error)).toBe(false)
  })

  it('retains the current route and requires consent for each failed navigation', async () => {
    const {router, loadPanel, browser, recovery} = setup()
    await router.push('/overview')
    for (let attempt = 0; attempt < 3; attempt++) {
      await expect(router.push('/hibernate?tab=findings#details')).rejects.toThrow('dynamically imported')
      expect(recovery.failure.value.fullPath).toBe('/hibernate?tab=findings#details')
      expect(router.currentRoute.value.path).toBe('/overview')
      expect(browser.location.reload).not.toHaveBeenCalled()
    }
    expect(loadPanel).toHaveBeenCalledTimes(3)
    expect(console.error).toHaveBeenCalledTimes(3)
  })

  it('handles initial navigation failures before the shell mounts', async () => {
    const {router, recovery, browser} = setup()
    await expect(router.push('/hibernate')).rejects.toThrow()
    expect(recovery.failure.value.meta.title).toBe('Hibernate')
    expect(browser.location.reload).not.toHaveBeenCalled()
  })

  it('preserves the custom mount, document query, destination query and nested hash on explicit reload', async () => {
    const {router, recovery, browser} = setup('http://localhost:8083/host/dev-console/?mode=dev#/overview')
    await expect(router.push('/hibernate?tab=findings#details')).rejects.toThrow()
    recovery.reload()
    expect(browser.history.replaceState).toHaveBeenCalledWith(
      browser.history.state,
      '',
      new URL('http://localhost:8083/host/dev-console/?mode=dev#/hibernate?tab=findings#details')
    )
    expect(browser.location.reload).toHaveBeenCalledTimes(1)
  })

  it('clears recovery after successful navigation, but not an aborted navigation', async () => {
    const {router, recovery, browser} = setup()
    await router.push('/overview')
    await expect(router.push('/hibernate')).rejects.toThrow()
    const removeGuard = router.beforeEach(() => false)
    await router.push('/health')
    expect(recovery.failure.value).not.toBeNull()
    removeGuard()
    await router.push('/health')
    expect(recovery.failure.value).toBeNull()
    recovery.reload()
    expect(browser.location.reload).not.toHaveBeenCalled()
  })

  it('keeps genuine module evaluation failures observable without offering a reload', async () => {
    const {router, loadPanel, recovery, browser} = setup()
    const error = new Error('Panel setup failed')
    loadPanel.mockRejectedValue(error)
    await expect(router.push('/hibernate')).rejects.toBe(error)
    expect(console.error).toHaveBeenCalledWith(error)
    expect(recovery.failure.value).toBeNull()
    expect(browser.location.reload).not.toHaveBeenCalled()
  })
})
