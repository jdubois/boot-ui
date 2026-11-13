import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import Cli from './Cli.vue'

const global = {stubs: {RouterLink: {template: '<a><slot /></a>'}}}

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

// Address the install snippets by what they say rather than by their position, so
// adding one does not silently repoint every assertion at a different block.
function blockContaining(wrapper, needle) {
  const text = wrapper.findAll('.config-block').map((block) => block.text())
  const match = text.find((candidate) => candidate.includes(needle))
  if (match === undefined) {
    throw new Error(`No .config-block contains '${needle}'. Blocks: ${JSON.stringify(text)}`)
  }
  return match
}

function cliStatus(overrides = {}) {
  return {
    enabled: true,
    serverName: 'bootui',
    serverVersion: 'dev',
    endpoint: '/bootui/api/cli',
    maxResults: 200,
    callCount: 4,
    totalLatencyMillis: 900,
    capacityRefusals: 0,
    timeouts: 0,
    toolCount: 2,
    tools: [
      {
        name: 'architecture_scan',
        command: 'architecture scan',
        description: 'Run the Architecture advisor.',
        panel: 'architecture',
        action: true,
        schema: 'NONE',
        arguments: [],
        panelEnabled: true,
        panelReadOnly: true
      },
      {
        name: 'get_beans',
        command: 'beans',
        description: 'Read the beans.',
        panel: 'beans',
        action: false,
        schema: 'QUERY_LIMIT',
        arguments: ['query', 'limit'],
        panelEnabled: false,
        panelReadOnly: false
      }
    ],
    ...overrides
  }
}

describe('Cli', () => {
  let wrapper

  beforeEach(() => {
    vi.useFakeTimers()
    Object.defineProperty(document, 'visibilityState', {configurable: true, value: 'visible'})
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    document.querySelector('meta[name="bootui-api-path"]')?.remove()
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('shows an unavailable state when the status cannot be loaded', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Failed to fetch')))

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(wrapper.text()).toContain('Command-line endpoint status is unavailable')
  })

  it('renders the endpoint, explanation, and command catalog', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(cliStatus())))

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/cli', {})
    expect(wrapper.text()).toContain('Command-line access is')
    expect(wrapper.text()).toContain('enabled')
    expect(wrapper.text()).toContain('What this endpoint does')
    expect(wrapper.text()).toContain('bootui instances')
    expect(wrapper.text()).toContain('bootui --app <name>')
    expect(wrapper.text()).toContain('bootui init --vscode')
    expect(wrapper.text()).toContain('architecture_scan')
    expect(wrapper.text()).toContain('get_beans')
    expect(wrapper.text()).toContain('/bootui/api/cli')
  })

  it('lists the command to type, with the tool it maps to as the secondary label', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(cliStatus())))

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(wrapper.text()).toContain('bootui architecture scan')
    expect(wrapper.text()).toContain('bootui beans')
    expect(wrapper.text()).toContain('architecture_scan')
    expect(wrapper.text()).toContain('get_beans')
  })

  it('spells out the arguments a command accepts the way the CLI does', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(cliStatus())))

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(wrapper.text()).toContain('--query --limit')
  })

  it('renders a required id as a positional rather than a flag', async () => {
    const status = cliStatus()
    status.tools[1].arguments = ['id']
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(status)))

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(wrapper.text()).toContain('<id>')
    expect(wrapper.text()).not.toContain('--id')
  })

  it('flags the backing panel state that explains a refusal', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(cliStatus())))

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(wrapper.text()).toContain('read-only')
    expect(wrapper.text()).toContain('panel disabled')
  })

  it('shows the required scan-id and optional paging flags for rule violation reads', async () => {
    const status = cliStatus()
    status.tools[1] = {
      ...status.tools[1],
      name: 'get_architecture_rule_violations',
      command: 'architecture violations',
      schema: 'RULE_VIOLATIONS',
      arguments: ['id', 'scanId', 'offset', 'limit'],
      panelEnabled: true,
      panelReadOnly: true
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(status)))

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(wrapper.text()).toContain('bootui architecture violations')
    expect(wrapper.text()).toContain('<id> --scan-id <scan-id> [--offset <offset>] [--limit <limit>]')
    expect(wrapper.text()).not.toContain('--scanId')
  })

  it('derives mean latency from the call counters', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(cliStatus())))

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(wrapper.text()).toContain('225 ms')
    expect(wrapper.text()).toContain('Mean latency')
  })

  it('shows no mean latency before the first call', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(cliStatus({callCount: 0, totalLatencyMillis: 0}))))

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(wrapper.text()).toContain('Mean latency')
    expect(wrapper.text()).not.toContain('NaN')
  })

  it('reports the disabled endpoint rather than offering a toggle', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(cliStatus({enabled: false, toolCount: 0, tools: []})))
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(wrapper.text()).toContain('bootui.cli.enabled=false')
    expect(wrapper.text()).toContain('No commands are currently available')
    // The panel reports the endpoint, it never switches it: every request it makes is the status read.
    expect(fetchMock.mock.calls.every(([url, options]) => url === 'api/cli' && !options?.method)).toBe(true)
  })

  it('renders a copyable command pointed at this instance', async () => {
    const writeText = vi.fn().mockResolvedValue()
    vi.stubGlobal('navigator', {clipboard: {writeText}})
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(cliStatus())))

    wrapper = mount(Cli, {global})
    await flushPromises()

    const block = blockContaining(wrapper, 'jbang app install')
    expect(block).toContain('jbang app install bootui@jdubois/boot-ui')
    expect(block).toContain('bootui --url ' + window.location.origin + ' tools')
    expect(block).not.toContain('--api-path')

    const copyButton = wrapper.findAll('button').find((b) => b.text().includes('Copy'))
    await copyButton.trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledTimes(1)
    expect(writeText.mock.calls[0][0]).toContain('--url')
  })

  it('spells out --api-path only when the API path is customised', async () => {
    const meta = document.createElement('meta')
    meta.setAttribute('name', 'bootui-api-path')
    meta.setAttribute('content', '/admin/api')
    document.head.appendChild(meta)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(cliStatus())))

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(wrapper.get('.config-block').text()).toContain('--api-path /admin/api')
  })

  it('offers a plain-Java install next to the JBang one', async () => {
    const writeText = vi.fn().mockResolvedValue()
    vi.stubGlobal('navigator', {clipboard: {writeText}})
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(cliStatus({serverVersion: '1.15.0'}))))

    wrapper = mount(Cli, {global})
    await flushPromises()

    const java = blockContaining(wrapper, 'VERSION=')
    expect(java).toContain('VERSION=1.15.0')
    expect(java).toContain('BASE=https://repo1.maven.org/maven2/com/julien-dubois/bootui/bootui-cli')
    expect(java).toContain('curl -fLO "${BASE}/${VERSION}/bootui-cli-${VERSION}-all.jar"')
    expect(java).toContain('java -jar "bootui-cli-${VERSION}-all.jar" --url ' + window.location.origin + ' tools')
    expect(java).not.toContain('jbang')

    const copyButtons = wrapper.findAll('button').filter((button) => button.text().includes('Copy'))
    expect(copyButtons).toHaveLength(3)
    await copyButtons[2].trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledTimes(1)
    expect(writeText.mock.calls[0][0]).toContain('java -jar')
  })

  // The installer the documentation site publishes resolves the version itself, so this
  // snippet must stay free of one however the application reports its own.
  it('offers the one-command install and never pins a version into it', async () => {
    const writeText = vi.fn().mockResolvedValue()
    vi.stubGlobal('navigator', {clipboard: {writeText}})
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(cliStatus({serverVersion: '1.15.0'}))))

    wrapper = mount(Cli, {global})
    await flushPromises()

    const script = blockContaining(wrapper, 'install.sh')
    expect(script).toContain('curl -fsSL https://www.julien-dubois.com/boot-ui/install.sh | sh')
    expect(script).toContain('bootui --url ' + window.location.origin + ' tools')
    expect(script).not.toContain('1.15.0')
    expect(wrapper.text()).toContain('irm https://www.julien-dubois.com/boot-ui/install.ps1 | iex')

    const copyButtons = wrapper.findAll('button').filter((button) => button.text().includes('Copy'))
    await copyButtons[0].trigger('click')
    await flushPromises()

    expect(writeText.mock.calls[0][0]).toContain('install.sh')
  })

  it('asks for a released version when the application reports a development build', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(cliStatus({serverVersion: 'dev'}))))

    wrapper = mount(Cli, {global})
    await flushPromises()

    expect(blockContaining(wrapper, 'VERSION=')).toContain('VERSION=<version>')
    expect(wrapper.text()).toContain('development build')
  })
})
