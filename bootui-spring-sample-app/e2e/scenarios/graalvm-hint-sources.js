// @ts-check

/**
 * Controlled report replay on the real Spring-served UI, not dependency-collector evidence.
 * The engine's real JAR fixtures establish discovery and repository-lookup behavior.
 * @param {typeof import('@playwright/test').test} test
 * @param {typeof import('@playwright/test').expect} expect
 * @param {{uiPath?: string, apiPath?: string}} paths
 */
export function registerGraalVmHintSourceTests(test, expect, {uiPath = '/bootui', apiPath = '/bootui/api'} = {}) {
  test('GraalVM controlled report replay: distinct neutral hint sources and legacy responses without render scans', async ({
    page
  }) => {
    const calls = []
    let reportReads = 0
    const dependencies = [
      {name: 'aot-only.jar', shipsMetadata: false, shipsSpringAotFactories: true},
      {name: 'both-sources.jar', shipsMetadata: true, shipsSpringAotFactories: true},
      {name: 'repository-match.jar', shipsMetadata: false, repositoryMetadata: true},
      {name: 'no-source.jar', shipsMetadata: false, shipsSpringAotFactories: false},
      {name: 'legacy-bundled.jar', shipsMetadata: true},
      {name: 'legacy-no-source.jar', shipsMetadata: false}
    ]
    await page.route(
      (url) => url.pathname === `${apiPath}/graalvm` || url.pathname.startsWith(`${apiPath}/graalvm/`),
      async (route) => {
        const request = route.request()
        calls.push(`${request.method()} ${new URL(request.url()).pathname}`)
        if (request.method() !== 'GET' || new URL(request.url()).pathname !== `${apiPath}/graalvm`) {
          await route.fulfill({status: 500, json: {error: 'Unexpected action during controlled report replay.'}})
          return
        }
        const response = await route.fetch()
        expect(response.ok(), 'real Spring GraalVM report endpoint is available').toBe(true)
        const body = await response.json()
        reportReads++
        await route.fulfill({
          response,
          json: {
            ...body,
            includeDependencies: true,
            dependenciesAnalyzed: dependencies.length,
            dependenciesWithoutMetadata: 4,
            dependencies
          }
        })
      }
    )
    try {
      await page.goto(`${uiPath}/#/graalvm`)
      const card = page.locator('.card').filter({has: page.getByText('Dependency hint sources', {exact: true})})
      await expect(card).toBeVisible()
      await expect(card).toContainText('2 of 6 dependencies ship bundled metadata')
      await expect(card).toContainText('Sources found do not prove native-image compatibility or complete hints.')
      await expect(card).toContainText('None found is not itself a defect.')
      const rows = card.locator('.list-group-item')
      await expect(rows).toHaveCount(6)
      const expectedLabels = [
        ['Spring AOT factories'],
        ['Bundled metadata', 'Spring AOT factories'],
        ['Repository match'],
        ['None found'],
        ['Bundled metadata'],
        ['None found']
      ]
      for (let index = 0; index < dependencies.length; index++) {
        const row = rows.filter({has: page.getByText(dependencies[index].name, {exact: true})})
        await expect(row.locator('.badge')).toHaveText(expectedLabels[index])
        await expect(row.locator('.badge:not(.text-bg-secondary)')).toHaveCount(0)
      }
      expect(reportReads).toBe(1)
      expect(calls).toEqual([`GET ${apiPath}/graalvm`])
    } finally {
      await page.unrouteAll({behavior: 'wait'})
    }
  })
}
