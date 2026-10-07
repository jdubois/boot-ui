import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import RuntimeInsights from './RuntimeInsights.vue'

const routeState = vi.hoisted(() => ({query: {}}))
vi.mock('vue-router', () => ({useRoute: () => routeState}))

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

function mountPanel(props = {}) {
  return mount(RuntimeInsights, {props, global: {stubs: {'router-link': {template: '<a><slot /></a>'}}}})
}

// v-show hides a tab panel that is not selected.
function shown(element) {
  return !(element.attributes('style') ?? '').includes('display: none')
}

// Nothing opens on its own: a row's evidence is loaded once the developer opens it.
async function openRow(wrapper, text = '') {
  const row = wrapper.findAll('.insight-item').find((candidate) => candidate.text().includes(text))
  await row.trigger('click')
  await flushPromises()
  return row
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

  it('shows the window, the coverage, the selected observation with its evidence, and checks that did not run', async () => {
    const fetchMock = vi.fn((url) =>
      Promise.resolve(jsonResponse(String(url).includes('/insights/') ? detail : report))
    )
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountPanel()
    await flushPromises()
    expect(wrapper.get('#insight-verdict-title').text()).toBe('1 thing to check across 9 requests')
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/insights/'))).toBe(false)
    expect(wrapper.find('.insight-detail').exists()).toBe(false)
    await openRow(wrapper)

    const text = wrapper.text()
    expect(text).toContain('9 requests')
    expect(text).toContain('request id 100 %')
    expect(wrapper.find('.insight-item[aria-expanded="true"]').text()).toContain('GET /api/owners/{id}')
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

  it('marks a kind not externally validated beside its title, and says why on its selected row (M4-20)', async () => {
    const reason = 'It found nothing on the seven validation applications (M4-20), so it is not externally validated.'
    const marked = {
      ...report,
      checks: [{...report.checks[0], validation: 'NOT_VALIDATED', validationReason: reason}, report.checks[1]]
    }
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => Promise.resolve(jsonResponse(String(url).includes('/insights/') ? detail : marked)))
    )
    wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.find('.insight-row-kind').text()).toBe('Repeated SELECTs')
    expect(wrapper.find('.insight-validation').text()).toBe('Not externally validated')
    expect(wrapper.find('.insight-verdict-facts').text()).toContain('1 from a check not externally validated')
    await openRow(wrapper)
    expect(wrapper.find('.insight-validation').attributes('title')).toBe(reason)
    expect(wrapper.find('.insight-validation-reason').text()).toBe(`Not externally validated: ${reason}`)
  })

  it('shows no validation marker for a kind that passed', async () => {
    const passed = {
      ...report,
      checks: [{...report.checks[0], validation: 'PASSED', validationReason: 'It passed.'}, report.checks[1]]
    }
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => Promise.resolve(jsonResponse(String(url).includes('/insights/') ? detail : passed)))
    )
    wrapper = mountPanel()
    await flushPromises()
    await openRow(wrapper)

    expect(wrapper.find('.insight-verdict-facts').text()).toContain('1 from a check that passed external validation')
    expect(wrapper.find('.insight-validation').exists()).toBe(false)
    expect(wrapper.find('.insight-validation-reason').exists()).toBe(false)
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
    expect(link.text()).toBe('1 change since run 4')
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
    expect(wrapper.text()).toContain('@Scheduled OrderJob.run')
    expect(wrapper.find('.insight-item').exists()).toBe(true)
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

    expect(wrapper.get('.insight-item').text()).toContain('Heap')
    expect(wrapper.get('#insight-verdict-title').text()).toBe('1 thing to check across 4 retained events')
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
    expect(wrapper.text()).toContain('No observation matches this search.')
  })

  it('leaves short routes out by default, counts them, and lists them with Show all routes', async () => {
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

    expect(wrapper.findAll('.insight-item')).toHaveLength(1)
    expect(wrapper.get('.insight-unlisted').text()).toContain('1 more not listed by default: 1 Route time breakdown.')
    const toggle = wrapper.get('.insight-show-all')
    expect(toggle.attributes('aria-pressed')).toBe('false')
    expect(toggle.text()).toBe('Show all routes · 1 more')

    await toggle.trigger('click')
    expect(toggle.attributes('aria-pressed')).toBe('true')
    expect(wrapper.findAll('.insight-item')).toHaveLength(2)
    expect(wrapper.find('.insight-unlisted').exists()).toBe(false)
    const item = wrapper.findAll('.insight-item').find((candidate) => candidate.text().includes('GET /api/fast'))
    expect(item.text()).toContain('Not listed by default')
    await item.trigger('click')
    await flushPromises()
    expect(wrapper.get('.insight-unlisted-reason').text()).toBe(
      'Not listed by default: Its warm median is under 20 ms.'
    )
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
    const item = wrapper.findAll('.insight-item').find((candidate) => candidate.text().includes('GET /api/slow'))
    await item.trigger('click')
    await flushPromises()

    current = refreshed
    await wrapper.findComponent({name: 'PanelHeader'}).vm.$emit('refresh')
    await flushPromises()

    expect(wrapper.get('.insight-item[aria-expanded="true"]').text()).toContain('GET /api/slow')
    expect(wrapper.get('.insight-item[aria-expanded="true"]').text()).toContain('Not listed by default')
    expect(wrapper.find('.insight-unlisted').exists()).toBe(false)
  })

  it('shows every row when a deep link names one the default list leaves out', async () => {
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

    expect(wrapper.get('.insight-show-all').attributes('aria-pressed')).toBe('true')
    expect(wrapper.get('.insight-item[aria-expanded="true"]').text()).toContain('GET /api/fast')
  })

  it('explains an empty default list instead of reading as no match', async () => {
    const fast = {...report.observations[0], listed: false, unlistedReason: 'Its warm median is under 20 ms.'}
    vi.stubGlobal(
      'fetch',
      vi.fn((url) =>
        Promise.resolve(jsonResponse(String(url).includes('/insights/') ? detail : {...report, observations: [fast]}))
      )
    )
    wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.get('.insight-none-listed').text()).toContain('Nothing is listed by default here. 1 not listed')
    expect(wrapper.text()).not.toContain('No observation matches this search.')
    await wrapper.get('.insight-none-listed button').trigger('click')
    expect(wrapper.findAll('.insight-item')).toHaveLength(1)
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
    expect(wrapper.get('.insight-item[aria-expanded="true"]').text()).toContain('GET /api/pets')
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

    expect(wrapper.findAll('.insight-item')).toHaveLength(1)
    const memory = wrapper.findAll('.insight-theme').find((chip) => chip.text().startsWith('Memory'))
    expect(memory.attributes('aria-pressed')).toBe('true')
    await openRow(wrapper, 'Heap')
    expect(wrapper.get('.insight-item[aria-expanded="true"]').text()).toContain('Heap')
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
        counts: {changed: 1, added: 0, removed: 0, executed: 1, notExecuted: 0},
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
          }
        ],
        methodsTotal: 1,
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

    // Before any check, Change impact offers the changed method too.
    expect(wrapper.get('#insights-panel-impact').find('[data-testid="impact-changed"]').text()).toContain(
      'OrderService#total'
    )

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

  it('counts each theme filter and closes an open row the filter leaves out', async () => {
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

    const chips = () => wrapper.findAll('.insight-theme').map((chip) => chip.text())
    expect(chips()).toEqual(['All 2', 'Memory 1', 'Queries 1'])
    expect(wrapper.get('#insights-tab-findings').text()).toContain('2')
    await openRow(wrapper, 'GET /api/owners/{id}')
    expect(wrapper.find('.insight-detail').exists()).toBe(true)

    await wrapper
      .findAll('.insight-theme')
      .find((chip) => chip.text().startsWith('Memory'))
      .trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.insight-item')).toHaveLength(1)
    expect(wrapper.find('.insight-detail').exists()).toBe(false)
    expect(wrapper.get('#insight-verdict-title').text()).toBe('2 things to check across 9 requests')
  })

  it('counts an open row the default list leaves out once, as listed, after a search is cleared', async () => {
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
    expect(wrapper.get('#insight-verdict-title').text()).toBe('1 thing to check across 9 requests')
    expect(wrapper.get('.insight-verdict-facts').text()).toContain('2 more not listed by default')

    await wrapper.get('.insight-search').setValue('/api/fast')
    await openRow(wrapper, 'GET /api/fast')
    await wrapper.get('.insight-search').setValue('')
    await flushPromises()

    expect(wrapper.get('#insight-verdict-title').text()).toBe('2 things to check across 9 requests')
    expect(wrapper.get('.insight-verdict-facts').text()).toContain('1 more not listed by default')
    expect(wrapper.get('.insight-show-all').text()).toBe('Show all routes · 1 more')
    expect(wrapper.get('.insight-unlisted').text()).toContain('1 more not listed by default: 1 Route time breakdown.')
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
})
