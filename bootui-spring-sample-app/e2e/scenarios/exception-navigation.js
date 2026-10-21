// @ts-check

export function registerExceptionNavigationTests(
  test,
  expect,
  {uiBase = '/bootui/', apiBase = '/bootui/api', appBase = ''} = {}
) {
  async function capturedFailure(page) {
    expect((await page.request.get(`${appBase}/api/sample/boom`)).status()).toBe(500)
    const response = await page.request.get(`${apiBase}/exceptions`)
    expect(response.ok()).toBeTruthy()
    const report = await response.json()
    const group = report.groups.find((candidate) => candidate.message?.includes('Sample failure'))
    expect(group?.id).toBeTruthy()
    const detailResponse = await page.request.get(`${apiBase}/exceptions/${encodeURIComponent(group.id)}`)
    expect(detailResponse.ok()).toBeTruthy()
    return {report, group, detail: await detailResponse.json()}
  }

  test('navigates a failed request profile to its exact exception and back by keyboard', async ({page}) => {
    await page.setViewportSize({width: 1600, height: 900})
    const {group} = await capturedFailure(page)
    await page.goto(`${uiBase}#/activity`)
    const requestRow = page.locator('.activity-row-clickable', {hasText: group.lastRequestPath}).first()
    await expect(requestRow).toBeVisible()
    await requestRow.getByRole('button', {name: /Profile/}).focus()
    await page.keyboard.press('Enter')
    const profile = page.getByRole('dialog', {name: 'Request profile', exact: true})
    const exceptionLink = profile.locator('a[title="Open exception details"]').first()
    await expect(exceptionLink).toBeVisible()
    const href = await exceptionLink.getAttribute('href')
    const query = new URLSearchParams(href.split('?')[1])
    const groupId = query.get('id')
    const requestId = query.get('request')
    expect(groupId).toBeTruthy()
    expect(requestId).toBeTruthy()
    await exceptionLink.focus()
    await page.keyboard.press('Enter')
    const details = page.locator('.exception-drawer')
    await expect(details).toBeFocused()
    await expect(page).toHaveURL(new RegExp(`id=${encodeURIComponent(groupId)}`))
    await expect(details.locator('.stack-pane')).toContainText(/Sample(?:ActionsController|Controller|Resource)/)
    await expect(details.locator('.stack-pane')).toContainText('Caused by: java.lang.NumberFormatException')
    const table = details.locator('xpath=ancestor::div[contains(@class, "table-responsive")]')
    expect(await table.evaluate((element) => element.scrollWidth)).toBeLessThanOrEqual(
      await table.evaluate((element) => element.clientWidth)
    )
    await expect(details).not.toContainText('sample-secret-token')
    const back = page.getByRole('link', {name: 'Back to request profile', exact: true})
    await expect(back).toHaveAttribute('href', `#/activity?request=${encodeURIComponent(requestId)}`)
    await back.focus()
    await page.keyboard.press('Enter')
    await expect(profile).toBeVisible()
    await expect(profile).toContainText('/api/sample/boom')
    await profile.getByRole('button', {name: 'Close', exact: true}).click()
    const childLink = page
      .locator(`.activity-child-row a[title="Open exception details"][href*="id=${encodeURIComponent(groupId)}"]`)
      .first()
    await expect(childLink).toHaveAttribute('href', new RegExp(`id=${encodeURIComponent(groupId)}`))
    await childLink.click()
    await expect(details.locator('.stack-pane')).toBeVisible()
  })

  test('never guesses between two groups of the same class, and preserves legacy search', async ({page}) => {
    const {report, group, detail} = await capturedFailure(page)
    const second = {...group, id: 'other-group', message: 'A different failure of the same class'}
    await page.route(`**${apiBase}/exceptions`, (route) =>
      route.fulfill({
        json: {...report, groups: [group, second]}
      })
    )
    await page.route(`**${apiBase}/exceptions/other-group`, (route) =>
      route.fulfill({
        json: {
          ...detail,
          group: second,
          frames: [
            {
              declaringClass: 'example.OtherFailure',
              methodName: 'run',
              fileName: 'OtherFailure.java',
              lineNumber: 77,
              applicationFrame: true
            }
          ]
        }
      })
    )
    await page.goto(`${uiBase}#/exceptions?id=other-group`)
    const details = page.locator('.exception-drawer')
    await expect(details).toContainText('A different failure of the same class')
    await expect(details.locator('.stack-pane')).toContainText('OtherFailure.java:77')
    await page.goto(`${uiBase}#/exceptions?id=${encodeURIComponent(group.id)}`)
    await expect(details).toContainText(group.message)
    await expect(details.locator('.stack-pane')).not.toContainText('OtherFailure.java:77')
    await page.goto(`${uiBase}#/exceptions?q=${encodeURIComponent(group.exceptionClassName)}`)
    await expect(page.getByRole('textbox', {name: 'Filter exception groups'})).toHaveValue(group.exceptionClassName)
    await expect(details).toHaveCount(0)
  })

  test('shows a cleared group as unavailable rather than opening a different failure', async ({page}) => {
    await capturedFailure(page)
    await page.goto(`${uiBase}#/exceptions?id=cleared-group&request=original-request`)
    await expect(page.getByRole('status').filter({hasText: 'no longer retained'})).toBeVisible()
    await expect(page.locator('.exception-drawer')).toHaveCount(0)
    await expect(page.getByRole('link', {name: 'Back to request profile'})).toHaveAttribute(
      'href',
      '#/activity?request=original-request'
    )
  })

  test('falls back to a class search for an activity entry without a group ID', async ({page}) => {
    const {group} = await capturedFailure(page)
    const response = await page.request.get(`${apiBase}/activity?source=buffers`)
    expect(response.ok()).toBeTruthy()
    const activity = await response.json()
    const exception = activity.entries.find(
      (entry) => entry.type === 'EXCEPTION' && entry.exceptionGroupId === group.id
    )
    expect(exception).toBeTruthy()
    const legacyActivity = {
      ...activity,
      entries: [{...exception, id: 'legacy-entry', parentId: null, exceptionGroupId: null}]
    }
    for (const pattern of [`**${apiBase}/activity`, `**${apiBase}/activity?*`]) {
      await page.route(pattern, (route) => route.fulfill({json: legacyActivity}))
    }
    await page.goto(`${uiBase}#/activity`)
    const link = page.locator('.activity-table a[title="Open in Exceptions"]')
    await expect(link).toHaveAttribute('href', `#/exceptions?q=${encodeURIComponent(group.exceptionClassName)}`)
    await link.click()
    await expect(page.getByRole('textbox', {name: 'Filter exception groups'})).toHaveValue(group.exceptionClassName)
    await expect(page.locator('.exception-drawer')).toHaveCount(0)
  })

  test('shows a denied detail read without exposing a stack or export', async ({page}) => {
    const {group} = await capturedFailure(page)
    await page.route(`**${apiBase}/exceptions/${encodeURIComponent(group.id)}`, (route) =>
      route.fulfill({
        status: 403,
        json: {error: 'Panel is disabled'}
      })
    )
    await page.goto(`${uiBase}#/exceptions?id=${encodeURIComponent(group.id)}&request=original-request`)
    const details = page.locator('.exception-drawer')
    await expect(details.getByRole('status')).toContainText('Exception details unavailable')
    await expect(details.locator('.stack-pane')).toHaveCount(0)
    await expect(details.getByRole('button', {name: 'Copy for AI'})).toHaveCount(0)
    await expect(page.getByRole('link', {name: 'Back to request profile'})).toBeVisible()
  })

  test('does not read exception data when the panel manifest disables it', async ({page}) => {
    let reads = 0
    await page.route(`**${apiBase}/panels`, async (route) => {
      const response = await route.fetch()
      const manifest = await response.json()
      manifest.panels = manifest.panels.map((panel) => (panel.id === 'exceptions' ? {...panel, enabled: false} : panel))
      await route.fulfill({json: manifest})
    })
    await page.route(`**${apiBase}/exceptions**`, async (route) => {
      reads++
      await route.fulfill({status: 403, json: {error: 'Panel is disabled'}})
    })
    await page.goto(`${uiBase}#/exceptions?id=hidden-group&request=original-request`)
    await expect(page.getByRole('status').filter({hasText: 'Exception details unavailable'})).toBeVisible()
    await expect(page.locator('.exception-drawer')).toHaveCount(0)
    expect(reads).toBe(0)
  })
}
