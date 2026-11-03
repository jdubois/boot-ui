// @ts-check

/**
 * @param {import('@playwright/test').Page} page
 * @param {typeof import('@playwright/test').expect} expect
 * @param {string} title
 * @param {string} subject
 */
export async function openInsightObservation(page, expect, title, subject) {
  const group = page.locator('.insight-group').filter({has: page.getByText(title, {exact: true})})
  const toggle = group.locator('.insight-group-toggle')
  await expect(toggle).toBeVisible()
  if ((await toggle.getAttribute('aria-expanded')) !== 'true') await toggle.click()
  await expect(group.locator('.insight-detail')).toBeVisible()
  const select = group.getByRole('combobox', {name: 'Observation', exact: true})
  if (await select.count()) {
    const option = select.locator('option').filter({hasText: subject}).first()
    await expect(option).toHaveCount(1)
    const value = await option.getAttribute('value')
    expect(value).not.toBeNull()
    await select.selectOption(String(value))
    await expect(select).toHaveValue(String(value))
  } else {
    await expect(group.locator('.insight-observation-subject')).toContainText(subject)
  }
  return group.locator('.insight-detail')
}

/**
 * @param {import('@playwright/test').Page} page
 * @param {typeof import('@playwright/test').expect} expect
 * @param {(view: string, title: string) => Promise<void>} openView
 */
export async function verifyInsightGroups(page, expect, openView) {
  const base = {
    status: 'OBSERVED',
    eligible: 8,
    affected: 2,
    minimumTier: 'REQUEST_ID',
    whatToCheck: ['Compare with the retained requests before changing code.'],
    exemplarRequestIds: [],
    evidenceRows: 1,
    limitations: [],
    listed: false,
    unlistedReason: 'Agent default selection excludes this observation.'
  }
  const observations = [
    {
      ...base,
      id: 'route-time-breakdown:short',
      kind: 'route-time-breakdown',
      subject: 'GET /api/short',
      sentence: 'Warm median 3 ms.'
    },
    {
      ...base,
      id: 'repeated-selects:limited',
      kind: 'repeated-selects',
      subject: 'GET /api/orders',
      sentence: 'Repeated SELECTs in retained orders requests.',
      status: 'PARTIAL',
      limitations: ['Some SQL events were dropped.']
    },
    {
      ...base,
      id: 'route-time-breakdown:thin',
      kind: 'route-time-breakdown',
      subject: 'GET /api/thin',
      sentence: 'Only two warm requests were retained.',
      status: 'INSUFFICIENT',
      limitations: ['Five warm requests are needed.']
    },
    {
      ...base,
      id: 'heap-growth-after-gc:heap',
      kind: 'heap-growth-after-gc',
      subject: 'Heap',
      sentence: 'Old-generation occupancy rose after garbage collection.',
      eligible: 0,
      affected: 0
    }
  ]
  const evidenceRequests = []
  await page.route('**/bootui/api/runtime-insights', (route) =>
    route.fulfill({
      json: {
        available: true,
        window: {requests: 8, retainedEvents: 24},
        checks: [
          {kind: 'route-time-breakdown', title: 'Route time breakdown', status: 'EVALUATED'},
          {kind: 'repeated-selects', title: 'Repeated SELECTs', status: 'PARTIAL'},
          {kind: 'heap-growth-after-gc', title: 'Heap growth after GC', status: 'EVALUATED'}
        ],
        observations,
        coverage: [],
        limitations: [],
        notExercised: [],
        notExercisedOmitted: 0
      }
    })
  )
  await page.route('**/bootui/api/runtime-insights/insights/*', (route) => {
    const id = decodeURIComponent(new URL(route.request().url()).pathname.split('/').pop() ?? '')
    const observation = observations.find((item) => item.id === id)
    expect(observation).toBeTruthy()
    evidenceRequests.push(id)
    return route.fulfill({
      json: {
        available: true,
        observation,
        columns: ['Request', 'Call site', 'Phase', 'In transaction'],
        rows: [{cells: ['r-1', 'OrderService#load', 'Handler', 'true']}],
        truncated: 0
      }
    })
  })
  await openView('runtime-insights', 'Runtime Insights')
  const main = page.locator('main')
  const search = main.getByRole('searchbox', {name: 'Search observations', exact: true})
  const topic = main.getByRole('combobox', {name: 'Topic', exact: true})
  await expect(main.locator('.insight-group-title')).toHaveText([
    'Repeated SELECTs',
    'Heap growth after GC',
    'Route time breakdown'
  ])
  await expect(main.getByRole('button', {name: 'Repeated SELECTs 1 observation', exact: true})).toHaveCount(1)
  await expect(main.getByRole('button', {name: 'Route time breakdown 2 observations', exact: true})).toHaveCount(1)
  await expect(main.locator('.insight-detail')).toHaveCount(0)
  await expect(main.locator('#insight-verdict-title')).toHaveText('4 observations recorded across 8 requests')
  await expect(main.locator('#insight-result-count')).toHaveText('All 4 observations, in 3 groups. Nothing excluded.')
  expect(evidenceRequests).toEqual([])
  const groups = main.locator('.insight-group-toggle')
  const controls = await groups.evaluateAll((buttons) =>
    buttons.map((button) => {
      const id = button.getAttribute('aria-controls')
      return {id, exists: Boolean(id && document.getElementById(id))}
    })
  )
  expect(new Set(controls.map(({id}) => id)).size).toBe(3)
  expect(controls.every(({exists}) => exists)).toBeTruthy()
  await groups.first().focus()
  await page.keyboard.press('Enter')
  await expect(groups.first()).toHaveAttribute('aria-expanded', 'true')
  await expect(main.locator('#insight-sentence')).toHaveText(observations[1].sentence)
  await expect(main.getByRole('combobox', {name: 'Observation', exact: true})).toHaveCount(0)
  await expect(main.getByRole('button', {name: 'Next observation', exact: true})).toHaveCount(0)
  await expect(main.locator('.insight-row-limit')).toHaveText('Evidence limit: Some source events were dropped')
  await expect(main.locator('.insight-detail')).toContainText('Some SQL events were dropped.')
  await expect.poll(() => evidenceRequests).toEqual([observations[1].id])
  await expect
    .poll(() =>
      main
        .locator('.insight-group.open')
        .evaluate(
          (element) => element.nextElementSibling.getBoundingClientRect().top - element.getBoundingClientRect().bottom
        )
    )
    .toBe(16)
  await expect(main.locator('.insight-group.open')).toHaveCSS('border-bottom-width', '1px')

  await groups.last().focus()
  await page.keyboard.press('Space')
  await expect(groups.first()).toHaveAttribute('aria-expanded', 'false')
  await expect(main.locator('.insight-detail')).toHaveCount(1)
  await expect(main.locator('#insight-sentence')).toHaveCount(1)
  await expect(main.locator('.insight-detail[aria-live="polite"]')).toHaveCount(1)
  const select = main.getByRole('combobox', {name: 'Observation', exact: true})
  const previous = main.getByRole('button', {name: 'Previous observation', exact: true})
  const next = main.getByRole('button', {name: 'Next observation', exact: true})
  await expect(select).toHaveValue(observations[0].id)
  await expect(main.locator('.insight-position')).toHaveText('1 of 2')
  await expect(previous).toBeDisabled()
  await expect(next).toBeEnabled()
  await select.selectOption(observations[2].id)
  await expect(main.locator('#insight-sentence')).toHaveText(observations[2].sentence)
  await expect(main.locator('.insight-position')).toHaveText('2 of 2')
  await expect(main.locator('.insight-row-limit')).toHaveText('Evidence limit: Not enough evidence')
  await expect(next).toBeDisabled()
  await previous.focus()
  await page.keyboard.press('Enter')
  await expect(select).toHaveValue(observations[0].id)
  await expect(select).toBeFocused()
  await expect(main.getByRole('region', {name: 'Recorded evidence', exact: true})).toHaveCount(1)
  await expect.poll(() => evidenceRequests.length).toBe(4)
  await expect(main).not.toContainText(/Show all routes|Not listed by default/)

  await search.fill('orders')
  await topic.selectOption('time')
  await expect(search).toHaveValue('orders')
  await expect(main.locator('#insight-result-count')).toHaveText('0 of 4 observations match your filters, in 0 groups.')
  await expect(main.locator('.insight-no-matches')).toBeVisible()
  await main.getByRole('button', {name: 'Clear filters', exact: true}).click()
  await expect(search).toBeFocused()
  await expect(topic).toHaveValue('')
  await expect(main.locator('.insight-detail')).toHaveCount(0)
  expect(evidenceRequests).toHaveLength(4)
  await groups.first().click()
  await expect(main.locator('.insight-evidence')).toBeVisible()
  await groups.first().focus()
  await page.keyboard.press('Space')
  await expect(groups.first()).toBeFocused()
  await expect(main.locator('.insight-detail')).toHaveCount(0)
  await expect(select).toHaveCount(0)
  await expect(previous).toHaveCount(0)
  await groups.last().click()
  for (const width of [1280, 390, 320]) {
    await page.setViewportSize({width, height: 900})
    await expect(search).toBeVisible()
    await expect(topic).toBeVisible()
    await expect(select).toBeVisible()
    await expect(main.locator('.insight-evidence')).toBeVisible()
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)
    ).toBeTruthy()
    await expect(main.locator('.insight-group.open')).toHaveCSS('margin-bottom', '16px')
    expect(
      await main
        .locator('.insight-group.open')
        .evaluate(
          (element) =>
            element.getBoundingClientRect().top - element.previousElementSibling.getBoundingClientRect().bottom
        )
    ).toBe(16)
  }
  await groups.nth(1).click()
  const middle = main.locator('.insight-group.open')
  const boundaries = await middle.evaluate((element) => {
    const rect = element.getBoundingClientRect()
    const previous = element.previousElementSibling
    const next = element.nextElementSibling
    if (!previous || !next) throw new Error('The middle observation group must have both neighbors.')
    const style = getComputedStyle(element)
    return {
      above: rect.top - previous.getBoundingClientRect().bottom,
      below: next.getBoundingClientRect().top - rect.bottom,
      borders: [style.borderTopWidth, style.borderRightWidth, style.borderBottomWidth, style.borderLeftWidth],
      corners: [
        style.borderTopLeftRadius,
        style.borderTopRightRadius,
        style.borderBottomLeftRadius,
        style.borderBottomRightRadius
      ],
      previousCorners: [
        getComputedStyle(previous).borderTopLeftRadius,
        getComputedStyle(previous).borderBottomLeftRadius
      ],
      nextCorners: [getComputedStyle(next).borderTopLeftRadius, getComputedStyle(next).borderBottomLeftRadius]
    }
  })
  expect(boundaries.above).toBe(16)
  expect(boundaries.below).toBe(16)
  expect(boundaries.borders).toEqual(['1px', '1px', '1px', '1px'])
  expect(
    [...boundaries.corners, ...boundaries.previousCorners, ...boundaries.nextCorners].every(
      (radius) => parseFloat(radius) > 0
    )
  ).toBeTruthy()
  await groups.nth(1).click()
  const closed = await main
    .locator('.insight-group')
    .evaluateAll((elements) =>
      elements
        .slice(1)
        .map((element, index) => element.getBoundingClientRect().top - elements[index].getBoundingClientRect().bottom)
    )
  expect(closed).toEqual([0, 0])
  await search.fill('Heap')
  await expect(groups).toHaveCount(1)
  const sole = main.locator('.insight-group')
  for (const property of [
    'border-top-left-radius',
    'border-top-right-radius',
    'border-bottom-left-radius',
    'border-bottom-right-radius'
  ]) {
    expect(
      parseFloat(
        await sole.evaluate((element, property) => getComputedStyle(element).getPropertyValue(property), property)
      )
    ).toBeGreaterThan(0)
  }
  await expect(sole).toHaveCSS('border-top-width', '1px')
  await expect(sole).toHaveCSS('border-bottom-width', '1px')
  await groups.click()
  await expect(sole).toHaveCSS('margin-top', '16px')
  await expect(sole).toHaveCSS('margin-bottom', '16px')
  await groups.click()
  await expect(sole).toHaveCSS('margin-top', '0px')
  await expect(sole).toHaveCSS('margin-bottom', '0px')
}
