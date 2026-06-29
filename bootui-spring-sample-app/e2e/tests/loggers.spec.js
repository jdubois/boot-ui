// @ts-check
import {expect, test} from './fixtures.js'

const SAMPLE_LOGGER = 'io.github.jdubois.bootui.sample'

test.describe('Loggers view', () => {
  test('filters loggers and changes the level of the sample logger', async ({openView, page}) => {
    await openView('loggers', 'Loggers')

    const rows = page.locator('table tbody tr')
    await expect.poll(async () => rows.count()).toBeGreaterThan(5)

    await page.getByPlaceholder(/Filter loggers by name/).fill(SAMPLE_LOGGER)
    const sampleRow = page.locator('table tbody tr', {has: page.locator('code', {hasText: SAMPLE_LOGGER})}).first()
    await expect(sampleRow).toBeVisible()

    // Change the level to WARN.
    await sampleRow.getByRole('button', {name: 'WARN', exact: true}).click()
    await expect(page.locator('.alert.alert-success')).toContainText(`Level updated for ${SAMPLE_LOGGER}`)
    await expect(sampleRow.locator('td').nth(1)).toHaveText('WARN')
    await expect(sampleRow.locator('td').nth(2)).toHaveText('WARN')

    // Reset (last button in the group is the ↺).
    await sampleRow.locator('.btn-group button').last().click()
    await expect(sampleRow.locator('td').nth(1)).not.toHaveText('WARN')
  })
})
