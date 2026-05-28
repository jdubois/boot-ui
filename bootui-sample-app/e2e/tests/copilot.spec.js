// @ts-check
import {expect, test} from './fixtures.js'

test.describe('Copilot panel', () => {
  test('shows an aggregated dashboard from sanitized Copilot telemetry', async ({page}) => {
    const dashboard = {
      available: true,
      sessionStateDir: '/home/dev/.copilot/session-state',
      sessionCount: 2,
      eventCount: 42,
      turnCount: 7,
      errorCount: 3,
      activeLast24Hours: 1,
      activeLast7Days: 2,
      sessionsWithSchemaDrift: 0,
      lastActivityEpochMillis: Date.now() - 60_000,
      categoryCounts: [
        {label: 'FILE_EDIT', count: 18},
        {label: 'SHELL', count: 12},
        {label: 'MCP', count: 6}
      ],
      modelCounts: [
        {label: 'gpt-5.5', count: 1},
        {label: 'gpt-5.4', count: 1}
      ],
      topTools: [
        {label: 'apply_patch', count: 14},
        {label: 'bash', count: 9}
      ],
      otherToolEventCount: 5,
      activityBuckets: Array.from({length: 24}, (_, index) => ({
        startEpochMillis: Date.now() - (23 - index) * 60 * 60 * 1000,
        endEpochMillis: Date.now() - (22 - index) * 60 * 60 * 1000,
        eventCount: index % 4 === 0 ? 10 : index,
        errorCount: index % 8 === 0 ? 1 : 0
      })),
      recentSessions: [
        {
          id: 'session-one',
          filename: 'session-one/events.jsonl',
          startedAtEpochMillis: Date.now() - 120_000,
          updatedAtEpochMillis: Date.now() - 60_000,
          model: 'gpt-5.5',
          workingDirectory: '/work/app',
          status: null,
          eventCount: 30,
          turnCount: 5,
          errorCount: 2,
          lastActivitySummary: 'FILE_EDIT · apply_patch',
          schemaDrift: false
        }
      ],
      warnings: []
    }
    const sessionDetail = {
      summary: dashboard.recentSessions[0],
      counts: {
        total: 30,
        byCategory: {FILE_EDIT: 18, SHELL: 12},
        errors: 2,
        lastActivityEpochMillis: dashboard.recentSessions[0].updatedAtEpochMillis
      },
      turns: [
        {
          index: 0,
          startedAtEpochMillis: Date.now() - 120_000,
          durationMillis: 1500,
          summary: 'Investigating session',
          eventCount: 2
        }
      ],
      recentEvents: [
        {
          id: 'ok',
          turnIndex: 0,
          timestampEpochMillis: Date.now() - 90_000,
          type: 'tool.execution_start',
          toolName: 'apply_patch',
          category: 'FILE_EDIT',
          summary: 'FILE_EDIT · apply_patch',
          success: true
        },
        {
          id: 'failure',
          turnIndex: 0,
          timestampEpochMillis: Date.now() - 60_000,
          type: 'tool.execution_complete',
          toolName: 'bash',
          category: 'SHELL',
          summary: 'SHELL · bash',
          success: false
        }
      ],
      warnings: []
    }

    await page.route('**/bootui/api/copilot/dashboard', async (route) => {
      await route.fulfill({contentType: 'application/json', body: JSON.stringify(dashboard)})
    })
    await page.route('**/bootui/api/copilot/sessions', async (route) => {
      await route.fulfill({
        contentType: 'application/json',
        body: JSON.stringify({
          available: true,
          sessionStateDir: dashboard.sessionStateDir,
          total: 5,
          returned: 1,
          maxSessions: 1,
          sessions: dashboard.recentSessions,
          warnings: ['Showing the 1 most recent Copilot sessions out of 5.']
        })
      })
    })
    await page.route('**/bootui/api/copilot/sessions/session-one', async (route) => {
      await route.fulfill({contentType: 'application/json', body: JSON.stringify(sessionDetail)})
    })
    await page.route('**/bootui/api/copilot/stream', async (route) => {
      await route.fulfill({contentType: 'text/event-stream', body: ''})
    })

    await page.goto('/bootui/#/copilot')

    await expect(page.getByRole('heading', {name: 'Copilot activity overview'})).toBeVisible()
    await expect(
      page.locator('.metric-card', {hasText: 'Sanitized events'}).getByText('42', {exact: true})
    ).toBeVisible()
    await expect(page.getByRole('heading', {name: 'Top tools'}).locator('..').getByText('apply_patch')).toBeVisible()
    await expect(page.getByRole('heading', {name: 'Event mix'}).locator('..').getByText('FILE_EDIT')).toBeVisible()
    await expect(page.getByRole('heading', {name: 'Session explorer'})).toBeVisible()
    await expect(page.getByText('1 / 5 sessions')).toBeVisible()
    await expect(page.getByText('bootui.copilot.max-sessions')).toBeVisible()

    await page.getByRole('button', {name: 'Show failures for session-one'}).click()
    await expect(page.getByRole('tab', {name: /Failures/})).toHaveClass(/active/)
    await expect(page.getByText('SHELL · bash')).toBeVisible()

    await page.getByRole('tab', {name: 'Turn story'}).click()
    await expect(page.getByRole('tab', {name: 'Turn story'})).toHaveClass(/active/)
    await expect(page.getByText('Investigating session')).toBeVisible()
  })
})
