// @ts-check

const UNCLASSIFIED = 'Unclassified (JVM and unobserved threads)'

export function registerResourceLedgerTests(test, expect) {
  async function openResources(page) {
    const toggle = page.getByRole('button', {name: 'Resources', exact: true})
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')
    await expect(page.getByRole('heading', {name: 'Work outside requests'})).toHaveCount(1)
    return page.locator('#activity-runtime-resources')
  }

  async function assertAttribution(resources, complete) {
    await expect(resources).toContainText('it is not proof that this work ran outside requests')
    await expect(resources).not.toContainText('CPU time went to work outside requests')
    if (complete) {
      await expect(resources).toContainText('of CPU time was not attributed to requests')
      await expect(resources.getByRole('columnheader', {name: 'Share', exact: true})).toBeVisible()
      await expect(resources.locator('.runtime-resources__stack')).toBeVisible()
      await expect(resources).not.toContainText('CPU attribution is incomplete')
    } else {
      await expect(resources).toContainText('CPU attribution is incomplete')
      await expect(resources).toContainText('Measured times are shown without percentage shares')
      await expect(resources.getByRole('columnheader', {name: 'Share', exact: true})).toHaveCount(0)
      await expect(resources.locator('.runtime-resources__stack')).toHaveCount(0)
      await expect(resources.getByRole('row', {name: `${UNCLASSIFIED} Unknown`, exact: true})).toBeVisible()
      await expect(resources.locator('table')).not.toContainText('%')
    }
  }

  test('opens the CPU ledger of work outside requests on demand', async ({page, openView}) => {
    await openView('activity', 'Live Activity')
    const resources = await openResources(page)
    let complete
    await expect(async () => {
      await expect(resources.getByRole('button', {name: 'Refresh'})).toBeEnabled()
      const response = page.waitForResponse(
        (candidate) => candidate.url().endsWith('/api/activity/resources') && candidate.request().method() === 'GET'
      )
      await resources.getByRole('button', {name: 'Refresh'}).click()
      const body = await (await response).json()
      complete = body.totals.internalCpuNanos >= 0
      await expect(resources.getByRole('rowheader', {name: UNCLASSIFIED, exact: true})).toBeVisible({timeout: 1000})
    }).toPass({timeout: 15_000})
    await assertAttribution(resources, complete)
  })

  for (const fixture of [
    {name: 'coherent', processCpuNanos: 100_000_000, internalCpuNanos: 60_000_000},
    {name: 'skewed', processCpuNanos: 20_000_000, internalCpuNanos: -1},
    {name: 'process-unavailable', processCpuNanos: 0, internalCpuNanos: -1}
  ]) {
    test(`renders the ${fixture.name} resource ledger without inventing attribution`, async ({page, openView}) => {
      const knownProcess = fixture.name !== 'process-unavailable'
      const body = {
        available: true,
        unavailableReason: null,
        families: ['worker-N'],
        points: [0, 1].map((index) => ({
          epochMillis: 1_700_000_000_000 + index * 1_000,
          sequence: index + 1,
          intervalNanos: 1_000_000_000,
          processCpuNanos: knownProcess ? fixture.processCpuNanos / 2 : -1,
          requestCpuNanos: 5_000_000,
          internalCpuNanos: fixture.internalCpuNanos >= 0 ? fixture.internalCpuNanos / 2 : -1,
          familyCpuNanos: [15_000_000],
          unreadThreads: 0,
          heapUsedBytes: 64 * 1024 * 1024,
          heapCommittedBytes: 128 * 1024 * 1024,
          heapAfterGcBytes: 32 * 1024 * 1024,
          allocatedBytes: 1_000,
          liveThreads: 40,
          daemonThreads: 30
        })),
        totals: {
          sweeps: 2,
          processCpuNanos: fixture.processCpuNanos,
          requestCpuNanos: 10_000_000,
          internalCpuNanos: fixture.internalCpuNanos,
          familyCpuNanos: {'worker-N': 30_000_000}
        }
      }
      let reads = 0
      await page.route('**/api/activity/resources', async (route) => {
        reads++
        await route.fulfill({json: body})
      })
      await openView('activity', 'Live Activity')
      await expect(page.getByRole('button', {name: 'Resources', exact: true})).toBeVisible()
      expect(reads).toBe(0)
      const resources = await openResources(page)
      await assertAttribution(resources, fixture.internalCpuNanos >= 0)
      await expect(resources.getByRole('row', {name: /^Requests /})).toContainText('10.0 ms')
      await expect(resources.getByRole('row', {name: /^worker-N /})).toContainText('30.0 ms')
      await expect(resources.locator('svg')).toBeVisible()
      const heapLine = resources.locator('polyline.runtime-resources__heap')
      await expect(heapLine).toHaveCount(1)
      const points = await heapLine.getAttribute('points')
      if (!points) throw new Error('Heap lane has no coordinates')
      const coordinates = points
        .trim()
        .split(/\s+/)
        .map((pair) => pair.split(',').map(Number))
      expect(coordinates).toHaveLength(2)
      for (const pair of coordinates) {
        expect(pair).toHaveLength(2)
        expect(pair.every(Number.isFinite)).toBe(true)
      }
      expect(coordinates.map(([x]) => x)).toEqual([0, 100])
      expect(coordinates.map(([, y]) => y)).toEqual([2, 2])
      await expect(resources.locator('polyline.runtime-resources__cpu')).toHaveCount(knownProcess ? 1 : 0)
    })
  }
}
