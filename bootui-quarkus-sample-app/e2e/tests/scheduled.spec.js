// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Scheduled tasks view (Quarkus)', () => {
  test('renders the sample echo scheduled task', async ({openView, page}) => {
    await openView('scheduled', 'Scheduled Tasks')

    await expect(page.locator('text=Loading…')).toHaveCount(0)

    const row = page.locator('table tbody tr', {hasText: 'FIXED_RATE'})
    await expect(row).toBeVisible()
    await expect(row).toContainText('30 s')
    // Real Jandex-discovered @Scheduled task from the sample app, not a placeholder --
    // QuarkusScheduledTaskProvider renders the annotated method as `class#method`.
    await expect(row).toContainText('io.github.jdubois.bootui.sample.scheduling.EchoScheduler#echo')
  })

  test('filtering by runnable name narrows the table', async ({openView, page}) => {
    await openView('scheduled', 'Scheduled Tasks')

    const filter = page.getByPlaceholder(/Filter by runnable name or expression/)
    const rows = page.locator('table tbody tr')

    await filter.fill('EchoScheduler')
    await expect(rows).toHaveCount(1)
    await expect(rows.first()).toContainText('echo')

    await filter.fill('no-such-scheduled-task-xyz')
    await expect(rows).toHaveCount(0)
  })
})
