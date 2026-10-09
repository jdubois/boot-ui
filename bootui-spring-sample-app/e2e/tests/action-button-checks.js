import {THEME_REGISTRY} from '../../../bootui-ui/src/main/frontend/src/utils/theme.js'

const subject = 'GET /api/orders/{id}?filter=a&b=two words'
const observation = {
  id: 'route-time-breakdown:orders',
  kind: 'route-time-breakdown',
  subject,
  status: 'OBSERVED',
  sentence: '`GET /api/orders/{id}` spent most of its time in the handler.',
  eligible: 9,
  affected: 9,
  minimumTier: 'REQUEST_ID',
  listed: true,
  whatToCheck: ['Inspect the retained method timings.'],
  exemplarRequestIds: ['request/a?b=two words&c=1'],
  evidenceRows: 1,
  limitations: []
}
const report = {
  available: true,
  window: {runId: 'run-1', requests: 9, retainedEvents: 9, firstEventAt: 1700000000000, lastEventAt: 1700000060000},
  coverage: [{source: 'http', events: 9, byRequestId: 9, byExecutionId: 0, byTraceId: 0, unlinked: 0}],
  checks: [
    {kind: observation.kind, title: 'Route time breakdown', status: 'EVALUATED', eligibleRequests: 9, findings: 1}
  ],
  observations: [observation],
  limitations: ['Only retained requests are shown.'],
  notExercised: [],
  notExercisedOmitted: 0
}
const detail = {
  available: true,
  observation,
  columns: ['Phase', 'Time'],
  rows: [{cells: ['Handler', '40 ms']}],
  truncated: 0
}

async function installFixtures(page, platform) {
  const writes = []
  const errors = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    if (request.method() !== 'GET') writes.push(`${request.method()} ${request.url()}`)
    const pathname = new URL(request.url()).pathname
    const bodies = {
      '/bootui/api/overview': {applicationName: 'Button fixtures', profiles: ['dev']},
      '/bootui/api/panels': {
        platform,
        panels: [
          {id: 'runtime-insights', enabled: true, available: true, readOnly: true, readOnlyReason: 'Read-only fixture'},
          {id: 'code-paths', enabled: true, available: true},
          {id: 'code-inventory', enabled: true, available: false, unavailableReason: 'No agent attached.'},
          {id: 'java-agent', enabled: true, available: true},
          {id: 'architecture', enabled: true, available: true, readOnly: true},
          {id: 'beans', enabled: true, available: true}
        ]
      },
      '/bootui/api/runtime-insights': report,
      [`/bootui/api/runtime-insights/insights/${encodeURIComponent(observation.id)}`]: detail,
      '/bootui/api/runtime-insights/comparison': {status: 'NO_PREVIOUS_RUN', reason: 'No previous run.'},
      '/bootui/api/runtime-insights/resource-profile': {state: 'IDLE', routes: [], maxDurationSeconds: 30},
      '/bootui/api/code-paths': {available: false, unavailableReason: 'No retained route tree.'},
      '/bootui/api/architecture': {
        evidence: {usable: true, coverageComplete: true, limitations: []},
        disclaimer: 'Fixture architecture checks.',
        scan: {status: 'SCANNED', scannedAt: 1700000000000},
        classesAnalyzed: 12,
        rulesEvaluated: 1,
        violationsFound: 1,
        severityCounts: [{severity: 'HIGH', count: 1}],
        results: [
          {
            id: 'ARCH-FIXTURE',
            name: 'Fixture rule',
            category: 'Coding practices',
            severity: 'HIGH',
            status: 'VIOLATION',
            violationCount: 1,
            sampleViolations: ['Fixture finding'],
            recommendation: 'Inspect the source.',
            learnMoreUrl: 'https://example.com/reference'
          }
        ]
      },
      '/bootui/api/beans': {
        beans: [
          {
            name: 'orderService',
            type: 'example.OrderService',
            dependencies: [],
            scope: 'singleton',
            classification: 'APPLICATION'
          }
        ],
        page: {offset: 0, limit: 200, total: 1, matched: 1, hasMore: false}
      },
      '/bootui/api/conditions': {positiveMatches: {}, negativeMatches: {}}
    }
    if (!(pathname in bodies)) {
      await route.fulfill({status: 500, json: {message: `Unconfigured button fixture: ${pathname}`}})
      return
    }
    await route.fulfill({json: bodies[pathname]})
  })
  return {writes, errors}
}

async function assertButton(expect, locator) {
  await expect(locator).toBeVisible()
  await expect(locator).toHaveClass(/\bbtn-outline-secondary\b/)
  await expect(locator).toHaveCSS('text-decoration-line', 'none')
  const size = await locator.boundingBox()
  expect(size.height).toBeGreaterThanOrEqual(24)
  await locator.focus()
  await locator.press('Tab')
  await locator.page().keyboard.press('Shift+Tab')
  await expect(locator).toBeFocused()
  const focus = await locator.evaluate((element) => {
    const style = getComputedStyle(element)
    return {width: parseFloat(style.outlineWidth), style: style.outlineStyle}
  })
  expect(focus.width).toBeGreaterThanOrEqual(focus.style === 'dotted' ? 1 : 2)
  expect(['solid', 'dotted']).toContain(focus.style)
}

async function assertContrast(expect, locator) {
  const ratios = () =>
    locator.evaluate((element) => {
      const parse = (value) => {
        const channels = value.match(/[\d.]+/g).map(Number)
        return [channels[0], channels[1], channels[2], channels[3] ?? 1]
      }
      const over = (front, back) => {
        const alpha = front[3] + back[3] * (1 - front[3])
        return [
          ...front
            .slice(0, 3)
            .map((value, index) => (value * front[3] + back[index] * back[3] * (1 - front[3])) / alpha),
          alpha
        ]
      }
      const luminance = (color) =>
        color
          .slice(0, 3)
          .map((value) => {
            const channel = value / 255
            return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
          })
          .reduce((sum, value, index) => sum + value * [0.2126, 0.7152, 0.0722][index], 0)
      const layers = []
      for (let ancestor = element; ancestor && ancestor !== document.body; ancestor = ancestor.parentElement) {
        layers.unshift(parse(getComputedStyle(ancestor).backgroundColor))
      }
      const body = getComputedStyle(document.body)
      const probe = document.createElement('span')
      probe.style.color = 'var(--bootui-surface-solid)'
      document.body.append(probe)
      const base = parse(getComputedStyle(probe).color)
      probe.remove()
      const stops = body.backgroundImage.match(/rgba?\([^)]*\)/g)?.map(parse) ?? [parse(body.backgroundColor)]
      const foreground = parse(getComputedStyle(element).color)
      return stops.map((stop) => {
        const background = layers.reduce((back, front) => over(front, back), over(stop, base))
        const a = luminance(over(foreground, background))
        const b = luminance(background)
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05)
      })
    })
  await expect.poll(async () => Math.min(...(await ratios()))).toBeGreaterThanOrEqual(4.5)
}

export function registerActionButtonTests(test, expect) {
  for (const platform of ['spring-boot', 'spring-boot-reactive', 'quarkus']) {
    for (const [viewportName, viewport] of [
      ['desktop', {width: 1600, height: 900}],
      ['mobile', {width: 390, height: 844}]
    ]) {
      test(`secondary buttons keep link semantics, keyboard behavior, and theme states: ${platform}, ${viewportName}`, async ({
        page
      }, testInfo) => {
        test.setTimeout(90000)
        await page.setViewportSize(viewport)
        const {writes, errors} = await installFixtures(page, platform)
        await page.goto(`/bootui/#/runtime-insights?insight=${encodeURIComponent(observation.id)}`)
        const requestLink = page
          .locator('.insight-detail')
          .getByRole('link', {name: observation.exemplarRequestIds[0], exact: true})
        for (const theme of THEME_REGISTRY) {
          await page.getByRole('button', {name: /^Theme:/}).click()
          await page.getByRole('menuitemradio', {name: new RegExp(`^${theme.label}`)}).click()
          await expect(page.locator('html')).toHaveAttribute('data-bootui-theme', theme.id)
          await expect.poll(() => page.evaluate(() => localStorage.getItem('bootui.theme'))).toBe(theme.id)
          await assertButton(expect, requestLink)
          await assertContrast(expect, requestLink)
          await requestLink.hover()
          await assertContrast(expect, requestLink)
          await expect
            .poll(() =>
              requestLink.evaluate(
                (link) => getComputedStyle(link.querySelector('code')).color === getComputedStyle(link).color
              )
            )
            .toBe(true)
          const href = await requestLink.getAttribute('href')
          expect(href.startsWith('#/activity?')).toBe(true)
          expect(new URLSearchParams(href.slice(href.indexOf('?') + 1)).get('request')).toBe(
            observation.exemplarRequestIds[0]
          )
          const codePaths = page.getByRole('link', {name: `Open ${subject} in Code Paths`})
          await assertButton(expect, codePaths)
          await assertContrast(expect, codePaths)
          if (platform === 'spring-boot') {
            const screenshot = testInfo.outputPath(`${theme.id}-${viewportName}.png`)
            await page.locator('.insight-detail').screenshot({path: screenshot})
            await testInfo.attach(`${theme.id}-${viewportName}`, {
              path: screenshot,
              contentType: 'image/png'
            })
          }
          await page.keyboard.press('Enter')
          await expect
            .poll(() => new URLSearchParams(page.url().slice(page.url().indexOf('?') + 1)).get('route'))
            .toBe(subject)
          await expect(page.locator('.code-paths-unavailable')).toContainText('No retained route tree.')
          await page.goBack()
          await expect(requestLink).toBeVisible()
          const jfr = page.getByRole('button', {name: 'Open the JFR profile tab'})
          await assertButton(expect, jfr)
          await page.keyboard.press('Space')
          await expect(page.getByRole('tab', {name: 'JFR profile'})).toBeFocused()
          await expect(page.getByRole('button', {name: 'Profile resources', exact: true})).toBeDisabled()
          await page.getByRole('tab', {name: /^Findings/}).click()
        }
        expect(writes).toEqual([])
        expect(errors).toEqual([])
        expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
      })
    }

    test(`setup, advisor, and disclosure buttons preserve behavior: ${platform}`, async ({page}) => {
      const {writes, errors} = await installFixtures(page, platform)
      let pendingDetail
      await page.route('**/api/runtime-insights/insights/*', (route) => {
        pendingDetail = route
      })
      await page.goto(`/bootui/#/runtime-insights?insight=${encodeURIComponent(observation.id)}`)
      const copy = page.getByRole('button', {name: 'Copy for AI', exact: true})
      await expect(copy).toBeDisabled()
      await expect.poll(() => !!pendingDetail).toBe(true)
      await pendingDetail.fulfill({json: detail})
      await expect(copy).toBeEnabled()
      const coverage = page.getByRole('button', {name: 'see Coverage & limits', exact: true})
      await assertButton(expect, coverage)
      await page.keyboard.press('Enter')
      await expect(page.getByRole('tab', {name: /^Coverage & limits/})).toHaveAttribute('aria-selected', 'true')

      await page.goto('/bootui/#/code-inventory')
      const setup = page.getByRole('link', {name: 'Open the Java Agent panel', exact: true})
      await assertButton(expect, setup)
      await expect(setup).toHaveAttribute('href', '#/java-agent')

      await page.goto('/bootui/#/architecture')
      const reference = page.getByRole('link', {name: /Learn more/})
      await assertButton(expect, reference)
      await expect(reference).toHaveAttribute('href', 'https://example.com/reference')
      await expect(reference).toHaveAttribute('target', '_blank')
      await expect(reference).toHaveAttribute('rel', /noopener/)
      await expect(page.getByRole('button', {name: 'Run architecture checks'})).toBeDisabled()

      await page.goto('/bootui/#/beans')
      await page.getByRole('button', {name: 'List view', exact: true}).click()
      const graph = page.getByRole('button', {name: 'Show dependency graph for orderService', exact: true})
      await assertButton(expect, graph)
      await page.keyboard.press('Enter')
      await expect(page.getByRole('button', {name: 'Dependency graph', exact: true})).toHaveAttribute(
        'aria-pressed',
        'true'
      )
      expect(writes).toEqual([])
      expect(errors).toEqual([])
    })
  }
}
