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
      logTemplates: [],
      models: ['gpt-4o (openai)']
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
    expect(text).toContain('AI models')
    expect(text).toContain('gpt-4o (openai)')
    expect(text).not.toContain('Transactions')
    expect(wrapper.findAll('.request-journal__bar--instant')).toHaveLength(1)
    expect(wrapper.findAll('.request-journal__bar--gc')).toHaveLength(1)
  })

  it('lists the tasks the agent propagated with what they did, how they ended, and their badges', () => {
    const wrapper = mount(RequestJournalProfile, {
      props: {
        profile: journalProfile({
          handoffs: [
            {
              executionId: 'async-1',
              parentExecutionId: null,
              thread: 'pool-1-thread-1',
              startOffsetMicros: 10000,
              durationMicros: 90000,
              queuedMicros: 2000,
              taskClass: 'java.util.concurrent.FutureTask',
              hook: 'ThreadPoolExecutor.runWorker',
              failed: true,
              exceptionClass: 'java.lang.IllegalStateException',
              afterResponse: true,
              afterResponseMicros: 60000,
              capped: false,
              sqlCount: 1,
              restClientCount: 2,
              messagingCount: 0,
              allocatedBytes: 2048
            }
          ],
          lateHandoffs: 1
        })
      }
    })
    const text = wrapper.text()

    expect(text).toContain('Handoffs')
    expect(text).toContain('FutureTask')
    expect(text).toContain('pool-1-thread-1')
    expect(text).toContain('90.0 ms after 2.0 ms queued')
    expect(text).toContain('1 SQL statement, 2 REST calls')
    expect(text).toContain('failed: IllegalStateException')
    expect(text).toContain('after response by 60.0 ms')
    expect(text).toContain('2.0 KB allocated')
    expect(text).toContain('1 later task started more than bootui.agent.executors.max-handoff after this request ended')
    expect(text.split('counted, not shown')).toHaveLength(2)
    expect(wrapper.findAll('.request-journal__late-handoffs')).toHaveLength(1)
    expect(text).not.toContain('past deadline')
  })

  it('shows no handoffs section without propagated tasks', () => {
    const wrapper = mount(RequestJournalProfile, {props: {profile: journalProfile({handoffs: []})}})
    expect(wrapper.text()).not.toContain('Handoffs')
  })

  it('qualifies shared execution handoffs instead of claiming that no work was recorded', () => {
    const handoff = {
      executionId: 'job-1',
      parentExecutionId: 'job-1',
      thread: 'worker',
      startOffsetMicros: 1000,
      durationMicros: 5000,
      queuedMicros: 0,
      taskClass: 'Task',
      hook: 'Executor.execute',
      failed: false,
      afterResponse: false,
      capped: false,
      sqlCount: 0,
      restClientCount: 0,
      messagingCount: 0,
      allocatedBytes: null
    }
    const wrapper = mount(RequestJournalProfile, {
      props: {
        profile: journalProfile({
          requestId: 'job-1',
          handoffs: [handoff, {...handoff, thread: 'another-worker'}]
        })
      }
    })
    expect(wrapper.findAll('.request-journal__handoff')).toHaveLength(2)
    expect(wrapper.text()).toContain('per-task counts unavailable')
    expect(wrapper.text()).not.toContain('nothing recorded')
    wrapper.unmount()
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

  it('sums the request Hibernate sessions and flags repeated auto-flushes', () => {
    const wrapper = mount(RequestJournalProfile, {
      props: {
        profile: journalProfile({
          orm: {
            sessions: 1,
            statements: 7,
            statementMicros: 4500,
            connectionAcquisitions: 1,
            flushes: 1,
            autoFlushes: 3,
            flushMicros: 300,
            autoFlushMicros: 600,
            dirtyEntities: 3,
            entitiesInContext: 40,
            l2Hits: 0,
            l2Misses: 0,
            l2Puts: 0
          }
        })
      }
    })

    const text = wrapper.text()
    expect(text).toContain('Hibernate')
    expect(text).toContain('1 session')
    expect(text).toContain('7 statements')
    expect(text).toContain('1 flush, 3 auto-flushes before a query')
    expect(text).toContain('up to 40 entities in context')
    expect(text).not.toContain('second-level cache')
    expect(wrapper.findAll('.request-journal__slow').map((node) => node.text())).toContain('1 session')
  })

  it('shows no Hibernate row when the request opened no session', () => {
    expect(mount(RequestJournalProfile, {props: {profile: journalProfile({orm: null})}}).text()).not.toContain(
      'Hibernate'
    )
  })

  it('renders the baseline comparison card, emits compare-with on selector change, and emits open-request on link click', async () => {
    const wrapper = mount(RequestJournalProfile, {
      props: {
        profile: journalProfile({
          baselineComparison: {
            baselineRequestId: 'req-warm-1',
            baselineDurationMicros: 12000,
            routeP50Micros: 11000,
            durationDeltaMicros: 28000,
            sqlExecutionsDelta: 5,
            sqlDurationMicrosDelta: 18000,
            connectionWaitMicrosDelta: 500,
            cacheHitsDelta: -1,
            cacheMissesDelta: 1,
            restClientCallsDelta: 1,
            restClientDurationMicrosDelta: 8000,
            ormFlushesDelta: 1,
            ormAutoFlushesDelta: 2,
            ormMaxEntitiesDelta: 24,
            gcPausesTarget: 1,
            gcPausesBaseline: 0,
            distinctSqlShapesOnlyInTarget: ['select * from order_lines where order_id = ?'],
            candidates: [
              {requestId: 'req-warm-1', startedAt: 1700000000000, durationMicros: 12000, status: 200},
              {requestId: 'req-warm-2', startedAt: 1700000001000, durationMicros: 15000, status: 200}
            ]
          }
        })
      }
    })

    const text = wrapper.text()
    expect(text).toContain('Compared with baseline request')
    expect(text).toContain('req-warm-1')
    expect(text).toContain('12.0 ms · route p50 11.0 ms')
    expect(text).toContain('+28.0 ms')
    expect(text).toContain('+5 executions (+18.0 ms)')
    expect(text).toContain('+500.0 µs')
    expect(text).toContain('+1 miss, -1 hit')
    expect(text).toContain('+1 call (+8.0 ms)')
    expect(text).toContain('+1 flush, +2 auto-flushes, +24 peak entities in context')
    expect(text).toContain('1 vs 0 completed during the request')
    expect(text).toContain('select * from order_lines where order_id = ?')

    await wrapper.get('.request-journal__baseline-link').trigger('click')
    expect(wrapper.emitted('open-request')).toEqual([['req-warm-1']])
    expect(wrapper.find('.request-journal__dual-track').exists()).toBe(true)
    expect(wrapper.get('.request-journal__dual-track').text()).toContain('40.0 ms')
    expect(wrapper.get('.request-journal__dual-track').text()).toContain('12.0 ms')

    const select = wrapper.get('.request-journal__baseline-select')
    await select.setValue('req-warm-2')
    expect(wrapper.emitted('compare-with')).toEqual([['req-warm-2']])
  })

  it('renders authorization decisions and offers the Spring Security explain link on 401 and 403 requests', () => {
    const RouterLinkStub = {
      props: ['to'],
      template: '<a class="router-link-stub" :data-to="JSON.stringify(to)"><slot /></a>'
    }
    const wrapper = mount(RequestJournalProfile, {
      props: {
        profile: journalProfile({
          status: 403,
          route: 'POST /api/orders/{id}/cancel',
          authorizations: [
            {
              target: 'REQUEST',
              subject: null,
              rule: 'hasAnyAuthority(ROLE_USER, ROLE_ADMIN)',
              authentication: 'AUTHENTICATED',
              granted: true,
              authorities: 1,
              offsetMillis: 1,
              durationMicros: 350
            },
            {
              target: 'METHOD',
              subject: 'OrderService#cancel',
              rule: null,
              authentication: 'AUTHENTICATED',
              granted: false,
              authorities: 1,
              offsetMillis: 4,
              durationMicros: null
            }
          ]
        }),
        method: 'POST',
        path: '/api/orders/42/cancel',
        status: 403
      },
      global: {stubs: {RouterLink: RouterLinkStub}}
    })

    const text = wrapper.text()
    expect(text).toContain('Authorization decisions')
    expect(text).toContain('REQUEST')
    expect(text).toContain('METHOD')
    expect(text).toContain('OrderService#cancel')
    expect(text).toContain('AUTHENTICATED · 1 authority')
    expect(text).toContain('hasAnyAuthority(ROLE_USER, ROLE_ADMIN)')
    expect(text).toContain('Granted')
    expect(text).toContain('Denied')
    expect(text).toContain('350.0 µs')
    expect(text).toContain('Explain in Spring Security')
    expect(text).toContain('Static filter-chain matching')
    const explainLink = wrapper
      .findAll('a.router-link-stub')
      .find((node) => node.text().includes('Explain in Spring Security'))
    expect(JSON.parse(explainLink.attributes('data-to'))).toEqual({
      path: '/spring-security',
      query: {method: 'POST', path: '/api/orders/42/cancel'}
    })
  })

  it('hides the Spring Security explain link when the spring-security panel is disabled, on 200 requests, or without a concrete path', () => {
    const okWrapper = mount(RequestJournalProfile, {
      props: {profile: journalProfile({status: 200, authorizations: []}), method: 'GET', path: '/api/orders/42'}
    })
    expect(okWrapper.text()).not.toContain('Authorization decisions')
    expect(okWrapper.text()).not.toContain('Explain in Spring Security')

    const templateOnlyWrapper = mount(RequestJournalProfile, {
      props: {profile: journalProfile({status: 403, route: 'GET /api/orders/{id}'})}
    })
    expect(templateOnlyWrapper.text()).not.toContain('Explain in Spring Security')

    const disabledWrapper = mount(RequestJournalProfile, {
      props: {profile: journalProfile({status: 401}), method: 'GET', path: '/api/orders/42'},
      global: {
        provide: {
          panels: {
            value: {
              platform: 'spring-boot',
              panels: [{id: 'spring-security', available: true, enabled: false}]
            }
          }
        }
      }
    })
    expect(disabledWrapper.text()).not.toContain('Explain in Spring Security')
  })
})
