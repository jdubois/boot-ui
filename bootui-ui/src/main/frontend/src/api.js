const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE'])

/**
 * @param {RequestInfo | URL} input
 * @param {RequestInit} [init]
 */
export async function apiFetch(input, init = {}) {
  const options = {...init}
  const method = (options.method || 'GET').toUpperCase()

  if (!SAFE_METHODS.has(method)) {
    const headers = new Headers(options.headers || {})
    let shouldSetHeaders = options.headers !== undefined
    if (!headers.has('X-XSRF-TOKEN')) {
      let token = csrfToken()
      if (!token) {
        await fetch('api/overview', {cache: 'no-store'})
        token = csrfToken()
      }
      if (token) {
        headers.set('X-XSRF-TOKEN', token)
        shouldSetHeaders = true
      }
    }
    if (shouldSetHeaders) {
      options.headers = headers
    }
  }

  return fetch(input, options)
}

/**
 * Fetches a URL via {@link apiFetch} and returns the parsed JSON body,
 * throwing on a non-OK response.
 *
 * @param {RequestInfo | URL} input
 * @param {RequestInit} [init]
 * @returns {Promise<any>}
 */
export async function getJson(input, init) {
  const res = await apiFetch(input, init)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

function csrfToken() {
  if (typeof document === 'undefined') return null

  const cookie = document.cookie
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith('XSRF-TOKEN='))

  if (!cookie) return null
  return decodeURIComponent(cookie.substring('XSRF-TOKEN='.length))
}
