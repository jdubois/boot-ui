import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import RuntimeInsights from './RuntimeInsights.vue'
import {areaOf, areaTabs, insightsLayout, themeTabs, validationCounts} from '../utils/runtimeInsightsLayouts.js'

// Layout proposals (temporary, ?insightsLayout=a|b|c): the same report and state in three layouts.
const routeState = vi.hoisted(() => ({query: {}}))
vi.mock('vue-router', () => ({useRoute: () => routeState}))

const observation = (id, kind, subject, extra = {}) => ({
  id,
  kind,
  subject,
  status: 'OBSERVED',
  sentence: `\`${subject}\` did something.`,
  eligible: 9,
  affected: 3,
  minimumTier: 'REQUEST_ID',
  whatToCheck: ['Check it.'],
  exemplarRequestIds: ['r-1'],
  evidenceRows: 1,
  limitations: [],
  ...extra
})

const report = {
  available: true,
  unavailableReason: null,
  window: {
    runId: 'run-1',
    firstEventAt: 1,
    lastEventAt: 2,
    retainedEvents: 120,
    requests: 9,
    evictedEvents: 0,
    droppedEvents: 0
  },
  coverage: [{source: 'http', events: 9, byRequestId: 9, byExecutionId: 0, byTraceId: 0, unlinked: 0, dropped: 0}],
  checks: [
    {kind: 'errors-behind-2xx', title: 'Errors behind 2xx responses', status: 'EVALUATED', validation: 'PASSED'},
    {
      kind: 'safe-method-dml',
      title: 'Writes in GET requests',
      status: 'EVALUATED',
      validation: 'NOT_VALIDATED',
      validationReason: 'It found nothing.'
    },
    {kind: 'event-loop-blocking', title: 'Blocking on event loops', status: 'NOT_APPLICABLE', reason: 'Worker threads.'}
  ],
  observations: [
    observation('errors:1', 'errors-behind-2xx', 'POST /import'),
    observation('dml:1', 'safe-method-dml', 'GET /orders/{id}')
  ],
  limitations: ['R2DBC statements are not recorded.'],
  notExercised: ['DELETE /api/owners/{id}'],
  notExercisedOmitted: 0
}

const detail = {
  available: true,
  observation: report.observations[0],
  columns: ['Request', 'Status'],
  rows: [{cells: ['r-1', '200']}],
  truncated: 0
}

function mountPanel() {
  vi.stubGlobal(
    'fetch',
    vi.fn((url) =>
      Promise.resolve({
        ok: true,
        status: 200,
        json: () => Promise.resolve(String(url).includes('/insights/') ? detail : report)
      })
    )
  )
  return mount(RuntimeInsights, {
    attachTo: document.body,
    global: {stubs: {'router-link': {template: '<a><slot /></a>'}}}
  })
}

describe('Runtime Insights layout helpers', () => {
  it('reads the layout from the query and keeps the current one otherwise', () => {
    expect(insightsLayout({insightsLayout: 'B'})).toBe('b')
    expect(insightsLayout({insightsLayout: 'z'})).toBe('')
    expect(insightsLayout({})).toBe('')
  })

  it('counts the observations of each area and theme, and how many are of a validated kind', () => {
    expect(areaOf('safe-method-dml')).toBe('data')
    expect(areaOf('unknown')).toBeNull()
    const tabs = areaTabs(report, report.observations)
    expect(tabs.map((tab) => [tab.id, tab.count])).toEqual([
      ['all', 2],
      ['data', 1],
      ['errors', 1],
      ['performance', 0],
      ['changes', 0],
      ['coverage', null]
    ])
    expect(themeTabs(report, report.observations).map((tab) => tab.id)).toEqual(['all', 'time', 'queries', 'errors'])
    expect(validationCounts(report, report.observations)).toEqual({total: 2, validated: 1, unvalidated: 1})
  })
})

describe('Runtime Insights layout proposals', () => {
  let wrapper

  afterEach(() => {
    routeState.query = {}
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('A: splits the panel into question tabs with the findings as a master-detail list', async () => {
    routeState.query = {insightsLayout: 'a'}
    wrapper = mountPanel()
    await flushPromises()

    const tabs = wrapper.findAll('[role="tab"]').map((tab) => tab.text())
    expect(tabs).toEqual(['Findings2', 'Changes', 'Not exercised1', 'Profile', 'Coverage & limits'])
    expect(wrapper.find('.insight-item.selected').text()).toContain('POST /import')
    expect(wrapper.find('#insight-sentence').text()).toContain('did something')
    expect(wrapper.find('.insight-validation').text()).toBe('Not externally validated')
    expect(wrapper.find('#insights-panel-coverage').isVisible()).toBe(false)

    await wrapper.find('#insights-tab-coverage').trigger('click')
    expect(wrapper.find('#insights-panel-coverage').isVisible()).toBe(true)
    expect(wrapper.find('.insight-unrun').text()).toContain('Worker threads.')
  })

  it('B: tabs by area count their findings, and a row opens its evidence in a drawer that Escape closes', async () => {
    routeState.query = {insightsLayout: 'b'}
    wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.find('#insights-tab-data').text()).toContain('Data access1')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.findAll('.insights-table-row')).toHaveLength(2)

    await wrapper.find('#insights-tab-data').trigger('click')
    expect(wrapper.findAll('.insights-table-row')).toHaveLength(1)

    await wrapper.find('.insights-open').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="dialog"]').text()).toContain('Writes in GET requests')

    document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape'}))
    await flushPromises()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })

  it('C: leads with a verdict, filters one list by theme, and opens a row in place', async () => {
    routeState.query = {insightsLayout: 'c'}
    wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.find('#insights-verdict-title').text()).toBe('2 things to check across 9 requests')
    expect(wrapper.find('.insights-verdict-facts').text()).toContain('1 from a validated check')
    expect(wrapper.find('.insights-verdict-facts').text()).toContain('1 from checks not externally validated')
    expect(wrapper.find('.insight-detail').exists()).toBe(false)

    const toggle = wrapper.find('.insights-finding-toggle')
    await toggle.trigger('click')
    await flushPromises()
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(wrapper.find('.insight-detail #insight-sentence').text()).toContain('did something')

    await wrapper.find('#insights-filter-tab-queries').trigger('click')
    expect(wrapper.findAll('.insights-finding')).toHaveLength(1)
    expect(wrapper.find('.insights-finding').text()).toContain('GET /orders/{id}')
  })
})
