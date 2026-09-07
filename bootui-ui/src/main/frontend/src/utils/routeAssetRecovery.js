import {readonly, shallowRef} from 'vue'

export const routeAssetRecoveryKey = Symbol('routeAssetRecovery')

export function isRouteAssetError(error) {
  if (!(error instanceof Error)) return false
  return (
    (error instanceof TypeError &&
      /^(Failed to fetch dynamically imported module(?::|$)|error loading dynamically imported module(?::|$)|Importing a module script failed\.?$)/i.test(
        error.message
      )) ||
    /^Unable to preload CSS for /.test(error.message)
  )
}

export function createRouteAssetRecovery(router, browser = window) {
  const failure = shallowRef(null)

  router.onError((error, to) => {
    // Keep genuine router/application errors observable; only asset failures get a reload action.
    console.error(error)
    if (isRouteAssetError(error)) failure.value = to
  })
  router.afterEach((_to, _from, navigationFailure) => {
    if (!navigationFailure) failure.value = null
  })

  function reload() {
    if (!failure.value) return
    const url = new URL(browser.location.href)
    url.hash = failure.value.fullPath
    // A hash-only navigation does not fetch a fresh entry module. Reload explicitly, only on consent.
    browser.history.replaceState(browser.history.state, '', url)
    browser.location.reload()
  }

  return {failure: readonly(failure), reload}
}
