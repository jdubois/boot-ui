// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Java Agent view (Quarkus)', () => {
  test('shows the not-attached agent status, setup snippets, copy, and API shape', async ({
    openView,
    page,
    browserName,
    context
  }) => {
    if (browserName === 'chromium') {
      try {
        await context.grantPermissions(['clipboard-read', 'clipboard-write'])
      } catch {
        /* no-op */
      }
    }

    const response = await page.request.get('/bootui/api/java-agent')
    expect(response.ok()).toBeTruthy()
    const report = await response.json()
    expect(report.state).toBe('NOT_ATTACHED')
    expect(report.setup.snippets.length).toBeGreaterThan(0)

    await openView('java-agent', 'Java Agent')

    await expect(page.getByRole('heading', {name: 'Not attached'})).toBeVisible()
    await expect(page.getByText('This JVM runs without the BootUI agent')).toBeVisible()
    await expect(
      page.getByText('No sensors yet: executor propagation arrives with the next agent release')
    ).toBeVisible()

    const tabs = page.getByRole('tab')
    await expect(tabs.first()).toBeVisible()
    expect(await tabs.count()).toBeGreaterThan(0)

    if ((await tabs.count()) > 1) {
      const secondTab = tabs.nth(1)
      const panelId = await secondTab.getAttribute('aria-controls')
      await secondTab.click()
      await expect(secondTab).toHaveAttribute('aria-selected', 'true')
      await expect(page.locator(`#${panelId}`)).toBeVisible()
    }

    const activeSnippet = page.getByRole('tabpanel').locator('pre code')
    const snippetText = await activeSnippet.textContent()
    await page.getByRole('button', {name: /^Copy$/}).click()
    await expect(page.getByRole('button', {name: /Copied!/})).toBeVisible({timeout: 5_000})
    expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(snippetText)
  })
})
