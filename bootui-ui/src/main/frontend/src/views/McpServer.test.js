import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import McpServer from './McpServer.vue'
import PanelHeader from './components/PanelHeader.vue'

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
    supportedProtocolVersions: ['2026-07-28', '2025-06-18'],
    maxResults: 200,
    callCount: 7,
    timeouts: 1,
    cancellations: 2,
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

function snippetFor(wrapper, client) {
  return wrapper.get(`#mcp-client-${client}-panel .config-block`).text()
}

describe('McpServer', () => {
  let wrapper
  let originalLocation

  beforeEach(() => {
    vi.useFakeTimers()
    originalLocation = window.location
    Object.defineProperty(document, 'visibilityState', {configurable: true, value: 'visible'})
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    Object.defineProperty(window, 'location', {configurable: true, value: originalLocation})
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('shows an unavailable state when the status cannot be loaded', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Failed to fetch')))

    wrapper = mount(McpServer)
    await flushPromises()

    expect(wrapper.text()).toContain('MCP server status is unavailable')
  })

  it('lists both protocol eras and counts timeouts apart from cancellations', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(mcpStatus())))

    wrapper = mount(McpServer)
    await flushPromises()

    const protocols = wrapper.get('[data-testid="mcp-protocols"]').text()
    expect(protocols).toContain('2026-07-28, 2025-06-18')
    expect(protocols).toContain('progress')
    expect(wrapper.get('[data-testid="mcp-call-stats"]').text()).toBe('7 · 1 timed out · 2 cancelled')
  })

  it('shows dropped progress events only when there are some', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(mcpStatus({progressDropped: 0}))))
    wrapper = mount(McpServer)
    await flushPromises()
    expect(wrapper.find('[data-testid="mcp-progress-dropped"]').exists()).toBe(false)
    wrapper.unmount()

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(mcpStatus({progressDropped: 3}))))
    wrapper = mount(McpServer)
    await flushPromises()
    expect(wrapper.get('[data-testid="mcp-progress-dropped"]').text()).toBe('3 over bootui.mcp.max-response-bytes')
  })

  it('falls back to the single advertised revision', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(mcpStatus({supportedProtocolVersions: undefined}))))

    wrapper = mount(McpServer)
    await flushPromises()

    expect(wrapper.get('[data-testid="mcp-protocols"]').text()).toBe('2025-06-18')
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
    expect(wrapper.text()).toContain('URL')
    expect(wrapper.text()).toContain('/bootui/api/mcp')
    expect(wrapper.get('#mcp-enabled-toggle').element.checked).toBe(false)
  })

  it('explains the assessment prompt above client configuration without invoking MCP', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(mcpStatus())))

    wrapper = mount(McpServer)
    await flushPromises()

    const assessment = wrapper.get('section[aria-labelledby="mcp-assessment-heading"]')
    expect(assessment.text()).toContain('MCP prompt, not a tool')
    expect(assessment.text()).toContain('does not appear under Tools exposed')
    expect(assessment.text()).toContain('Without prompt support')
    expect(assessment.get('blockquote').text()).toContain('ask before fresh scans')
    expect(assessment.get('blockquote').text()).toContain('until I approve specific actions')
    expect(assessment.text()).toContain('does not run scans or fixes')
    expect(assessment.element.nextElementSibling.textContent).toContain('Client configuration')
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(fetch).toHaveBeenCalledWith('api/mcp-server', {})
  })

  it('posts the new state when the toggle is flipped', async () => {
    document.cookie = 'XSRF-TOKEN=test-token'
    let enabled = false
    const fetchMock = vi.fn().mockImplementation((url) => {
      if (url === 'api/mcp-server/toggle') {
        enabled = true
        return Promise.resolve(jsonResponse(mcpStatus({enabled: true, overridden: true})))
      }
      return Promise.resolve(jsonResponse(mcpStatus({enabled})))
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

  it.each(['invalid JSON', 'empty', 'wrong shape'])(
    'preserves accepted status after an %s acknowledgement and reports unknown outcome',
    async (kind) => {
      document.cookie = 'XSRF-TOKEN=test-token'
      let reads = 0
      const fetchMock = vi.fn((url) => {
        if (url === 'api/mcp-server/toggle') {
          return Promise.resolve(
            kind === 'wrong shape'
              ? jsonResponse({enabled: false})
              : new Response(kind === 'empty' ? '' : '<html>Not JSON</html>')
          )
        }
        reads++
        return reads === 1
          ? Promise.resolve(jsonResponse(mcpStatus({enabled: true})))
          : Promise.reject(new Error('status temporarily unreachable'))
      })
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mount(McpServer)
      await flushPromises()
      wrapper.getComponent(PanelHeader).vm.$emit('update:autoRefresh', false)
      await wrapper.get('#mcp-enabled-toggle').trigger('change')
      await flushPromises()
      expect(wrapper.get('#mcp-enabled-toggle').element.checked).toBe(true)
      expect(wrapper.text()).toContain('action outcome is unknown')
      expect(wrapper.text()).not.toContain('MCP server disabled.')
      expect(reads).toBe(2)
      expect(fetchMock.mock.calls.filter(([url]) => url === 'api/mcp-server/toggle')).toHaveLength(1)
    }
  )

  it('supersedes a late poll with the valid toggle report and completes its follow-up with auto-refresh off', async () => {
    document.cookie = 'XSRF-TOKEN=test-token'
    let finishStale
    let finishFresh
    let reads = 0
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        if (url === 'api/mcp-server/toggle')
          return Promise.resolve(jsonResponse(mcpStatus({enabled: true, overridden: true})))
        reads++
        if (reads === 1) return Promise.resolve(jsonResponse(mcpStatus()))
        return new Promise((resolve) => {
          if (reads === 2) finishStale = resolve
          else finishFresh = resolve
        })
      })
    )
    wrapper = mount(McpServer)
    await flushPromises()
    const header = wrapper.getComponent(PanelHeader)
    header.vm.$emit('update:autoRefresh', false)
    header.vm.$emit('refresh')
    await flushPromises()
    await wrapper.get('#mcp-enabled-toggle').trigger('change')
    await flushPromises()
    expect(wrapper.get('#mcp-enabled-toggle').element.checked).toBe(true)
    finishStale(jsonResponse(mcpStatus()))
    await flushPromises()
    expect(wrapper.get('#mcp-enabled-toggle').element.checked).toBe(true)
    expect(reads).toBe(3)
    finishFresh(jsonResponse(mcpStatus({enabled: true, callCount: 99})))
    await flushPromises()
    expect(wrapper.get('[data-testid="mcp-call-stats"]').text()).toContain('99')
  })

  it('renders a copyable MCP client configuration for each supported client', async () => {
    const writeText = vi.fn().mockResolvedValue()
    vi.stubGlobal('navigator', {clipboard: {writeText}})
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(mcpStatus())))

    wrapper = mount(McpServer)
    await flushPromises()

    expect(wrapper.text()).toContain('Client configuration')

    const vsCode = snippetFor(wrapper, 'vscode')
    expect(vsCode).toContain('"servers"')
    expect(vsCode).toContain('"bootui"')
    expect(vsCode).toContain('"type": "http"')
    expect(vsCode).toContain('/bootui/api/mcp')

    expect(snippetFor(wrapper, 'claude')).toContain('claude mcp add --transport http bootui')
    expect(snippetFor(wrapper, 'claude')).toContain('/bootui/api/mcp')

    const cursor = snippetFor(wrapper, 'cursor')
    expect(cursor).toContain('"mcpServers"')
    expect(cursor).not.toContain('"type"')

    const generic = snippetFor(wrapper, 'json')
    expect(generic).toContain('"mcpServers"')
    expect(generic).toContain('"type": "http"')

    const copyButton = wrapper.findAll('button').find((b) => b.text().includes('Copy'))
    await copyButton.trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledTimes(1)
    expect(writeText.mock.calls[0][0]).toContain('"servers"')
    expect(writeText.mock.calls[0][0]).toContain('/bootui/api/mcp')
  })

  it('copies the snippet of the selected client tab', async () => {
    const writeText = vi.fn().mockResolvedValue()
    vi.stubGlobal('navigator', {clipboard: {writeText}})
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(mcpStatus())))

    wrapper = mount(McpServer)
    await flushPromises()

    await wrapper.get('#mcp-client-claude-tab').trigger('click')
    const copyButton = wrapper.findAll('button').find((b) => b.text().includes('Copy'))
    await copyButton.trigger('click')
    await flushPromises()

    expect(wrapper.get('#mcp-client-claude-tab').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('#mcp-client-vscode-tab').attributes('aria-selected')).toBe('false')
    expect(writeText.mock.calls[0][0]).toContain('claude mcp add')
  })

  it('moves between client tabs with the arrow keys', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(mcpStatus())))

    wrapper = mount(McpServer)
    await flushPromises()

    await wrapper.get('#mcp-client-vscode-tab').trigger('keydown', {key: 'ArrowRight'})
    expect(wrapper.get('#mcp-client-claude-tab').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('#mcp-client-claude-tab').attributes('tabindex')).toBe('0')
    expect(wrapper.get('#mcp-client-vscode-tab').attributes('tabindex')).toBe('-1')

    await wrapper.get('#mcp-client-claude-tab').trigger('keydown', {key: 'End'})
    expect(wrapper.get('#mcp-client-json-tab').attributes('aria-selected')).toBe('true')

    await wrapper.get('#mcp-client-json-tab').trigger('keydown', {key: 'ArrowRight'})
    expect(wrapper.get('#mcp-client-vscode-tab').attributes('aria-selected')).toBe('true')
  })

  it('adds the bearer header to every snippet when the agent is not on loopback', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(mcpStatus())))

    wrapper = mount(McpServer)
    await flushPromises()

    expect(snippetFor(wrapper, 'vscode')).not.toContain('Authorization')
    expect(snippetFor(wrapper, 'claude')).not.toContain('--header')

    await wrapper.get('#mcp-remote-agent').setValue(true)

    expect(snippetFor(wrapper, 'vscode')).toContain('"Authorization": "Bearer <BootUI authentication token>"')
    expect(snippetFor(wrapper, 'cursor')).toContain('"Authorization": "Bearer <BootUI authentication token>"')
    expect(snippetFor(wrapper, 'json')).toContain('"Authorization": "Bearer <BootUI authentication token>"')
    expect(snippetFor(wrapper, 'claude')).toContain('--header "Authorization: Bearer <BootUI authentication token>"')

    await wrapper.get('#mcp-remote-agent').setValue(false)

    expect(snippetFor(wrapper, 'vscode')).not.toContain('Authorization')
    expect(snippetFor(wrapper, 'claude')).not.toContain('--header')
  })

  it('always explains that a non-loopback agent needs the bearer header', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(mcpStatus())))

    wrapper = mount(McpServer)
    await flushPromises()

    expect(wrapper.text()).toContain('Agent connects from another host or container')
    expect(wrapper.text()).toContain('must send BootUI')
    expect(wrapper.text()).toContain('bootui.authentication.token')
  })

  it('pre-selects the bearer header when the browser itself is remote', async () => {
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: {origin: 'https://devbox.example.com', hostname: 'devbox.example.com'}
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(mcpStatus())))

    wrapper = mount(McpServer)
    await flushPromises()

    expect(wrapper.get('#mcp-remote-agent').element.checked).toBe(true)
    expect(snippetFor(wrapper, 'vscode')).toContain('"Authorization": "Bearer <BootUI authentication token>"')
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
