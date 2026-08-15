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
})
