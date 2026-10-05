// @ts-check
import {expect, test} from '../tests/fixtures.js'

/**
 * The Exceptions panel's Caught in application code section on Spring MVC, with the BootUI agent's caught-exceptions
 * sensor (docs/PLAN-v2.md M5-6): the IOException StockLookup.stockOrDefault drops is a finding, not seen rethrown or
 * logged at WARN or above, once its request settled; logged, logged by its message, wrapped and rethrown, or handed on,
 * it is not.
 */
const SEEDS = {
  swallowed: 'stockOrDefault',
  logged: 'stockLogged',
  'logged-message': 'stockLoggedMessage',
  rethrown: 'stockOrFail',
  'handed-on': 'stockLater'
}

const stockRow = (report, method) =>
  (report.rows ?? []).find((row) => row.siteClass.endsWith('.StockLookup') && row.method === method)

test.describe('Caught in application code', () => {
  test('reports the dropped IOException as a finding and none of its counterexamples', async ({openView, page}) => {
    for (const route of Object.keys(SEEDS)) {
      const response = await page.request.get(`/api/caught/${route}`)
      expect(response.status(), route).toBe(route === 'rethrown' ? 404 : 200)
    }
    const caught = async () => (await page.request.get('/bootui/api/exceptions/caught')).json()
    // A request's occurrences are judged once it ended five seconds ago: until then they are settling.
    await expect
      .poll(
        async () => {
          const report = await caught()
          return Object.values(SEEDS).every((method) => stockRow(report, method)?.pending === 0)
        },
        {timeout: 30_000, intervals: [1_000]}
      )
      .toBe(true)
    const report = await caught()
    expect(report.available).toBe(true)
    expect(stockRow(report, 'stockOrDefault')).toMatchObject({finding: true, family: 'io'})
    expect(stockRow(report, 'stockOrDefault').notRethrownOrLogged).toBeGreaterThan(0)
    expect(stockRow(report, 'stockLogged').logged).toBeGreaterThan(0)
    expect(stockRow(report, 'stockLoggedMessage').logged).toBeGreaterThan(0)
    expect(stockRow(report, 'stockOrFail').rethrown).toBeGreaterThan(0)
    expect(stockRow(report, 'stockLater').handedOn).toBeGreaterThan(0)
    for (const method of ['stockLogged', 'stockLoggedMessage', 'stockOrFail', 'stockLater']) {
      expect(stockRow(report, method), method).toMatchObject({finding: false, notRethrownOrLogged: 0})
    }
    // Never the exception's message.
    expect(JSON.stringify(report)).not.toContain('unavailable for console')

    await openView('exceptions', 'Exceptions')
    const section = page.getByRole('region', {name: 'Caught in application code'})
    await expect(section).toContainText('Not seen rethrown or logged at WARN or above')
    await expect(section).toContainText('StockLookup')
    // The outcome labels state facts: the seed's route is named /swallowed, its verdict never is.
    for (const label of await section.locator('.caught-chip-group, .caught-finding').allTextContents()) {
      expect(label).not.toMatch(/swallowed/i)
    }
  })
})
