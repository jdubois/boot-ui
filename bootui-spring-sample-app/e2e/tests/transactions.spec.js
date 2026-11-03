// @ts-check
import {acceptConfirm, expect, test} from './fixtures.js'

test.describe('Transactions view', () => {
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
