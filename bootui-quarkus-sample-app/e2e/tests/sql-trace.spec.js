// @ts-check
import {acceptConfirm, expect, test} from './fixtures.js'

/**
 * SQL Trace + Live Activity capture on Quarkus.
 *
 * The Quarkus adapter captures Hibernate ORM SQL through a {@code @PersistenceUnitExtension}
 * StatementInspector (BootUiHibernateStatementInspector) that feeds the shared bootui-engine SQL
 * recorder — complementing the manual-JDBC Agroal DataSource wrap. The sample's catalog uses Panache,
 * so {@code /api/sample/product-search} runs a live SELECT that the SQL Trace panel must show.
 *
 * Live Activity then merges those SQL statements together with HTTP requests and captured exceptions
 * into one feed, so the panel no longer shows the old "SQL trace and exceptions are not yet captured
 * on Quarkus" warning. That stale banner was the user-visible symptom this work removes.
 */
test.describe('SQL Trace + Live Activity capture (Quarkus)', () => {
  test('SQL Trace shows Hibernate ORM SQL issued through Panache', async ({openView, page}) => {
    // Seed a live, uncached Panache SELECT before the panel loads (mirrors cache.spec.js seeding).
    await page.request.get('/api/sample/product-search?term=console').catch(() => {})
    await page.request.get('/api/sample/products').catch(() => {})

    await openView('sql-trace', 'SQL Trace')

    // The captured SELECT renders as statement text — this only appears when the panel is available
    // (the inspector registers the datasource on the first statement) and a SELECT was classified.
    const selectStatement = page
      .locator('code.sql-text')
      .filter({hasText: /select/i})
      .first()
    await expect(selectStatement).toBeVisible()
  })

  test('Live Activity merges SQL and exceptions without the "not yet captured" banner', async ({openView, page}) => {
    // Exercise all three sources the Quarkus assembler now merges: SQL, an exception, and a request.
    await page.request.get('/api/sample/product-search?term=console').catch(() => {})
    await page.request.get('/api/sample/boom').catch(() => {})
    await page.request.get('/api/sample/hello').catch(() => {})

    await openView('activity', 'Live Activity')

    // The stale assembler warning (the user's literal symptom) must be gone on Quarkus.
    await expect(page.locator('main')).not.toContainText(/not yet captured/i)

    // The merged feed shows the captured SQL and exception activity.
    const feed = page.locator('table.activity-table')
    await expect(feed).toContainText('SQL')
    await expect(feed).toContainText('EXCEPTION')
  })

  test('pauses and resumes recording through the live BootUiSqlTraceProducer wrap', async ({openView, page}) => {
    await openView('sql-trace', 'SQL Trace')

    const toggleButton = page.getByRole('button', {name: /Pause|Resume/})
    await expect(toggleButton).toHaveText('Pause')

    await toggleButton.click()
    await expect(page.locator('.alert-success')).toContainText('Recording paused; existing executions are kept.')
    await expect(toggleButton).toHaveText('Resume')

    // Restore recording so later specs in this sequential run keep capturing SQL.
    await toggleButton.click()
    await expect(page.locator('.alert-success')).toContainText('Recording resumed.')
    await expect(toggleButton).toHaveText('Pause')
  })

  test('clears the retained SQL trace buffer', async ({openView, page}) => {
    await page.request.get('/api/sample/product-search?term=console').catch(() => {})
    await openView('sql-trace', 'SQL Trace')

    const clearButton = page.getByRole('button', {name: 'Clear'})
    await expect(clearButton).toBeEnabled()
    await clearButton.click()
    await acceptConfirm(page)

    await expect(page.locator('.alert-success')).toContainText('SQL trace cleared.')
    await expect(clearButton).toBeDisabled()
  })
})
