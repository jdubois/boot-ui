// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Log Tail view', () => {
  test('connects to the SSE stream, then can pause and resume', async ({openView, page}) => {
    await openView('log-tail', 'Log Tail')

    const status = page.getByRole('status', {name: 'Log stream status'})
    await expect(status).toHaveText(/Connected/, {timeout: 15_000})

    // Generate a log line via the sample API.
    const apiResponse = await page.request.get('/api/sample/hello')
    expect(apiResponse.ok()).toBeTruthy()

    // We don't strictly require a line to appear (logging.level controls that),
    // but the pane must remain stable and the pause button must work.
    await page.getByRole('button', {name: /Pause/}).click()
    await expect(status).toHaveText(/Paused/)

    await page.getByRole('button', {name: /Resume/}).click()
    await expect(status).toHaveText(/Connected/, {timeout: 15_000})
  })

  test('clear empties the log pane', async ({openView, page}) => {
    await openView('log-tail', 'Log Tail')

    await page.getByRole('button', {name: /Clear/}).click()
    // Either the empty-state message is shown, or there are no rendered lines.
    const empty = page.locator('text=No log lines to display.')
    const lineCount = page.locator('.log-pane code span.d-block')
    await expect(async () => {
      const visible = (await empty.count()) + ((await lineCount.count()) === 0 ? 1 : 0)
      expect(visible).toBeGreaterThan(0)
    }).toPass()
  })

  test('level selector exposes the four severity buckets', async ({openView, page}) => {
    await openView('log-tail', 'Log Tail')
    const select = page.locator('select.form-select')
    const options = await select.locator('option').allInnerTexts()
    expect(options).toEqual(['All', 'Info+', 'Warn+', 'Error'])
  })

  test('marks messages omitted by the value-exposure policy instead of showing empty lines', async ({
    openView,
    page
  }) => {
    const lines = [
      {
        timestamp: Date.now(),
        level: 'WARN',
        logger: 'com.example.Db',
        message: null,
        thread: 'main',
        messageOmitted: true
      },
      {
        timestamp: Date.now(),
        level: 'INFO',
        logger: 'com.example.Api',
        message: 'login password=******',
        thread: 'main',
        messageOmitted: false
      }
    ]
    await page.route(
      (url) => url.pathname.endsWith('/api/log-tail/stream'),
      (route) =>
        route.fulfill({
          contentType: 'text/event-stream',
          body: 'retry: 600000\n\n' + lines.map((line) => `event: log\ndata: ${JSON.stringify(line)}\n\n`).join('')
        })
    )

    await openView('log-tail', 'Log Tail')

    const pane = page.getByRole('log', {name: 'Live application logs'})
    await expect(pane).toContainText('com.example.Db - message omitted by policy')
    await expect(pane).toContainText('login password=******')
    await expect(page.getByRole('note')).toContainText(
      'Some log messages were omitted because bootui.expose-values was METADATA_ONLY when they were read.'
    )
  })
})
