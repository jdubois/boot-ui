import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {ref} from 'vue'
import {createMemoryHistory, createRouter, RouterLink} from 'vue-router'

import RuntimeInsights from './RuntimeInsights.vue'

const routeState = vi.hoisted(() => ({query: {}}))
vi.mock('vue-router', async (importOriginal) => ({...(await importOriginal()), useRoute: () => routeState}))

const report = {
  available: true,
  unavailableReason: null,
  window: {
    runId: 'run-1',
    firstEventAt: 1_700_000_000_000,
    lastEventAt: 1_700_000_060_000,
    retainedEvents: 120,
    requests: 9,
    evictedEvents: 0,
    droppedEvents: 0
  },
  coverage: [{source: 'http', events: 9, byRequestId: 9, byExecutionId: 0, byTraceId: 0, unlinked: 0, dropped: 0}],
  checks: [
    {
      kind: 'repeated-selects',
      title: 'Repeated SELECTs',
      status: 'EVALUATED',
      eligibleRequests: 9,
      findings: 1,
      reason: null
    },
    {
      kind: 'event-loop-blocking',
      title: 'Blocking on event loops',
      status: 'NOT_APPLICABLE',
      eligibleRequests: 0,
      findings: 0,
      reason: 'Spring MVC serves requests on worker threads, not on event loops.'
    }
  ],
  observations: [
    {
      id: 'repeated-selects:0123456789',
      kind: 'repeated-selects',
      subject: 'GET /api/owners/{id}',
      status: 'OBSERVED',
      sentence: '`GET /api/owners/{id}` repeated `select * from pets where owner_id = ?` in 3 of 9 requests.',
      eligible: 9,
      affected: 3,
      minimumTier: 'REQUEST_ID',
      whatToCheck: ['Fetch the pets with their owner in one query.'],
      exemplarRequestIds: ['r-1', 'r-2'],
      evidenceRows: 1,
      limitations: ['Counts statements the journal retained.']
    }
  ],
  limitations: [],
  notExercised: ['DELETE /api/owners/{id}'],
  notExercisedOmitted: 2
}

const detail = {
  available: true,
  unavailableReason: null,
  observation: report.observations[0],
  columns: ['Request', 'Executions'],
  rows: [{cells: ['r-1', '6']}],
  truncated: 4
}

function jsonResponse(body) {
  return {ok: true, status: 200, json: () => Promise.resolve(body)}
}

function mountPanel(props = {}, panels = null, router = null) {
  return mount(RuntimeInsights, {
    props,
    global: {
      provide: {panels: ref(panels)},
      plugins: router ? [router] : [],
      stubs: {
        'router-link': router
          ? RouterLink
          : {
              props: ['to'],
              template: '<a :data-to="JSON.stringify(to)"><slot /></a>'
            }
      }
    }
  })
}

// v-show hides a tab panel that is not selected.
function shown(element) {
  return !(element.attributes('style') ?? '').includes('display: none')
}

async function openRow(wrapper, text = '') {
  for (const group of wrapper.findAll('.insight-group-toggle')) {
    if (group.attributes('aria-expanded') !== 'true') {
      await group.trigger('click')
      await flushPromises()
    }
    const select = wrapper.find('.insight-observation-select')
    if (select.exists()) {
      const option = select.findAll('option').find((candidate) => candidate.text().includes(text))
      if (!option) continue
      await select.setValue(option.element.value)
      await flushPromises()
      return group
    }
    if (wrapper.get('.insight-observation-subject').text().includes(text)) return group
  }
  throw new Error(`No observation matches ${text}`)
}

describe('Runtime Insights panel', () => {
  let wrapper

  afterEach(() => {
    routeState.query = {}
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('does not call the API when the manifest reports the panel unavailable', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountPanel({
      panel: {id: 'runtime-insights', enabled: true, available: false, unavailableReason: 'journal disabled'}
    })
    await flushPromises()

    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('journal disabled')
  })

  it('opens the JFR profile tab from a route breakdown without starting a recording', async () => {
    const breakdown = {
      ...report.observations[0],
      id: 'route-time-breakdown:orders',
      kind: 'route-time-breakdown',
      subject: 'GET /api/orders/{id}',
      listed: true
    }
    const fetchMock = vi.fn((url) =>
      Promise.resolve(
        jsonResponse(
          String(url).includes('/insights/')
            ? {...detail, observation: breakdown}
            : {...report, observations: [breakdown]}
        )
      )
    )
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountPanel({}, {panels: [{id: 'code-paths', available: true, enabled: true, readOnly: true}]})
    await flushPromises()
    await openRow(wrapper, 'GET /api/orders/{id}')

    const section = wrapper.get('.insight-performance-deep-dives')
    expect(section.text()).toContain('Open the JFR profile tab')
    expect(section.text()).toContain('recording starts only when you choose Profile resources')
    expect(section.text()).toContain('not an exact request replay')
    expect(JSON.parse(section.get('a').attributes('data-to'))).toEqual({
      path: '/code-paths',
      query: {route: 'GET /api/orders/{id}'}
    })
    await section.get('button').trigger('click')
    expect(shown(wrapper.get('#insights-panel-profile'))).toBe(true)
    expect(wrapper.get('#insights-tab-profile').text()).toContain('JFR profile')
    expect(fetchMock.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false)
  })

  it.each([
    {
      name: 'detached agent',
      panel: {available: false, unavailableReason: "Requires the BootUI agent's code-paths sensor: no agent attached."},
      tip: true
    },
    {
      name: 'disabled sensor',
      panel: {
        available: false,
        unavailableReason: "Requires the BootUI agent's code-paths sensor: the sensor is disabled."
      },
      tip: true
    },
    {
      name: 'disabled panel',
      panel: {enabled: false, available: false, unavailableReason: "Requires the BootUI agent's code-paths sensor."},
      reason: 'bootui.panels.code-paths.enabled=false'
    },
    {
      name: 'unavailable dependency',
      panel: {available: false, unavailableReason: 'The HTTP Exchanges panel is disabled.'},
      reason: 'The HTTP Exchanges panel is disabled.'
    },
    {name: 'missing panel', panel: null, reason: 'Code Paths is not available in this runtime.'},
    {name: 'unknown manifest', manifest: null, reason: 'Code Paths availability is not known yet.'}
  ])('shows honest guidance for $name without a Code Paths action', async ({panel, manifest, reason, tip}) => {
    const breakdown = {...report.observations[0], kind: 'route-time-breakdown'}
    const fetchMock = vi.fn((url) =>
      Promise.resolve(
        jsonResponse(
          String(url).includes('/insights/')
            ? {...detail, observation: breakdown}
            : {...report, observations: [breakdown]}
        )
      )
    )
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountPanel(
      {},
      manifest === null
        ? null
        : {
            panels: [
              ...(panel ? [{id: 'code-paths', enabled: true, ...panel}] : []),
              {id: 'java-agent', enabled: true, available: true}
            ]
          }
    )
    await flushPromises()
    await openRow(wrapper)

    const section = wrapper.get('.insight-performance-deep-dives')
    expect(section.text()).toContain(reason ?? panel.unavailableReason)
    expect(section.find('.insight-agent-tip').exists()).toBe(!!tip)
    expect(section.findAll('a').some((link) => JSON.parse(link.attributes('data-to'))?.path === '/code-paths')).toBe(
      false
    )
    expect(section.findAll('a').length).toBe(tip ? 1 : 0)
    if (tip) expect(section.get('a').attributes('data-to')).toBe('"/java-agent"')
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('api/code-paths'))).toBe(false)
    await section.get('button').trigger('click')
    expect(shown(wrapper.get('#insights-panel-profile'))).toBe(true)
    expect(fetchMock.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false)
  })

  it('keeps the agent tip without a setup link when the Java Agent panel is disabled', async () => {
    const breakdown = {...report.observations[0], kind: 'route-time-breakdown'}
    vi.stubGlobal(
      'fetch',
      vi.fn((url) =>
        Promise.resolve(
          jsonResponse(
            String(url).includes('/insights/')
              ? {...detail, observation: breakdown}
              : {...report, observations: [breakdown]}
          )
        )
      )
    )
    wrapper = mountPanel(
      {},
      {
        panels: [
          {id: 'code-paths', available: false, unavailableReason: "Requires the BootUI agent's code-paths sensor."},
          {id: 'java-agent', enabled: false, available: true}
        ]
      }
    )
    await flushPromises()
    await openRow(wrapper)
    expect(wrapper.get('.insight-agent-tip').text()).toContain('method-level timing')
    expect(wrapper.get('.insight-performance-deep-dives').findAll('a')).toHaveLength(0)
  })

  it.each(['GET /api/orders/{id}?name=a+b&view=#résumé', null])(
    'preserves the real route identity or explains its absence (%s)',
    async (subject) => {
      const breakdown = {...report.observations[0], kind: 'route-time-breakdown', subject}
      vi.stubGlobal(
        'fetch',
        vi.fn((url) =>
          Promise.resolve(
            jsonResponse(
              String(url).includes('/insights/')
                ? {...detail, observation: breakdown}
                : {...report, observations: [breakdown]}
            )
          )
        )
      )
      const router = createRouter({
        history: createMemoryHistory('/host/dev-console/'),
        routes: [{path: '/:pathMatch(.*)*', component: {template: '<div />'}}]
      })
      await router.push('/runtime-insights')
      wrapper = mountPanel({}, {panels: [{id: 'code-paths', available: true, enabled: true}]}, router)
      await flushPromises()
      await openRow(wrapper)
      const section = wrapper.get('.insight-performance-deep-dives')
      if (subject) {
        const link = section.get('a')
        const href = link.attributes('href')
        expect(href).toMatch(/^\/host\/dev-console\/code-paths\?route=/)
        expect(router.resolve(href.replace('/host/dev-console', '')).query.route).toBe(subject)
        await link.trigger('click')
        await flushPromises()
        expect(router.currentRoute.value.query.route).toBe(subject)
      } else {
        expect(section.text()).toContain('This observation has no known route')
        expect(section.find('a').exists()).toBe(false)
      }
    }
  )

  it('shows the window, the coverage, the selected observation with its evidence, and checks that did not run', async () => {
    const fetchMock = vi.fn((url) =>
      Promise.resolve(jsonResponse(String(url).includes('/insights/') ? detail : report))
    )
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.get('#insight-verdict-title').text()).toBe('1 observation recorded across 9 requests')
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/insights/'))).toBe(false)
    expect(wrapper.find('.insight-detail').exists()).toBe(false)
    await openRow(wrapper)

    const text = wrapper.text()
    expect(text).toContain('9 requests')
    expect(text).toContain('request id 100 %')
    expect(wrapper.find('.insight-row.open').text()).toContain('GET /api/owners/{id}')
    expect(wrapper.find('#insight-sentence').text()).toContain('repeated')
    expect(wrapper.find('#insight-sentence').text()).not.toContain('`')
    expect(wrapper.find('#insight-sentence code').text()).toBe('GET /api/owners/{id}')
    expect(text).toContain('Fetch the pets with their owner in one query.')
    expect(wrapper.find('.insight-evidence').text()).toContain('Executions')
    expect(text).toContain('4 more rows not shown.')
    expect(wrapper.find('.insight-unrun').text()).toContain('Spring MVC serves requests on worker threads')
    expect(fetchMock.mock.calls.map(([url]) => String(url))).toContain(
      'api/runtime-insights/insights/repeated-selects%3A0123456789'
    )
  })

  it('never shows the external validation of a kind, which stays in the plan (M4-20)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => Promise.resolve(jsonResponse(String(url).includes('/insights/') ? detail : report)))
    )
    wrapper = mountPanel()
    await flushPromises()
    await openRow(wrapper)

    expect(wrapper.find('.insight-group-title').text()).toBe('Repeated SELECTs')
    expect(wrapper.text()).not.toMatch(/validat/i)
  })

  it('names the comparison in the verdict and opens it in the Changes tab', async () => {
    const comparison = {
      status: 'COMPARED',
      reason: null,
      current: {runId: 'run-5', ordinal: 5, startedAt: 1, endedAt: null, requests: 9, source: 'CURRENT'},
      previous: {runId: 'run-4', ordinal: 4, startedAt: 1, endedAt: 2, requests: 9, source: 'MEMORY'},
      runs: [],
      notComparableReasons: [],
      behavior: [
        {kind: 'route-new', change: 'ADDED', sentence: '`GET /api/pets` served 3 requests, and none in run 4.'}
      ],
      edges: [],
      restartCost: {status: 'UNAVAILABLE', reason: 'Quarkus', beans: []},
      latency: [],
      limitations: []
    }
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        const target = String(url)
        if (target.includes('/comparison')) return Promise.resolve(jsonResponse(comparison))
        return Promise.resolve(
          jsonResponse(target.includes('/insights/') ? detail : {...report, notExercised: ['DELETE /api/pets/{id}']})
        )
      })
    )
    wrapper = mountPanel()
    await flushPromises()

    expect(shown(wrapper.get('#insights-panel-changes'))).toBe(false)
    const link = wrapper.get('.insight-comparison-link')
    expect(link.text()).toBe('1 framework change since run 4')
    await link.trigger('click')
    expect(shown(wrapper.get('#insights-panel-changes'))).toBe(true)
    expect(wrapper.get('#insights-tab-changes').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('#insights-panel-changes').find('.insight-comparison').exists()).toBe(true)
    expect(shown(wrapper.get('#insights-panel-findings'))).toBe(false)
  })

  it('says why nothing is listed rather than reading as healthy', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({...report, observations: []})))
    wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.get('#insight-verdict-title').text()).toBe('Nothing to report across 9 requests')
    expect(wrapper.get('.insight-empty').text()).toContain('1 of 2 checks ran')
    const elsewhere = wrapper.get('.insight-elsewhere')
    expect(elsewhere.text().replace(/\s+/g, ' ')).toBe(
      'Related evidence is also available in other panels: Live Activity (why a route is slow), Exceptions, ' +
        'Database Connection Pools, AI Framework, and Memory (garbage collection and heap).'
    )
    expect(elsewhere.findAll('a')).toHaveLength(5)
  })

  it('links a partial comparison from the verdict to its limits without claiming no change', async () => {
    const comparison = {
      status: 'PARTIAL',
      reason: 'Some journal evidence could not be compared.',
      current: {runId: 'run-5', ordinal: 5, requests: 9, source: 'CURRENT'},
      previous: {runId: 'run-4', ordinal: 4, requests: 9, source: 'MEMORY'},
      runs: [],
      notComparableReasons: [],
      behavior: [],
      edges: [],
      restartCost: {status: 'UNAVAILABLE', reason: 'No adjacent restart.', beans: []},
      latency: [],
      limitations: ['The sql source dropped 3 events; statement changes are not compared.']
    }
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => Promise.resolve(jsonResponse(String(url).includes('/comparison') ? comparison : report)))
    )
    wrapper = mountPanel()
    await flushPromises()

    const link = wrapper.get('.insight-comparison-link')
    expect(link.text()).toBe('Partly compared with run 4: incomplete evidence')
    await link.trigger('click')
    expect(shown(wrapper.get('#insights-panel-changes'))).toBe(true)
    expect(wrapper.get('#insights-tab-changes').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('#insights-panel-changes').text()).toContain('The sql source dropped 3 events')
    expect(wrapper.text()).not.toContain('No change in behavior')
    expect(wrapper.text()).not.toContain('No eligible route or execution changed')
  })

  it('never counts a check that could not see its evidence as one that ran', async () => {
    const unavailable = {
      kind: 'safe-method-dml',
      title: 'Writes from safe methods',
      status: 'UNAVAILABLE',
      eligibleRequests: 0,
      findings: 0,
      reason: "This application's database access is not recorded: it uses R2DBC."
    }
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse({...report, observations: [], checks: [...report.checks, unavailable]}))
    )
    wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.get('.insight-empty').text()).toContain('1 of 3 checks ran')
    const unrun = wrapper.find('.insight-unrun')
    expect(unrun.text()).toContain('Writes from safe methods · Unavailable')
    expect(unrun.text()).toContain('it uses R2DBC')
  })

  it('counts a collection-based check as run at zero requests and explains a check with no eligible work', async () => {
    const heap = {
      kind: 'heap-growth-after-gc',
      title: 'Heap growth after GC',
      status: 'EVALUATED',
      eligibleRequests: 0,
      findings: 0,
      reason: 'Examined 4 collections that reclaimed old-generation space; none met the growth threshold.'
    }
    const insufficient = {
      kind: 'gc-inflated-latency',
      title: 'GC-inflated latency',
      status: 'INSUFFICIENT',
      eligibleRequests: 0,
      findings: 0,
      reason: 'No eligible work was recorded for this check.'
    }
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(jsonResponse({...report, observations: [], checks: [...report.checks, heap, insufficient]}))
    )
    wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.get('.insight-empty').text()).toContain('2 of 4 checks ran')
    const checks = wrapper.find('.insight-unrun')
    expect(checks.text()).toContain('Checks and their limits')
    expect(checks.text()).toContain('Heap growth after GC · Ran')
    expect(checks.text()).toContain('GC-inflated latency · Not enough evidence')
    expect(checks.text()).not.toContain('INSUFFICIENT')
    expect(checks.text()).toContain('No eligible work was recorded')
  })

  it('states a disabled journal and an empty run', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse({...report, available: false, unavailableReason: 'set bootui.runtime-journal.enabled=true'})
        )
    )
    wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.text()).toContain('set bootui.runtime-journal.enabled=true')
    wrapper.unmount()

    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse({...report, window: {...report.window, requests: 0, retainedEvents: 0}, observations: []})
        )
    )
    wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.text()).toContain('No HTTP requests recorded in this run yet')
  })

  it('shows job and listener observations when no HTTP requests were recorded', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url) =>
        Promise.resolve(
          jsonResponse(
            String(url).includes('/insights/')
              ? detail
              : {
                  ...report,
                  window: {...report.window, requests: 0, retainedEvents: 5},
                  observations: [{...report.observations[0], subject: '@Scheduled OrderJob.run'}]
                }
          )
        )
      )
    )
    wrapper = mountPanel()
    await flushPromises()
    await openRow(wrapper)
    expect(wrapper.text()).toContain('@Scheduled OrderJob.run')
    expect(wrapper.find('.insight-row.open').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('No HTTP requests recorded in this run yet')
  })

  it('shows a heap-growth finding even when the run recorded no HTTP requests', async () => {
    const heap = {
      ...report.observations[0],
      id: 'heap-growth-after-gc:heap',
      kind: 'heap-growth-after-gc',
      subject: 'Heap',
      sentence: 'Old-generation occupancy rose after garbage collection.',
      eligible: 0,
      affected: 0,
      exemplarRequestIds: []
    }
    vi.stubGlobal(
      'fetch',
      vi.fn((url) =>
        Promise.resolve(
          jsonResponse(
            String(url).includes('/insights/')
              ? {...detail, observation: heap}
              : {
                  ...report,
                  window: {...report.window, requests: 0, retainedEvents: 4},
                  checks: [
                    ...report.checks,
                    {
                      kind: 'heap-growth-after-gc',
                      title: 'Heap growth after GC',
                      status: 'EVALUATED',
                      eligibleRequests: 0,
                      findings: 1,
                      reason: null
                    }
                  ],
                  observations: [heap]
                }
          )
        )
      )
    )
    wrapper = mountPanel()
    await flushPromises()

    await openRow(wrapper)
    expect(wrapper.get('.insight-row.open').text()).toContain('Heap')
    expect(wrapper.get('#insight-verdict-title').text()).toBe('1 observation recorded across 4 retained events')
    await openRow(wrapper)
    expect(wrapper.text()).toContain('Old-generation occupancy rose after garbage collection.')
    expect(wrapper.text()).not.toContain('No HTTP requests recorded in this run yet')
  })

  it('filters observations by search', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => Promise.resolve(jsonResponse(String(url).includes('/insights/') ? detail : report)))
    )
    wrapper = mountPanel()
    await flushPromises()

    await wrapper.find('.insight-search').setValue('no-such-route')
    expect(wrapper.text()).toContain('No observations match your filters.')
  })

  it('shows short routes alongside other observations without a widening control or hidden-row labels', async () => {
    const fast = {
      ...report.observations[0],
      id: 'route-time-breakdown:fast',
      kind: 'route-time-breakdown',
      subject: 'GET /api/fast',
      sentence: '`GET /api/fast`: warm median 3 ms over 5 requests.',
      listed: false,
      unlistedReason: 'Its warm median is under 20 ms.'
    }
    const withFast = {
      ...report,
      checks: [...report.checks, {...report.checks[0], kind: 'route-time-breakdown', title: 'Route time breakdown'}],
      observations: [{...report.observations[0], listed: true, unlistedReason: null}, fast]
    }
    vi.stubGlobal(
      'fetch',
      vi.fn((url) =>
        Promise.resolve(jsonResponse(String(url).includes('/insights/') ? {...detail, observation: fast} : withFast))
      )
    )
    wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.findAll('.insight-group-toggle')).toHaveLength(2)
    expect(wrapper.findAll('.insight-row.open')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('Show all routes')
    expect(wrapper.text()).not.toContain('Not listed by default')
    expect(wrapper.get('#insight-result-count').text()).toBe('All 2 observations, in 2 groups. Nothing excluded.')
    await openRow(wrapper, 'GET /api/fast')
    expect(wrapper.get('.insight-detail').text()).not.toContain('Not listed by default')
  })

  it('keeps the open observation when a refresh leaves it out of the default list', async () => {
    const slow = {
      ...report.observations[0],
      id: 'route-time-breakdown:slow',
      kind: 'route-time-breakdown',
      subject: 'GET /api/slow',
      listed: true,
      unlistedReason: null
    }
    const first = {...report, observations: [{...report.observations[0], listed: true}, slow]}
    const refreshed = {
      ...first,
      observations: [first.observations[0], {...slow, listed: false, unlistedReason: 'Its warm median is under 20 ms.'}]
    }
    let current = first
    vi.stubGlobal(
      'fetch',
      vi.fn((url) =>
        Promise.resolve(jsonResponse(String(url).includes('/insights/') ? {...detail, observation: slow} : current))
      )
    )
    wrapper = mountPanel()
    await flushPromises()
    await openRow(wrapper, 'GET /api/slow')

    current = refreshed
    await wrapper.findComponent({name: 'PanelHeader'}).vm.$emit('refresh')
    await flushPromises()

    expect(wrapper.get('.insight-row.open').text()).toContain('GET /api/slow')
    expect(wrapper.get('.insight-row.open').text()).not.toContain('Not listed by default')
    expect(wrapper.find('.insight-unlisted').exists()).toBe(false)
  })

  it('keeps every group available when a deep link names an observation the default list leaves out', async () => {
    const fast = {
      ...report.observations[0],
      id: 'route-time-breakdown:fast',
      kind: 'route-time-breakdown',
      subject: 'GET /api/fast',
      listed: false,
      unlistedReason: 'Its warm median is under 20 ms.'
    }
    routeState.query = {insight: fast.id}
    vi.stubGlobal(
      'fetch',
      vi.fn((url) =>
        Promise.resolve(
          jsonResponse(
            String(url).includes('/insights/')
              ? {...detail, observation: fast}
              : {...report, observations: [...report.observations, fast]}
          )
        )
      )
    )
    wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.findAll('.insight-group-toggle')).toHaveLength(2)
    expect(wrapper.findAll('.insight-row.open')).toHaveLength(1)
    expect(wrapper.get('.insight-row.open').text()).toContain('GET /api/fast')
  })

  it('shows a report made entirely of rows excluded from agent defaults, including legacy all links', async () => {
    const fast = {...report.observations[0], listed: false, unlistedReason: 'Its warm median is under 20 ms.'}
    vi.stubGlobal(
      'fetch',
      vi.fn((url) =>
        Promise.resolve(jsonResponse(String(url).includes('/insights/') ? detail : {...report, observations: [fast]}))
      )
    )
    routeState.query = {all: '1'}
    wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.findAll('.insight-group-toggle')).toHaveLength(1)
    expect(wrapper.findAll('.insight-row.open')).toHaveLength(0)
    expect(wrapper.get('#insight-verdict-title').text()).toBe('1 observation recorded across 9 requests')
    expect(wrapper.find('.insight-no-matches').exists()).toBe(false)
  })

  it('lists the declared routes this run never reached', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => Promise.resolve(jsonResponse(String(url).includes('/insights/') ? detail : report)))
    )
    wrapper = mountPanel()
    await flushPromises()

    const section = wrapper.get('.insight-not-exercised')
    expect(section.text()).toContain('Not exercised in this run')
    expect(section.text()).toContain('DELETE /api/owners/{id}')
    expect(section.text()).toContain('2 more routes not listed.')
  })

  it('opens the observation and search a deep link names', async () => {
    const second = {...report.observations[0], id: 'repeated-selects:9999999999', subject: 'GET /api/pets'}
    routeState.query = {q: '/api/pets', insight: second.id}
    const fetchMock = vi.fn((url) =>
      Promise.resolve(
        jsonResponse(
          String(url).includes('/insights/')
            ? {...detail, observation: second}
            : {
                ...report,
                observations: [...report.observations, second]
              }
        )
      )
    )
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.get('.insight-search').element.value).toBe('/api/pets')
    expect(wrapper.get('.insight-row.open').text()).toContain('GET /api/pets')
    expect(fetchMock.mock.calls.map(([url]) => String(url))).toContain(
      'api/runtime-insights/insights/repeated-selects%3A9999999999'
    )
  })

  it('opens on the theme a deep link names, without a request count for a run-wide observation', async () => {
    const heap = {
      ...report.observations[0],
      id: 'heap-growth-after-gc:heap',
      kind: 'heap-growth-after-gc',
      subject: 'Heap',
      sentence: 'Old-generation occupancy after the 4 collections that reclaimed it rose from 40.0 MiB to 60.0 MiB.',
      eligible: 0,
      affected: 0,
      exemplarRequestIds: []
    }
    routeState.query = {theme: 'memory'}
    const withHeap = {
      ...report,
      checks: [...report.checks, {...report.checks[0], kind: 'heap-growth-after-gc', title: 'Heap growth after GC'}],
      observations: [...report.observations, heap]
    }
    vi.stubGlobal(
      'fetch',
      vi.fn((url) =>
        Promise.resolve(jsonResponse(String(url).includes('/insights/') ? {...detail, observation: heap} : withHeap))
      )
    )
    wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.findAll('.insight-row.open')).toHaveLength(0)
    expect(wrapper.get('.insight-topic').element.value).toBe('memory')
    await openRow(wrapper, 'Heap')
    expect(wrapper.get('.insight-row.open').text()).toContain('Heap')
    expect(wrapper.get('.insight-detail').text()).not.toContain('of 0 requests')
  })

  it('draws the shares of a breakdown as bars, the largest phase emphasized and an unshared row left bare', async () => {
    const breakdown = {
      ...detail,
      columns: ['Phase', 'Total (ms)', 'Share', 'Median per request'],
      rows: [
        {cells: ['SQL', '75', '60 %', '15']},
        {cells: ['Response write', '25', '20 %', '5.0']},
        {cells: ['Overlapping calls, counted once above', '25', '', '']}
      ],
      truncated: 0
    }
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => Promise.resolve(jsonResponse(String(url).includes('/insights/') ? breakdown : report)))
    )
    wrapper = mountPanel()
    await flushPromises()
    await openRow(wrapper)

    const bars = wrapper.findAll('.insight-share-bar')
    expect(bars.map((bar) => bar.attributes('style'))).toEqual(['width: 60%;', 'width: 20%;'])
    expect(bars[0].classes()).toContain('insight-share-bar-top')
    expect(wrapper.get('.insight-evidence-top').text()).toContain('SQL')
    expect(wrapper.findAll('.insight-share-value').map((value) => value.text())).toEqual(['60 %', '20 %'])
  })

  it('previews the open observation as Markdown for an AI without another request', async () => {
    const fetchMock = vi.fn((url) =>
      Promise.resolve(jsonResponse(String(url).includes('/insights/') ? detail : report))
    )
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountPanel()
    await flushPromises()
    await openRow(wrapper)
    const calls = fetchMock.mock.calls.length

    await wrapper.get('.insight-copy-ai').trigger('click')

    const preview = wrapper.get('textarea').element.value
    expect(preview).toContain('# BootUI runtime insight: Repeated SELECTs')
    expect(preview).toContain('`GET /api/owners/{id}` repeated `select * from pets where owner_id = ?`')
    expect(preview).toContain('| r-1 | 6 |')
    expect(preview).toContain('4 evidence rows beyond the first 1.')
    expect(fetchMock.mock.calls.length).toBe(calls)
  })

  it('reloads the open observation’s evidence when the report refreshes, keeping the rows meanwhile', async () => {
    let evidence = detail
    let current = report
    const fetchMock = vi.fn((url) =>
      Promise.resolve(jsonResponse(String(url).includes('/insights/') ? evidence : current))
    )
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountPanel()
    await flushPromises()
    await openRow(wrapper)
    expect(wrapper.find('.insight-evidence').text()).toContain('r-1')

    current = {
      ...report,
      observations: [{...report.observations[0], affected: 5, evidenceRows: 2}]
    }
    evidence = {...detail, observation: current.observations[0], rows: [{cells: ['r-1', '6']}, {cells: ['r-7', '9']}]}
    await wrapper.findComponent({name: 'PanelHeader'}).vm.$emit('refresh')
    await flushPromises()

    expect(wrapper.find('.insight-evidence').text()).toContain('r-7')
    const detailCalls = fetchMock.mock.calls.filter(([url]) => String(url).includes('/insights/'))
    expect(detailCalls).toHaveLength(2)
  })

  it('labels the evidence as stale and turns Copy for AI off when its refresh fails, until one succeeds', async () => {
    let detailFails = false
    let held = null
    let current = report
    const fetchMock = vi.fn((url) => {
      // Every read parses a new report, as the browser does, so the open observation is a new object each time.
      if (!String(url).includes('/insights/')) return Promise.resolve(jsonResponse(structuredClone(current)))
      const failure = {ok: false, status: 500, json: () => Promise.resolve({message: 'boom'})}
      if (held) return new Promise((resolve) => (held = () => resolve(failure)))
      return Promise.resolve(detailFails ? failure : jsonResponse(detail))
    })
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountPanel()
    await flushPromises()
    await openRow(wrapper)
    expect(wrapper.find('.insight-evidence-stale').exists()).toBe(false)
    expect(wrapper.get('.insight-copy-ai').attributes('disabled')).toBeUndefined()
    await wrapper.get('.insight-copy-ai').trigger('click')
    expect(wrapper.find('textarea').exists()).toBe(true)

    current = {...report, observations: [{...report.observations[0], affected: 5, evidenceRows: 2}]}
    detailFails = true
    await wrapper.findComponent({name: 'PanelHeader'}).vm.$emit('refresh')
    await flushPromises()

    // The open preview held the earlier evidence: it closes, so nothing stale can be copied.
    expect(wrapper.find('textarea').exists()).toBe(false)
    const stale = wrapper.get('.insight-evidence-stale')
    expect(stale.attributes('role')).toBe('alert')
    expect(stale.text()).toContain('Unable to refresh this observation’s evidence')
    expect(stale.text()).toContain('from an earlier refresh and may not match the sentence and counts above')
    expect(wrapper.find('.insight-evidence').text()).toContain('r-1')
    expect(wrapper.get('.insight-copy-ai').attributes('disabled')).toBeDefined()

    // Another failed refresh keeps the same warning on screen throughout, so it is not announced again.
    const warning = stale.text()
    held = true
    await wrapper.findComponent({name: 'PanelHeader'}).vm.$emit('refresh')
    await flushPromises()
    expect(typeof held).toBe('function')
    expect(wrapper.get('.insight-evidence-stale').text()).toBe(warning)
    held()
    held = null
    await flushPromises()
    expect(wrapper.get('.insight-evidence-stale').text()).toBe(warning)

    detailFails = false
    await wrapper.findComponent({name: 'PanelHeader'}).vm.$emit('refresh')
    await flushPromises()

    expect(wrapper.find('.insight-evidence-stale').exists()).toBe(false)
    expect(wrapper.get('.insight-copy-ai').attributes('disabled')).toBeUndefined()
  })

  it('exports the report it already has as JSON without another request', async () => {
    const fetchMock = vi.fn((url) =>
      Promise.resolve(jsonResponse(String(url).includes('/insights/') ? detail : report))
    )
    vi.stubGlobal('fetch', fetchMock)
    const createObjectURL = vi.fn(() => 'blob:report')
    vi.stubGlobal('URL', {...URL, createObjectURL, revokeObjectURL: vi.fn()})
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    wrapper = mountPanel()
    await flushPromises()
    const calls = fetchMock.mock.calls.length

    await wrapper.get('.insight-export').trigger('click')

    expect(createObjectURL).toHaveBeenCalledTimes(1)
    expect(click).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls.length).toBe(calls)
    click.mockRestore()
  })

  it('opens the Change impact tab, checked, when a deep link names a symbol', async () => {
    routeState.query = {impact: 'OrderService#total'}
    const fetchMock = vi.fn((url) =>
      Promise.resolve(jsonResponse(String(url).includes('/insights/') ? detail : report))
    )
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.get('#insights-tab-impact').attributes('aria-selected')).toBe('true')
    expect(shown(wrapper.get('#insights-panel-impact'))).toBe(true)
    expect(shown(wrapper.get('#insights-panel-changes'))).toBe(false)
    expect(shown(wrapper.get('#insights-panel-findings'))).toBe(false)
    expect(wrapper.get('#insights-panel-impact').find('.insight-impact').exists()).toBe(true)
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('impact?symbol=OrderService%23total'))).toBe(true)
  })

  it("opens Coverage & limits on the app-wide list from Change impact's scoped list", async () => {
    const resolvedImpact = {
      status: 'RESOLVED',
      reason: null,
      symbol: 'ProductRepository',
      node: 'REPOSITORY productRepository',
      candidates: [],
      structuralReach: 2,
      observed: [],
      observedTotal: 0,
      notExercised: [],
      notExercisedTotal: 0,
      notExercisedUndetermined: false,
      sharedResources: [],
      sharedResourcesTotal: 0,
      limitations: []
    }
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        const target = String(url)
        if (target.includes('/impact')) return Promise.resolve(jsonResponse(resolvedImpact))
        return Promise.resolve(jsonResponse(target.includes('/insights/') ? detail : report))
      })
    )
    routeState.query = {impact: 'ProductRepository'}
    wrapper = mount(RuntimeInsights, {
      attachTo: document.body,
      global: {stubs: {'router-link': {template: '<a><slot /></a>'}}}
    })
    await flushPromises()

    await wrapper.get('#insights-panel-impact .insight-impact-coverage-link').trigger('click')
    await flushPromises()

    expect(wrapper.get('#insights-tab-coverage').attributes('aria-selected')).toBe('true')
    expect(shown(wrapper.get('#insights-panel-coverage'))).toBe(true)
    expect(shown(wrapper.get('#insights-panel-impact'))).toBe(false)
    expect(document.activeElement?.id).toBe('insight-not-exercised')
  })

  it('opens the tab a deep link names, and ignores one it does not know', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => Promise.resolve(jsonResponse(String(url).includes('/insights/') ? detail : report)))
    )
    routeState.query = {tab: 'impact'}
    wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.get('#insights-tab-impact').attributes('aria-selected')).toBe('true')

    wrapper.unmount()
    routeState.query = {tab: 'nope'}
    wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.get('#insights-tab-findings').attributes('aria-selected')).toBe('true')
  })

  it("opens Change impact on a changed method from the comparison's See its impact", async () => {
    const comparison = {
      status: 'COMPARED',
      reason: null,
      notComparableReasons: [],
      previous: {runId: 'run-0', ordinal: 4, startedAt: 1, endedAt: 2, closedBy: 'SHUTDOWN'},
      runs: [],
      behavior: [],
      edges: [],
      restartCost: {status: 'UNAVAILABLE', reason: 'Quarkus', beans: []},
      latency: [],
      limitations: [],
      codeChanges: {
        available: true,
        unavailableReason: null,
        counts: {changed: 4, added: 0, removed: 0, executed: 4, notExecuted: 0},
        methods: [
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
          },
          {
            key: 'com.example.OrderService#<init>()V',
            className: 'com.example.OrderService',
            name: '<init>',
            descriptor: '()V',
            change: 'CHANGED',
            status: 'EXECUTED',
            notTrackedReason: null,
            routes: [],
            routesTotal: 0,
            routesNote: null
          }
        ],
        methodsTotal: 4,
        limitations: []
      }
    }
    const fetchMock = vi.fn((url) => {
      const target = String(url)
      if (target.includes('/comparison')) return Promise.resolve(jsonResponse(comparison))
      if (target.includes('/impact')) return Promise.resolve(jsonResponse({status: 'NOT_FOUND', reason: 'Nothing.'}))
      return Promise.resolve(jsonResponse(target.includes('/insights/') ? detail : report))
    })
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(RuntimeInsights, {
      attachTo: document.body,
      global: {stubs: {'router-link': {template: '<a><slot /></a>'}}}
    })
    await flushPromises()

    // Before any check, Change impact offers the changed method too, counting the methods the comparison did not list.
    const picks = wrapper.get('#insights-panel-impact').get('[data-testid="impact-changed"]')
    expect(picks.findAll('button').map((button) => button.text())).toEqual(['OrderService#total'])
    expect(picks.text()).toContain('and 3 more in Changes')

    await wrapper.get('#insights-tab-changes').trigger('click')
    await wrapper.get('.insight-comparison-impact').trigger('click')
    await flushPromises()

    expect(wrapper.get('#insights-tab-impact').attributes('aria-selected')).toBe('true')
    expect(shown(wrapper.get('#insights-panel-impact'))).toBe(true)
    expect(document.activeElement?.id).toBe('insights-panel-impact')
    expect(
      fetchMock.mock.calls.some(([url]) => String(url).includes(encodeURIComponent('OrderService#total(J)J')))
    ).toBe(true)
    expect(wrapper.get('#insights-panel-impact input').element.value).toBe('OrderService#total')
  })

  it('offers one topic selector and closes an open row the filter leaves out', async () => {
    const heap = {...report.observations[0], id: 'heap:1', kind: 'heap-growth-after-gc', subject: 'Heap'}
    const both = {
      ...report,
      checks: [...report.checks, {...report.checks[0], kind: 'heap-growth-after-gc', title: 'Heap growth after GC'}],
      observations: [...report.observations, heap]
    }
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => Promise.resolve(jsonResponse(String(url).includes('/insights/') ? detail : both)))
    )
    wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.findAll('.insight-topic option').map((option) => option.text())).toEqual([
      'Any topic',
      'Memory',
      'Queries'
    ])
    expect(wrapper.get('#insights-tab-findings').text()).toContain('2')
    await openRow(wrapper, 'GET /api/owners/{id}')
    expect(wrapper.find('.insight-detail').exists()).toBe(true)

    await wrapper.get('.insight-topic').setValue('memory')
    await flushPromises()
    expect(wrapper.findAll('.insight-row.open')).toHaveLength(0)
    expect(wrapper.find('.insight-detail').exists()).toBe(false)
    expect(wrapper.get('#insight-verdict-title').text()).toBe('2 observations recorded across 9 requests')
    expect(wrapper.get('#insight-result-count').text()).toBe('1 of 2 observations match your filters, in 1 group.')
  })

  it('keeps the run total independent of search and an open row', async () => {
    const fast = {
      ...report.observations[0],
      id: 'route-time-breakdown:fast',
      kind: 'route-time-breakdown',
      subject: 'GET /api/fast',
      listed: false,
      unlistedReason: 'Its warm median is under 20 ms.'
    }
    const slow = {...fast, id: 'route-time-breakdown:slow', subject: 'GET /api/slow'}
    const withHidden = {
      ...report,
      checks: [...report.checks, {...report.checks[0], kind: 'route-time-breakdown', title: 'Route time breakdown'}],
      observations: [report.observations[0], fast, slow]
    }
    vi.stubGlobal(
      'fetch',
      vi.fn((url) =>
        Promise.resolve(jsonResponse(String(url).includes('/insights/') ? {...detail, observation: fast} : withHidden))
      )
    )
    wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.get('#insight-verdict-title').text()).toBe('3 observations recorded across 9 requests')

    await wrapper.get('.insight-search').setValue('/api/fast')
    expect(wrapper.get('#insight-result-count').text()).toBe('1 of 3 observations match your filters, in 1 group.')
    expect(wrapper.get('#insights-tab-findings').text()).toContain('Observations')
    expect(wrapper.get('#insights-tab-findings .bootui-tabs__count').text()).toBe('3')
    await openRow(wrapper, 'GET /api/fast')
    await wrapper.get('.insight-search').setValue('')
    await flushPromises()

    expect(wrapper.get('#insight-verdict-title').text()).toBe('3 observations recorded across 9 requests')
    expect(wrapper.get('#insight-result-count').text()).toBe('All 3 observations, in 2 groups. Nothing excluded.')
    expect(wrapper.findAll('.insight-row.open')).toHaveLength(1)
    expect(wrapper.get('.insight-observation-select').findAll('option')).toHaveLength(2)
    expect(wrapper.get('.insight-row.open').text()).toContain('GET /api/fast')
  })

  it('keeps search and topic independent and restores every row with an accessible clear action', async () => {
    const heap = {
      ...report.observations[0],
      id: 'heap:1',
      kind: 'heap-growth-after-gc',
      subject: 'Heap',
      sentence: 'Old-generation occupancy remained stable.'
    }
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonResponse({...report, observations: [...report.observations, heap]})))
    )
    wrapper = mount(RuntimeInsights, {
      attachTo: document.body,
      global: {stubs: {'router-link': {template: '<a><slot /></a>'}}}
    })
    await flushPromises()

    await wrapper.get('.insight-search').setValue('owners')
    await wrapper.get('.insight-topic').setValue('memory')
    expect(wrapper.get('.insight-search').element.value).toBe('owners')
    expect(wrapper.get('#insight-result-count').text()).toBe('0 of 2 observations match your filters, in 0 groups.')
    expect(wrapper.get('.insight-no-matches').text()).toContain('Change the search or topic')
    expect(wrapper.findAll('.insight-topic option').map((option) => option.text())).toContain('Queries')

    await wrapper.get('.insight-clear-filters').trigger('click')
    await flushPromises()
    expect(wrapper.get('.insight-search').element.value).toBe('')
    expect(wrapper.get('.insight-topic').element.value).toBe('')
    expect(wrapper.findAll('.insight-group-toggle')).toHaveLength(2)
    expect(wrapper.findAll('.insight-row.open')).toHaveLength(0)
    expect(document.activeElement).toBe(wrapper.get('.insight-search').element)
    expect(wrapper.find('.insight-clear-filters').exists()).toBe(false)
  })

  it('qualifies partial and insufficient evidence beside the affected row, not with a global filter', async () => {
    const partial = {...report.observations[0], id: 'partial:1', status: 'PARTIAL', listed: false}
    const insufficient = {...partial, id: 'insufficient:1', status: 'INSUFFICIENT'}
    vi.stubGlobal(
      'fetch',
      vi.fn((url) =>
        Promise.resolve(
          jsonResponse(
            String(url).includes('/insights/')
              ? detail
              : {...report, observations: [...report.observations, partial, insufficient]}
          )
        )
      )
    )
    wrapper = mountPanel()
    await flushPromises()

    await openRow(wrapper)
    const select = wrapper.get('.insight-observation-select')
    expect(select.findAll('option')).toHaveLength(3)
    expect(select.findAll('option').map((option) => option.text())).toEqual([
      'GET /api/owners/{id}',
      'GET /api/owners/{id} - Evidence limit: Some source events were dropped',
      'GET /api/owners/{id} - Evidence limit: Not enough evidence'
    ])
    await select.setValue(partial.id)
    expect(wrapper.get('.insight-row-limit').text()).toBe('Evidence limit: Some source events were dropped')
    await select.setValue(insufficient.id)
    expect(wrapper.get('.insight-row-limit').text()).toBe('Evidence limit: Not enough evidence')
    expect(wrapper.findAll('.insight-topic option').map((option) => option.text())).toEqual(['Any topic', 'Queries'])
  })

  it('opens one observation directly and preserves its selection across report refreshes', async () => {
    let current = report
    const fetchMock = vi.fn((url) =>
      Promise.resolve(jsonResponse(String(url).includes('/insights/') ? detail : current))
    )
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountPanel()
    await flushPromises()

    const group = wrapper.get('.insight-group-toggle')
    expect(group.attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('.insight-group-count').text()).toBe('1 observation')
    expect(wrapper.find('.insight-row.open').exists()).toBe(false)
    expect(wrapper.get(`#${group.attributes('aria-controls')}`).isVisible()).toBe(false)
    await group.trigger('click')
    await flushPromises()
    expect(group.attributes('aria-expanded')).toBe('true')
    expect(wrapper.findAll('.insight-row.open')).toHaveLength(1)
    expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/insights/'))).toHaveLength(1)
    expect(wrapper.get('.insight-detail').text()).toContain('Evidence')
    expect(wrapper.find('.insight-observation-select').exists()).toBe(false)
    expect(wrapper.find('[aria-label="Next observation"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="Previous observation"]').exists()).toBe(false)

    current = {...report, observations: [...report.observations, {...report.observations[0], id: 'second'}]}
    await wrapper.findComponent({name: 'PanelHeader'}).vm.$emit('refresh')
    await flushPromises()
    expect(group.attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('.insight-group-count').text()).toBe('2 observations')
    expect(wrapper.findAll('.insight-row.open')).toHaveLength(1)
    expect(wrapper.get('.insight-observation-select').element.value).toBe(report.observations[0].id)
    await group.trigger('click')
    await wrapper.findComponent({name: 'PanelHeader'}).vm.$emit('refresh')
    await flushPromises()
    expect(group.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('.insight-row.open').exists()).toBe(false)

    current = {...current, window: {...current.window, runId: 'run-2'}}
    await group.trigger('click')
    await wrapper.findComponent({name: 'PanelHeader'}).vm.$emit('refresh')
    await flushPromises()
    expect(group.attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('.insight-observation-select').element.value).toBe(report.observations[0].id)
  })

  it('filters groups without selecting an observation or fetching evidence', async () => {
    const fetchMock = vi.fn(() => Promise.resolve(jsonResponse(report)))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountPanel()
    await flushPromises()

    await wrapper.get('.insight-search').setValue('owners')
    const group = wrapper.get('.insight-group-toggle')
    expect(group.attributes('aria-expanded')).toBe('false')
    await wrapper.findComponent({name: 'PanelHeader'}).vm.$emit('refresh')
    await flushPromises()
    expect(group.attributes('aria-expanded')).toBe('false')
    await wrapper.get('.insight-search').setValue('/api/owners')
    expect(group.attributes('aria-expanded')).toBe('false')
    await wrapper.get('.insight-clear-filters').trigger('click')
    expect(group.attributes('aria-expanded')).toBe('false')
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/insights/'))).toBe(false)
  })

  it('selects and pages through matching observations with boundary controls and retains the selection on refresh', async () => {
    const second = {...report.observations[0], id: 'repeated-selects:second', subject: 'GET /api/pets'}
    const both = {...report, observations: [...report.observations, second]}
    const fetchMock = vi.fn((url) => {
      const observation = both.observations.find(
        (item) => String(url) === `api/runtime-insights/insights/${encodeURIComponent(item.id)}`
      )
      return Promise.resolve(jsonResponse(observation ? {...detail, observation} : structuredClone(both)))
    })
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(RuntimeInsights, {
      attachTo: document.body,
      global: {stubs: {'router-link': {template: '<a><slot /></a>'}}}
    })
    await flushPromises()
    await wrapper.get('.insight-group-toggle').trigger('click')
    await flushPromises()

    const select = wrapper.get('.insight-observation-select')
    const previous = wrapper.get('[aria-label="Previous observation"]')
    const next = wrapper.get('[aria-label="Next observation"]')
    expect(wrapper.get(`label[for="${select.attributes('id')}"]`).text()).toBe('Observation')
    expect(wrapper.get(`#${select.attributes('aria-describedby')}`).text()).toBe('1 of 2')
    expect(select.element.value).toBe(report.observations[0].id)
    expect(previous.element.disabled).toBe(true)
    expect(next.element.disabled).toBe(false)
    await next.trigger('click')
    await flushPromises()
    expect(select.element.value).toBe(second.id)
    expect(wrapper.get('.insight-position').text()).toBe('2 of 2')
    expect(previous.element.disabled).toBe(false)
    expect(next.element.disabled).toBe(true)
    expect(document.activeElement).toBe(select.element)
    expect(wrapper.get('.insight-evidence').text()).toContain('r-1')
    expect(wrapper.findAll('.insight-detail')).toHaveLength(1)

    await wrapper.findComponent({name: 'PanelHeader'}).vm.$emit('refresh')
    await flushPromises()
    expect(select.element.value).toBe(second.id)
    await previous.trigger('click')
    await flushPromises()
    expect(select.element.value).toBe(report.observations[0].id)
    expect(document.activeElement).toBe(select.element)
    await select.setValue(second.id)
    await flushPromises()
    expect(wrapper.get('.insight-position').text()).toBe('2 of 2')
    expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/insights/'))).toHaveLength(5)

    await wrapper.get('.insight-search').setValue(second.subject)
    await flushPromises()
    expect(wrapper.find('.insight-observation-select').exists()).toBe(false)
    expect(wrapper.get('.insight-observation-subject').text()).toBe(second.subject)
    expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/insights/'))).toHaveLength(5)
  })

  it('opens an unfiltered deep link on its exact non-first observation without loading the first', async () => {
    const second = {...report.observations[0], id: 'repeated-selects:second', subject: 'GET /api/pets'}
    routeState.query = {insight: second.id}
    const fetchMock = vi.fn((url) =>
      Promise.resolve(
        jsonResponse(
          String(url).includes('/insights/')
            ? {...detail, observation: second}
            : {...report, observations: [...report.observations, second]}
        )
      )
    )
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.get('.insight-observation-select').element.value).toBe(second.id)
    expect(wrapper.get('.insight-position').text()).toBe('2 of 2')
    expect(wrapper.get('.insight-row.open').attributes('id')).toBe(`insight-row-${second.id}`)
    expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/insights/')).map(([url]) => url)).toEqual([
      `api/runtime-insights/insights/${encodeURIComponent(second.id)}`
    ])
  })

  it('closes a pending observation with its group, restores focus, and ignores the late evidence response', async () => {
    let resolveDetail
    const fetchMock = vi.fn((url) =>
      String(url).includes('/insights/')
        ? new Promise((resolve) => (resolveDetail = resolve))
        : Promise.resolve(jsonResponse(report))
    )
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(RuntimeInsights, {
      attachTo: document.body,
      global: {stubs: {'router-link': {template: '<a><slot /></a>'}}}
    })
    await flushPromises()
    await wrapper.get('.insight-group-toggle').trigger('click')
    await flushPromises()
    expect(wrapper.find('.insight-detail').exists()).toBe(true)
    await wrapper.get('.insight-group-toggle').trigger('click')
    await flushPromises()
    expect(document.activeElement).toBe(wrapper.get('.insight-group-toggle').element)
    expect(wrapper.find('.insight-detail').exists()).toBe(false)
    resolveDetail(jsonResponse(detail))
    await flushPromises()
    await wrapper.get('.insight-group-toggle').trigger('click')
    await flushPromises()
    expect(wrapper.find('.insight-detail').exists()).toBe(true)
    expect(wrapper.get('.insight-group-toggle').attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('.insight-detail').text()).toContain('Loading evidence')
    expect(wrapper.find('.insight-evidence').exists()).toBe(false)
    expect(fetchMock.mock.calls.filter(([url]) => String(url).includes('/insights/'))).toHaveLength(2)
  })

  it('switches directly to another group without allowing late evidence from the previous group to replace it', async () => {
    const second = {
      ...report.observations[0],
      id: 'route-time-breakdown:second',
      kind: 'route-time-breakdown',
      subject: 'GET /api/second'
    }
    let resolvePrevious
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => {
        if (String(url).endsWith(`/insights/${encodeURIComponent(report.observations[0].id)}`)) {
          return new Promise((resolve) => (resolvePrevious = resolve))
        }
        return Promise.resolve(
          jsonResponse(
            String(url).includes('/insights/')
              ? {...detail, observation: second, rows: [{cells: ['new-evidence', '1']}]}
              : {...report, observations: [...report.observations, second]}
          )
        )
      })
    )
    wrapper = mountPanel()
    await flushPromises()
    const groups = wrapper.findAll('.insight-group-toggle')
    await groups[0].trigger('click')
    await flushPromises()
    expect(wrapper.get('.insight-detail').text()).toContain('Loading evidence')
    await groups[1].trigger('click')
    await flushPromises()
    expect(groups[0].attributes('aria-expanded')).toBe('false')
    expect(groups[1].attributes('aria-expanded')).toBe('true')
    expect(wrapper.findAll('.insight-detail')).toHaveLength(1)
    expect(wrapper.get('.insight-observation-subject').text()).toBe(second.subject)
    expect(wrapper.get('.insight-evidence').text()).toContain('new-evidence')
    resolvePrevious(jsonResponse(detail))
    await flushPromises()
    expect(wrapper.get('.insight-evidence').text()).toContain('new-evidence')
    expect(wrapper.get('.insight-evidence').text()).not.toContain('r-1')
  })

  it('moves focus to what a verdict link names when it opens a tab', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url) =>
        Promise.resolve(
          jsonResponse(
            String(url).includes('/insights/') ? detail : {...report, limitations: ['R2DBC is not recorded.']}
          )
        )
      )
    )
    wrapper = mount(RuntimeInsights, {
      attachTo: document.body,
      global: {stubs: {'router-link': {template: '<a><slot /></a>'}}}
    })
    await flushPromises()

    await wrapper.get('.insight-verdict-limits button').trigger('click')
    await flushPromises()
    expect(wrapper.get('#insights-tab-coverage').attributes('aria-selected')).toBe('true')
    expect(document.activeElement?.id).toBe('insights-panel-coverage')
  })

  it('pivots between Check and Subject grouping, shows subject preview chips, and passes return context on exemplar links', async () => {
    const breakdown = {
      ...report.observations[0],
      id: 'route-time-breakdown:owners',
      kind: 'route-time-breakdown',
      subject: 'GET /api/owners/{id}',
      sentence: 'Warm median 18 ms.'
    }
    const multiReport = {
      ...report,
      checks: [...report.checks, {kind: 'route-time-breakdown', title: 'Route time breakdown', status: 'EVALUATED'}],
      observations: [report.observations[0], breakdown]
    }
    const fetchMock = vi.fn((url) =>
      Promise.resolve(jsonResponse(String(url).includes('/insights/') ? detail : multiReport))
    )
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountPanel()
    await flushPromises()

    // Collapsed check rows preview their subjects without changing the accessible button title.
    expect(wrapper.find('.insight-subject-chip').text()).toBe('GET /api/owners/{id}')

    // Open the first observation in Check mode; it offers a 1-click pivot to all observations on that subject.
    await openRow(wrapper, 'GET /api/owners/{id}')
    const exemplarLink = JSON.parse(wrapper.get('.insight-detail ul.list-inline a').attributes('data-to'))
    expect(exemplarLink).toEqual({
      path: '/activity',
      query: {request: 'r-1', fromInsight: 'repeated-selects:0123456789'}
    })
    expect(wrapper.get('.insight-exemplar-meta').text()).toContain('Executions: 6')

    const pivotBtn = wrapper.get('.insight-pivot-subject-btn')
    expect(pivotBtn.text()).toContain('All 2 observations on GET /api/owners/{id}')
    await pivotBtn.trigger('click')
    await flushPromises()

    // Now grouped by Subject: 1 group for GET /api/owners/{id} with 2 quick-switch pills (Repeated SELECTs, Route time breakdown).
    expect(wrapper.findAll('.insight-group-toggle')).toHaveLength(1)
    expect(wrapper.get('.insight-group-title').text()).toBe('GET /api/owners/{id}')
    const pills = wrapper.findAll('.insight-observation-pill')
    expect(pills.map((p) => p.text())).toEqual(['Repeated SELECTs', 'Route time breakdown'])
    await pills[1].trigger('click')
    await flushPromises()
    expect(wrapper.get('.insight-observation-select').element.value).toBe(breakdown.id)
  })
})
