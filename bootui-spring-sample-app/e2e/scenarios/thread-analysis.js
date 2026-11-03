// @ts-check
import {readFile} from 'node:fs/promises'

/** Shared real-backend investigation flow, registered by MVC, WebFlux and Quarkus. */
export function registerThreadAnalysisTests(test, expect, acceptConfirm) {
  test('explains observation comparisons with contextual settings and no automatic acquisition', async ({
    page,
    openView
  }, testInfo) => {
    const acquisitions = []
    page.on('request', (request) => {
      if (request.method() === 'POST' && /\/threads\/(captures|observations)$/.test(new URL(request.url()).pathname))
        acquisitions.push(request)
    })
    await page.setViewportSize({width: 1600, height: 900})
    const inventoryResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' && /\/threads\/captures$/.test(new URL(response.url()).pathname)
    )
    await openView('threads', 'Threads')
    const inventory = await (await inventoryResponse).json()
    await page.getByRole('tab', {name: 'Observe & compare', exact: true}).click()
    const comparison = page.getByRole('tabpanel', {name: 'Observe & compare', exact: true})
    await expect(comparison.getByRole('heading', {name: 'Compare threads over time', exact: true})).toBeVisible()
    if (inventory.observationId) {
      await expect(comparison).toContainText('Next observation settings')
      await expect(comparison).toContainText("This comparison uses the observation's samples")
    } else {
      await expect(comparison).toContainText('Single captures belong to Threads and Contention')
    }
    await expect(page.getByLabel('Thread source', {exact: true})).toHaveCount(0)
    await expect(page.getByRole('button', {name: 'Export capture', exact: true})).toHaveCount(0)
    await expect(page.getByRole('button', {name: 'Observe threads', exact: true})).toHaveCount(1)
    await expect(page.getByLabel('Samples', {exact: true})).toHaveValue('3')
    await expect(page.getByLabel('Interval (seconds)', {exact: true})).toHaveValue('5')
    await page.evaluate(() => {
      window.scrollTo(0, 0)
      document.querySelector('.bootui-workspace')?.scrollTo(0, 0)
    })
    await page.screenshot({
      path: testInfo.outputPath('threads-observe-compare-desktop.webp'),
      type: 'webp',
      quality: 80,
      animations: 'disabled'
    })
    await page.setViewportSize({width: 390, height: 844})
    for (const label of ['Samples', 'Interval (seconds)']) {
      const input = page.getByLabel(label, {exact: true})
      expect((await input.boundingBox())?.height).toBeGreaterThanOrEqual(44)
      await input.focus()
      await expect(input).toBeFocused()
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
    await page.evaluate(() => {
      document.activeElement?.blur()
      window.scrollTo(0, 0)
      document.querySelector('.bootui-workspace')?.scrollTo(0, 0)
    })
    await page.screenshot({
      path: testInfo.outputPath('threads-observe-compare-mobile.webp'),
      type: 'webp',
      quality: 80,
      animations: 'disabled'
    })
    expect(acquisitions).toHaveLength(0)
  })

  test('hides BootUI list rows by default while retaining full captures and comparison evidence', async ({
    page,
    openView
  }) => {
    await openView('threads', 'Threads')
    const toggle = page.getByRole('switch', {name: 'Show BootUI threads', exact: true})
    await expect(toggle).not.toBeChecked()
    await page.setViewportSize({width: 390, height: 844})
    const switchBox = await toggle.boundingBox()
    const labelBox = await page.locator('label[for="thread-show-bootui"]').boundingBox()
    if (!switchBox || !labelBox) throw new Error('The BootUI thread visibility control is not visible.')
    expect(switchBox.width / switchBox.height).toBeGreaterThanOrEqual(1.75)
    await expect
      .poll(async () => (await page.locator('label[for="thread-show-bootui"]').boundingBox())?.height ?? 0)
      .toBeGreaterThanOrEqual(44)
    await page.setViewportSize({width: 1280, height: 720})
    const readVisibility = (path, included) =>
      page.waitForResponse((response) => {
        const url = new URL(response.url())
        return (
          response.request().method() === 'GET' &&
          url.pathname.endsWith(path) &&
          url.searchParams.get('includeBootUi') === String(included)
        )
      })
    let response = readVisibility('/threads', true)
    await expect(toggle).toHaveCount(1)
    await toggle.focus()
    await expect(toggle).toBeFocused()
    await page.keyboard.press('Space')
    await expect(toggle).toBeChecked()
    const live = await (await response).json()
    expect(live.threads.some((row) => row.name.startsWith('bootui-'))).toBe(true)
    await page.getByRole('button', {name: 'Capture snapshot', exact: true}).click()
    await acceptConfirm(page)
    await expect(page.getByRole('heading', {name: 'Root blockers', exact: true})).toBeVisible()
    await expect(toggle).toBeDisabled()
    await page.getByRole('tab', {name: 'Threads', exact: true}).click()
    response = readVisibility('/threads', false)
    await toggle.uncheck()
    const retained = await (await response).json()
    expect(retained.hiddenBootUiThreads).toBeGreaterThan(0)
    expect(retained.threads.every((row) => !row.thread.name.startsWith('bootui-'))).toBe(true)
    await expect(page.locator('#thread-bootui-visibility')).toContainText('hidden')
    const pendingDownload = page.waitForEvent('download')
    await page.getByRole('button', {name: 'Export capture', exact: true}).click()
    await acceptConfirm(page)
    const file = await (await pendingDownload).path()
    if (!file) throw new Error('The retained capture download did not produce a local file.')
    const artifact = JSON.parse(await readFile(file, 'utf8'))
    expect(artifact.threads.some((row) => row.thread.name.startsWith('bootui-'))).toBe(true)
    await page.getByRole('tab', {name: 'Observe & compare', exact: true}).click()
    await page.getByLabel('Samples', {exact: true}).fill('2')
    await page.getByLabel('Interval (seconds)', {exact: true}).fill('1')
    await page.getByRole('button', {name: 'Observe threads', exact: true}).click()
    await acceptConfirm(page)
    const compare = page.getByRole('tabpanel', {name: 'Observe & compare', exact: true})
    await expect(compare.getByRole('status')).toContainText('COMPLETED', {timeout: 15_000})
    await expect(page.locator('#thread-bootui-visibility')).toContainText(/[1-9]\d* BootUI threads? hidden/)
    expect(await compare.locator('tbody tr button').allTextContents()).not.toContain('bootui-thread-observation-1')
    const fullComparison = page.waitForResponse((reply) => {
      const url = new URL(reply.url())
      return /\/observations\/[^/]+$/.test(url.pathname) && url.searchParams.get('includeBootUi') === 'true'
    })
    await toggle.check()
    const comparison = await (await fullComparison).json()
    expect(comparison.threads.some((row) => row.name.startsWith('bootui-'))).toBe(true)
    expect(comparison.hiddenBootUiThreads).toBe(0)
    await page.getByRole('button', {name: 'Clear captures', exact: true}).click()
    await acceptConfirm(page)
    await page.getByRole('tab', {name: 'Threads', exact: true}).click()
    await expect(page.getByLabel('Thread source', {exact: true})).toHaveValue('')
  })

  test('offers investigation and low-footprint actions immediately from the live view', async ({page, openView}) => {
    await openView('threads', 'Threads')
    const header = page.locator('.panel-header')
    const live = page.getByRole('tab', {name: 'Threads', exact: true})
    const selection = page.getByLabel('Thread source', {exact: true})
    const capture = header.getByRole('button', {name: 'Capture snapshot', exact: true})
    const observe = header.getByRole('button', {name: 'Observe threads', exact: true})
    const footprint = header.getByTestId('memory-offload')
    await expect(live).toHaveAttribute('aria-selected', 'true')
    await expect(selection).toHaveValue('')
    await expect(capture).toBeEnabled()
    await expect(observe).toBeEnabled()
    await expect(footprint).toHaveAttribute('aria-pressed', 'false')
    await observe.click()
    await expect(page.getByRole('heading', {name: 'Observe threads?', exact: true})).toBeVisible()
    await page.locator('dialog.confirm-dialog').getByRole('button', {name: 'Cancel', exact: true}).click()
    await expect(live).toHaveAttribute('aria-selected', 'true')
    await capture.click()
    await acceptConfirm(page)
    await expect(page.getByRole('tab', {name: 'Contention', exact: true})).toHaveAttribute('aria-selected', 'true')
    await expect(selection).not.toHaveValue('')
    await observe.click()
    await acceptConfirm(page)
    const comparison = page.getByRole('tabpanel', {name: 'Observe & compare', exact: true})
    await expect(comparison.getByRole('status')).toContainText('RUNNING')
    try {
      await footprint.click()
      await acceptConfirm(page)
      await expect(footprint).toHaveAttribute('aria-pressed', 'true')
      await expect(capture).toBeEnabled()
      await expect(observe).toBeEnabled()
      await expect(selection).toHaveCount(0)
      await expect(comparison.getByRole('status')).toContainText('POLICY_STOPPED')
      await header.getByTestId('memory-offload-panel').getByRole('button', {name: 'Close', exact: true}).click()
      await live.click()
      await expect(selection).toHaveValue('')
      await capture.click()
      await acceptConfirm(page)
      await expect(selection).not.toHaveValue('')
      await expect(page.getByRole('tabpanel', {name: 'Contention', exact: true})).toBeVisible()
      await observe.click()
      await acceptConfirm(page)
      await expect(comparison.getByRole('status')).toContainText('COMPLETED', {timeout: 20000})
      await expect(footprint).toHaveAttribute('aria-pressed', 'true')
      await expect(comparison).toContainText('3 / 3 samples')
      await page.getByRole('button', {name: 'Clear captures', exact: true}).click()
      await acceptConfirm(page)
    } finally {
      if ((await footprint.getAttribute('aria-pressed')) === 'true') await footprint.click()
      await expect(footprint).toHaveAttribute('aria-pressed', 'false')
    }
    await expect(capture).toBeEnabled()
    await expect(observe).toBeEnabled()
    await expect(comparison.getByRole('status')).toContainText('COMPLETED')
  })

  test('retains explicit thread captures, navigates evidence with accessible tabs, and never collects on tab selection', async ({
    page,
    openView
  }) => {
    const acquisitions = []
    page.on('request', (request) => {
      if (request.method() === 'POST' && /\/threads\/(captures|observations)$/.test(new URL(request.url()).pathname))
        acquisitions.push(request)
    })
    const inventoryResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' && /\/threads\/captures$/.test(new URL(response.url()).pathname)
    )
    await openView('threads', 'Threads')
    const inventory = await (await inventoryResponse).json()
    const views = page.getByRole('tablist', {name: 'Thread views', exact: true})
    await expect(views).toHaveCount(1)
    await expect(views.getByRole('tab')).toHaveCount(3)
    await expect(views.getByRole('tab', {name: 'Threads', exact: true})).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByRole('tab', {name: 'Investigate', exact: true})).toHaveCount(0)
    const header = page.locator('.panel-header')
    await expect(header.getByRole('button', {name: 'Observe threads', exact: true})).toHaveClass(
      'btn btn-outline-primary'
    )
    await expect(header.getByRole('button', {name: 'Capture snapshot', exact: true})).toHaveClass('btn btn-primary')
    await header.getByRole('button', {name: 'Observe threads', exact: true}).focus()
    await page.keyboard.press('Tab')
    await expect(header.getByRole('button', {name: 'Capture snapshot', exact: true})).toBeFocused()
    await expect(page.getByRole('tablist')).toHaveCount(1)
    const threads = page.getByRole('tab', {name: 'Threads', exact: true})
    await threads.focus()
    await page.keyboard.press('ArrowRight')
    await expect(page.getByRole('tab', {name: 'Contention', exact: true})).toBeFocused()
    await expect(page.getByRole('tab', {name: 'Contention', exact: true})).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByRole('tabpanel', {name: 'Threads', exact: true})).toHaveCount(0)
    await page.keyboard.press('End')
    await expect(page.getByRole('tab', {name: 'Observe & compare', exact: true})).toBeFocused()
    expect(acquisitions).toHaveLength(0)
    await page.getByRole('button', {name: 'Capture snapshot', exact: true}).click()
    await expect(page.getByRole('heading', {name: 'Capture thread snapshot?'})).toBeVisible()
    expect(acquisitions).toHaveLength(0)
    await acceptConfirm(page)
    await expect(page.getByRole('tab', {name: 'Contention', exact: true})).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByRole('heading', {name: 'Root blockers', exact: true})).toBeVisible()
    await expect.poll(() => acquisitions.length).toBe(1)
    const selection = page.getByLabel('Thread source', {exact: true})
    const captureId = await selection.inputValue()
    expect(captureId).toMatch(/^[a-f0-9-]{36}$/)
    await threads.click()
    const panel = page.getByRole('tabpanel', {name: 'Threads', exact: true})
    await expect(panel.locator('tbody tr').first()).toBeVisible()
    await panel.locator('tbody tr button').first().click()
    await expect(page.getByRole('region', {name: 'Thread evidence'})).toBeVisible()
    await expect(page.getByRole('region', {name: 'Thread evidence'}).getByRole('heading').first()).toBeFocused()
    await page.getByRole('button', {name: 'Close thread evidence', exact: true}).click()
    await page.getByRole('tab', {name: 'Observe & compare', exact: true}).click()
    expect(acquisitions).toHaveLength(1)
    await expect(selection).toHaveCount(0)
    await expect(page.getByRole('heading', {name: 'Compare threads over time', exact: true})).toBeVisible()
    if (inventory.observationId) {
      await expect(page.getByRole('tabpanel', {name: 'Observe & compare', exact: true})).toContainText(
        'Next observation settings'
      )
    } else {
      await expect(page.getByText('Observation results will appear here', {exact: true})).toBeVisible()
    }
    await expect(page.getByRole('button', {name: 'Observe threads', exact: true})).toHaveCount(1)
    await threads.click()
    await expect(selection).toHaveValue(captureId)
    await selection.selectOption('')
    await expect(threads).toHaveAttribute('aria-selected', 'true')
    await expect(page.getByRole('button', {name: 'Refresh panel', exact: true})).toBeVisible()
    expect(acquisitions).toHaveLength(1)
    await selection.selectOption(captureId)
    await expect(panel.locator('tbody tr').first()).toBeVisible()
    expect(acquisitions).toHaveLength(1)
    await page.getByRole('button', {name: 'Clear captures', exact: true}).click()
    await acceptConfirm(page)
    await expect(selection).toHaveValue('')
    await expect(page.getByRole('button', {name: 'Export capture', exact: true})).toBeDisabled()
    expect(acquisitions).toHaveLength(1)
  })

  test('completes a finite explicit thread observation and displays interval evidence', async ({page, openView}) => {
    const observations = []
    page.on('request', (request) => {
      if (request.method() === 'POST' && /\/threads\/observations$/.test(new URL(request.url()).pathname))
        observations.push(request)
    })
    await openView('threads', 'Threads')
    await page.getByRole('tab', {name: 'Observe & compare', exact: true}).click()
    await page.getByLabel('Samples', {exact: true}).fill('2')
    await page.getByLabel('Interval (seconds)', {exact: true}).fill('3')
    await page.getByRole('button', {name: 'Observe threads', exact: true}).click()
    await expect(page.getByRole('heading', {name: 'Observe threads?', exact: true})).toBeVisible()
    expect(observations).toHaveLength(0)
    await acceptConfirm(page)
    const comparison = page.getByRole('tabpanel', {name: 'Observe & compare', exact: true})
    await expect(comparison.getByRole('status')).toContainText('RUNNING')
    await page.getByRole('tab', {name: 'Threads', exact: true}).click()
    await expect(
      page.getByText('Live refresh and downloads are paused until the observation ends.', {exact: false})
    ).toBeVisible()
    await expect(page.getByRole('button', {name: /Download dump$/})).toBeDisabled()
    await expect(page.getByRole('button', {name: 'Capture snapshot', exact: true})).toBeDisabled()
    await expect(page.getByRole('textbox', {name: 'Filter threads', exact: true})).toBeDisabled()
    await page.getByRole('tab', {name: 'Observe & compare', exact: true}).click()
    await expect(comparison.getByRole('status')).toContainText('COMPLETED', {timeout: 15_000})
    await expect(comparison.getByRole('status')).toContainText('2 / 2 samples')
    expect(observations[0].postDataJSON()).toEqual({samples: 2, intervalMillis: 3000})
    await expect(comparison.locator('tbody tr').first()).toBeVisible()
    await expect(comparison.getByRole('columnheader', {name: 'Interval CPU', exact: true})).toBeVisible()
    await expect(comparison.getByRole('button', {name: 'Cancel observation', exact: true})).toHaveCount(0)
    const hiddenSourceReads = []
    page.on('request', (request) => {
      if (/\/captures\/[^/]+\/threads$/.test(new URL(request.url()).pathname)) hiddenSourceReads.push(request)
    })
    await comparison.locator('tbody tr button').first().click()
    const evidence = page.getByRole('region', {name: 'Thread evidence', exact: true})
    await expect(evidence).toBeVisible()
    await expect(evidence.getByRole('heading').first()).toBeFocused()
    await page.getByRole('button', {name: 'Close thread evidence', exact: true}).click()
    await page.getByRole('tab', {name: 'Threads', exact: true}).click()
    await expect(page.getByLabel('Thread source', {exact: true})).toHaveValue('')
    await expect(page.getByRole('button', {name: /Download dump$/})).toBeEnabled()
    expect(hiddenSourceReads).toHaveLength(0)
    expect(observations).toHaveLength(1)
    await page.getByLabel('Thread source', {exact: true}).selectOption('')
    await page.getByRole('tab', {name: 'Observe & compare', exact: true}).click()
    await expect(
      page.getByRole('tabpanel', {name: 'Observe & compare', exact: true}).getByRole('status')
    ).toContainText('COMPLETED')
    expect(observations).toHaveLength(1)
  })
}
