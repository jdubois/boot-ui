// @ts-check
import {acceptConfirm, expect, test} from './fixtures.js'

test('sample buttons produce real thread evidence and every workload can be stopped', async ({page, context}) => {
  await page.goto('/')
  await page.getByRole('tab', {name: 'Threads', exact: true}).click()
  const lab = page.getByRole('region', {name: 'Thread investigation lab'})
  await expect(lab).toBeVisible()
  await expect(lab).toContainText('Capture snapshot → Contention')
  await expect(lab).toContainText('Observe threads → Observe & compare')
  await expect(lab).toContainText('low-footprint mode on')
  expect((await page.request.get('/api/sample/threads')).ok()).toBe(true)
  expect((await page.request.post('/api/sample/threads/cpu')).status()).toBe(403)
  const consolePage = await context.newPage()
  await consolePage.goto('/bootui/#/threads')
  const scenarios = [
    ['monitor', 'Create monitor contention', 'BLOCKED', 3],
    ['synchronizer', 'Create lock contention', 'WAITING', 3],
    ['cpu', 'Run CPU-heavy work', 'RUNNABLE', 1],
    ['waiting', 'Create timed waiting threads', 'TIMED_WAITING', 3],
    ['deadlock', 'Create a recoverable deadlock', 'WAITING', 2]
  ]
  try {
    for (const [scenario, label, state, count] of scenarios) {
      try {
        await lab.getByRole('button', {name: String(label), exact: true}).click()
        await expect(lab.getByRole('status')).toContainText(`Started ${scenario}`)
        await expect
          .poll(async () => {
            const response = await page.request.get(`/bootui/api/threads?q=sample-threads-${scenario}&limit=1000`)
            const body = await response.json()
            return body.threads.filter((row) => row.state === state && !row.name.endsWith('-cleanup')).length
          })
          .toBeGreaterThanOrEqual(Number(count))
        await consolePage.getByRole('button', {name: 'Capture snapshot', exact: true}).click()
        const captured = consolePage.waitForResponse(
          (response) => response.request().method() === 'POST' && response.url().endsWith('/threads/captures')
        )
        await acceptConfirm(consolePage)
        const summary = await (await captured).json()
        const analysis = await (await page.request.get(`/bootui/api/threads/captures/${summary.id}?limit=1000`)).json()
        if (scenario === 'monitor' || scenario === 'synchronizer') {
          const owner = analysis.rootBlockers.find((root) => root.name === `sample-threads-${scenario}-owner`)
          expect(owner).toBeTruthy()
          expect(owner.directBlocked).toBe(3)
          expect(owner.transitiveBlocked).toBe(3)
        } else if (scenario === 'waiting') {
          expect(analysis.stackGroups.some((group) => group.count >= 3)).toBe(true)
          expect(analysis.rootBlockers.some((root) => root.name.startsWith('sample-threads-waiting-'))).toBe(false)
        } else if (scenario === 'deadlock') {
          expect(analysis.capture.deadlockDetected).toBe(true)
          expect(analysis.cycles.some((cycle) => cycle.participantCount === 2)).toBe(true)
        } else {
          await consolePage.getByRole('tab', {name: 'Observe & compare', exact: true}).click()
          await consolePage.getByLabel('Samples', {exact: true}).fill('2')
          await consolePage.getByLabel('Interval (seconds)', {exact: true}).fill('1')
          await consolePage.getByRole('button', {name: 'Observe threads', exact: true}).click()
          await acceptConfirm(consolePage)
          const comparison = consolePage.getByRole('tabpanel', {name: 'Observe & compare', exact: true})
          await expect(comparison.getByRole('status')).toContainText('COMPLETED', {timeout: 15_000})
          const row = comparison.locator('tbody tr').filter({hasText: 'sample-threads-cpu-worker'})
          await expect(row).toBeVisible()
          await expect(row).toContainText('% of one core')
          expect(Number((await row.locator('td').last().innerText()).split('%')[0])).toBeGreaterThan(0)
        }
      } finally {
        await lab.getByRole('button', {name: 'Stop thread scenario', exact: true}).click()
        await expect(lab.getByRole('status')).toContainText('Thread scenario stopped.')
      }
    }
    expect((await (await page.request.get('/api/sample/threads')).json()).active).toBe(false)
  } finally {
    await consolePage.close()
  }
})
