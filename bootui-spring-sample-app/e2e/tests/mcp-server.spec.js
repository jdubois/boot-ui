// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The MCP Server panel's client-configuration card is shared UI: one tab per MCP client, each with the
 * shape that client actually accepts, plus the switch that adds BootUI's bearer header for an agent that
 * does not reach the app over loopback. This spec never touches the server toggle, so it is safe to run
 * alongside the suites that do.
 */
test.describe('MCP Server client configuration', () => {
  test('offers one snippet per client and moves between them from the keyboard', async ({openView}) => {
    const page = await openView('mcp-server', 'MCP Server')

    const tablist = page.getByRole('tablist', {name: 'MCP client'})
    await expect(tablist).toHaveCount(1)
    const vsCode = page.getByRole('tab', {name: 'VS Code'})
    const claude = page.getByRole('tab', {name: 'Claude Code'})
    const other = page.getByRole('tab', {name: 'Other clients'})

    // Exactly one tab is selected and reachable with Tab; the rest are removed from the tab order.
    await expect(page.getByRole('tab', {selected: true})).toHaveCount(1)
    await expect(vsCode).toHaveAttribute('aria-selected', 'true')
    await expect(claude).toHaveAttribute('tabindex', '-1')

    const vsCodeSnippet = page.locator('#mcp-client-vscode-panel .config-block')
    await expect(vsCodeSnippet).toBeVisible()
    await expect(vsCodeSnippet).toContainText('"servers"')
    await expect(vsCodeSnippet).toContainText('/bootui/api/mcp')

    await vsCode.focus()
    await page.keyboard.press('ArrowRight')
    await expect(claude).toHaveAttribute('aria-selected', 'true')
    await expect(claude).toBeFocused()

    const claudeSnippet = page.locator('#mcp-client-claude-panel .config-block')
    await expect(claudeSnippet).toBeVisible()
    await expect(claudeSnippet).toContainText('claude mcp add --transport http bootui')
    await expect(vsCodeSnippet).toBeHidden()

    await page.keyboard.press('End')
    await expect(other).toHaveAttribute('aria-selected', 'true')
    await expect(page.locator('#mcp-client-json-panel .config-block')).toContainText('"mcpServers"')
    await expect(page.getByRole('tab', {selected: true})).toHaveCount(1)

    // Cursor keys a remote server on `url` alone.
    await page.getByRole('tab', {name: 'Cursor'}).click()
    const cursorSnippet = page.locator('#mcp-client-cursor-panel .config-block')
    await expect(cursorSnippet).toContainText('"mcpServers"')
    await expect(cursorSnippet).not.toContainText('"type"')
  })

  test('adds the bearer header to every snippet for an agent that is not on loopback', async ({openView}) => {
    const page = await openView('mcp-server', 'MCP Server')

    const remoteAgent = page.locator('#mcp-remote-agent')
    await expect(remoteAgent).not.toBeChecked()
    await expect(page.locator('#mcp-client-vscode-panel .config-block')).not.toContainText('Authorization')

    await remoteAgent.check()

    await expect(page.locator('#mcp-client-vscode-panel .config-block')).toContainText('"Authorization"')
    await expect(page.locator('#mcp-client-claude-panel .config-block')).toContainText('--header "Authorization:')
    await expect(page.locator('#mcp-client-cursor-panel .config-block')).toContainText('"Authorization"')
    await expect(page.locator('#mcp-client-json-panel .config-block')).toContainText('"Authorization"')

    await expect(page.getByText('bootui.authentication.token')).toBeVisible()
  })
})
