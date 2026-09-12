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
    expect(text).toContain('Checkpoint write time')
    expect(text).toContain('6.5 s')
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
    expect(wrapper.text()).toContain('orders_customer_idx')
    expect(wrapper.text()).toContain('public.orders')
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
    expect(wrapper.text()).toContain('A row bound was reached')
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
