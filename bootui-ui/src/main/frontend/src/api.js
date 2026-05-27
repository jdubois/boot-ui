const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE'])

export async function apiFetch(input, init = {}) {
  const options = { ...init }
  const method = (options.method || 'GET').toUpperCase()

  if (!SAFE_METHODS.has(method)) {
    let token = csrfToken()
    if (!token) {
      await fetch('api/overview', { cache: 'no-store' })
      token = csrfToken()
    }
    if (token) {
      const headers = new Headers(options.headers || {})
      headers.set('X-XSRF-TOKEN', token)
      options.headers = headers
    }
  }

  return fetch(input, options)
}

function csrfToken() {
  if (typeof document === 'undefined') return null

  const cookie = document.cookie
    .split(';')
    .map(part => part.trim())
    .find(part => part.startsWith('XSRF-TOKEN='))

  if (!cookie) return null
  return decodeURIComponent(cookie.substring('XSRF-TOKEN='.length))
}
