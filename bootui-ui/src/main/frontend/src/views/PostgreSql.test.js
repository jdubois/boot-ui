import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import PostgreSql from './PostgreSql.vue'

function finding(id, title, severity, dataSource = 'default', overrides = {}) {
  return {
    id,
    dataSource,
    sectionId: 'vital-signs',
    title,
    category: 'Vital signs',
    severity,
    description: `${title} description.`,
    evidence: `${id} evidence`,
    samples: [`${id} sample`],
    recommendation: `${title} recommendation.`,
    caveat: `${title} caveat.`,
    learnMoreUrl: 'https://example.com/postgresql-check',
    ...overrides
  }
}

function section(id, title, status, overrides = {}) {
  return {
    id,
    title,
    status,
    reason: null,
    hint: null,
    rowCount: 0,
    findingCount: 0,
    truncated: false,
    ...overrides
  }
}

function database(overrides = {}) {
  return {
    name: 'default',
    databaseName: 'appdb',
    serverVersion: 'PostgreSQL 16.1',
    serverMajorVersion: 16,
    role: 'app',
    monitoringRole: true,
    status: 'SCANNED',
    message: null,
    vitalSigns: {
      databaseName: 'appdb',
      cacheHitRatio: 0.98,
      rollbackRatio: 0.01,
      connections: 12,
      maxConnections: 100,
      connectionUsageRatio: 0.12,
      idleInTransactionSessions: 0,
      longestTransactionSeconds: 1.2,
      blockedSessions: 0,
      wraparoundUsageRatio: 0.05,
      databaseSizeBytes: 1_048_576,
      deadlocks: 0
    },
    sections: [],
    statements: [],
    indexes: [],
    tables: [],
    vacuum: [],
    replication: null,
    settings: [],
    changes: [],
    truncated: false,
    ...overrides
  }
}

function report(overrides = {}) {
  const findings = overrides.findings ?? []
  return {
    localOnly: true,
    disclaimer: 'PostgreSQL insight disclaimer.',
    status: 'READ',
    message: 'PostgreSQL read completed.',
    readAt: 1_700_000_000_000,
    databasesRead: 1,
    findingsFound: findings.length,
    truncated: false,
    databases: [database()],
    severityCounts: [
      {severity: 'CRITICAL', count: severityCount(findings, 'CRITICAL')},
      {severity: 'HIGH', count: severityCount(findings, 'HIGH')},
      {severity: 'MEDIUM', count: severityCount(findings, 'MEDIUM')},
      {severity: 'LOW', count: severityCount(findings, 'LOW')},
      {severity: 'INFO', count: severityCount(findings, 'INFO')}
    ],
    diagnostics: [],
    evidence: {usable: true, coverageComplete: true, limitations: []},
    ...overrides,
    findings
  }
}

function severityCount(findings, severity) {
  return findings.filter((item) => item.severity === severity).length
}

async function mountWith(body, {status = 200} = {}) {
  const fetchMock = vi.fn(() => Promise.resolve(new Response(JSON.stringify(body), {status})))
  vi.stubGlobal('fetch', fetchMock)
  const wrapper = mount(PostgreSql)
  await flushPromises()
  return {wrapper, fetchMock}
}

describe('PostgreSql', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows the not-read prompt before the first read', async () => {
    const {wrapper} = await mountWith(report({status: 'NOT_READ', message: null, readAt: null, databasesRead: 0}))

    expect(wrapper.text()).toContain('No PostgreSQL data yet')
    expect(wrapper.text()).toContain('Run the PostgreSQL read')
    expect(wrapper.text()).not.toContain('Findings by severity')
  })

  it('renders a disabled report honestly as unavailable', async () => {
    const {wrapper} = await mountWith(
      report({
        status: 'DISABLED',
        message: 'No PostgreSQL datasource was detected.',
        readAt: null,
        databases: [],
        databasesRead: 0
      })
    )

    expect(wrapper.text()).toContain('No PostgreSQL datasource was detected.')
    expect(wrapper.text()).not.toContain('Findings by severity')
  })

  it('renders findings sorted by severity with their evidence', async () => {
    const {wrapper} = await mountWith(
      report({
        findings: [
          finding('PG-CACHE-001', 'Low informational note', 'INFO'),
          finding('PG-VACUUM-001', 'High severity bloat', 'HIGH'),
          finding('PG-TX-001', 'Medium severity idle transaction', 'MEDIUM')
        ]
      })
    )

    expect(wrapper.text()).toContain('Findings by severity')
    expect(wrapper.text()).toContain('3 findings, sorted by severity')
    expect(wrapper.text()).toContain('Evidence:')
    expect(wrapper.findAll('.list-group-item h4').map((title) => title.text())).toEqual([
      'High severity bloat',
      'Medium severity idle transaction',
      'Low informational note'
    ])
  })

  it('shows a skipped section with its reason and hint, never as passing', async () => {
    const {wrapper} = await mountWith(
      report({
        databases: [
          database({
            sections: [
              section('vital-signs', 'Vital signs', 'AVAILABLE'),
              section('statements', 'Statements', 'SKIPPED', {
                reason: 'pg_stat_statements is not installed',
                hint: 'CREATE EXTENSION pg_stat_statements;'
              })
            ]
          })
        ]
      })
    )

    expect(wrapper.text()).toContain('Skipped — pg_stat_statements is not installed')
    expect(wrapper.text()).toContain('CREATE EXTENSION pg_stat_statements;')
    expect(wrapper.text()).toContain('Checked and clean')
  })

  it('shows an available section that carries a reason as partially read, not clean', async () => {
    const {wrapper} = await mountWith(
      report({
        databases: [
          database({
            sections: [
              section('vital-signs', 'Vital signs', 'AVAILABLE', {
                reason: 'Session states are hidden from this role',
                hint: 'Grant pg_monitor to the application role.'
              })
            ]
          })
        ]
      })
    )

    expect(wrapper.text()).toContain('PARTIAL')
    expect(wrapper.text()).toContain('Partially read — Session states are hidden from this role')
    expect(wrapper.text()).toContain('Grant pg_monitor to the application role.')
    expect(wrapper.text()).not.toContain('Checked and clean')
  })

  it('runs the read via POST when the button is clicked', async () => {
    const {wrapper, fetchMock} = await mountWith(report({status: 'NOT_READ', readAt: null, databasesRead: 0}))
    fetchMock.mockClear()

    await wrapper.find('button.btn-primary').trigger('click')
    await flushPromises()

    const readCall = fetchMock.mock.calls.find(([url]) => String(url).includes('api/postgresql/read'))
    expect(readCall).toBeTruthy()
    expect(readCall[1].method).toBe('POST')
  })
})
