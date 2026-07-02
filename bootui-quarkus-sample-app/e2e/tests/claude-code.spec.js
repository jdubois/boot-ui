// @ts-check
import {expect, test} from './fixtures.js'

// Claude Code reuses the same Copilot.vue component with no platform-specific branching, and the
// dashboard/session DTOs are shared, framework-neutral records, so this spec mirrors the Spring
// suite's claude-code.spec.js closely. Real session directories are host-machine-specific and
// non-deterministic, so -- exactly like the Spring spec -- the three Claude Code endpoints are
// mocked with realistic sanitized sample data.
test.describe('Claude Code panel (Quarkus)', () => {
  test('shows an aggregated dashboard from sanitized Claude Code activity', async ({page}) => {
    const dashboard = {
      available: true,
      sessionStateDir: '/home/dev/.claude/projects',
      sessionCount: 1,
      eventCount: 12,
      turnCount: 4,
      totalInputTokens: 54321,
      totalOutputTokens: 1234,
      errorCount: 1,
      activeLast24Hours: 1,
      activeLast7Days: 1,
      sessionsWithSchemaDrift: 0,
      lastActivityEpochMillis: Date.now() - 60_000,
      categoryCounts: [
        {label: 'SHELL', count: 8},
        {label: 'FILE_EDIT', count: 4}
      ],
      modelCounts: [{label: 'claude-sonnet-4', count: 1}],
      topTools: [{label: 'Bash', count: 8}],
      otherToolEventCount: 0,
      activityBuckets: Array.from({length: 24}, (_, index) => ({
        startEpochMillis: Date.now() - (23 - index) * 60 * 60 * 1000,
        endEpochMillis: Date.now() - (22 - index) * 60 * 60 * 1000,
        eventCount: index === 23 ? 12 : 0,
        errorCount: index === 23 ? 1 : 0,
        inputTokens: index === 23 ? 54321 : 0,
        outputTokens: index === 23 ? 1234 : 0
      })),
      dailyActivityBuckets: Array.from({length: 7}, (_, index) => ({
        startEpochMillis: Date.now() - (6 - index) * 24 * 60 * 60 * 1000,
        endEpochMillis: Date.now() - (5 - index) * 24 * 60 * 60 * 1000,
        eventCount: index === 6 ? 12 : 0,
        errorCount: index === 6 ? 1 : 0,
        inputTokens: index === 6 ? 54321 : 0,
        outputTokens: index === 6 ? 1234 : 0
      })),
      recentSessions: [
        {
          id: 'session-one',
          filename: 'project-one/session-one.jsonl',
          startedAtEpochMillis: Date.now() - 120_000,
          updatedAtEpochMillis: Date.now() - 60_000,
          model: 'claude-sonnet-4',
          workingDirectory: '/work/app',
          status: null,
          eventCount: 12,
          turnCount: 4,
          inputTokens: 54321,
          outputTokens: 1234,
          errorCount: 1,
          lastActivitySummary: 'SHELL · Bash · failed',
          schemaDrift: false
        }
      ],
      warnings: []
    }
    const sessionDetail = {
      summary: dashboard.recentSessions[0],
      counts: {
        total: 12,
        byCategory: {SHELL: 8, FILE_EDIT: 4},
        errors: 1,
        lastActivityEpochMillis: dashboard.recentSessions[0].updatedAtEpochMillis
      },
      turns: [
        {
          index: 0,
          startedAtEpochMillis: Date.now() - 120_000,
          durationMillis: 1500,
          summary: 'assistant',
          eventCount: 2,
          inputTokens: 54321,
          outputTokens: 1234
        }
      ],
      recentEvents: [
        {
          id: 'toolu_1',
          turnIndex: 0,
          timestampEpochMillis: Date.now() - 90_000,
          type: 'tool_use',
          toolName: 'Bash',
          category: 'SHELL',
          summary: 'SHELL · Bash',
          success: true
        },
        {
          id: 'toolu_1-result',
          turnIndex: 0,
          timestampEpochMillis: Date.now() - 60_000,
          type: 'tool_result',
          toolName: 'Bash',
          category: 'SHELL',
          summary: 'SHELL · Bash · failed',
          success: false
        }
      ],
      failureEvents: [
        {
          id: 'toolu_1-result',
          turnIndex: 0,
          timestampEpochMillis: Date.now() - 60_000,
          type: 'tool_result',
          toolName: 'Bash',
          category: 'SHELL',
          summary: 'SHELL · Bash · failed',
          success: false
        }
      ],
      warnings: []
    }

    await page.route('**/bootui/api/claude-code/dashboard', async (route) => {
      await route.fulfill({contentType: 'application/json', body: JSON.stringify(dashboard)})
    })
    await page.route(
      (url) => url.pathname === '/bootui/api/claude-code/sessions',
      async (route) => {
        await route.fulfill({
          contentType: 'application/json',
          body: JSON.stringify({
            available: true,
            sessionStateDir: dashboard.sessionStateDir,
            total: 3,
            returned: 1,
            maxSessions: 1,
            sessions: dashboard.recentSessions,
            warnings: ['Showing the 1 most recent Claude Code sessions out of 3.']
          })
        })
      }
    )
    await page.route('**/bootui/api/claude-code/sessions/session-one', async (route) => {
      await route.fulfill({contentType: 'application/json', body: JSON.stringify(sessionDetail)})
    })
    await page.route('**/bootui/api/claude-code/stream', async (route) => {
      await route.fulfill({contentType: 'text/event-stream', body: ''})
    })

    await page.goto('/bootui/#/claude-code')

    await expect(page.getByLabel('Auto-refresh')).toBeChecked()
    await expect(page.getByTitle('Refresh', {exact: true})).toBeVisible()
    await expect(page.locator('.panel-header__actions .badge').filter({hasText: 'Live'})).toHaveCount(0)
    await expect(page.getByRole('heading', {name: 'Claude Code activity overview'})).toBeVisible()
    await expect(page.getByText('/home/dev/.claude/projects')).toBeVisible()
    await expect(page.getByText('bootui.claude-code.max-sessions')).toBeVisible()
    await expect(page.locator('#activity-mode-tokens')).toBeChecked()
    await expect(page.locator('.metric-card', {hasText: 'Tokens'}).getByText('55,55k', {exact: true})).toBeVisible()
    await expect(page.locator('.metric-card', {hasText: 'Tokens'}).getByText('54,32k in · 1,23k out')).toBeVisible()
    await expect(page.getByText('copilot-mission-control')).toHaveCount(0)
    await expect(page.getByRole('heading', {name: 'Top tools'}).locator('..').getByText('Bash')).toBeVisible()

    await page.getByRole('button', {name: 'Show failures for session-one'}).click()
    await expect(page.getByRole('tab', {name: /Failures/})).toHaveClass(/active/)
    await expect(page.getByRole('list').getByText('SHELL · Bash · failed')).toBeVisible()
  })
})
