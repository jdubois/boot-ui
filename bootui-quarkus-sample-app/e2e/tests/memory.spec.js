// @ts-check
import {expect, test} from './fixtures.js'

/**
 * The Memory advisor runs the shared engine's JMX-based rules and always captures a live "Runtime
 * snapshot" (heap, threads, classes, deadlock detection) as part of every scan. Advisor findings depend
 * on the JVM's actual runtime state, which isn't deterministic to force in a sample app, so this test
 * proves the Quarkus backend performs a real scan by asserting the snapshot renders plausible live
 * numbers rather than pinning a specific finding.
 */
test.describe('Memory advisor (Quarkus)', () => {
  test('runs memory checks and renders a real runtime snapshot', async ({openView, page}) => {
    await openView('memory', 'Memory')

    await page.getByRole('button', {name: 'Run memory checks'}).click()
    await expect(page.locator('.advisor-summary__value')).toBeVisible({timeout: 20_000})

    const snapshotCard = page.locator('.card', {hasText: 'Runtime snapshot'})
    await expect(snapshotCard).toBeVisible()

    const heapDd = snapshotCard.locator('dt', {hasText: 'Heap used'}).locator('xpath=following-sibling::dd[1]')
    await expect(heapDd).toHaveText(/\(\d+%\)/)

    const threadsDd = snapshotCard.locator('dt', {hasText: 'Live threads'}).locator('xpath=following-sibling::dd[1]')
    const liveThreads = Number((await threadsDd.textContent())?.trim())
    expect(liveThreads).toBeGreaterThan(0)

    const classesDd = snapshotCard.locator('dt', {hasText: 'Loaded classes'}).locator('xpath=following-sibling::dd[1]')
    const loadedClasses = Number((await classesDd.textContent())?.trim())
    expect(loadedClasses).toBeGreaterThan(0)
  })
})
