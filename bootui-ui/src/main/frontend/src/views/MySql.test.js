import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import MySql from './MySql.vue'
import {mysqlDataSource, mysqlReport} from '../../../../../../bootui-spring-sample-app/e2e/scenarios/mysql-fixture.js'

function section(id, overrides = {}) {
  return {
    id,
    title: id[0].toUpperCase() + id.slice(1),
    status: 'AVAILABLE',
    scope: 'SERVER',
    reason: null,
    hint: null,
    rowCount: 0,
    truncated: false,
    ...overrides
  }
}
function source(overrides = {}) {
  return {
    name: 'primary',
    schemaName: 'shop',
    serverVersion: 'MySQL 8.4.6',
    status: 'READ',
    message: null,
    sections: [section('sessions'), section('statements'), section('replication')],
    truncated: false,
    ...overrides
  }
}
function report(overrides = {}) {
  return {
    localOnly: true,
    disclaimer: 'Statistics cover every client of this server.',
    status: 'READ',
    message: 'MySQL read completed.',
    readStartedAt: 1700000000000,
    readAt: 1700000000500,
    dataSourcesRead: 1,
    dataSources: [source()],
    diagnostics: [],
    limitations: [],
    truncated: false,
    ...overrides
  }
}
function response(body, status = 200) {
  return new Response(JSON.stringify(body), {status, headers: {'content-type': 'application/json'}})
}
const wrappers = []
async function mountReport(body = report(), props = {}) {
  document.cookie = 'XSRF-TOKEN=test'
  const fetchMock = vi.fn().mockResolvedValue(response(body))
  vi.stubGlobal('fetch', fetchMock)
  const wrapper = mount(MySql, {attachTo: document.body, props})
  wrappers.push(wrapper)
  await flushPromises()
  return {wrapper, fetchMock}
}
function readButton(wrapper) {
  return wrapper.findAll('button').find((button) => button.text().includes('Run MySQL read'))
}
function tab(wrapper, title) {
  return wrapper.findAll('[role="tab"]').find((candidate) => candidate.text().startsWith(title))
}

describe('MySQL report lifecycle', () => {
  afterEach(() => {
    wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
    document.cookie = 'XSRF-TOKEN=; Max-Age=0'
    vi.unstubAllGlobals()
  })

  it('mounts with one cached GET only and never schedules a database read', async () => {
    const {wrapper, fetchMock} = await mountReport(
      report({status: 'NOT_READ', readAt: null, dataSources: [], dataSourcesRead: 0})
    )
    expect(fetchMock).toHaveBeenCalledExactlyOnceWith('api/mysql', {})
    expect(wrapper.text()).toContain('No MySQL data yet')
    expect(wrapper.text()).toContain('MySQL 8.4')
    expect(wrapper.text()).toContain('MariaDB is not supported')
  })

  it('shows manifest absence without calling an unwired endpoint', async () => {
    const {wrapper, fetchMock} = await mountReport(null, {
      panel: {
        id: 'mysql',
        available: false,
        unavailableReason: 'A MySQL JDBC datasource is required; reactive clients alone are not supported.'
      }
    })
    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('reactive clients alone are not supported')
    expect(readButton(wrapper).attributes('disabled')).toBeDefined()
  })

  it('honors panel disablement even when availability is true', async () => {
    const {wrapper, fetchMock} = await mountReport(null, {panel: {id: 'mysql', enabled: false, available: true}})
    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('bootui.panels.mysql.enabled=false')
  })

  it('blocks external reads under read-only policy while showing cached evidence', async () => {
    const {wrapper, fetchMock} = await mountReport(report(), {
      panel: {readOnly: true, readOnlyReason: 'External reads are disabled by policy.'}
    })
    expect(readButton(wrapper).attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('External reads are disabled by policy.')
    expect(wrapper.text()).toContain('primary')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('renders DISABLED distinctly and disables the read control', async () => {
    const {wrapper} = await mountReport(
      report({status: 'DISABLED', message: 'No supported datasource was detected.', dataSources: []})
    )
    expect(wrapper.text()).toContain('No supported datasource was detected.')
    expect(readButton(wrapper).attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('[role="tabpanel"]')).toHaveLength(0)
  })

  it('runs exactly one explicit POST and disables the control during collection', async () => {
    const {wrapper, fetchMock} = await mountReport()
    let finish
    fetchMock.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    await readButton(wrapper).trigger('click')
    const busy = wrapper.findAll('button').find((button) => button.text().includes('Reading'))
    expect(busy.attributes('disabled')).toBeDefined()
    expect(fetchMock.mock.calls[1][0]).toBe('api/mysql/read')
    expect(fetchMock.mock.calls[1][1].method).toBe('POST')
    finish(response(report({dataSources: [source({name: 'fresh'})]})))
    await flushPromises()
    expect(wrapper.text()).toContain('fresh')
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it.each([
    [500, {error: 'Read unavailable'}, 'Unable to run the MySQL read'],
    [403, {error: 'Read-only policy'}, 'Unable to run the MySQL read'],
    [
      409,
      {error: 'BootUI action already in progress', message: 'A MySQL read is already running.'},
      'A MySQL read is already running.'
    ]
  ])('preserves the last successful report after HTTP %s without automatic retries', async (status, body, message) => {
    const {wrapper, fetchMock} = await mountReport()
    fetchMock.mockResolvedValueOnce(response(body, status))
    await readButton(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain(message)
    expect(wrapper.text()).toContain('primary')
    expect(wrapper.text()).toContain('The last completed report is still shown')
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('[role="alert"], [role="status"]')).toHaveLength(1)
  })

  it('reports a failed cached GET without inventing an empty report', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))
    const wrapper = mount(MySql)
    wrappers.push(wrapper)
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('No MySQL data yet')
  })

  it('renders ERROR with no usable datasource as failed, not a clean empty read', async () => {
    const {wrapper} = await mountReport(
      report({
        status: 'ERROR',
        message: 'Safe read-only execution could not be established.',
        dataSourcesRead: 0,
        dataSources: []
      })
    )
    expect(wrapper.get('[role="status"]').text()).toContain('Read failed.')
    expect(wrapper.get('[role="status"]').text()).toContain('Safe read-only execution')
  })

  it('presents normal row limits without warning banners or partial-read badges', async () => {
    const {wrapper} = await mountReport(
      report({
        status: 'PARTIAL',
        truncated: true,
        dataSources: [
          source({
            status: 'PARTIAL',
            truncated: true,
            sections: [section('statements', {rowCount: 100, truncated: true})]
          })
        ]
      })
    )
    expect(wrapper.get('[role="status"]').text()).toContain('Limited results')
    expect(wrapper.get('[role="status"]').classes()).not.toContain('alert')
    expect(wrapper.findAll('.alert-warning, .text-bg-warning')).toHaveLength(0)
    expect(wrapper.get('[role="tabpanel"]').text()).toContain('Showing the top 100 statements')
    expect(wrapper.get('[role="tabpanel"] .badge').classes()).toContain('text-bg-secondary')
    expect(tab(wrapper, 'Statements').text()).toContain('Limited')
    expect(wrapper.get('article .card-header .badge').classes()).toContain('text-bg-secondary')
    expect(wrapper.text()).not.toContain('Partly read')
    expect(wrapper.text()).not.toContain('Incomplete read.')
  })

  it('retains every coverage/failure reason alongside a row cap', async () => {
    const {wrapper} = await mountReport(
      report({
        status: 'PARTIAL',
        truncated: true,
        dataSources: [
          source({
            status: 'PARTIAL',
            truncated: true,
            sections: [
              section('statements', {rowCount: 100, truncated: true}),
              section('sessions', {reason: 'Transaction instrumentation is disabled.'}),
              section('replication', {
                status: 'FAILED',
                reason: 'Replication source access denied.',
                hint: 'Grant table-specific SELECT.'
              })
            ]
          })
        ],
        limitations: ['The server digest capacity is exhausted.'],
        diagnostics: [{level: 'WARNING', source: 'primary', message: 'The read budget expired.'}]
      })
    )
    const warning = wrapper.get('[role="status"]').text()
    expect(wrapper.get('[role="status"]').classes()).toContain('alert-warning')
    expect(warning).toContain('Incomplete read.')
    for (const text of [
      'Showing the top 100',
      'Transaction instrumentation is disabled',
      'Replication source access denied',
      'server digest capacity is exhausted'
    ])
      expect(warning).toContain(text)
    expect(wrapper.text()).toContain('The read budget expired.')
    await tab(wrapper, 'Replication').trigger('click')
    expect(wrapper.get('[role="tabpanel"]').text()).toContain('Grant table-specific SELECT.')
    expect(wrapper.get('[role="tabpanel"]').text()).toContain('Missing observations do not establish absence.')
  })

  it('does not mislabel an omitted datasource as a row-limit-only report', async () => {
    const {wrapper} = await mountReport(
      report({
        status: 'PARTIAL',
        truncated: true,
        dataSources: [
          source({
            status: 'PARTIAL',
            truncated: true,
            sections: [section('statements', {rowCount: 100, truncated: true})]
          })
        ],
        limitations: ['secondary: not reached because collection stopped.']
      })
    )
    expect(wrapper.get('[role="status"]').text()).toContain('Incomplete read.')
    expect(wrapper.get('[role="status"]').text()).toContain('secondary: not reached')
    expect(wrapper.get('[role="status"]').classes()).toContain('alert-warning')
    expect(wrapper.text()).not.toContain('Limited results')
  })

  it('recognizes the engine’s canonical cap limitation as limited results', async () => {
    const {wrapper} = await mountReport(
      report({
        status: 'PARTIAL',
        truncated: true,
        dataSources: [
          source({
            status: 'PARTIAL',
            truncated: true,
            sections: [section('statements', {rowCount: 100, truncated: true})]
          })
        ],
        limitations: ['primary: Statements retained 100 rows; additional rows were omitted by BootUI caps.']
      })
    )
    expect(wrapper.get('[role="status"]').text()).toContain('Limited results')
    expect(wrapper.findAll('.alert-warning, .text-bg-warning')).toHaveLength(0)
    expect(wrapper.get('[role="tabpanel"]').text()).toContain('Showing the top 100 statements')
  })

  it('keeps a connection-restoration warning even when all sections are capped but readable', async () => {
    const {wrapper} = await mountReport(
      report({
        status: 'PARTIAL',
        truncated: true,
        dataSources: [
          source({
            status: 'PARTIAL',
            message: 'The connection could not be restored.',
            truncated: true,
            sections: [section('statements', {rowCount: 100, truncated: true})]
          })
        ]
      })
    )
    expect(wrapper.get('[role="status"]').classes()).toContain('alert-warning')
    expect(wrapper.get('[role="status"]').text()).toContain('The connection could not be restored')
    expect(wrapper.get('article .card-header .badge').classes()).toContain('text-bg-warning')
    expect(wrapper.get('[role="tabpanel"] .badge').classes()).toContain('text-bg-secondary')
  })

  it('keeps every capped datasource and section identifiable without warning colors', async () => {
    const {wrapper} = await mountReport(
      report({
        status: 'PARTIAL',
        truncated: true,
        dataSourcesRead: 2,
        dataSources: [
          source({
            status: 'PARTIAL',
            truncated: true,
            sections: [section('statements', {rowCount: 100, truncated: true})]
          }),
          source({
            name: 'archive',
            status: 'PARTIAL',
            truncated: true,
            sections: [section('tables', {rowCount: 200, truncated: true})]
          })
        ]
      })
    )
    expect(wrapper.findAll('[role="status"]')).toHaveLength(1)
    expect(wrapper.findAll('.alert-warning, .text-bg-warning')).toHaveLength(0)
    expect(wrapper.findAll('article').map((item) => item.text())).toEqual([
      expect.stringContaining('Showing the top 100 statements'),
      expect.stringContaining('Showing 200 retained rows')
    ])
  })

  it('keeps independent keyboard tab selection and unique IDs for multiple datasources', async () => {
    const {wrapper, fetchMock} = await mountReport(
      report({dataSourcesRead: 2, dataSources: [source(), source({name: 'archive'})]})
    )
    const lists = wrapper.findAll('[role="tablist"]')
    expect(lists).toHaveLength(2)
    expect(wrapper.findAll('[role="tab"][aria-selected="true"]')).toHaveLength(2)
    const first = lists[0].findAll('[role="tab"]')
    first[0].element.focus()
    await first[0].trigger('keydown', {key: 'ArrowRight'})
    expect(document.activeElement).toBe(first[1].element)
    await first[1].trigger('keydown', {key: 'End'})
    expect(document.activeElement).toBe(first[2].element)
    await first[2].trigger('keydown', {key: 'ArrowRight'})
    expect(document.activeElement).toBe(first[0].element)
    await first[0].trigger('keydown', {key: 'ArrowLeft'})
    expect(document.activeElement).toBe(first[2].element)
    await first[2].trigger('keydown', {key: 'Home'})
    expect(document.activeElement).toBe(first[0].element)
    expect(lists[1].find('[aria-selected="true"]').text()).toContain('Sessions')
    for (const panel of wrapper.findAll('[role="tabpanel"]')) {
      const selected = wrapper.get(`#${panel.attributes('aria-labelledby')}`)
      expect(selected.attributes('aria-controls')).toBe(panel.attributes('id'))
      expect(selected.attributes('tabindex')).toBe('0')
    }
    const ids = wrapper.findAll('[id]').map((item) => item.attributes('id'))
    expect(new Set(ids).size).toBe(ids.length)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('does not claim a time-ranked top list when the engine falls back to calls', async () => {
    const database = mysqlDataSource()
    Object.assign(
      database.sections.find((item) => item.id === 'statements'),
      {
        truncated: true,
        reason: 'Statement timing is unavailable; ranking falls back to calls.'
      }
    )
    const {wrapper} = await mountReport(mysqlReport({status: 'PARTIAL', truncated: true, dataSources: [database]}))
    await tab(wrapper, 'Statements').trigger('click')
    expect(wrapper.get('[role="tabpanel"]').text()).toContain('ranking falls back to calls')
    expect(wrapper.get('[role="tabpanel"]').text()).toContain('Showing 6 retained rows')
    expect(wrapper.text()).not.toContain('statements by total execution time')
  })

  it('preserves the engine metadata-only mask marker without guessing withheld values', async () => {
    const database = mysqlDataSource()
    database.statements[0].digestText = '******'
    database.sessions[0].host = '******'
    const {wrapper} = await mountReport(mysqlReport({dataSources: [database]}))
    expect(wrapper.get('[role="tabpanel"]').text()).toContain('******')
    await tab(wrapper, 'Statements').trigger('click')
    expect(wrapper.get('[role="tabpanel"]').find('tbody td').text()).toBe('******')
  })

  it('falls back when a new report removes a selected section and does not blend old sections', async () => {
    const {wrapper, fetchMock} = await mountReport()
    await tab(wrapper, 'Replication').trigger('click')
    fetchMock.mockResolvedValueOnce(response(report({dataSources: [source({sections: [section('settings')]})]})))
    await readButton(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[role="tabpanel"]')).toHaveLength(1)
    expect(wrapper.get('[role="tabpanel"]').text()).toContain('Settings')
    expect(tab(wrapper, 'Replication')).toBeUndefined()
  })

  it('renders the exact datasource contract with persistent metrics and honest evidence scopes', async () => {
    const {wrapper, fetchMock} = await mountReport(mysqlReport())
    expect(wrapper.text()).toContain('bootui_shop')
    expect(wrapper.text()).toContain('shop_app')
    expect(wrapper.text()).toContain('42,890,531 count')
    expect(wrapper.text()).toContain('0 count')
    expect(tab(wrapper, 'Sessions').find('[aria-label="5 retained sessions and lock waits"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('do not sum across datasources')
    const sections = [
      [
        'Sessions',
        [
          'Default-schema associated',
          'State age is not query age',
          '12.4 s',
          'Row-lock waits',
          'Pending metadata locks',
          'Metadata-lock blockers are unknown',
          'X,REC_NOT_GAP'
        ]
      ],
      [
        'Statements',
        [
          'Default-schema associated',
          'SELECT `status`',
          '28,460 ms',
          '8,421',
          'Raw SQL and sample text are never shown'
        ]
      ],
      [
        'Indexes',
        [
          'Selected schema',
          'idx_orders_customer_created',
          'No-index / insert bucket',
          'not query counts or physical disk reads'
        ]
      ],
      ['Tables', ['Selected schema', '48,210', '12,582,912', 'estimates']],
      ['InnoDB', ['Server-wide', 'History-list length', '42', 'information_schema.innodb_metrics']],
      [
        'Replication',
        ['Server-wide', 'warehouse', 'ON', 'Workers with errors', 'Observed error number', 'not a downstream topology']
      ],
      ['Settings', ['max_connections', 'REPEATABLE-READ', 'GLOBAL', 'global-system-variables']]
    ]
    for (const [title, expected] of sections) {
      await tab(wrapper, title).trigger('click')
      const panelText = wrapper.get('[role="tabpanel"]').text()
      for (const value of expected) expect(panelText).toContain(value)
      expect(wrapper.text()).toContain('42,890,531 count')
    }
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('shows capability and comparison provenance without inventing rates or stale intervals', async () => {
    const body = mysqlReport()
    body.dataSources[0].capabilities[0].collection = 'UNKNOWN'
    body.dataSources[0].capabilities[0].reason = 'Historical collection coverage cannot be established.'
    const {wrapper} = await mountReport(body)
    expect(wrapper.text()).toContain('UNKNOWN')
    expect(wrapper.text()).toContain('Historical collection coverage cannot be established.')
    expect(wrapper.text()).toContain('Changes between compatible observations')
    expect(wrapper.text()).toContain('2 waits')
    expect(wrapper.text()).toContain('not an application-only rate')
  })

  it('renders missing metrics/timing as unknown and sorts exact huge statement counters locally', async () => {
    const database = mysqlDataSource()
    database.vitalSigns[0].value = null
    database.statements = [
      {...database.statements[0], digestText: 'larger digest', calls: '9007199254740993', totalTimeMs: null},
      {...database.statements[1], digestText: 'smaller digest', calls: '9007199254740992', totalTimeMs: 0}
    ]
    const {wrapper, fetchMock} = await mountReport(mysqlReport({dataSources: [database]}))
    expect(wrapper.find('dd').text()).toBe('—')
    await tab(wrapper, 'Statements').trigger('click')
    const panel = wrapper.get('[role="tabpanel"]')
    const calls = panel.findAll('thead button').find((button) => button.text() === 'Calls')
    await calls.trigger('click')
    expect(panel.findAll('tbody tr')[0].text()).toContain('smaller digest')
    expect(panel.findAll('tbody tr')[1].text()).toContain('9,007,199,254,740,993')
    expect(panel.findAll('tbody tr')[1].findAll('td')[2].text()).toBe('—')
    expect(panel.findAll('tbody tr')[0].findAll('td')[2].text()).toBe('0 ms')
    await panel.get('input').setValue('larger digest')
    expect(panel.findAll('tbody tr')).toHaveLength(1)
    expect(panel.text()).toContain('1 of 2 retained rows · local filter only')
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it.each(['AVAILABLE', 'FAILED', 'SKIPPED'])('distinguishes empty replication from %s evidence', async (status) => {
    const database = mysqlDataSource({replication: []})
    const part = database.sections.find((item) => item.id === 'replication')
    part.status = status
    part.reason = status === 'AVAILABLE' ? null : 'The replication source could not be read.'
    const {wrapper} = await mountReport(mysqlReport({dataSources: [database]}))
    await tab(wrapper, 'Replication').trigger('click')
    const panel = wrapper.get('[role="tabpanel"]')
    if (status === 'AVAILABLE') {
      expect(panel.text()).toContain('No local replication channels were observed in this successful read.')
    } else {
      expect(panel.text()).toContain('Missing observations do not establish absence.')
      expect(panel.text()).not.toContain('No local replication channels were observed')
      expect(panel.find('table').exists()).toBe(false)
    }
  })

  it('does not call an incomplete empty replication sub-read evidence of absence', async () => {
    const database = mysqlDataSource({replication: []})
    database.sections.find((item) => item.id === 'replication').reason =
      'The receiver query succeeded; the applier query was denied.'
    const {wrapper} = await mountReport(mysqlReport({status: 'PARTIAL', dataSources: [database]}))
    await tab(wrapper, 'Replication').trigger('click')
    expect(wrapper.get('[role="tabpanel"]').text()).toContain('this incomplete read does not establish absence')
    expect(wrapper.text()).not.toContain('No local replication channels were observed')
  })

  it('never displays sampled SQL or raw lock payloads even if unrecognized fields arrive', async () => {
    const database = mysqlDataSource()
    database.statements[0].digestText = null
    database.statements[0].querySampleText = 'private-sample-marker'
    database.sessions[0].processlistInfo = 'private-session-marker'
    database.lockWaits[0].lockData = 'private-lock-marker'
    const {wrapper} = await mountReport(mysqlReport({dataSources: [database]}))
    expect(wrapper.text()).not.toContain('private-session-marker')
    expect(wrapper.text()).not.toContain('private-lock-marker')
    await tab(wrapper, 'Statements').trigger('click')
    expect(wrapper.text()).not.toContain('private-sample-marker')
    expect(wrapper.get('[role="tabpanel"]').find('tbody td').text()).toBe('—')
  })
})
