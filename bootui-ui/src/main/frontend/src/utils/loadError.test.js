import {describe, expect, it} from 'vitest'
import {ApiError} from '../api.js'

import {
  describeLoadError,
  formatLoadError,
  isAbortError,
  isServerUnreachableError,
  toErrorMessage
} from './loadError.js'

describe('loadError', () => {
  it('includes the canonical policy reason, status and request context', () => {
    const error = new ApiError(403, {
      error: 'BootUI panel access denied',
      panel: 'memory',
      reason: 'BootUI is read-only via bootui.read-only=true'
    })

    expect(formatLoadError(error, 'Unable to run Memory')).toBe(
      'Unable to run Memory: BootUI is read-only via bootui.read-only=true (HTTP 403)'
    )
    expect(describeLoadError(error).serverUnreachable).toBe(false)
  })

  it('does not misclassify policy text containing a network-error phrase', () => {
    const error = new ApiError(403, {
      error: 'BootUI panel access denied',
      panel: 'memory',
      reason: 'Load failed because Memory is read-only.'
    })
    expect(formatLoadError(error)).toBe('Load failed because Memory is read-only. (HTTP 403)')
    expect(describeLoadError(error).serverUnreachable).toBe(false)
  })

  it('detects browser fetch failures as server unreachable', () => {
    expect(isServerUnreachableError(new TypeError('Load failed'))).toBe(true)
    expect(isServerUnreachableError('Could not load beans: Failed to fetch')).toBe(true)
    expect(isServerUnreachableError('HTTP 503')).toBe(false)
  })

  it('describes network failures with a server-not-running tip', () => {
    const description = describeLoadError(new TypeError('Load failed'), 'Unable to load overview')

    expect(description).toEqual({
      title: 'Server unreachable',
      message:
        'BootUI could not reach the application. The server may have been stopped. Start it again, then retry or refresh this page.',
      serverUnreachable: true
    })
  })

  it('keeps HTTP failures specific to the failed request', () => {
    expect(formatLoadError(new Error('HTTP 500'), 'Unable to load overview')).toBe('Unable to load overview: HTTP 500')
  })

  it('formats browser fetch failures with the same backend-down message', () => {
    expect(formatLoadError(new TypeError('Failed to fetch'), 'Unable to load health')).toBe(
      'Server unreachable: BootUI could not reach the application. The server may have been stopped. Start it again, then retry or refresh this page.'
    )
  })

  it('normalizes non-error values', () => {
    expect(toErrorMessage('Load failed')).toBe('Load failed')
    expect(toErrorMessage(null)).toBe('Request failed')
  })

  it('recognizes abort errors without requiring DOMException support', () => {
    expect(isAbortError({name: 'AbortError'})).toBe(true)
    expect(isAbortError(new Error('request failed'))).toBe(false)
    expect(isAbortError(null)).toBe(false)
  })
})
