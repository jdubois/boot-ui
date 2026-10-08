import {flushPromises, mount} from '@vue/test-utils'
import {ref} from 'vue'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import SideEffects from './SideEffects.vue'

const RouterLinkStub = {
  props: ['to'],
  template: '<a class="router-link-stub" :data-to="JSON.stringify(to)"><slot /></a>'
}

const GROUPS = [
  ['network', 'Network', 'network'],
  ['files', 'Files and processes', 'files'],
  ['processes', 'Files and processes', 'processes'],
  ['environment', 'Environment', 'environment'],
  ['thread-activity', 'Threads and leaks', 'thread activity'],
  ['thread-locals', 'Threads and leaks', 'thread locals'],
  ['resources', 'Threads and leaks', 'resources'],
  ['blocking', 'Blocking', 'blocking'],
  ['security-sinks', 'Security sinks', 'security sinks']
]

function sensor(id, group, label, state = 'not-available', extra = {}) {
  return {
    id,
    group,
    label,
    state,
    reason: state === 'not-available' ? 'Not available in this version.' : null,
    rows: 0,
    occurrences: 0,
    dropped: 0,
    hooks: [],
    ...extra
  }
}

function sensors(overrides = {}) {
  return GROUPS.map(([id, group, label]) => sensor(id, group, label, undefined, overrides[id] ?? {})).map((entry) => ({
    ...entry,
    state: entry.state ?? 'not-available',
    reason: entry.reason ?? (entry.state === 'not-available' ? 'Not available in this version.' : null)
  }))
}

function summary(overrides = {}) {
  const {sensors: sensorOverrides = {}, ...rest} = overrides
  return {
    available: true,
    unavailableReason: null,
    sensors: sensors(sensorOverrides),
    limitations: ['Only operations that pass through installed hooks are visible.'],
    ...rest
  }
}

function sensorReport(id, rows = [], overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    sensor: sensor(id, id === 'processes' ? 'Files and processes' : 'Network', id),
    rows,
    page: {total: rows.length, matched: rows.length, offset: 0, limit: 50, returned: rows.length, hasMore: false},
    limitations: [],
    ...overrides
  }
}

function row(overrides = {}) {
  return {
    scope: 'route',
    attribution: 'GET /api/export',
    sensor: 'processes',
    kind: 'process',
    target: 'java',
    callSite: 'demo.ExportService#runReport()',
    insideMethod: 'demo.ExportController#export()',
    count: 3,
    failed: 1,
    completed: 2,
    nonZeroExits: 1,
    lastExitStatus: 2,
    totalMillis: 165.4,
    maxMillis: 150.2,
    firstSeen: Date.now() - 120_000,
    lastSeen: Date.now() - 45_000,
    exemplarRequestIds: ['00000000000000aa', '00000000000000bb'],
    ...overrides
  }
}

function jsonResponse(body) {
  return {
    ok: true,
    status: 200,
    headers: new Headers({'content-type': 'application/json'}),
    json: () => Promise.resolve(body)
  }
}

function routeFetch(responses) {
  return vi.fn((url) => {
    const path = decodeURIComponent(String(url))
    const key = Object.keys(responses)
      .sort((a, b) => b.length - a.length)
      .find((prefix) => path.includes(prefix))
    if (!key) throw new Error(`Unexpected fetch: ${path}`)
    return Promise.resolve(jsonResponse(responses[key]))
  })
}

function mountPanel(responses, props = {}, panels = ref(null)) {
  const fetch = routeFetch(responses)
  vi.stubGlobal('fetch', fetch)
  const wrapper = mount(SideEffects, {props, global: {stubs: {RouterLink: RouterLinkStub}, provide: {panels}}})
  return {wrapper, fetch}
}

describe('Side Effects panel', () => {
  let wrapper

  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-04T12:00:00Z'))
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('shows the agent unavailable state and Java Agent link', async () => {
    ;({wrapper} = mountPanel({
      'api/side-effects': {
        available: false,
        unavailableReason: 'The BootUI agent is not attached.',
        sensors: [],
        limitations: []
      }
    }))
    await flushPromises()

    expect(wrapper.get('h2').text()).toBe('Side Effects')
    expect(wrapper.get('.side-effects-unavailable').text()).toContain('The BootUI agent is not attached.')
    expect(wrapper.get('.side-effects-agent-link').attributes('data-to')).toBe('"/java-agent"')
  })

  it('renders the six sensor group tabs and not-available sensors without sensor fetches', async () => {
    let fetch
    ;({wrapper, fetch} = mountPanel({'api/side-effects': summary()}))
    await flushPromises()

    expect(wrapper.findAll('[role="tab"]').map((tab) => tab.text())).toEqual([
      'Network',
      'Files and processes',
      'Environment',
      'Threads and leaks',
      'Blocking',
      'Security sinks'
    ])
    expect(wrapper.text()).toContain('network')
    expect(wrapper.text()).toContain('Not available in this version.')

    for (const label of ['Files and processes', 'Environment', 'Threads and leaks', 'Blocking', 'Security sinks']) {
      await wrapper
        .findAll('[role="tab"]')
        .find((tab) => tab.text() === label)
        .trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('Not available in this version.')
    }

    expect(fetch.mock.calls.map(([url]) => String(url)).filter((url) => url.includes('side-effects/sensor'))).toEqual(
      []
    )
  })

  it('shows network rows with their client, a not-captured badge, and a link to the capturing panel', async () => {
    const hidden = row({
      sensor: 'network',
      kind: 'connect',
      attribution: 'GET /api/side-effects/sdk-call',
      target: 'localhost:8081',
      callSite: 'demo.LicenseSdkClient#check',
      insideMethod: null,
      count: 2,
      failed: 0,
      completed: 2,
      nonZeroExits: 0,
      lastExitStatus: null,
      client: null,
      capture: 'not-captured',
      capturedBy: null
    })
    const captured = row({
      sensor: 'network',
      kind: 'connect',
      attribution: 'GET /api/side-effects/rest-call',
      target: 'localhost:8081',
      callSite: 'demo.RestCall#call',
      insideMethod: null,
      count: 1,
      failed: 0,
      completed: 1,
      nonZeroExits: 0,
      lastExitStatus: null,
      client: 'JDK HttpClient',
      capture: 'captured',
      capturedBy: 'rest-client-trace'
    })
    const lookup = row({
      sensor: 'network',
      kind: 'lookup',
      attribution: 'startup',
      scope: 'startup',
      target: 'db.internal',
      callSite: 'org.postgresql.Driver#connect',
      insideMethod: null,
      count: 1,
      failed: 0,
      completed: 0,
      client: 'PostgreSQL JDBC',
      capture: null,
      capturedBy: null,
      exemplarRequestIds: []
    })
    ;({wrapper} = mountPanel({
      'api/side-effects/sensor?sensor=network&offset=0&limit=50': sensorReport('network', [hidden, captured, lookup]),
      'api/side-effects': summary({sensors: {network: {state: 'recording', reason: null, rows: 3, occurrences: 4}}})
    }))
    await flushPromises()

    const table = wrapper.get('.side-effects-table')
    expect(table.findAll('thead th').map((cell) => cell.text())).toContain('Captured')
    const rows = table.findAll('tbody tr')
    expect(rows).toHaveLength(3)
    const sdk = rows.find((entry) => entry.text().includes('sdk-call'))
    expect(sdk.text()).toContain('Not captured by any panel')
    expect(sdk.text()).toContain('unrecognized')
    expect(sdk.text()).toContain('localhost:8081')
    const rest = rows.find((entry) => entry.text().includes('rest-call'))
    expect(rest.text()).toContain('JDK HttpClient')
    expect(rest.get('.side-effects-capture').text()).toBe('Captured')
    expect(rest.findAll('.router-link-stub').map((link) => link.attributes('data-to'))).toContain(
      '"/rest-client-trace"'
    )
    const name = rows.find((entry) => entry.text().includes('db.internal'))
    expect(name.text()).toContain('lookup')
    expect(name.find('.side-effects-capture').exists()).toBe(false)
  })

  it('loads a recording processes sensor lazily, sorts Other last, and links exemplars', async () => {
    const routeRow = row()
    const otherRow = row({
      scope: 'other',
      attribution: 'Background process',
      target: 'sh',
      callSite: null,
      insideMethod: null,
      count: 1,
      failed: 0,
      completed: 1,
      nonZeroExits: 0,
      lastExitStatus: 0,
      totalMillis: 20,
      maxMillis: 20,
      exemplarRequestIds: []
    })
    let fetch
    ;({wrapper, fetch} = mountPanel({
      'api/side-effects/sensor?sensor=processes&offset=0&limit=50': sensorReport('processes', [otherRow, routeRow], {
        limitations: ['Process arguments are never recorded.']
      }),
      'api/side-effects': summary({
        sensors: {
          processes: {
            state: 'recording',
            reason: 'Process starts are being recorded.',
            rows: 2,
            occurrences: 4,
            hooks: [
              {
                id: 'process-builder-start',
                type: 'jdk',
                present: true,
                transformed: true,
                selfTest: true,
                recorded: 4
              }
            ]
          }
        }
      })
    }))
    await flushPromises()

    expect(fetch).toHaveBeenCalledTimes(1)
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === 'Files and processes')
      .trigger('click')
    await flushPromises()

    expect(fetch.mock.calls.map(([url]) => decodeURIComponent(String(url)))).toContain(
      'api/side-effects/sensor?sensor=processes&offset=0&limit=50'
    )
    expect(wrapper.get('.side-effects-hooks').text()).toContain('process-builder-start')
    const rows = wrapper.findAll('.side-effects-table tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('Route')
    expect(rows[0].text()).toContain('GET /api/export')
    expect(rows[0].text()).toContain('java')
    expect(rows[0].text()).toContain('demo.ExportService#runReport()')
    expect(rows[0].text()).toContain('inside demo.ExportController#export()')
    expect(rows[0].text()).toContain('3')
    expect(rows[0].text()).toContain('1 non-zero')
    expect(rows[0].text()).toContain('last 2')
    expect(rows[0].text()).toContain('165 / 150')
    expect(rows[0].text()).toContain('45s ago')
    expect(rows[1].classes()).toContain('side-effects-row-other')
    expect(rows[1].text()).toContain('Other')
    expect(rows[1].text()).toContain('sh')

    const links = wrapper.findAll('.router-link-stub')
    expect(links.map((link) => link.attributes('data-to'))).toContain(
      JSON.stringify({path: '/activity', query: {request: '00000000000000aa'}})
    )
    expect(links.map((link) => link.attributes('data-to'))).toContain(
      JSON.stringify({path: '/activity', query: {request: '00000000000000bb'}})
    )
    expect(wrapper.text()).toContain('Process arguments are never recorded.')
  })

  it('shows blocking rows by event loop, operation, and call site with how long they blocked', async () => {
    const blockingRow = row({
      sensor: 'blocking',
      kind: 'sleep',
      target: 'reactor-http-nio-{n}',
      callSite: 'demo.SlowHandler#sleepOnEventLoop',
      insideMethod: null,
      count: 4,
      failed: 1,
      completed: 0,
      nonZeroExits: 0,
      lastExitStatus: null,
      totalMillis: 800,
      maxMillis: 250,
      exemplarRequestIds: ['00000000000000cc']
    })
    let fetch
    ;({wrapper, fetch} = mountPanel({
      'api/side-effects/sensor?sensor=blocking&offset=0&limit=50': sensorReport('blocking', [blockingRow]),
      'api/side-effects': summary({
        sensors: {
          blocking: {
            state: 'recording',
            rows: 1,
            occurrences: 4,
            hooks: [{id: 'LockSupport.park', type: 'java.util.concurrent.locks.LockSupport', transformed: true}]
          }
        }
      })
    }))
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === 'Blocking')
      .trigger('click')
    await flushPromises()

    expect(fetch.mock.calls.map(([url]) => decodeURIComponent(String(url)))).toContain(
      'api/side-effects/sensor?sensor=blocking&offset=0&limit=50'
    )
    const headers = wrapper.findAll('.side-effects-table thead th').map((th) => th.text())
    expect(headers).toContain('Event loop / operation')
    expect(headers).toContain('Blocked (total / max ms)')
    expect(headers).not.toContain('Exits')
    const cells = wrapper.findAll('.side-effects-table tbody tr td')
    expect(cells).toHaveLength(headers.length)
    const text = wrapper.get('.side-effects-table tbody tr').text()
    expect(text).toContain('reactor-http-nio-{n}')
    expect(text).toContain('sleep')
    expect(text).toContain('demo.SlowHandler#sleepOnEventLoop')
    expect(text).toContain('800 / 250')
    expect(wrapper.findAll('.router-link-stub').map((link) => link.attributes('data-to'))).toContain(
      JSON.stringify({path: '/activity', query: {request: '00000000000000cc'}})
    )
  })

  it('shows thread activity rows per route, with threads left running and library pools grouped apart', async () => {
    const leftRow = row({
      sensor: 'thread-activity',
      kind: 'thread',
      target: 'report-refresher-{n}',
      callSite: 'demo.ReportService#refreshLater',
      insideMethod: null,
      origin: 'application',
      count: 6,
      requests: 3,
      leftRunning: 2,
      failed: 0,
      completed: 0,
      totalMillis: 0,
      maxMillis: 0,
      exemplarRequestIds: ['00000000000000dd']
    })
    const executorRow = row({
      sensor: 'thread-activity',
      kind: 'executor',
      target: 'java.util.concurrent.ThreadPoolExecutor',
      callSite: 'demo.ExportService#export',
      insideMethod: null,
      origin: 'application',
      count: 4,
      requests: 4,
      leftRunning: 1,
      failed: 1,
      completed: 3,
      totalMillis: 90,
      maxMillis: 40
    })
    const libraryRow = row({
      sensor: 'thread-activity',
      kind: 'thread',
      target: 'HikariPool-{n} housekeeper',
      callSite: 'com.zaxxer.hikari.pool.HikariPool#<init>',
      insideMethod: null,
      origin: 'library',
      count: 1,
      requests: 1,
      leftRunning: 0
    })
    ;({wrapper} = mountPanel({
      'api/side-effects/sensor?sensor=thread-activity&offset=0&limit=50': sensorReport('thread-activity', [
        leftRow,
        executorRow,
        libraryRow
      ]),
      'api/side-effects': summary({
        sensors: {'thread-activity': {state: 'recording', rows: 3, occurrences: 11}}
      })
    }))
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === 'Threads and leaks')
      .trigger('click')
    await flushPromises()

    const headers = wrapper.findAll('.side-effects-table thead th').map((th) => th.text())
    expect(headers).toEqual(
      expect.arrayContaining(['Thread / executor', 'Started', 'Per request', 'Left running', 'Shut down'])
    )
    const rows = wrapper.findAll('.side-effects-table tbody tr')
    expect(rows[0].findAll('td')).toHaveLength(headers.length / 2)
    const own = wrapper.get('.side-effects-table').text()
    expect(own).toContain('report-refresher-{n}')
    expect(own).toContain('demo.ReportService#refreshLater')
    expect(wrapper.findAll('.side-effects-left-running').map((badge) => badge.text())).toEqual(['1', '2'])
    expect(own).toContain('java.util.concurrent.ThreadPoolExecutor')
    const apart = wrapper.get('.side-effects-apart')
    expect(apart.text()).toContain('Libraries and the JDK (1), grouped apart')
    expect(apart.text()).toContain('HikariPool-{n} housekeeper')
    expect(own).not.toContain('HikariPool')
  })

  it('shows thread locals left set by their holder, set during the request, never a value', async () => {
    const leftRow = row({
      sensor: 'thread-locals',
      kind: 'left set',
      target: 'demo.TenantContext.CURRENT',
      callSite: null,
      insideMethod: null,
      origin: 'application',
      count: 4,
      requests: 4,
      exemplarRequestIds: ['00000000000000ee']
    })
    const cacheRow = row({
      sensor: 'thread-locals',
      kind: 'left set (with initial value)',
      target: 'demo.Formats.FORMAT',
      callSite: null,
      insideMethod: null,
      origin: 'application',
      count: 2,
      requests: 2
    })
    const unresolvedRow = row({
      sensor: 'thread-locals',
      kind: 'left set',
      target: 'holder not resolved (java.lang.ThreadLocal)',
      callSite: null,
      insideMethod: null,
      origin: 'unknown',
      count: 1,
      requests: 1
    })
    ;({wrapper} = mountPanel({
      'api/side-effects/sensor?sensor=thread-locals&offset=0&limit=50': sensorReport('thread-locals', [
        leftRow,
        cacheRow,
        unresolvedRow
      ]),
      'api/side-effects': summary({
        sensors: {'thread-locals': {state: 'recording', rows: 2, occurrences: 6}}
      })
    }))
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === 'Threads and leaks')
      .trigger('click')
    await flushPromises()

    const table = wrapper
      .findAll('.side-effects-table')
      .find((candidate) => candidate.text().includes('demo.TenantContext.CURRENT'))
    expect(table).toBeTruthy()
    const headers = table.findAll('thead th').map((th) => th.text())
    expect(headers).toEqual(expect.arrayContaining(['Thread local (holder)', 'Times left set', 'Origin']))
    expect(table.findAll('tbody tr')[0].findAll('td')).toHaveLength(headers.length)
    expect(table.text()).toContain('left set (with initial value)')
    expect(table.findAll('.side-effects-set-during').map((cell) => cell.text())).toEqual([
      'set during the request',
      'set during the request'
    ])
    expect(table.text()).not.toContain('holder not resolved')
    const apart = wrapper.get('.side-effects-apart')
    expect(apart.text()).toContain('Holders not resolved (1), grouped apart')
    expect(apart.text()).toContain('holder not resolved (java.lang.ThreadLocal)')
  })

  it('says why blocking is not applicable on a stack without event loops', async () => {
    ;({wrapper} = mountPanel({
      'api/side-effects/sensor?sensor=blocking&offset=0&limit=50': sensorReport('blocking', []),
      'api/side-effects': summary({
        sensors: {
          blocking: {
            state: 'not-applicable',
            reason:
              'This application serves requests on Spring MVC, a thread per request: there is no event loop to block.'
          }
        }
      })
    }))
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === 'Blocking')
      .trigger('click')
    await flushPromises()

    expect(wrapper.get('.side-effects-state').text()).toBe('Not applicable')
    expect(wrapper.get('.side-effects-state-note').text()).toContain('no event loop to block')
    expect(wrapper.find('.side-effects-table').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('No blocking call has started')
  })

  it('shows not-claimed sensors honestly with hook coverage', async () => {
    ;({wrapper} = mountPanel({
      'api/side-effects/sensor?sensor=network&offset=0&limit=50': sensorReport('network', [], {
        available: false,
        unavailableReason: 'Sensor network is not claimed.'
      }),
      'api/side-effects': summary({
        sensors: {
          network: {
            state: 'not-claimed',
            reason: 'bootui.agent.sensors does not include network.',
            hooks: [
              {
                id: 'socket-connect',
                type: 'jdk',
                present: true,
                transformed: false,
                selfTest: false,
                recorded: 0
              }
            ]
          }
        }
      })
    }))
    await flushPromises()

    expect(wrapper.text()).toContain('Not claimed')
    expect(wrapper.text()).toContain('bootui.agent.sensors does not include network.')
    expect(wrapper.text()).toContain('socket-connect')
    expect(wrapper.text()).toContain('transformed no')
    expect(wrapper.text()).toContain('self-test no')
    expect(wrapper.text()).toContain('Sensor network is not claimed.')
  })

  it('loads additional pages for a recording sensor', async () => {
    const first = row({target: 'java', exemplarRequestIds: []})
    const second = row({target: 'node', count: 2, exemplarRequestIds: []})
    let fetch
    ;({wrapper, fetch} = mountPanel({
      'api/side-effects/sensor?sensor=processes&offset=1&limit=50': sensorReport('processes', [second], {
        page: {total: 2, matched: 2, offset: 1, limit: 50, returned: 1, hasMore: false}
      }),
      'api/side-effects/sensor?sensor=processes&offset=0&limit=50': sensorReport('processes', [first], {
        page: {total: 2, matched: 2, offset: 0, limit: 50, returned: 1, hasMore: true}
      }),
      'api/side-effects': summary({sensors: {processes: {state: 'recording'}}})
    }))
    await flushPromises()

    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === 'Files and processes')
      .trigger('click')
    await flushPromises()
    await wrapper.get('.side-effects-load-more').trigger('click')
    await flushPromises()

    expect(fetch.mock.calls.map(([url]) => decodeURIComponent(String(url)))).toContain(
      'api/side-effects/sensor?sensor=processes&offset=1&limit=50'
    )
    expect(wrapper.findAll('.side-effects-table tbody tr')).toHaveLength(2)
    expect(wrapper.text()).toContain('java')
    expect(wrapper.text()).toContain('node')
    expect(wrapper.find('.side-effects-load-more').exists()).toBe(false)
  })

  it('keeps the rows on screen while an auto-refresh reloads them, with every row already loaded', async () => {
    const first = row({target: 'java', exemplarRequestIds: []})
    const second = row({target: 'node', count: 2, exemplarRequestIds: []})
    const responses = {
      'api/side-effects/sensor?sensor=processes&offset=1&limit=50': sensorReport('processes', [second], {
        page: {total: 2, matched: 2, offset: 1, limit: 50, returned: 1, hasMore: false}
      }),
      'api/side-effects/sensor?sensor=processes&offset=0&limit=50': sensorReport('processes', [first], {
        page: {total: 2, matched: 2, offset: 0, limit: 50, returned: 1, hasMore: true}
      }),
      'api/side-effects': summary({sensors: {processes: {state: 'recording'}}})
    }
    let release
    const pending = new Promise((resolve) => {
      release = resolve
    })
    const fetch = vi.fn((url) => {
      const path = decodeURIComponent(String(url))
      if (path.includes('api/side-effects/sensor?sensor=processes&offset=0&limit=50') && fetch.refreshing) {
        return pending.then(() =>
          jsonResponse(
            sensorReport('processes', [first, second, row({target: 'python', exemplarRequestIds: []})], {
              page: {total: 3, matched: 3, offset: 0, limit: 50, returned: 3, hasMore: false}
            })
          )
        )
      }
      const key = Object.keys(responses)
        .sort((a, b) => b.length - a.length)
        .find((prefix) => path.includes(prefix))
      if (!key) throw new Error(`Unexpected fetch: ${path}`)
      return Promise.resolve(jsonResponse(responses[key]))
    })
    vi.stubGlobal('fetch', fetch)
    wrapper = mount(SideEffects, {global: {stubs: {RouterLink: RouterLinkStub}}})
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === 'Files and processes')
      .trigger('click')
    await flushPromises()
    await wrapper.get('.side-effects-load-more').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.side-effects-table tbody tr')).toHaveLength(2)

    fetch.refreshing = true
    await vi.advanceTimersByTimeAsync(10_000)
    await flushPromises()

    expect(fetch.mock.calls.map(([url]) => decodeURIComponent(String(url)))).toContain(
      'api/side-effects/sensor?sensor=processes&offset=0&limit=50'
    )
    expect(wrapper.text()).not.toContain('Loading…')
    expect(wrapper.findAll('.side-effects-table tbody tr')).toHaveLength(2)

    release()
    await flushPromises()
    expect(wrapper.findAll('.side-effects-table tbody tr')).toHaveLength(3)
    expect(wrapper.text()).toContain('python')
  })

  it('shows file rows by pattern, location, and origin, with class path, JDK, and logging grouped apart', async () => {
    const report = row({
      sensor: 'files',
      kind: 'write',
      target: './target/bootui-side-effects/report-{n}-{n}-{n}.csv',
      origin: 'application',
      location: 'working-directory',
      callSite: 'demo.ReportWriter#writeReport',
      completed: 0,
      nonZeroExits: 0,
      lastExitStatus: null
    })
    const log = row({
      sensor: 'files',
      kind: 'write',
      target: '$TMPDIR/app-{n}.log',
      origin: 'logging',
      location: 'temporary-directory',
      callSite: null,
      exemplarRequestIds: []
    })
    const classes = row({
      scope: 'unattributed',
      attribution: 'all threads (counted)',
      sensor: 'files',
      kind: 'open',
      target: '(class files)',
      origin: 'class-path',
      location: null,
      callSite: null,
      exemplarRequestIds: []
    })
    ;({wrapper} = mountPanel({
      'api/side-effects/sensor?sensor=files&offset=0&limit=50': sensorReport('files', [classes, log, report]),
      'api/side-effects/sensor?sensor=processes&offset=0&limit=50': sensorReport('processes', []),
      'api/side-effects': summary({
        sensors: {
          files: {state: 'recording', reason: null},
          processes: {state: 'recording', reason: null}
        }
      })
    }))
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === 'Files and processes')
      .trigger('click')
    await flushPromises()

    const own = wrapper.findAll('.side-effects-sensor')[0]
    const tables = own.findAll('.side-effects-table')
    expect(tables).toHaveLength(2)
    expect(tables[0].text()).toContain('Path pattern')
    expect(tables[0].text()).toContain('./target/bootui-side-effects/report-{n}-{n}-{n}.csv')
    expect(tables[0].text()).toContain('Working directory')
    expect(tables[0].text()).toContain('Application')
    expect(tables[0].text()).not.toContain('(class files)')
    const apart = own.get('details.side-effects-apart')
    expect(apart.get('summary').text()).toContain('Class path, JDK, and logging (2)')
    expect(tables[1].text()).toContain('(class files)')
    expect(tables[1].text()).toContain('Logging')
    expect(tables[1].text()).toContain('Temporary directory')
  })

  it('shows environment rows by name and explains that the sensor is opt-in', async () => {
    const read = row({
      sensor: 'environment',
      kind: 'system property',
      target: 'sample.report.title',
      origin: 'application',
      location: null,
      count: 2,
      failed: 0,
      completed: 0,
      nonZeroExits: 0,
      lastExitStatus: null
    })
    ;({wrapper} = mountPanel({
      'api/side-effects/sensor?sensor=environment&offset=0&limit=50': sensorReport('environment', [read]),
      'api/side-effects': summary({sensors: {environment: {state: 'recording', reason: null}}})
    }))
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === 'Environment')
      .trigger('click')
    await flushPromises()

    const table = wrapper.get('.side-effects-table')
    expect(table.text()).toContain('Name')
    expect(table.text()).toContain('Reads')
    expect(table.text()).toContain('sample.report.title')
    expect(table.text()).toContain('system property')
    expect(table.text()).not.toContain('Failed')
    wrapper.unmount()

    ;({wrapper} = mountPanel({
      'api/side-effects': summary({
        sensors: {
          environment: {
            state: 'not-claimed',
            reason: "This application's bootui.agent.sensors does not include environment."
          }
        }
      }),
      'api/side-effects/sensor?sensor=environment&offset=0&limit=50': sensorReport('environment', [])
    }))
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === 'Environment')
      .trigger('click')
    await flushPromises()
    expect(wrapper.get('.side-effects-state-note').text()).toContain('It is opt-in')
  })

  it('shows security-sinks rows with the parameter name and the fact, never as a vulnerability', async () => {
    const sink = row({
      sensor: 'security-sinks',
      kind: 'SQL text',
      target: "select * from users where name = '{name}'",
      location: 'inside a literal',
      parameter: 'name',
      detail:
        'Request input reached this SQL text unchanged: the value of `name` appeared inside a literal. Check that it is bound as a parameter or escaped. Seen in one request so far.',
      count: 1,
      failed: 0,
      completed: 0,
      nonZeroExits: 0,
      lastExitStatus: null
    })
    ;({wrapper} = mountPanel({
      'api/side-effects/sensor?sensor=security-sinks&offset=0&limit=50': sensorReport('security-sinks', [sink]),
      'api/side-effects': summary({sensors: {'security-sinks': {state: 'recording', reason: null}}})
    }))
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === 'Security sinks')
      .trigger('click')
    await flushPromises()

    const table = wrapper.get('.side-effects-table')
    expect(table.text()).toContain('Parameter')
    expect(wrapper.get('.side-effects-parameter').text()).toBe('name')
    expect(table.text()).toContain("select * from users where name = '{name}'")
    expect(wrapper.get('.side-effects-detail').text()).toContain('Check that it is bound as a parameter or escaped.')
    expect(table.text()).toContain('inside a literal')
    expect(table.text().toLowerCase()).not.toContain('vulnerab')
    expect(table.text()).not.toContain('Failed')
  })

  it('shows JDK check rows with their origin, and groups what libraries requested apart', async () => {
    const own = row({
      sensor: 'security-sinks',
      kind: 'weak digest',
      target: 'MD5',
      origin: 'application',
      callSite: 'com.example.UserService#hash',
      detail:
        'Weak algorithm MD5 requested by application code at `com.example.UserService#hash`. MD5 and SHA-1 remain fine for checksums and ETags; check that this one protects no password, signature, or token.',
      count: 3
    })
    const library = row({
      sensor: 'security-sinks',
      kind: 'weak digest',
      target: 'MD5',
      origin: 'library',
      location: 'org.springframework.util.DigestUtils#md5',
      callSite: 'com.example.EtagService#tag',
      detail:
        'Weak algorithm MD5 requested by library code `org.springframework.util.DigestUtils#md5` for application frame `com.example.EtagService#tag`.',
      count: 9
    })
    ;({wrapper} = mountPanel({
      'api/side-effects/sensor?sensor=security-sinks&offset=0&limit=50': sensorReport('security-sinks', [own, library]),
      'api/side-effects': summary({sensors: {'security-sinks': {state: 'recording', reason: null}}})
    }))
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === 'Security sinks')
      .trigger('click')
    await flushPromises()

    const tables = wrapper.findAll('.side-effects-table')
    expect(tables).toHaveLength(2)
    expect(tables[0].text()).toContain('Sink (value redacted) or check')
    expect(tables[0].text()).toContain('Application')
    expect(tables[0].text()).toContain('requested by application code')
    expect(wrapper.get('.side-effects-apart summary').text()).toBe('Requested by libraries (1), grouped apart')
    expect(tables[1].text()).toContain('org.springframework.util.DigestUtils#md5')
    expect(wrapper.text().toLowerCase()).not.toContain('vulnerab')
  })

  it('explains that the files sensor is opt-in, and not the processes sensor', async () => {
    ;({wrapper} = mountPanel({
      'api/side-effects': summary({
        sensors: {
          files: {
            state: 'not-claimed',
            reason: "This application's bootui.agent.sensors does not include files."
          },
          processes: {
            state: 'not-claimed',
            reason: "This application's bootui.agent.sensors does not include processes."
          }
        }
      }),
      'api/side-effects/sensor?sensor=files&offset=0&limit=50': sensorReport('files', []),
      'api/side-effects/sensor?sensor=processes&offset=0&limit=50': sensorReport('processes', [])
    }))
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === 'Files and processes')
      .trigger('click')
    await flushPromises()
    const notes = wrapper.findAll('.side-effects-state-note').map((note) => note.text())
    const files = notes.find((note) => note.includes('does not include files'))
    const processes = notes.find((note) => note.includes('does not include processes'))
    expect(files).toContain('It is opt-in: add files to bootui.agent.sensors')
    expect(files).toContain('path patterns')
    expect(processes).not.toContain('opt-in')
  })

  it('offers an opt-in sensor’s runtime switch with its reason and the property as the other way', async () => {
    const toggle = {
      id: 'environment',
      configured: false,
      enabled: false,
      overridden: false,
      state: 'off',
      optInReason: 'Off by default: it advises System.getProperty.',
      available: true,
      unavailableReason: null
    }
    const panels = ref({panels: [{id: 'java-agent', enabled: true, available: true, readOnly: false}]})
    ;({wrapper} = mountPanel(
      {
        'api/side-effects': summary({
          sensors: {
            environment: {
              state: 'not-claimed',
              reason: "This application's bootui.agent.sensors does not include environment.",
              toggle
            }
          }
        }),
        'api/side-effects/sensor?sensor=environment&offset=0&limit=50': sensorReport('environment', [])
      },
      {},
      panels
    ))
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === 'Environment')
      .trigger('click')
    await flushPromises()

    const control = wrapper.get('[data-testid="agent-sensor-toggle-environment"]')
    expect(control.get('input[role="switch"]').element.checked).toBe(false)
    expect(control.text()).toContain('advises System.getProperty')
    const note = wrapper.get('.side-effects-state-note').text()
    expect(note).toContain('add environment to bootui.agent.sensors')
    expect(note).toContain('or switch it on above for this JVM')
    wrapper.unmount()

    // Without a usable Java Agent panel, neither the switch nor the sentence pointing at it shows.
    ;({wrapper} = mountPanel({
      'api/side-effects': summary({
        sensors: {environment: {state: 'not-claimed', reason: 'Not claimed.', toggle}}
      }),
      'api/side-effects/sensor?sensor=environment&offset=0&limit=50': sensorReport('environment', [])
    }))
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === 'Environment')
      .trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="agent-sensor-toggle-environment"]').exists()).toBe(false)
    expect(wrapper.get('.side-effects-state-note').text()).not.toContain('switch it on')
  })

  it('shows a switch’s answer at once and reads the summary again', async () => {
    const off = {
      id: 'environment',
      configured: false,
      enabled: false,
      overridden: false,
      state: 'off',
      optInReason: 'Off by default.',
      available: true,
      unavailableReason: null
    }
    const on = {...off, enabled: true, overridden: true, state: 'installing'}
    const panels = ref({panels: [{id: 'java-agent', enabled: true, available: true, readOnly: false}]})
    let fetch
    const responses = {
      'api/overview': {},
      'api/java-agent/sensors/environment': {state: 'ARMED', toggles: [on]},
      'api/side-effects/sensor?sensor=environment&offset=0&limit=50': sensorReport('environment', []),
      'api/side-effects': summary({
        sensors: {environment: {state: 'not-claimed', reason: 'Not claimed.', toggle: off}}
      })
    }
    ;({wrapper, fetch} = mountPanel(responses, {}, panels))
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === 'Environment')
      .trigger('click')
    await flushPromises()
    const summaries = () => fetch.mock.calls.filter(([url]) => String(url).endsWith('api/side-effects')).length
    const before = summaries()
    responses['api/side-effects'] = summary({
      sensors: {environment: {state: 'installing', reason: 'The sensor is installing.', toggle: on}}
    })

    await wrapper.get('[data-testid="agent-sensor-toggle-environment"] input').setValue(true)
    await vi.waitFor(() =>
      expect(fetch.mock.calls.some(([url]) => String(url).includes('api/java-agent/sensors/environment'))).toBe(true)
    )
    await flushPromises()
    await flushPromises()

    expect(wrapper.get('[data-testid="agent-sensor-toggle-environment"]').text()).toContain('Overridden')
    await vi.advanceTimersByTimeAsync(2_500)
    await flushPromises()
    expect(summaries()).toBeGreaterThan(before)
    expect(wrapper.get('[data-testid="agent-sensor-toggle-environment"]').text()).toContain('Installing')
  })

  it('does not point at the switch while the Java Agent panel is read-only', async () => {
    const toggle = {
      id: 'environment',
      configured: false,
      enabled: false,
      overridden: false,
      state: 'off',
      optInReason: 'Off by default.',
      available: true,
      unavailableReason: null
    }
    const panels = ref({
      panels: [{id: 'java-agent', enabled: true, available: true, readOnly: true, readOnlyReason: 'read-only'}]
    })
    ;({wrapper} = mountPanel(
      {
        'api/side-effects/sensor?sensor=environment&offset=0&limit=50': sensorReport('environment', []),
        'api/side-effects': summary({sensors: {environment: {state: 'not-claimed', reason: 'Not claimed.', toggle}}})
      },
      {},
      panels
    ))
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === 'Environment')
      .trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="agent-sensor-toggle-environment"] input').element.disabled).toBe(true)
    expect(wrapper.get('.side-effects-state-note').text()).not.toContain('switch it on')
  })

  it('offers the security-sinks switch, which says request-value matching still needs its property', async () => {
    const toggle = {
      id: 'security-sinks',
      configured: false,
      enabled: false,
      overridden: false,
      state: 'off',
      optInReason:
        'Off by default: its JDK checks added about 3.9 % on the benchmark. Its request-value matching also needs bootui.agent.security-sinks.request-values=true.',
      available: true,
      unavailableReason: null
    }
    const panels = ref({panels: [{id: 'java-agent', enabled: true, available: true, readOnly: false}]})
    const responses = {
      'api/side-effects': summary({
        sensors: {
          'security-sinks': {
            state: 'not-claimed',
            reason: "This application's bootui.agent.sensors does not include security-sinks.",
            toggle
          }
        }
      }),
      'api/side-effects/sensor?sensor=security-sinks&offset=0&limit=50': sensorReport('security-sinks', [])
    }
    ;({wrapper} = mountPanel(responses, {}, panels))
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === 'Security sinks')
      .trigger('click')
    await flushPromises()

    const control = wrapper.get('[data-testid="agent-sensor-toggle-security-sinks"]')
    const input = control.get('input[role="switch"]')
    expect(input.element.checked).toBe(false)
    expect(input.attributes('aria-describedby')).toBeTruthy()
    expect(control.text()).toContain('Record with the security-sinks sensor')
    expect(control.text()).toContain('bootui.agent.security-sinks.request-values=true')
    const note = wrapper.get('.side-effects-state-note').text()
    expect(note).toContain('add security-sinks to bootui.agent.sensors')
    expect(note).toContain('or switch it on above for this JVM')
    wrapper.unmount()

    // Switched on with request-value matching off: the JDK checks record, and the reason says how to turn matching on.
    responses['api/side-effects'] = summary({
      sensors: {
        'security-sinks': {
          state: 'recording',
          reason:
            'JDK checks: deserialization without a filter, weak algorithms, trust managers and hostname verifiers. Request-value matching is off: set bootui.agent.security-sinks.request-values=true and restart the application to check whether request input reaches SQL text, a command, a file path, or an outbound URL.',
          toggle: {...toggle, enabled: true, overridden: true, state: 'installed'}
        }
      }
    })
    ;({wrapper} = mountPanel(responses, {}, panels))
    await flushPromises()
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === 'Security sinks')
      .trigger('click')
    await flushPromises()
    const on = wrapper.get('[data-testid="agent-sensor-toggle-security-sinks"]')
    expect(on.get('input[role="switch"]').element.checked).toBe(true)
    expect(on.text()).toContain('Recording')
    expect(on.text()).toContain('Overridden')
    expect(wrapper.text()).toContain(
      'Request-value matching is off: set bootui.agent.security-sinks.request-values=true and restart the application'
    )
  })
})
