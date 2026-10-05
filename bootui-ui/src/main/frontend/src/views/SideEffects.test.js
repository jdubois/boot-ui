import {flushPromises, mount} from '@vue/test-utils'
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

function mountPanel(responses, props = {}) {
  const fetch = routeFetch(responses)
  vi.stubGlobal('fetch', fetch)
  const wrapper = mount(SideEffects, {props, global: {stubs: {RouterLink: RouterLinkStub}}})
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
})
