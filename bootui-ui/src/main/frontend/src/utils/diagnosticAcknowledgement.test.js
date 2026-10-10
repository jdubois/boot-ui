import {describe, expect, it} from 'vitest'
import {ApiError} from '../api.js'
import {
  diagnosticActionError,
  isCacheClearAcknowledgement,
  isDevServiceRestartAcknowledgement,
  isDevToolsAcknowledgement,
  isHttpSessionAcknowledgement,
  isMcpServerStatus
} from './diagnosticAcknowledgement.js'

describe('diagnostic action refusal details', () => {
  it('preserves the specific panel reason rather than only the canonical generic error', () => {
    const error = new ApiError(403, {
      error: 'BootUI panel access denied',
      panel: 'mcp-server',
      reason: "Panel 'mcp-server' is read-only (bootui.panels.mcp-server.read-only=true)"
    })

    expect(diagnosticActionError(error, 'Could not toggle')).toBe(
      "Panel 'mcp-server' is read-only (bootui.panels.mcp-server.read-only=true)"
    )
  })

  it('preserves an action-specific message and a canonical authentication refusal', () => {
    expect(
      diagnosticActionError(
        new ApiError(409, {error: 'unavailable', message: 'Runtime journal is disabled.'}),
        'Failed'
      )
    ).toBe('Runtime journal is disabled.')
    expect(
      diagnosticActionError(new ApiError(403, {error: 'BootUI is only accessible from localhost'}), 'Failed')
    ).toBe('BootUI is only accessible from localhost')
  })

  it.each([null, '<html>not JSON</html>', {error: {}, reason: [], message: 42}, {reason: ' '}])(
    'uses the HTTP fallback for unvalidated error detail %j',
    (body) => {
      expect(diagnosticActionError(new ApiError(403, body), 'Failed')).toBe('HTTP 403')
    }
  )
})

describe('acknowledgement DTO recognition', () => {
  it('accepts documented action outcomes and ignores additional fields', () => {
    expect(
      isDevToolsAcknowledgement(
        {action: 'livereload', status: 'no_clients', message: 'No browsers connected.'},
        'livereload'
      )
    ).toBe(true)
    expect(
      isDevToolsAcknowledgement({action: 'restart', status: 'scheduled', message: 'Restart scheduled.'}, 'restart')
    ).toBe(true)
    expect(
      isCacheClearAcknowledgement({
        status: 'cleared',
        message: 'No cache to clear.',
        clearedCaches: 0,
        caches: [],
        future: true
      })
    ).toBe(true)
    expect(
      isDevServiceRestartAcknowledgement({id: 'service', status: 'restarted', message: 'Service restarted.'}, 'service')
    ).toBe(true)
    expect(
      isHttpSessionAcknowledgement(
        {status: 'destroyed', message: 'Destroyed.', sessionKey: 'key', affectedAttributes: 0},
        'invalidate',
        'key'
      )
    ).toBe(true)
  })

  it('recognizes the stable MCP report without inventing a toggle status or requiring newer counters', () => {
    const report = {
      enabled: true,
      configuredMode: 'AUTO',
      overridden: false,
      serverName: 'bootui',
      serverVersion: 'dev',
      transport: 'http',
      endpoint: '/custom/api/mcp',
      protocolVersion: '2025-06-18',
      maxResults: 200,
      toolCount: 0,
      tools: [],
      future: 'accepted'
    }
    expect(isMcpServerStatus(report)).toBe(true)
    expect(isMcpServerStatus({...report, enabled: 'false'})).toBe(false)
    expect(isMcpServerStatus({...report, tools: {}})).toBe(false)
    expect(isMcpServerStatus({enabled: false})).toBe(false)
  })

  it.each([null, {}, [], {status: 'cleared', message: 'Done'}])('rejects non-acknowledgements %j', (value) => {
    expect(isCacheClearAcknowledgement(value)).toBe(false)
    expect(isDevServiceRestartAcknowledgement(value, 'service')).toBe(false)
    expect(isDevToolsAcknowledgement(value, 'restart')).toBe(false)
    expect(isHttpSessionAcknowledgement(value, 'clear', 'key')).toBe(false)
  })
})
