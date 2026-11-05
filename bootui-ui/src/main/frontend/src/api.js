import {resolveBootUiApiUrl} from './utils/bootUiPath.js'

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE'])
const ACTION_BUSY_ERROR = 'BootUI action already in progress'

export class ApiError extends Error {
  constructor(status, body = null) {
    super(isPanelAccessDenied(status, body) ? `${body.reason.trim()} (HTTP ${status})` : `HTTP ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.body = body
  }
}

function isPanelAccessDenied(status, body) {
  return (
    status === 403 &&
    body?.error === 'BootUI panel access denied' &&
    typeof body.reason === 'string' &&
    body.reason.trim().length > 0
  )
}

export function isPanelAccessError(error) {
  return error instanceof ApiError && isPanelAccessDenied(error.status, error.body)
}

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
        await fetch(resolveBootUiApiUrl('api/overview'), {cache: 'no-store'})
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

  return fetch(resolveBootUiApiUrl(input), options)
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
  if (!res.ok) throw await apiError(res)
  return res.json()
}

export function isActionBusyError(error) {
  return error instanceof ApiError && error.status === 409 && error.body?.error === ACTION_BUSY_ERROR
}

export function actionBusyMessage(error) {
  return isActionBusyError(error) && typeof error.body?.message === 'string'
    ? error.body.message
    : 'This action is already in progress.'
}

export async function apiError(response) {
  if (!response.headers?.get('content-type')?.toLowerCase().includes('json')) {
    return new ApiError(response.status)
  }
  try {
    return new ApiError(response.status, await response.json())
  } catch {
    return new ApiError(response.status)
  }
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
