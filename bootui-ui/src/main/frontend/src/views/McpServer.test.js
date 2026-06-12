import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import McpServer from './McpServer.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function mcpStatus(overrides = {}) {
  return {
    enabled: false,
    configuredMode: 'OFF',
    overridden: false,
    serverName: 'bootui',
    serverVersion: 'dev',
    transport: 'http',
    endpoint: '/bootui/api/mcp',
    protocolVersion: '2025-06-18',
    maxResults: 200,
    toolCount: 2,
    tools: [
      {
        name: 'architecture_scan',
        description: 'Run the Architecture advisor.',
        panel: 'architecture',
        action: true,
        panelEnabled: true,
        panelReadOnly: false
      },
      {
        name: 'get_overview',
        description: 'Read the overview.',
        panel: 'overview',
        action: false,
        panelEnabled: true,
        panelReadOnly: false
      }
    ],
    ...overrides
  }
}

describe('McpServer', () => {
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

  it('shows an unavailable state when the status cannot be loaded', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Failed to fetch')))

    wrapper = mount(McpServer)
    await flushPromises()

    expect(wrapper.text()).toContain('MCP server status is unavailable')
  })

  it('renders the toggle, explanation, and tool catalog', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(mcpStatus())))

    wrapper = mount(McpServer)
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/mcp-server', {})
    expect(wrapper.text()).toContain('MCP server is')
    expect(wrapper.text()).toContain('What this server does')
    expect(wrapper.text()).toContain('architecture_scan')
    expect(wrapper.text()).toContain('get_overview')
    expect(wrapper.get('#mcp-enabled-toggle').element.checked).toBe(false)
  })

  it('posts the new state when the toggle is flipped', async () => {
    document.cookie = 'XSRF-TOKEN=test-token'
    const fetchMock = vi.fn().mockImplementation((url) => {
      if (url === 'api/mcp-server/toggle') {
        return Promise.resolve(jsonResponse(mcpStatus({enabled: true, overridden: true})))
      }
      return Promise.resolve(jsonResponse(mcpStatus()))
    })
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(McpServer)
    await flushPromises()

    await wrapper.get('#mcp-enabled-toggle').trigger('change')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      'api/mcp-server/toggle',
      expect.objectContaining({method: 'POST', body: JSON.stringify({enabled: true})})
    )
    expect(wrapper.get('#mcp-enabled-toggle').element.checked).toBe(true)
  })

  it('does not toggle and warns when the panel is read-only', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(mcpStatus()))
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(McpServer, {props: {panel: {readOnly: true, readOnlyReason: 'locked'}}})
    await flushPromises()
    fetchMock.mockClear()

    await wrapper.get('#mcp-enabled-toggle').trigger('change')
    await flushPromises()

    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('locked')
  })
})
