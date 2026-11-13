// @ts-check
import {expect, test} from './fixtures.js'
import {NAVIGATION_GROUPS} from '../../../bootui-ui/src/main/frontend/src/utils/panelNavigation.js'
import {registerBrowserIconTests} from '../scenarios/browser-icons.js'

registerBrowserIconTests(test, expect)

const groups = [
  ['Requests', 'Call public API'],
  ['Runtime Insights', 'Generate all findings'],
  ['Runtime', 'Allocate sample memory'],
  ['Threads', 'Create monitor contention'],
  ['Data', 'Check products'],
  ['Security', 'Create session data'],
  ['Integrations & AI', 'Test native WebSocket echo'],
  ['Java agent', 'Compare caught exceptions']
]

test('groups every scenario without running workloads on navigation or reload', async ({page}) => {
  const workloads = []
  page.on('request', (request) => {
    if (/\/api\/(sample|insights|caught|side-effects|thread-locals|thread-activity)(\/|$)/.test(request.url())) {
      workloads.push(request.url())
    }
  })
  await page.goto('/')
  await expect(page.getByRole('tablist', {name: 'Scenario groups'}).getByRole('tab')).toHaveCount(groups.length)
  await expect(page.getByRole('tablist').getByRole('tab')).toHaveText(groups.map(([name]) => name))
  await expect(page.getByRole('tablist').getByRole('tab').first()).toHaveText('Requests')
  await expect(page.getByRole('tab', {name: 'Requests', exact: true})).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('tabpanel', {name: 'Requests', exact: true})).toBeVisible()
  const menuGroup = {
    'Runtime Insights': 'home',
    Runtime: 'runtime',
    Threads: 'runtime',
    Data: 'database',
    Security: 'security',
    'Integrations & AI': 'services',
    'Java agent': 'agent'
  }
  const menuOrder = ['home', ...NAVIGATION_GROUPS.map(({key}) => key)]
  const positions = groups.slice(1).map(([name]) => menuOrder.indexOf(menuGroup[name]))
  expect(positions.every((position) => position >= 0)).toBe(true)
  expect(positions).toEqual([...positions].sort((a, b) => a - b))
  await expect(page.getByRole('tabpanel')).toHaveCount(1)
  for (const [group, action] of groups) {
    await page.getByRole('tab', {name: group, exact: true}).click()
    await expect(page.getByRole('tabpanel')).toHaveCount(1)
    await expect(page.getByRole('tabpanel', {name: group, exact: true})).toBeVisible()
    await expect(page.getByRole('button', {name: action, exact: true})).toBeVisible()
  }
  await page.reload()
  await expect(page.getByRole('tab', {name: 'Java agent', exact: true})).toHaveAttribute('aria-selected', 'true')
  expect(workloads).toEqual([])
})

test('supports keyboard tabs, preserved inputs, and a compact desktop layout', async ({page}) => {
  await page.setViewportSize({width: 1600, height: 900})
  await page.goto('/')
  const first = page.getByRole('tab', {name: 'Requests', exact: true})
  const last = page.getByRole('tab', {name: 'Java agent', exact: true})
  await first.focus()
  await page.keyboard.press('End')
  await expect(last).toBeFocused()
  await expect(last).toHaveAttribute('aria-selected', 'true')
  await page.keyboard.press('ArrowRight')
  await expect(first).toBeFocused()
  await page.keyboard.press('ArrowLeft')
  await expect(last).toBeFocused()
  await page.keyboard.press('Home')
  await expect(first).toBeFocused()
  await page.getByRole('tab', {name: 'Requests', exact: true}).click()
  for (const button of await page.getByRole('tabpanel').getByRole('button').all()) {
    const box = await button.boundingBox()
    expect(box?.y).toBeGreaterThanOrEqual(0)
    expect((box?.y ?? 0) + (box?.height ?? 0)).toBeLessThanOrEqual(900)
  }
  await page.getByRole('tab', {name: 'Integrations & AI', exact: true}).click()
  await page.getByLabel('Question for the LLM').fill('Keep this question while I inspect data')
  await page.getByRole('tab', {name: 'Data', exact: true}).click()
  await page.getByRole('tab', {name: 'Integrations & AI', exact: true}).click()
  await expect(page.getByLabel('Question for the LLM')).toHaveValue('Keep this question while I inspect data')
})

test('keeps mobile tabs reachable and all action targets at least 44 pixels high', async ({page}) => {
  await page.setViewportSize({width: 390, height: 844})
  await page.goto('/')
  const first = page.getByRole('tab', {name: 'Requests', exact: true})
  await first.focus()
  await page.keyboard.press('End')
  const last = page.getByRole('tab', {name: 'Java agent', exact: true})
  await expect(last).toBeFocused()
  const lastBox = await last.boundingBox()
  expect(lastBox?.x).toBeGreaterThanOrEqual(0)
  expect((lastBox?.x ?? 0) + (lastBox?.width ?? 0)).toBeLessThanOrEqual(390)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  for (const button of await page.getByRole('tabpanel').getByRole('button').all()) {
    expect((await button.boundingBox())?.height).toBeGreaterThanOrEqual(44)
  }
  await page.getByRole('tab', {name: 'Threads', exact: true}).click()
  await expect(page.getByRole('button', {name: 'Stop thread scenario', exact: true})).toBeVisible()
  await expect(page.getByRole('button', {name: 'Check scenario status', exact: true})).toBeVisible()
  const scenario = await page.getByRole('button', {name: 'Create monitor contention', exact: true}).boundingBox()
  expect((scenario?.y ?? 0) + (scenario?.height ?? 0)).toBeLessThanOrEqual(844)
})

test('shares the default BootUI theme without changing the console palette', async ({page}) => {
  const stylesheet = await page.request.get('/bootui-theme.css')
  expect(stylesheet.ok()).toBe(true)
  expect(stylesheet.headers()['content-type']).toContain('text/css')
  const consoleStylesheet = await page.request.get('/bootui/bootui-theme.css')
  expect(consoleStylesheet.ok()).toBe(true)
  expect(await stylesheet.text()).toBe(await consoleStylesheet.text())
  await page.goto('/#lab-threads')
  const sample = await page.evaluate(() => {
    const root = getComputedStyle(document.documentElement)
    const selected = getComputedStyle(document.querySelector('[role="tab"][aria-selected="true"]'))
    return {
      green: root.getPropertyValue('--bootui-green').trim(),
      text: root.getPropertyValue('--bootui-text').trim(),
      font: getComputedStyle(document.documentElement).fontFamily,
      selected: selected.backgroundImage
    }
  })
  expect(sample.green).toBe('#198754')
  expect(sample.text).toBe('#152033')
  expect(sample.font).toContain('system-ui')
  expect(sample.selected).toContain('linear-gradient')
  await page.evaluate(() => localStorage.setItem('bootui.theme', 'light'))
  await page.goto('/bootui/')
  await expect(page.locator('.bootui-shell')).toBeVisible()
  const consoleTokens = await page.evaluate(() => {
    const root = getComputedStyle(document.documentElement)
    return {
      green: root.getPropertyValue('--bootui-green').trim(),
      text: root.getPropertyValue('--bootui-text').trim()
    }
  })
  expect(consoleTokens).toEqual({green: sample.green, text: sample.text})
})

test('filters Runtime Insights without generating traffic and handles empty results', async ({page}) => {
  await page.goto('/#lab-insights')
  const filter = page.getByRole('searchbox', {name: 'Filter findings'})
  await filter.fill('Hibernate eager N+1')
  await expect(page.locator('.insight-card:visible')).toHaveCount(1)
  await expect(page.getByRole('button', {name: 'Hibernate eager N+1', exact: true})).toBeVisible()
  await expect(page.locator('#insights-filter-count')).toHaveText('1 of 26 findings')
  await filter.fill('no-such-finding')
  await expect(page.locator('.insight-card:visible')).toHaveCount(0)
  await expect(page.locator('#insights-filter-empty')).toBeVisible()
  await filter.fill('')
  await expect(page.locator('.insight-card:visible')).toHaveCount(26)
})

test('runs the new paired agent examples and exposes recoverable failures', async ({page}) => {
  await page.goto('/#lab-agent')
  for (const name of [
    'Compare caught exceptions',
    'Compare process launches',
    'Compare outbound calls',
    'Compare thread-local cleanup'
  ]) {
    await page.getByRole('button', {name, exact: true}).click()
    await expect(page.locator('#sample-action-status')).toHaveText(`${name}: both requests completed`)
    await expect(page.locator('#sample-action-details')).not.toHaveAttribute('open')
    await page.getByText('Response details', {exact: true}).click()
    await expect(page.locator('#sample-action-result')).toBeVisible()
  }
  await page.route('**/api/caught/swallowed', (route) => route.fulfill({status: 503, body: 'Unavailable'}))
  const button = page.getByRole('button', {name: 'Compare caught exceptions', exact: true})
  await button.click()
  await expect(page.locator('#sample-action-status')).toHaveText('Action failed')
  await expect(page.locator('#sample-action-result')).toBeVisible()
  await expect(page.locator('#sample-action-result')).toContainText('/api/caught/swallowed failed with HTTP 503')
  await expect(button).toBeEnabled()
})

test('checks current thread scenario state without starting a workload', async ({page}) => {
  await page.goto('/#lab-threads')
  const mutations = []
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().includes('/api/sample/threads/')) mutations.push(request.url())
  })
  await page.getByRole('button', {name: 'Check scenario status', exact: true}).click()
  await expect(page.locator('#thread-scenarios-status')).toHaveText('No thread scenario is running.')
  expect(mutations).toEqual([])
})
