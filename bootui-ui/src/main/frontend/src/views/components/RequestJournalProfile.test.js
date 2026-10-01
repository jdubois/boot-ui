import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'

import RequestJournalProfile from './RequestJournalProfile.vue'

function journalProfile(overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    requestId: '0a1b2c3d4e5f6a7b',
    route: 'GET /api/orders/{id}',
    startedAt: 1700000000000,
    durationMicros: 40000,
    status: 200,
    resources: {
      availability: 'AVAILABLE',
      unmeasuredReason: null,
      cpuNanos: 9000000,
      allocatedBytes: 65536,
      segments: 2,
      unmeasuredSegments: 0,
      gcPauses: 1,
      gcPausesTruncated: false
    },
    timeline: [
      {
        source: 'sql',
        label: 'select * from orders where id = ?',
        detail: 'dataSource',
        offsetMillis: 8,
        durationMicros: 4000,
        severity: 'OK',
        thread: 'http-nio-1',
        threadKind: 'WORKER'
      },
      {
        source: 'cache',
        label: 'MISS prices',
        detail: null,
        offsetMillis: 15,
        durationMicros: null,
        severity: 'WARN',
        thread: 'http-nio-1',
        threadKind: 'WORKER'
      }
    ],
    gcPauses: [
      {
        collector: 'G1 Young Generation',
        gcId: 8,
        retained: true,
        offsetMillis: 10,
        pauseMillis: 3,
        cause: 'G1 Evacuation Pause'
      },
      {collector: 'G1 Young Generation', gcId: 9, retained: false, offsetMillis: null, pauseMillis: null, cause: null}
    ],
    routeComparison: {
      route: 'GET /api/orders/{id}',
      requests: 12,
      p50Micros: 10000,
      p95Micros: 30000,
      durationMicros: 40000,
      standing: 'ABOVE_P95',
      minimumRequests: 5
    },
    touched: {
      tables: ['orders', 'lines'],
      dataSources: ['dataSource'],
      transactions: [],
      caches: ['prices (MISS)'],
      messages: [],
      restCalls: [],
      logTemplates: []
    },
    notes: [],
    ...overrides
  }
}

describe('RequestJournalProfile', () => {
  it('shows the route standing, resources, timeline with its GC lane, and touched resources', () => {
    const wrapper = mount(RequestJournalProfile, {props: {profile: journalProfile()}})
    const text = wrapper.text()

    expect(text).toContain('GET /api/orders/{id}')
    expect(text).toContain('Slower than 95% of the route')
    expect(text).toContain('p50 10.0 ms · p95 30.0 ms over 12 requests')
    expect(text).toContain('9.0 ms CPU · 64.0 KB allocated · 1 GC pause')
    expect(text).toContain('select * from orders where id = ?')
    expect(text).toContain('at 8 ms for 4.0 ms')
    expect(text).toContain('at 15 ms')
    expect(text).toContain('G1 Young Generation #8 completed during this request')
    expect(text).toContain('3 ms pause')
    expect(text).toContain('no longer retained')
    expect(text).toContain('Tables')
    expect(text).toContain('lines')
    expect(text).not.toContain('Transactions')
    expect(wrapper.findAll('.request-journal__bar--instant')).toHaveLength(1)
    expect(wrapper.findAll('.request-journal__bar--gc')).toHaveLength(1)
  })

  it('says why CPU time is unavailable or partial, never showing zero', () => {
    const unavailable = mount(RequestJournalProfile, {
      props: {
        profile: journalProfile({
          resources: {
            ...journalProfile().resources,
            availability: 'UNAVAILABLE',
            unmeasuredReason: 'VIRTUAL_THREAD',
            cpuNanos: 0,
            unmeasuredSegments: 2
          }
        })
      }
    })
    expect(unavailable.text()).toContain('CPU time and memory unavailable: it ran on a virtual thread')
    expect(unavailable.text()).not.toContain('0.0 µs CPU')

    const partial = mount(RequestJournalProfile, {
      props: {
        profile: journalProfile({
          resources: {
            ...journalProfile().resources,
            availability: 'PARTIAL',
            unmeasuredReason: 'VIRTUAL_THREAD',
            unmeasuredSegments: 1
          }
        })
      }
    })
    expect(partial.text()).toContain('at least: 1 of 2 segments were not measured')
  })

  it('explains a route with too few requests and a request the journal does not retain', () => {
    const few = mount(RequestJournalProfile, {
      props: {
        profile: journalProfile({routeComparison: {...journalProfile().routeComparison, requests: 2, standing: null}})
      }
    })
    expect(few.text()).toContain('Too few requests to compare (2 of 5)')

    const missing = mount(RequestJournalProfile, {
      props: {profile: {available: false, unavailableReason: 'The runtime journal does not retain request r1.'}}
    })
    expect(missing.text()).toContain('does not retain request r1')
    expect(missing.find('ol').exists()).toBe(false)
  })
})
