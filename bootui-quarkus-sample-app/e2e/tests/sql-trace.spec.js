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

    // The inspector runs synchronously on the worker thread that issued the query, and the sample app's own
    // frames are application code rather than BootUI's, so the expanded statement names a sample method.
    const executions = page.locator('section').filter({hasText: 'Recent executions'})
    const productRow = executions
      .locator('tbody tr.sql-row')
      .filter({hasText: 'SELECT'})
      .filter({hasText: 'sample_products'})
      .first()
    await productRow.click()
    await expect(executions.locator('tr.sql-detail-row').first()).toContainText('io.github.jdubois.bootui.sample.')
  })

  test('ranks normalized statements and attributes them without claiming thread affinity', async ({openView, page}) => {
    // Two executions of the same parameterized query with different values, plus a second route.
    await page.request.get('/api/sample/product-search?term=console').catch(() => {})
    await page.request.get('/api/sample/product-search?term=laptop').catch(() => {})
    await page.request.get('/api/sample/products').catch(() => {})

    await openView('sql-trace', 'SQL Trace')

    const rankings = page.locator('section').filter({hasText: 'Statement rankings'}).first()
    await expect(rankings).toBeVisible()
    await expect(rankings).toContainText('not lifetime metrics')

    const rankingRows = rankings.locator('table.sql-ranking-table tbody tr')
    await expect(rankingRows.first()).toBeVisible()

    // Normalization must have folded the bound values out of the ranked statement text.
    const rankedSql = await rankings.locator('table.sql-ranking-table code.sql-text').allInnerTexts()
    expect(rankedSql.length).toBeGreaterThan(0)
    for (const sql of rankedSql) {
      expect(sql).not.toContain("'")
      expect(sql.toLowerCase()).not.toContain('console')
      expect(sql.toLowerCase()).not.toContain('laptop')
    }

    const routes = page.locator('section').filter({hasText: 'Database time by request route'}).first()
    await expect(routes).toBeVisible()
    // The Vert.x event loop gives no reliable per-request thread, so this stack must NOT claim one.
    await expect(routes).toContainText('trace id')
    await expect(routes).not.toContainText('by serving thread')
    // Unplaceable work stays visible in its own buckets rather than being attributed to a guess.
    await expect(routes).toContainText('Unattributed')
    await expect(routes).toContainText('Ambiguous')

    // RESTEasy Reactive exposes no per-request route template, so the route key is either resolved from
    // the application's own declared JAX-RS mappings or masked — and never carries the query string we
    // sent above, nor a raw path-parameter value.
    const routeKeys = await routes.locator('table.sql-route-table tbody code').allInnerTexts()
    for (const key of routeKeys) {
      expect(key).not.toContain('?')
      expect(key).not.toContain('term=')
    }
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
