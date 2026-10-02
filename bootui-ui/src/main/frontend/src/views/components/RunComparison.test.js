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
    expect(wrapper.find('[data-section="behavior"] h3').text().replace(/\s+/g, ' ')).toBe('What the routes did · 1')
    expect(wrapper.find('[data-section="behavior"] .visually-hidden').text()).toBe('Up:')
    expect(wrapper.find('[data-section="edges"] .insight-comparison-marker i').classes()).toContain('bi-plus-lg')
    expect(wrapper.emitted('loaded')[0][0].status).toBe('COMPARED')
    expect(wrapper.findAll('[data-section]').map((section) => section.attributes('data-section'))).toEqual([
      'behavior',
      'edges',
      'restart'
    ])
    expect(wrapper.find('[data-section="behavior"] code').text()).toBe('GET /api/orders')
    expect(wrapper.text()).toContain('Ready in 2,000 ms after this restart, 4,000 ms after the previous one.')
    expect(wrapper.find('details').text()).toContain('Tracing was on before')
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

    expect(wrapper.text()).toContain('No route changed what it ran, called, or raised.')
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
