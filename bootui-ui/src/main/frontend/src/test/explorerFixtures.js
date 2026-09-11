export function event(id = 'request-1', overrides = {}) {
  return {
    id,
    type: 'REQUEST',
    timestamp: 1000,
    severity: 'OK',
    summary: 'GET /orders/{id} → 200',
    durationMs: 80,
    method: 'GET',
    path: '/orders/{id}',
    status: 200,
    parentId: null,
    ...overrides
  }
}
export function call(id, parentId = null, overrides = {}) {
  return {
    id,
    parentId,
    beanName: id,
    typeName: `example.${id}`,
    method: 'load()',
    role: 'SERVICE',
    offsetMs: 1,
    durationMs: 20,
    failed: false,
    slow: false,
    exceptionType: null,
    ...overrides
  }
}
export function detail(overrides = {}) {
  return {
    found: true,
    event: event(),
    related: [
      event('sql-1', {
        type: 'SQL',
        summary: 'select * from public.orders join public.lines',
        timestamp: 1030,
        durationMs: 15,
        parentId: 'request-1'
      }),
      event('cache-1', {
        type: 'CACHE',
        summary: 'MISS orders',
        timestamp: 1005,
        severity: 'WARN',
        durationMs: null,
        parentId: 'request-1'
      })
    ],
    invocations: [
      call('controller', 'request-1', {role: 'CONTROLLER', offsetMs: 0}),
      call('service', 'controller', {offsetMs: 1}),
      call('repository', 'service', {role: 'REPOSITORY', offsetMs: 10})
    ],
    links: [
      {eventId: 'sql-1', invocationId: 'repository'},
      {eventId: 'cache-1', invocationId: 'service'}
    ],
    sqlReferences: [
      {eventId: 'sql-1', dataSource: 'ordersDb', identifiers: ['public.orders', 'public.lines'], status: 'COMPLETE'}
    ],
    cacheOperations: [{eventId: 'cache-1', managerName: 'local', cacheName: 'orders', operation: 'MISS'}],
    warnings: [],
    partial: false,
    omittedInvocations: 0,
    ...overrides
  }
}
export function repeatedFailureDetail() {
  return detail({
    invocations: [
      call('parent-failed', 'request-1', {
        beanName: 'service',
        typeName: 'example.Service',
        failed: true,
        offsetMs: 0
      }),
      call('child-failed', 'parent-failed', {
        beanName: 'repository',
        typeName: 'example.Repository',
        role: 'REPOSITORY',
        failed: true,
        offsetMs: 1
      }),
      call('parent-handled', 'request-1', {
        beanName: 'service',
        typeName: 'example.Service',
        failed: false,
        offsetMs: 30
      }),
      call('child-handled', 'parent-handled', {
        beanName: 'repository',
        typeName: 'example.Repository',
        role: 'REPOSITORY',
        failed: true,
        offsetMs: 31
      })
    ]
  })
}
export function faultToleranceDetail() {
  return detail({
    event: event('request-1', {
      path: '/api/sample/fault-tolerance/circuit-breaker',
      summary: 'GET /api/sample/fault-tolerance/circuit-breaker → 200'
    }),
    related: Array.from({length: 7}, (_, index) => {
      const operation = index < 4 ? 'ERROR' : index === 4 ? 'STATE_TRANSITION' : 'SHORT_CIRCUITED'
      return event(`fault-${index}`, {
        type: 'FAULT_TOLERANCE',
        summary: `${operation} inventory-service (circuit breaker)`,
        severity: index < 4 ? 'ERROR' : 'WARN',
        parentId: 'request-1',
        timestamp: 1001 + index
      })
    }),
    invocations: [],
    links: [],
    sqlReferences: [],
    cacheOperations: [],
    partial: true
  })
}
export function report(entries = [event()], overrides = {}) {
  return {
    available: true,
    activity: {available: true, entries, typeCounts: {}, sources: ['http', 'sql'], warnings: [], pageInfo: null},
    setup: {beanCaptureEnabled: true, beanDetailAvailable: true, requestSlowThresholdMs: 1000, limitations: []},
    ...overrides
  }
}
