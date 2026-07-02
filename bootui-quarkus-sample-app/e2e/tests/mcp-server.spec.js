// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The MCP server toggle overrides bootui.mcp.enabled (unset here, so it starts disabled) at runtime via
 * the live McpServerState singleton — no restart required. This round-trips the toggle both ways so the
 * suite is left exactly as it found it (McpServerState is in-memory only, not persisted to disk, and
 * lives for the whole sequential test run).
 */
test.describe('MCP Server (Quarkus)', () => {
  test('toggles the server on and back off, updating status live', async ({openView, page}) => {
    await openView('mcp-server', 'MCP Server')

    const toggle = page.locator('#mcp-enabled-toggle')
    const initiallyEnabled = await toggle.isChecked()
    expect(initiallyEnabled).toBe(false)

    await toggle.click()
    await expect(page.locator('.alert-success')).toContainText('MCP server enabled.')
    await expect(toggle).toBeChecked()
    await expect(page.getByText('Server disabled — tools are not reachable')).not.toBeVisible()

    await toggle.click()
    await expect(page.locator('.alert')).toContainText('MCP server disabled.')
    await expect(toggle).not.toBeChecked()
    await expect(page.getByText('Server disabled — tools are not reachable')).toBeVisible()
  })
})
