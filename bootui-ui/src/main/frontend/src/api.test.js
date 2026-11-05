import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {actionBusyMessage, apiError, ApiError, apiFetch, getJson, isActionBusyError} from './api.js'

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

describe('getJson', () => {
  it('keeps canonical panel denial details and HTTP status in the error message', async () => {
    const body = {error: 'BootUI panel access denied', panel: 'memory', reason: 'Memory is read-only.'}
    const error = await apiError(
      new Response(JSON.stringify(body), {
        status: 403,
        headers: {'Content-Type': 'application/json'}
      })
    )
    expect(error.message).toBe('Memory is read-only. (HTTP 403)')
    expect(error.body).toEqual(body)
    expect(error.status).toBe(403)
  })

  it.each([
    null,
    {},
    {reason: 'Do not expose an arbitrary reason'},
    {error: 'Other error', reason: 'Do not expose this either'},
    {error: 'BootUI panel access denied', reason: {}},
    {error: 'BootUI panel access denied', reason: ' '},
    {error: 'BootUI panel access denied', reason: ['not a string']}
  ])('does not expose unrecognized or invalid response details: %j', (body) => {
    expect(new ApiError(403, body).message).toBe('HTTP 403')
  })

  it.each([
    ['text/html', '<html>Private server error</html>'],
    ['application/json', '{not valid JSON']
  ])('uses the HTTP fallback for %s responses', async (contentType, body) => {
    const error = await apiError(new Response(body, {status: 403, headers: {'Content-Type': contentType}}))
    expect(error.message).toBe('HTTP 403')
  })

  beforeEach(() => {
    clearXsrfCookie()
  })

  afterEach(() => {
    clearXsrfCookie()
    vi.unstubAllGlobals()
  })

  it('returns the parsed JSON body on a successful response', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('{"value":42}', {status: 200})))
    )

    await expect(getJson('api/health')).resolves.toEqual({value: 42})
    expect(fetch).toHaveBeenCalledWith('api/health', {})
  })

  it('throws with the HTTP status on a non-OK response', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response('nope', {status: 503})))
    )

    await expect(getJson('api/health')).rejects.toThrow('HTTP 503')
  })

  it('preserves the canonical busy response for action-aware callers', async () => {
    const body = {
      error: 'BootUI action already in progress',
      operation: 'architecture.scan',
      activeOperation: 'architecture.scan',
      message: "Operation 'architecture.scan' cannot start while 'architecture.scan' is in progress."
    }
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify(body), {
            status: 409,
            headers: {'Content-Type': 'application/json'}
          })
        )
      )
    )

    const error = await getJson('api/architecture/scan').catch((failure) => failure)

    expect(isActionBusyError(error)).toBe(true)
    expect(actionBusyMessage(error)).toBe(body.message)
    expect(error.status).toBe(409)
    expect(error.body).toEqual(body)
  })
})
