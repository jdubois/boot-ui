import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {apiFetch} from './api.js'

function clearXsrfCookie() {
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
}

function setXsrfCookie(value) {
  document.cookie = `XSRF-TOKEN=${encodeURIComponent(value)}; path=/`
}

function okResponse() {
  return Promise.resolve(new Response('{}', {status: 200}))
}

describe('apiFetch', () => {
  beforeEach(() => {
    clearXsrfCookie()
    vi.stubGlobal('fetch', vi.fn(okResponse))
  })

  afterEach(() => {
    clearXsrfCookie()
    vi.unstubAllGlobals()
  })

  it('does not add CSRF headers for safe methods', async () => {
    await apiFetch('api/overview', {
      method: 'GET',
      headers: {Accept: 'application/json'}
    })

    expect(fetch).toHaveBeenCalledTimes(1)
    expect(fetch).toHaveBeenCalledWith('api/overview', {
      method: 'GET',
      headers: {Accept: 'application/json'}
    })
  })

  it('adds the CSRF cookie value to unsafe requests', async () => {
    setXsrfCookie('token value')

    await apiFetch('api/config/overrides', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'}
    })

    expect(fetch).toHaveBeenCalledTimes(1)
    const headers = fetch.mock.calls[0][1].headers
    expect(headers).toBeInstanceOf(Headers)
    expect(headers.get('Content-Type')).toBe('application/json')
    expect(headers.get('X-XSRF-TOKEN')).toBe('token value')
  })

  it('preserves caller-provided CSRF headers for unsafe requests', async () => {
    await apiFetch('api/config/overrides', {
      method: 'POST',
      headers: {'X-XSRF-TOKEN': 'caller-token'}
    })

    expect(fetch).toHaveBeenCalledTimes(1)
    const headers = fetch.mock.calls[0][1].headers
    expect(headers).toBeInstanceOf(Headers)
    expect(headers.get('X-XSRF-TOKEN')).toBe('caller-token')
  })

  it('loads overview once before unsafe requests when the CSRF cookie is missing', async () => {
    fetch.mockImplementationOnce(() => {
      setXsrfCookie('loaded-token')
      return okResponse()
    })

    await apiFetch('api/config/overrides', {method: 'DELETE'})

    expect(fetch).toHaveBeenCalledTimes(2)
    expect(fetch).toHaveBeenNthCalledWith(1, 'api/overview', {cache: 'no-store'})

    const headers = fetch.mock.calls[1][1].headers
    expect(headers).toBeInstanceOf(Headers)
    expect(headers.get('X-XSRF-TOKEN')).toBe('loaded-token')
  })

  it('leaves unsafe requests without a CSRF header when overview does not issue a token', async () => {
    await apiFetch('api/config/overrides', {method: 'PUT'})

    expect(fetch).toHaveBeenCalledTimes(2)
    expect(fetch).toHaveBeenNthCalledWith(1, 'api/overview', {cache: 'no-store'})
    expect(fetch.mock.calls[1][1]).toEqual({method: 'PUT'})
  })
})
