// @ts-check
import {acceptConfirm, expect, test} from './fixtures.js'

test.describe('Transactions view', () => {
  test('shows the independent audit boundary without turning the ship control into a child', async ({
    openView,
    page
  }) => {
    expect((await page.request.post('/api/insights/orders/2/confirm')).ok()).toBeTruthy()
    expect((await page.request.post('/api/insights/orders/2/confirm')).ok()).toBeTruthy()
    expect((await page.request.post('/api/insights/orders/3/ship')).ok()).toBeTruthy()
    const report = await (await page.request.get('/bootui/api/transactions')).json()
    const confirm = report.entries.find((entry) => entry.methodName.endsWith('InsightOrderService.confirm'))
    const audit = report.entries.find((entry) =>
      entry.methodName.endsWith('InsightAuditWriter.recordInItsOwnTransaction')
    )
    const ship = report.entries.find((entry) => entry.methodName.endsWith('InsightOrderService.ship'))
    expect(confirm).toMatchObject({propagation: 'NEW', connectionCount: 2})
    expect(audit).toMatchObject({propagation: 'REQUIRES_NEW', parentId: confirm.id})
    // SQL correlation uses millisecond windows, so the parent statement can share this boundary's start tick.
    expect([1, 2]).toContain(audit.connectionCount)
    expect(ship).toMatchObject({propagation: 'NEW', parentId: null, connectionCount: 1})
    expect(report.entries.filter((entry) => entry.parentId === ship.id)).toHaveLength(0)

    await openView('transactions', 'Transactions')
    const row = page.locator('tr.tx-row-nested').filter({
      has: page.locator(`button[aria-controls="tx-details-${audit.id}"]`)
    })
    await expect(row).toContainText('REQUIRES_NEW')
    await row.click()
    await expect(page.locator('.tx-detail-row').first()).toBeVisible()
    await page.locator('input.trace-filter').fill('REQUIRES_NEW')
    await expect(page.locator('table.tx-table')).toContainText('InsightAuditWriter.recordInItsOwnTransaction')
    await expect(page.locator('table.tx-table')).not.toContainText('InsightOrderService.ship')
  })

  test('captures the sample product query and refreshes automatically', async ({openView, page}) => {
    await openView('transactions', 'Transactions')

    const clearButton = page.getByRole('button', {name: 'Clear'})
    if (await clearButton.isEnabled()) {
      await clearButton.click()
      await acceptConfirm(page)
      await expect(page.locator('.alert-success')).toBeVisible()
    }

    const response = await page.request.get('/api/sample/product-search?term=console')
    expect(response.status()).toBe(200)

    const executions = page.locator('section').filter({hasText: 'Recent transactions'})
    await expect(executions).toContainText('SampleCatalog.searchProducts')
    await expect(executions).toContainText('COMMITTED')
  })

  test('generates representative transaction samples from the sample app', async ({openView, page}) => {
    await page.goto('/')
    await page.getByRole('tab', {name: 'Data', exact: true}).click()
    await page.getByRole('button', {name: 'Generate transaction samples'}).click()
    await expect(page.locator('#sample-action-status')).toContainText('Generated 4 transaction scenarios')

    await openView('transactions', 'Transactions')
    const executions = page.locator('section').filter({hasText: 'Recent transactions'})
    await expect(executions).toContainText('SampleTransactionScenarios.commit')
    await expect(executions).toContainText('SampleTransactionScenarios.slowCommit')
    await expect(executions).toContainText('SampleTransactionScenarios.rollBack')
    await expect(executions).toContainText('ROLLED_BACK')
    await expect(executions).toContainText('slow')
    await expect(executions).toContainText('nested')
  })
})
