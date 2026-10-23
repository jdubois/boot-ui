// @ts-check
import {expect, test} from './fixtures.js'
import {expectedAdvisorScore} from '../../../bootui-spring-sample-app/e2e/scenarios/advisor-scoring.js'

/**
 * The Hibernate advisor runs the shared engine's mapping/identifier/fetch rules against the live JPA
 * metamodel, discovered through the EntityManagerFactory (Quarkus has no repository layer, so the
 * Spring-Data-repository-specific rules never fire here - only entity-mapping findings are asserted).
 * The sample's advisor.hibernate entities intentionally trigger several rules so a real scan has
 * deterministic findings; the same annotations exist on the Spring sample, so this pins parity.
 */
test.describe('Hibernate advisor (Quarkus)', () => {
  test('runs Hibernate checks and renders real entity-mapping findings', async ({openView, page}) => {
    await openView('hibernate', 'Hibernate')

    const scanResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' && /\/hibernate\/scan$/.test(new URL(response.url()).pathname)
    )
    await page.getByRole('button', {name: 'Run Hibernate checks'}).click()
    const response = await scanResponse
    expect(response.ok()).toBeTruthy()
    const report = await response.json()
    expect(report.rulesEvaluated).toBe(72)
    // Quarkus persistence units coordinate transactions through JTA, so HIB-CONFIG-008 and HIB-CONFIG-021 are
    // skipped as not applicable rather than missing evidence, and a scan with nothing unobservable is complete.
    expect(report.scan.status).toBe('SCANNED')
    expect(report.scan.message).toBeTruthy()
    expect(report.scan.message).not.toMatch(/Incomplete: |\+\d+ more/)
    expect(report.evidence.coverageComplete).toBe(true)
    expect(report.diagnostics).toEqual([])
    expect(report.results.map((result) => result.id)).not.toContain('HIB-CONFIG-021')
    expect(report.entitiesAnalyzed).toBeGreaterThan(0)
    expect(new Set(report.results.map((result) => result.id)).size).toBe(report.results.length)
    for (const id of [
      'HIB-FETCH-004',
      'HIB-MAP-012',
      'HIB-MAP-017',
      'HIB-MAP-019',
      'HIB-MAP-021',
      'HIB-ENTITY-002',
      'HIB-ENTITY-003',
      'HIB-ENTITY-004',
      'HIB-CONFIG-001'
    ]) {
      expect(report.results.map((result) => result.id)).not.toContain(id)
    }
    expect(report.results.some((result) => result.id.startsWith('HIB-QUERY-'))).toBe(false)

    await expect(page.locator('.advisor-summary__metric--status .badge')).toHaveText('Results available')
    expect(report.evidence.usable).toBe(true)
    await expect(page.locator('.advisor-summary__value')).toHaveText(String(expectedAdvisorScore(report)))
    await expect(page.getByRole('img', {name: /Known-findings score: \d+ out of 100$/})).toHaveCount(1)
    await expect(page.locator('main')).toContainText('Entities analysed')

    await expect(page.getByText('Scan diagnostics')).toHaveCount(0)

    // Effective batching makes the stronger IDENTITY finding apply, without duplicate generic advice.
    await expect(page.locator('.list-group-item', {hasText: 'HIB-ID-001'})).toHaveCount(0)
    const identityRow = page.locator('.list-group-item', {hasText: 'HIB-ID-006'})
    await expect(identityRow).toContainText('GenerationType.IDENTITY disables JDBC batch inserts')
    await expect(identityRow).toContainText('Product#id uses GenerationType.IDENTITY')

    // SampleLegacyTicket uses GenerationType.TABLE.
    const tableRow = page.locator('.list-group-item', {hasText: 'HIB-ID-002'})
    await expect(tableRow).toContainText('Review table-based identifier allocation')

    // SampleCustomer#invoices is a unidirectional @OneToMany.
    const mappingRow = page.locator('.list-group-item', {hasText: 'HIB-MAP-001'})
    await expect(mappingRow).toContainText('One-to-many associations should be bidirectional or join-column based')

    // SampleOrder#customer is @ManyToOne(fetch = FetchType.EAGER).
    const fetchRow = page.locator('.list-group-item', {hasText: 'HIB-FETCH-001'})
    await expect(fetchRow).toContainText('Eager fetching should stay explicit and bounded')
  })

  test('flags @Lob large-object storage on PostgreSQL instead of lazy-loading advice', async ({openView, page}) => {
    await openView('hibernate', 'Hibernate')

    await page.getByRole('button', {name: 'Run Hibernate checks'}).click()

    const lobRow = page.locator('.list-group-item', {hasText: 'HIB-MAP-024'})
    await expect(lobRow).toContainText('@Lob on PostgreSQL stores values as large objects', {timeout: 20_000})
    await expect(lobRow).toContainText('SampleAuditEntry#payload is @Lob on PostgreSQL')
    await expect(page.locator('.list-group-item', {hasText: 'HIB-FETCH-005'})).toHaveCount(0)
  })

  test('a second scan with nothing changed reports no changes since the previous scan', async ({openView, page}) => {
    await openView('hibernate', 'Hibernate')
    const scan = async () => {
      const scanResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' && /\/hibernate\/scan$/.test(new URL(response.url()).pathname)
      )
      await page.getByRole('button', {name: 'Run Hibernate checks'}).click()
      return (await scanResponse).json()
    }
    const first = await scan()
    const second = await scan()
    const changes = second.violationDetails.changes
    expect(changes.previousScanId).toBe(first.violationDetails.scanId)
    expect(changes.newCount).toBe(0)
    expect(changes.resolvedCount).toBe(0)
    expect(changes.unchangedCount).toBeGreaterThan(0)
    expect(changes.unverifiedCount).toBe(0)
    expect(changes.complete).toBe(true)
    const region = page.getByTestId('advisor-changes')
    await expect(region).toContainText('No changes since the previous scan')
    await expect(page.locator('.advisor-score-card [role="status"]')).toHaveCount(1)
    await expect(page.locator('.advisor-score-card [role="status"]')).toContainText(
      'No changes since the previous scan'
    )
  })
})
