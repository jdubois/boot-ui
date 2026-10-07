import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import RunComparison from './RunComparison.vue'

const compared = {
  status: 'COMPARED',
  reason: null,
  current: {runId: 'run-5', ordinal: 5, startedAt: 1, endedAt: null, requests: 3, source: 'CURRENT'},
  previous: {runId: 'run-4', ordinal: 4, startedAt: 1, endedAt: 2, requests: 3, source: 'MEMORY'},
  runs: [
    {runId: 'run-4', ordinal: 4, startedAt: 1, endedAt: 2, requests: 3, source: 'MEMORY'},
    {runId: 'run-1', ordinal: 1, startedAt: 1, endedAt: 2, requests: 40, source: 'BASELINE_FILE'}
  ],
  notComparableReasons: [],
  behavior: [
    {
      kind: 'statements-per-request',
      subject: 'GET /api/orders',
      change: 'INCREASED',
      sentence: '`GET /api/orders` ran 2.0 statements per request, up from 1.0 in run 4 (3 and 3 requests).'
    }
  ],
  edges: [
    {kind: 'edge', change: 'ADDED', sentence: '`GET /api/orders` reads table `order_line`, 3 times, and not in run 4.'}
  ],
  restartCost: {
    status: 'COMPARED',
    reason: null,
    readyMsBefore: 4000,
    readyMsAfter: 2000,
    beans: [
      {subject: 'orderService', sentence: 'Bean `orderService` took 100 ms to initialize, down from 900 ms in run 4.'}
    ]
  },
  latency: [],
  limitations: ['Tracing was on before and is off now, so links by trace id differ.']
}

function jsonResponse(body) {
  return {ok: true, status: 200, json: () => Promise.resolve(body)}
}

describe('RunComparison', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    vi.unstubAllGlobals()
  })

  it('lists behavior first, then edges and the restart cost, with code in the sentences', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(compared)))
    wrapper = mount(RunComparison)
    await flushPromises()

    expect(wrapper.find('h2').text()).toBe('Compared with the previous run')
    expect(wrapper.find('[data-testid="comparison-status"]').text()).toBe('Compared')
    expect(wrapper.find('.insight-comparison-against').text()).toContain('Against Run 4 · 3 requests')
    expect(wrapper.find('[data-section="behavior"] h3').text().replace(/\s+/g, ' ')).toBe(
      'What the routes and executions did · 1'
    )
    expect(wrapper.find('[data-section="behavior"] .visually-hidden').text()).toBe('Up:')
    expect(wrapper.find('[data-section="edges"] .insight-comparison-marker i').classes()).toContain('bi-plus-lg')
    expect(wrapper.emitted('loaded')[0][0].status).toBe('COMPARED')
    expect(wrapper.findAll('[data-section]').map((section) => section.attributes('data-section'))).toEqual([
      'behavior',
      'edges',
      'restart'
    ])
    expect(wrapper.find('[data-section="behavior"] code').text()).toBe('GET /api/orders')
    expect(wrapper.text()).toContain(
      'Ready in 2,000 ms after this restart, 4,000 ms after run 4 (the immediately preceding restart).'
    )
    expect(wrapper.find('details').text()).toContain('Tracing was on before')
  })

  it('leads with the code changes when the agent lists them, and says why when it cannot', async () => {
    const withCode = {
      ...compared,
      codeChanges: {
        available: true,
        unavailableReason: null,
        counts: {
          previousRun: true,
          note: null,
          changed: 1,
          added: 1,
          removed: 2,
          executed: 1,
          notExecuted: 1,
          partial: false,
          scanStatus: 'COMPLETE'
        },
        methods: [
          {
            key: 'com.example.OrderService#discount()V',
            className: 'com.example.OrderService',
            name: 'discount',
            descriptor: '()V',
            change: 'ADDED',
            status: 'NEVER_EXECUTED',
            notTrackedReason: null,
            routes: [],
            routesTotal: 0,
            routesNote: null
          },
          {
            key: 'com.example.OrderService#total(J)J',
            className: 'com.example.OrderService',
            name: 'total',
            descriptor: '(J)J',
            change: 'CHANGED',
            status: 'EXECUTED',
            notTrackedReason: null,
            routes: ['GET /api/orders'],
            routesTotal: 1,
            routesNote: null
          }
        ],
        methodsTotal: 2,
        limitations: ["2 removed methods are counted, not named: the previous run keeps only its methods' hashes."]
      }
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(withCode)))
    wrapper = mount(RunComparison)
    await flushPromises()

    expect(wrapper.findAll('[data-section]').map((section) => section.attributes('data-section'))).toEqual([
      'code-changes',
      'behavior',
      'edges',
      'restart'
    ])
    const code = wrapper.find('[data-section="code-changes"]')
    expect(code.find('h3').text().replace(/\s+/g, ' ')).toBe(
      'Code changes · 1 changed · 1 added · 2 removed · 1 not run yet'
    )
    const rows = code.findAll('.insight-comparison-row')
    expect(rows[0].text().replace(/\s+/g, ' ')).toContain('Added: OrderService#discount not run yet')
    expect(rows[1].text().replace(/\s+/g, ' ')).toContain('OrderService#total ran in this run on GET /api/orders')
    expect(code.find('details').text()).toContain('2 removed methods are counted')

    // Each changed or added method offers its change impact, named for a screen reader, by its full key.
    const see = rows[1].get('.insight-comparison-impact')
    expect(see.text()).toBe('See its impact')
    expect(see.attributes('aria-label')).toBe('See its impact: OrderService#total')
    await see.trigger('click')
    expect(wrapper.emitted('impact')).toEqual([
      [{symbol: 'com.example.OrderService#total(J)J', name: 'OrderService#total'}]
    ])

    wrapper.unmount()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          ...compared,
          codeChanges: {
            available: false,
            unavailableReason: "Code changes need the BootUI agent's inventory sensor: see the Java Agent panel.",
            counts: null,
            methods: [],
            methodsTotal: 0,
            limitations: []
          }
        })
      )
    )
    wrapper = mount(RunComparison)
    await flushPromises()
    expect(wrapper.find('[data-section="code-changes"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="code-changes-unavailable"]').text()).toContain('need the BootUI agent')

    wrapper.unmount()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({...compared, codeChanges: null})))
    wrapper = mount(RunComparison)
    await flushPromises()
    expect(wrapper.find('[data-section="code-changes"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="code-changes-unavailable"]').exists()).toBe(false)
  })

  it('offers the impact of a method only, never of a constructor or a lambda change impact cannot check', async () => {
    const method = (name, descriptor) => ({
      key: `com.example.OrderService#${name}${descriptor}`,
      className: 'com.example.OrderService',
      name,
      descriptor,
      change: 'CHANGED',
      status: 'EXECUTED',
      notTrackedReason: null,
      routes: [],
      routesTotal: 0,
      routesNote: null
    })
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          ...compared,
          codeChanges: {
            available: true,
            unavailableReason: null,
            counts: {changed: 3, added: 0, removed: 0, executed: 3, notExecuted: 0},
            methods: [
              method('<init>', '(Ljava/lang/String;)V'),
              method('lambda$total$0', '(J)J'),
              method('total', '(J)J')
            ],
            methodsTotal: 3,
            limitations: []
          }
        })
      )
    )
    wrapper = mount(RunComparison)
    await flushPromises()

    const rows = wrapper.findAll('[data-section="code-changes"] .insight-comparison-row')
    expect(rows).toHaveLength(3)
    expect(rows.map((row) => row.find('.insight-comparison-impact').exists())).toEqual([false, false, true])
  })

  it('says what changed outside the JVM per sensor, and why a sensor or the whole section is not compared', async () => {
    const withSideEffects = {
      ...compared,
      sideEffects: {
        available: true,
        unavailableReason: null,
        partial: false,
        sensors: [
          {sensor: 'network', status: 'COMPARED', reason: null, added: 1, removed: 1, notExercised: 0},
          {sensor: 'files', status: 'COMPARED', reason: null, added: 0, removed: 0, notExercised: 1},
          {
            sensor: 'processes',
            status: 'NOT_COMPARED',
            reason: 'Not compared: processes was not recording the whole previous run: it was not claimed.',
            added: 0,
            removed: 0,
            notExercised: 0
          },
          {sensor: 'environment', status: 'COMPARED', reason: null, added: 0, removed: 0, notExercised: 0}
        ],
        changes: [
          {
            sensor: 'network',
            kind: 'connect',
            target: 'api.example.com:443',
            scope: 'route',
            owner: 'GET /api/orders',
            change: 'ADDED',
            client: null,
            count: 2,
            sentence: '`GET /api/orders` now connects to `api.example.com:443`.'
          },
          {
            sensor: 'network',
            kind: 'connect',
            target: 'old.example.com:443',
            scope: 'route',
            owner: 'GET /api/orders',
            change: 'REMOVED',
            client: null,
            count: 1,
            sentence: '`GET /api/orders` no longer connects to `old.example.com:443`.'
          },
          {
            sensor: 'files',
            kind: 'read',
            target: '/tmp/{file}',
            scope: 'route',
            owner: 'GET /reports',
            change: 'NOT_EXERCISED',
            client: null,
            count: 1,
            sentence: '`GET /reports` read `/tmp/{file}` in the previous run, and was not exercised in this run.'
          }
        ],
        changesTotal: 5,
        limitations: ['Side effects are compared by name and normalized, masked pattern only.']
      }
    }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(withSideEffects)))
    wrapper = mount(RunComparison)
    await flushPromises()

    expect(wrapper.findAll('[data-section]').map((section) => section.attributes('data-section'))).toEqual([
      'side-effects',
      'behavior',
      'edges',
      'restart'
    ])
    const outside = wrapper.find('[data-section="side-effects"]')
    expect(outside.find('h3').text()).toContain('Outside the JVM')
    expect(outside.find('[data-sensor="network"]').text().replace(/\s+/g, ' ')).toBe(
      'Network · compared · 1 new · 1 gone'
    )
    expect(outside.find('[data-sensor="files"]').text()).toContain('1 not exercised')
    expect(outside.find('[data-sensor="processes"]').classes()).toContain('text-muted')
    expect(outside.find('[data-sensor="processes"]').text()).toContain('it was not claimed')
    const rows = outside.findAll('.insight-comparison-row')
    expect(rows.map((row) => row.find('.visually-hidden').text())).toEqual(['New:', 'Gone:', 'Not exercised:'])
    expect(rows[0].find('code').text()).toBe('GET /api/orders')
    expect(outside.text()).toContain('2 more not listed: see the Side Effects panel.')
    expect(outside.find('details').text()).toContain('masked pattern only')

    wrapper.unmount()
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          ...compared,
          sideEffects: {
            available: false,
            unavailableReason: 'The previous run kept no side effects.',
            partial: false,
            sensors: [],
            changes: [],
            changesTotal: 0,
            limitations: []
          }
        })
      )
    )
    wrapper = mount(RunComparison)
    await flushPromises()
    expect(wrapper.find('[data-section="side-effects"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="side-effects-unavailable"]').text()).toBe(
      'Side effects not compared: The previous run kept no side effects.'
    )

    wrapper.unmount()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({...compared, sideEffects: null})))
    wrapper = mount(RunComparison)
    await flushPromises()
    expect(wrapper.find('[data-testid="side-effects-unavailable"]').exists()).toBe(false)
  })

  it('shows a failed load as its message, never as an object', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Request failed with status 403')))
    wrapper = mount(RunComparison)
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toBe('Unable to load the run comparison: Request failed with status 403')
    expect(alert.text()).not.toContain('{')
  })

  it('compares with a chosen kept run', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(compared))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(RunComparison)
    await flushPromises()

    const options = wrapper.findAll('option').map((option) => option.text())
    expect(options).toContain('Run 1 · 40 requests · ended ' + options[2].split('ended ')[1])
    expect(options[2]).toContain('baseline file')
    await wrapper.find('select').setValue('run-1')
    await flushPromises()

    expect(String(fetchMock.mock.calls.at(-1)[0])).toContain('api/runtime-insights/comparison?run=run-1')
  })

  it('labels hidden request totals instead of presenting them as zero traffic', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          ...compared,
          previous: {...compared.previous, requests: 0},
          runs: compared.runs.map((run) => ({...run, requests: 0})),
          limitations: ['Facts are not compared because http-exchanges is disabled.']
        })
      )
    )
    wrapper = mount(RunComparison)
    await flushPromises()
    expect(wrapper.find('.insight-comparison-against').text()).toContain('request count hidden')
    expect(
      wrapper
        .findAll('option')
        .slice(1)
        .every((option) => option.text().includes('request count hidden'))
    ).toBe(true)
    expect(wrapper.text()).not.toContain('0 requests')
    expect(wrapper.find('details').text()).toContain('not compared because http-exchanges is disabled')
  })

  it('says why a run could not be compared, never that nothing changed', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          ...compared,
          status: 'NOT_COMPARABLE',
          reason: 'The data sources differ: dataSource jdbc:h2:mem before, dataSource jdbc:postgresql://localhost now.',
          notComparableReasons: [
            'The data sources differ: dataSource jdbc:h2:mem before, dataSource jdbc:postgresql://localhost now.',
            'The active profiles differ: dev before, docker now.'
          ],
          behavior: [],
          edges: [],
          restartCost: {status: 'UNAVAILABLE', reason: 'The runs are not comparable.', beans: []}
        })
      )
    )
    wrapper = mount(RunComparison)
    await flushPromises()

    expect(wrapper.text()).toContain('Not comparable')
    expect(wrapper.find('.insight-comparison-reason').text()).toContain('The data sources differ')
    expect(wrapper.text()).toContain('The active profiles differ')
    expect(wrapper.text()).not.toContain('No route changed')
    expect(wrapper.find('[data-section]').exists()).toBe(false)
  })

  it('says when every route ran the same work', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse({...compared, behavior: [], edges: [], restartCost: {status: 'UNAVAILABLE', beans: []}})
        )
    )
    wrapper = mount(RunComparison)
    await flushPromises()

    expect(wrapper.text()).toContain('No eligible route or execution changed what it ran, called, or raised.')
  })

  it('shows why restart timing is unavailable for a selected non-adjacent run', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({
          ...compared,
          restartCost: {status: 'UNAVAILABLE', reason: 'Restart cost compares adjacent restarts only.', beans: []}
        })
      )
    )
    wrapper = mount(RunComparison)
    await flushPromises()
    expect(wrapper.get('[data-testid="restart-unavailable"]').text()).toContain('adjacent restarts only')
    expect(wrapper.find('[data-section="restart"]').exists()).toBe(false)
  })
})

describe('comparisonSummary', () => {
  it('names the changes since the run compared with, or the status otherwise', async () => {
    const {comparisonSummary} = await import('../../utils/runComparison.js')
    expect(comparisonSummary(compared)).toBe('2 changes since run 4')
    expect(comparisonSummary({...compared, behavior: [], edges: []})).toBe('No change in behavior since run 4')
    expect(comparisonSummary({...compared, status: 'INSUFFICIENT'})).toBe('Compared with run 4: needs more traffic')
    expect(comparisonSummary({...compared, status: 'NO_PREVIOUS_RUN', previous: null})).toBe('No previous run')
    expect(comparisonSummary(null)).toBeNull()
  })
})
