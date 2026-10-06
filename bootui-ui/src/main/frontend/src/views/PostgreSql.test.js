import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import PostgreSql from './PostgreSql.vue'

function section(id, title, status, overrides = {}) {
  return {
    id,
    title,
    status,
    reason: null,
    hint: null,
    rowCount: 0,
    truncated: false,
    ...overrides
  }
}

function session(overrides = {}) {
  return {
    pid: 4242,
    user: 'app',
    applicationName: 'sample-app',
    clientAddress: '127.0.0.1',
    state: 'active',
    waitEventType: null,
    waitEvent: null,
    blockedBy: null,
    stateSeconds: 0.4,
    transactionSeconds: 0.9,
    querySeconds: 0.4,
    query: 'select * from orders where id = $1',
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
    status: 'READ',
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
    sessions: [],
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
  return {
    localOnly: true,
    disclaimer: 'PostgreSQL runtime disclaimer.',
    status: 'READ',
    message: 'PostgreSQL read completed.',
    readAt: 1_700_000_000_000,
    databasesRead: 1,
    truncated: false,
    databases: [database()],
    diagnostics: [],
    limitations: [],
    ...overrides
  }
}

function limitedReport(databaseOverrides = {}, reportOverrides = {}) {
  return report({
    status: 'PARTIAL',
    truncated: true,
    databases: [
      database({
        status: 'PARTIAL',
        truncated: true,
        sections: [section('statements', 'Statement ranking', 'AVAILABLE', {rowCount: 25, truncated: true})],
        ...databaseOverrides
      })
    ],
    ...reportOverrides
  })
}

async function mountWith(body, {status = 200, attachTo} = {}) {
  const fetchMock = vi.fn(() => Promise.resolve(new Response(JSON.stringify(body), {status})))
  vi.stubGlobal('fetch', fetchMock)
  const wrapper = mount(PostgreSql, attachTo ? {attachTo} : {})
  await flushPromises()
  return {wrapper, fetchMock}
}

function tabs(wrapper) {
  return wrapper.findAll('[role="tab"]')
}

// Sections are tabbed, so a section other than the selected one is genuinely not rendered:
// a test that wants its rows has to open it exactly like a reader does.
async function openTab(wrapper, label) {
  const tab = tabs(wrapper).find((candidate) => candidate.text().startsWith(label))
  expect(tab, `no section tab labelled ${label}`).toBeTruthy()
  await tab.trigger('click')
}

describe('PostgreSql', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows the not-read prompt before the first read', async () => {
    const {wrapper} = await mountWith(report({status: 'NOT_READ', message: null, readAt: null, databasesRead: 0}))

    expect(wrapper.text()).toContain('No PostgreSQL data yet')
    expect(wrapper.text()).toContain('Run the PostgreSQL read')
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
  })

  it('renders the live session snapshot as a table', async () => {
    const {wrapper} = await mountWith(
      report({
        databases: [
          database({
            sections: [section('sessions', 'Sessions', 'AVAILABLE', {rowCount: 2})],
            sessions: [
              session(),
              session({
                pid: 91,
                state: 'idle in transaction',
                waitEventType: 'Lock',
                waitEvent: 'transactionid',
                blockedBy: '4242',
                query: 'update orders set total = $1 where id = $2'
              })
            ]
          })
        ]
      })
    )

    const rows = wrapper.findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(wrapper.text()).toContain('select * from orders where id = $1')
    expect(wrapper.text()).toContain('Lock/transactionid')
    expect(wrapper.text()).toContain('4242')
    // A blocked session is the one thing in this table a developer must not scroll past.
    expect(rows[1].classes()).toContain('table-warning')
  })

  it('renders every session and vital-signs value the read reported, not only the headline ones', async () => {
    // Each of these was carried by the report and silently dropped by the view. A number the server
    // reported and BootUI parsed must reach the screen, or the panel is quietly less honest than its data.
    const {wrapper} = await mountWith(
      report({
        databases: [
          database({
            sections: [
              section('vital-signs', 'Vital signs', 'AVAILABLE'),
              section('sessions', 'Sessions', 'AVAILABLE', {rowCount: 1}),
              section('replication', 'Replication', 'AVAILABLE')
            ],
            vitalSigns: {
              ...database().vitalSigns,
              temporaryFiles: 7,
              temporaryBytes: 3_145_728
            },
            sessions: [session({querySeconds: 12.5, clientAddress: '10.1.2.3'})],
            replication: {
              inRecovery: false,
              replicasAvailable: true,
              replicas: [],
              checkpointsTimed: 4,
              checkpointsRequested: 1,
              checkpointWriteSeconds: 6.5,
              replicationSlots: 0,
              inactiveReplicationSlots: 0,
              walLevel: 'replica'
            }
          })
        ]
      })
    )

    const text = wrapper.text()
    expect(text).toContain('10.1.2.3')
    expect(text).toContain('12.5 s')
    expect(text).toContain('Temporary files')
    expect(text).toContain('Temporary bytes')

    await openTab(wrapper, 'Replication')

    expect(wrapper.text()).toContain('Checkpoint write time')
    expect(wrapper.text()).toContain('6.5 s')
  })

  it('renders the statement, index, table and settings tables from the read', async () => {
    const {wrapper} = await mountWith(
      report({
        databases: [
          database({
            sections: [
              section('statements', 'Statement ranking', 'AVAILABLE', {rowCount: 1}),
              section('indexes', 'Index usage', 'AVAILABLE', {rowCount: 1}),
              section('tables', 'Table access', 'AVAILABLE', {rowCount: 1}),
              section('settings', 'Settings', 'AVAILABLE', {rowCount: 1})
            ],
            statements: [
              {
                queryId: '42',
                query: 'select * from orders',
                calls: 120,
                totalTimeMs: 2400,
                meanTimeMs: 20,
                maxTimeMs: 95,
                rows: 1200,
                cacheHitRatio: 0.99
              }
            ],
            indexes: [
              {
                schema: 'public',
                table: 'orders',
                index: 'orders_customer_idx',
                scans: 0,
                tuplesRead: 0,
                sizeBytes: 2_097_152,
                unique: false,
                primaryKey: false,
                constraintBacked: false
              }
            ],
            tables: [
              {
                schema: 'public',
                table: 'orders',
                totalSizeBytes: 10_485_760,
                tableSizeBytes: 8_388_608,
                indexSizeBytes: 2_097_152,
                liveTuples: 90_000,
                deadTuples: 1_000,
                sequentialScans: 40,
                sequentialTuplesRead: 100,
                indexScans: 900,
                sequentialScanRatio: 0.04
              }
            ],
            settings: [{name: 'work_mem', value: '4', unit: 'MB', source: 'default', note: 'Per-sort memory'}]
          })
        ]
      })
    )

    expect(wrapper.text()).toContain('select * from orders')

    await openTab(wrapper, 'Index usage')
    expect(wrapper.text()).toContain('orders_customer_idx')

    await openTab(wrapper, 'Table access')
    expect(wrapper.text()).toContain('public.orders')

    await openTab(wrapper, 'Settings')
    expect(wrapper.text()).toContain('work_mem')
    expect(wrapper.text()).toContain('Per-sort memory')
  })

  it('shows a skipped section with its reason and hint instead of an empty table', async () => {
    const {wrapper} = await mountWith(
      report({
        databases: [
          database({
            sections: [
              section('statements', 'Statement ranking', 'SKIPPED', {
                reason: 'pg_stat_statements is not installed',
                hint: 'CREATE EXTENSION pg_stat_statements;'
              })
            ]
          })
        ]
      })
    )

    expect(wrapper.text()).toContain('SKIPPED')
    expect(wrapper.text()).toContain('pg_stat_statements is not installed')
    expect(wrapper.text()).toContain('CREATE EXTENSION pg_stat_statements;')
    expect(wrapper.findAll('tbody tr')).toHaveLength(0)
  })

  it('marks a section that carries a reason or a row bound as partially read', async () => {
    const {wrapper} = await mountWith(
      report({
        databases: [
          database({
            sections: [
              section('sessions', 'Sessions', 'AVAILABLE', {
                reason: 'pg_stat_activity hides the state of other backends'
              }),
              section('tables', 'Table access', 'AVAILABLE', {truncated: true})
            ]
          })
        ]
      })
    )

    expect(wrapper.text()).toContain('PARTIAL')
    expect(wrapper.text()).toContain('pg_stat_activity hides the state of other backends')
    // Both partly read sections say so on their own tab's count chip.
    expect(wrapper.findAll('[role="tab"] .postgres-tab-count--partial')).toHaveLength(2)

    await openTab(wrapper, 'Table access')
    expect(wrapper.text()).toContain("Additional rows are not shown because this section reached BootUI's row limit.")
  })

  it.each([1, 25, 100, 250])('explains a top-%i ranking once in the section without warning chrome', async (count) => {
    const note = `Showing the top ${count} ${count === 1 ? 'statement' : 'statements'} by total execution time. Additional statements are not shown.`
    const body = limitedReport(
      {sections: [section('statements', 'Statement ranking', 'AVAILABLE', {rowCount: count, truncated: true})]},
      {
        message: 'Some results are incomplete; see the section details and diagnostics.',
        limitations: [`default / Statement ranking: ${note}`]
      }
    )
    const {wrapper, fetchMock} = await mountWith(body)

    expect(wrapper.find('[role="status"]').exists()).toBe(false)
    expect(wrapper.find('.alert-warning').exists()).toBe(false)
    expect(wrapper.find('.text-bg-warning').exists()).toBe(false)
    expect(wrapper.find('.postgres-tab-count--partial').exists()).toBe(false)
    expect(wrapper.text()).not.toContain(body.message)
    expect(wrapper.text()).not.toContain('What this read does not cover')
    expect(wrapper.text()).not.toContain('Truncated')
    expect(wrapper.text()).not.toContain('Partly read')
    expect(wrapper.text().split(note)).toHaveLength(2)
    expect(wrapper.find('[role="tabpanel"]').text()).toContain(note)
    expect(wrapper.find('.card-header .badge').text()).toBe('Read')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(body.status).toBe('PARTIAL')
    expect(body.truncated).toBe(true)
  })

  it('keeps ranking notes local when multiple datasources have only statement caps', async () => {
    const body = limitedReport()
    body.databases.push({...body.databases[0], name: 'analytics'})
    const {wrapper} = await mountWith(body)

    expect(wrapper.find('.alert-warning').exists()).toBe(false)
    expect(wrapper.find('.text-bg-warning').exists()).toBe(false)
    expect(wrapper.findAll('[role="tabpanel"]')).toHaveLength(2)
    for (const panel of wrapper.findAll('[role="tabpanel"]')) {
      expect(panel.text()).toContain('Showing the top 25 statements')
    }
  })

  it('shows the statement note only when its tab is open and preserves normal read statuses', async () => {
    const body = limitedReport({
      sections: [
        section('sessions', 'Sessions', 'AVAILABLE'),
        section('statements', 'Statement ranking', 'AVAILABLE', {rowCount: 100, truncated: true})
      ]
    })
    body.databases.push(database({name: 'analytics', sections: [section('sessions', 'Sessions', 'AVAILABLE')]}))
    const {wrapper} = await mountWith(body)

    expect(wrapper.text()).not.toContain('Showing the top')
    expect(wrapper.find('.alert-warning').exists()).toBe(false)
    await openTab(wrapper, 'Statement ranking')
    expect(wrapper.text()).toContain('Showing the top 100 statements')
    await openTab(wrapper, 'Sessions')
    expect(wrapper.text()).not.toContain('Showing the top')
  })

  it('names every capped section and datasource with its own retained count', async () => {
    const body = limitedReport()
    body.databases.push(
      database({
        name: 'analytics',
        status: 'PARTIAL',
        truncated: true,
        sections: [section('tables', 'Largest relations', 'AVAILABLE', {rowCount: 1, truncated: true})]
      })
    )
    const {wrapper} = await mountWith(body)

    const warning = wrapper.find('[role="status"]')
    expect(warning.text()).toContain('Limited results.')
    expect(warning.text()).toContain('default / Statement ranking: Showing the top 25 statements')
    expect(warning.text()).toContain('analytics / Largest relations: Showing 1 row.')
  })

  it.each([null, 'Checkpoint statistics could not be read: permission denied.'])(
    'distinguishes a replica cap from a replica cap with another read failure: %s',
    async (reason) => {
      const {wrapper} = await mountWith(
        limitedReport({
          sections: [
            section('replication', 'Replication, checkpoints and WAL', 'AVAILABLE', {
              rowCount: 1,
              truncated: true,
              reason
            })
          ],
          replication: {inRecovery: false, replicasAvailable: true, replicas: [{applicationName: 'replica-1'}]}
        })
      )

      const warning = wrapper.find('[role="status"]')
      expect(warning.text()).toContain(reason ? 'Incomplete read.' : 'Limited results.')
      const panel = wrapper.find('[role="tabpanel"]')
      expect(panel.text()).toContain('Showing 1 row.')
      if (reason) expect(panel.text()).toContain(reason)
    }
  )

  it.each([
    {name: 'section failure', part: section('sessions', 'Sessions', 'FAILED', {reason: 'Permission denied.'})},
    {name: 'skipped section', part: section('sessions', 'Sessions', 'SKIPPED', {reason: 'Read budget ran out.'})},
    {name: 'partial section', part: section('sessions', 'Sessions', 'AVAILABLE', {reason: 'Statistics restricted.'})}
  ])('keeps incomplete-read warnings for a row cap plus a $name', async ({part}) => {
    const body = limitedReport()
    body.databases[0].sections.push(part)
    const {wrapper} = await mountWith(body)

    const warning = wrapper.find('[role="status"]')
    expect(warning.text()).toContain('Incomplete read.')
    expect(warning.text()).toContain('1 datasource was read only partially')
    expect(warning.text()).not.toContain('Limited results.')
    await openTab(wrapper, 'Sessions')
    expect(wrapper.find('[role="tabpanel"]').text()).toContain(part.reason)
  })

  it.each([
    {diagnostics: [{source: 'secondary', level: 'ERROR', message: 'Discovery failed.'}]},
    {diagnostics: [{source: 'secondary', level: 'WARNING', message: 'Read budget ran out.'}]}
  ])('does not hide datasource diagnostics behind a row-limit notice', async (overrides) => {
    const {wrapper} = await mountWith(limitedReport({}, overrides))

    expect(wrapper.find('[role="status"]').text()).toContain('Incomplete read.')
  })

  it('keeps a failed datasource visible when another datasource has only a statement cap', async () => {
    const body = limitedReport()
    body.databases.push(database({name: 'offline', status: 'ERROR', message: 'Connection refused.'}))
    const {wrapper} = await mountWith(body)

    expect(wrapper.find('[role="status"]').text()).toContain('1 datasource could not be read')
    expect(wrapper.text()).toContain('Connection refused.')
    expect(wrapper.text()).toContain('Unreadable')
  })

  it('keeps connection-restoration failures visible alongside a row cap', async () => {
    const {wrapper} = await mountWith(limitedReport({message: 'The connection could not be restored.'}))

    expect(wrapper.find('[role="status"]').text()).toContain('Incomplete read.')
    expect(wrapper.text()).toContain('The connection could not be restored.')
  })

  it('shows both the section reason and its row-limit explanation', async () => {
    const reason = 'Statement text is restricted.'
    const {wrapper} = await mountWith(
      limitedReport({
        sections: [section('statements', 'Statement ranking', 'AVAILABLE', {reason, rowCount: 25, truncated: true})]
      })
    )

    const panel = wrapper.find('[role="tabpanel"]')
    expect(panel.text()).toContain(reason)
    expect(panel.text()).toContain('Showing the top 25 statements')
    expect(panel.text()).toContain('PARTIAL')
    expect(panel.text()).toContain('Truncated')
    expect(wrapper.find('[role="status"]').text()).toContain('Incomplete read.')
  })

  it.each([
    {inRecovery: true, replicasAvailable: false},
    {inRecovery: false, replicasAvailable: false},
    {inRecovery: false}
  ])('does not infer replica absence from an unread or older report: %j', async (replication) => {
    const {wrapper} = await mountWith(
      report({
        databases: [
          database({
            sections: [section('replication', 'Replication', 'AVAILABLE', {reason: 'Replica list unavailable.'})],
            replication: {...replication, replicas: []}
          })
        ]
      })
    )

    expect(wrapper.text()).toContain('The replica list was not read')
    expect(wrapper.text()).not.toContain('No streaming replica is connected')
  })

  it('reports a successfully observed empty replica list even if another replication sub-read failed', async () => {
    const {wrapper} = await mountWith(
      report({
        databases: [
          database({
            sections: [section('replication', 'Replication', 'AVAILABLE', {reason: 'Slots could not be read.'})],
            replication: {inRecovery: false, replicasAvailable: true, replicas: []}
          })
        ]
      })
    )

    expect(wrapper.text()).toContain('No streaming replica is connected')
    expect(wrapper.text()).not.toContain('The replica list was not read')
    expect(wrapper.text()).toContain('Slots could not be read')
  })

  it('keeps budget-limited rows visible without claiming a row cap', async () => {
    const reason = 'The read budget ran out while reading Session activity.'
    const {wrapper} = await mountWith(
      report({
        status: 'PARTIAL',
        limitations: [`default/sessions: ${reason}`],
        databases: [
          database({
            status: 'PARTIAL',
            sections: [section('sessions', 'Sessions', 'AVAILABLE', {rowCount: 1, reason})],
            sessions: [session()]
          })
        ]
      })
    )

    expect(wrapper.text()).toContain('PARTIAL')
    expect(wrapper.text()).toContain(reason)
    expect(wrapper.text()).toContain('sample-app')
    expect(wrapper.text()).not.toContain('A row bound')
    expect(wrapper.text()).not.toContain('Truncated')
  })

  it('shows one section at a time, keeping the vital signs and the other tabs in view', async () => {
    const {wrapper} = await mountWith(
      report({
        databases: [
          database({
            sections: [
              section('vital-signs', 'Vital signs', 'AVAILABLE', {rowCount: 1}),
              section('sessions', 'Sessions', 'AVAILABLE', {rowCount: 1}),
              section('settings', 'Settings', 'AVAILABLE', {rowCount: 1})
            ],
            sessions: [session()],
            settings: [{name: 'work_mem', value: '4', unit: 'MB', source: 'default', note: 'Per-sort memory'}]
          })
        ]
      })
    )

    // Vital signs stay pinned above the tabs, so they are never one of them.
    expect(tabs(wrapper).map((tab) => tab.text().replace(/\s+/g, ' ').trim())).toEqual(['Sessions1', 'Settings1'])
    expect(wrapper.text()).toContain('Cache hit ratio')
    expect(wrapper.findAll('[role="tabpanel"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('sample-app')
    expect(wrapper.text()).not.toContain('work_mem')

    await openTab(wrapper, 'Settings')

    expect(wrapper.findAll('[role="tabpanel"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('work_mem')
    expect(wrapper.text()).not.toContain('sample-app')
    expect(wrapper.text()).toContain('Cache hit ratio')
  })

  it('marks exactly one section tab as selected and keeps the others out of the tab order', async () => {
    const {wrapper} = await mountWith(
      report({
        databases: [
          database({
            sections: [
              section('sessions', 'Sessions', 'AVAILABLE', {rowCount: 0}),
              section('settings', 'Settings', 'AVAILABLE', {rowCount: 0})
            ]
          })
        ]
      })
    )

    const selected = tabs(wrapper).filter((tab) => tab.attributes('aria-selected') === 'true')
    expect(selected).toHaveLength(1)
    expect(selected[0].text()).toContain('Sessions')
    expect(tabs(wrapper).map((tab) => tab.attributes('tabindex'))).toEqual(['0', '-1'])
    const panel = wrapper.find('[role="tabpanel"]')
    expect(panel.attributes('aria-labelledby')).toBe(selected[0].attributes('id'))
    expect(panel.attributes('id')).toBe(selected[0].attributes('aria-controls'))
  })

  it('moves between section tabs with the arrow keys', async () => {
    const {wrapper} = await mountWith(
      report({
        databases: [
          database({
            sections: [
              section('sessions', 'Sessions', 'AVAILABLE', {rowCount: 0}),
              section('settings', 'Settings', 'AVAILABLE', {rowCount: 0})
            ],
            settings: [{name: 'work_mem', value: '4', unit: 'MB', source: 'default', note: 'Per-sort memory'}]
          })
        ]
      }),
      {attachTo: document.body}
    )

    await tabs(wrapper)[0].trigger('keydown', {key: 'ArrowRight'})

    expect(tabs(wrapper)[1].attributes('aria-selected')).toBe('true')
    expect(document.activeElement).toBe(tabs(wrapper)[1].element)
    expect(wrapper.text()).toContain('work_mem')

    await tabs(wrapper)[1].trigger('keydown', {key: 'ArrowRight'})

    // The list wraps rather than trapping the reader at its end.
    expect(tabs(wrapper)[0].attributes('aria-selected')).toBe('true')

    wrapper.unmount()
  })

  it('falls back to the first section when a later read no longer reports the selected one', async () => {
    const first = report({
      databases: [
        database({
          sections: [
            section('sessions', 'Sessions', 'AVAILABLE', {rowCount: 0}),
            section('statements', 'Statement ranking', 'AVAILABLE', {rowCount: 0})
          ]
        })
      ]
    })
    // A second read where pg_stat_statements is gone must not leave the card with no panel at all.
    const second = report({
      databases: [database({sections: [section('sessions', 'Sessions', 'AVAILABLE', {rowCount: 0})]})]
    })
    const bodies = [first, second]
    const fetchMock = vi.fn(() =>
      Promise.resolve(new Response(JSON.stringify(bodies.shift() ?? second), {status: 200}))
    )
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(PostgreSql)
    await flushPromises()

    await openTab(wrapper, 'Statement ranking')
    expect(wrapper.find('[role="tabpanel"]').attributes('id')).toContain('statements')

    await wrapper.find('button.btn-primary').trigger('click')
    await flushPromises()

    expect(tabs(wrapper)).toHaveLength(1)
    expect(wrapper.findAll('[role="tabpanel"]')).toHaveLength(1)
    expect(wrapper.find('[role="tabpanel"]').attributes('id')).toContain('sessions')
  })

  it('presents a failed read as a failure rather than an empty database', async () => {
    const {wrapper} = await mountWith(
      report({
        status: 'ERROR',
        message: 'The read budget ran out before any datasource was read, so nothing was inspected.',
        databases: [],
        databasesRead: 0,
        limitations: ['primary: the read budget ran out before this datasource was read.']
      })
    )

    expect(wrapper.text()).toContain('Read failed.')
    expect(wrapper.text()).toContain('The read budget ran out')
    expect(wrapper.text()).toContain('What this read does not cover')
    expect(wrapper.find('.alert-danger').exists()).toBe(true)
  })

  it('shows the later of a manual and an automatic vacuum, not always the automatic one', async () => {
    // A manual VACUUM run after the last autovacuum is the more recent truth about the table.
    const now = Date.now()
    const {wrapper} = await mountWith(
      report({
        readAt: now,
        databases: [
          database({
            sections: [section('vacuum', 'Autovacuum health', 'AVAILABLE', {rowCount: 1})],
            vacuum: [
              {
                schema: 'public',
                table: 'orders',
                liveTuples: 1000,
                deadTuples: 10,
                deadTupleRatio: 0.01,
                vacuumThreshold: 250,
                vacuumDue: false,
                autovacuumEnabled: true,
                lastVacuum: now - 10_000,
                lastAutoVacuum: now - 7_200_000,
                lastAnalyze: now - 10_000,
                lastAutoAnalyze: now - 7_200_000
              }
            ]
          })
        ]
      })
    )

    const row = wrapper.findAll('tr').find((candidate) => candidate.text().includes('orders'))
    // The manual vacuum ran 10 seconds ago; the autovacuum two hours ago. Preferring the automatic one
    // would report the table as two hours stale.
    expect(row.text()).toContain('10s ago')
    expect(row.text()).not.toContain('2h ago')
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
