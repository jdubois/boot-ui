import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import RestApi from './RestApi.vue'

// The Exceptions panel deep-links into this panel with ?errorContract=<component>; the route is mocked so
// the query can be varied per test without booting a router.
let currentRoute = {query: {}}
vi.mock('vue-router', () => ({useRoute: () => currentRoute}))

function ruleResult(id, name, severity, status, violationCount = 0) {
  return {
    id,
    name,
    category: 'Routing & HTTP method mapping',
    severity,
    description: `${name} description.`,
    status,
    violationCount,
    sampleViolations: violationCount > 0 ? [`${id} detail`] : [],
    recommendation: `${name} recommendation.`,
    learnMoreUrl: 'https://example.com/learn'
  }
}

function restApiReport(results, violationsFound = results.filter((result) => result.status === 'VIOLATION').length) {
  return {
    localOnly: true,
    assessmentEvidence: {usable: true, incomplete: false},
    disclaimer: 'REST API disclaimer.',
    basePackages: ['com.example'],
    controllersAnalyzed: 4,
    handlersAnalyzed: 9,
    rulesEvaluated: 30,
    violationsFound,
    severityCounts: [
      {severity: 'HIGH', count: severityCount(results, 'HIGH')},
      {severity: 'MEDIUM', count: severityCount(results, 'MEDIUM')},
      {severity: 'LOW', count: severityCount(results, 'LOW')},
      {severity: 'INFO', count: severityCount(results, 'INFO')}
    ],
    scan: {
      analyzer: 'BootUI REST API Advisor',
      status: 'SCANNED',
      message: 'REST API rules completed.',
      scannedAt: 1_700_000_000_000,
      rulesEvaluated: 30,
      controllersAnalyzed: 4,
      handlersAnalyzed: 9,
      violationsFound
    },
    results
  }
}

function severityCount(results, severity) {
  return results
    .filter((result) => result.status === 'VIOLATION' && result.severity === severity)
    .reduce((total, result) => total + result.violationCount, 0)
}

function errorContractReport(entries = [], overrides = {}) {
  const total = entries.length
  return {
    available: true,
    unavailableReason: null,
    total,
    handlerCount: total,
    componentCount: total,
    exceptionTypeCount: total,
    truncated: false,
    maxEntries: 500,
    entries,
    page: {total, matched: total, offset: 0, limit: 50, returned: total, hasMore: false},
    ...overrides
  }
}

function contractEntry(overrides = {}) {
  return {
    id: 'com.example.GlobalAdvice#handleNotFound(com.example.OrderNotFoundException)',
    exceptionType: 'com.example.OrderNotFoundException',
    exceptionSimpleName: 'OrderNotFoundException',
    component: 'com.example.GlobalAdvice',
    componentSimpleName: 'GlobalAdvice',
    method: 'handleNotFound',
    source: 'SPRING_CONTROLLER_ADVICE',
    scope: 'GLOBAL',
    scopeTarget: null,
    precedence: 1,
    precedenceSource: 'DECLARED',
    status: '404',
    statusSource: 'ANNOTATION',
    bodyCategory: 'PROBLEM_DETAIL',
    bodyType: 'org.springframework.http.ProblemDetail',
    produces: [],
    ...overrides
  }
}

async function mountWithReport(report, contract = errorContractReport()) {
  vi.stubGlobal(
    'fetch',
    vi.fn((url) =>
      Promise.resolve(
        new Response(JSON.stringify(String(url).includes('error-contract') ? contract : report), {status: 200})
      )
    )
  )

  const wrapper = mount(RestApi)
  await flushPromises()
  return wrapper
}

describe('RestApi', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    currentRoute = {query: {}}
  })

  it('shows only violation results sorted by importance', async () => {
    const wrapper = await mountWithReport(
      restApiReport([
        ruleResult('RAPI-MAP-005', 'Low severity finding', 'LOW', 'VIOLATION', 1),
        ruleResult('RAPI-DOC-001', 'Passing informational rule', 'INFO', 'PASS'),
        ruleResult('RAPI-RESP-001', 'Medium severity finding', 'MEDIUM', 'VIOLATION', 3),
        ruleResult('RAPI-DTO-001', 'High severity finding', 'HIGH', 'VIOLATION', 1)
      ])
    )

    expect(wrapper.text()).toContain('Scan complete')
    expect(wrapper.text()).toContain('3 flagged rules, sorted by importance')
    expect(wrapper.text()).toContain('What happened:')
    expect(wrapper.text()).toContain('3 findings found for this rule.')
    expect(wrapper.text()).not.toContain('Passing informational rule')
    expect(wrapper.findAll('.list-group-item h3').map((title) => title.text())).toEqual([
      'High severity finding',
      'Medium severity finding',
      'Low severity finding'
    ])
    expect(wrapper.find('a[href="https://example.com/learn"]').exists()).toBe(true)
  })

  it('shows an empty findings state when every evaluated rule passes', async () => {
    const wrapper = await mountWithReport(
      restApiReport([ruleResult('RAPI-DOC-001', 'Passing informational rule', 'INFO', 'PASS')], 0)
    )

    expect(wrapper.text()).toContain('No REST API rule findings')
    expect(wrapper.text()).not.toContain('Passing informational rule')
  })

  describe('declared error contract', () => {
    it('renders declared handlers without claiming anything the declaration does not prove', async () => {
      const wrapper = await mountWithReport(
        restApiReport([], 0),
        errorContractReport([
          contractEntry(),
          contractEntry({
            id: 'com.example.OrderController#handleLocally(com.example.OrderRejectedException)',
            exceptionType: 'com.example.OrderRejectedException',
            exceptionSimpleName: 'OrderRejectedException',
            component: 'com.example.OrderController',
            componentSimpleName: 'OrderController',
            method: 'handleLocally',
            source: 'SPRING_CONTROLLER',
            scope: 'CONTROLLER',
            scopeTarget: 'com.example.OrderController',
            status: null,
            statusSource: 'DYNAMIC',
            bodyCategory: 'DYNAMIC',
            bodyType: null
          }),
          contractEntry({
            id: 'com.example.OtherAdvice#handle(com.example.OrderNotFoundException)',
            component: 'com.example.OtherAdvice',
            componentSimpleName: 'OtherAdvice',
            method: 'handle',
            precedence: 1,
            precedenceSource: 'UNRESOLVED',
            status: null,
            statusSource: 'UNRESOLVED',
            bodyCategory: 'UNRESOLVED',
            bodyType: null
          })
        ])
      )

      const text = wrapper.text()
      expect(text).toContain('Declared error contract')
      expect(text).toContain('Nothing is executed and no error is triggered.')
      expect(text).toContain('GlobalAdvice#handleNotFound')
      expect(text).toContain('Application-wide')
      expect(text).toContain('Problem detail')
      expect(text).toContain('Wins')
      // A runtime-built status and body are labelled as such rather than shown as missing.
      expect(text).toContain('Controller-local')
      expect(text).toContain('Runtime')
      expect(text).toContain('Runtime-decided')
      // An unresolvable precedence is reported as ambiguous instead of picking a winner.
      expect(text).toContain('Ambiguous')
      expect(text).toContain('Unresolved')
    })

    it('opens filtered to the handler the Exceptions panel linked to', async () => {
      currentRoute = {query: {errorContract: 'com.example.GlobalAdvice'}}

      await mountWithReport(restApiReport([], 0))

      const requested = fetch.mock.calls.map((call) => String(call[0])).filter((url) => url.includes('error-contract'))
      expect(requested).not.toHaveLength(0)
      expect(requested.every((url) => url.includes('q=com.example.GlobalAdvice'))).toBe(true)
    })

    it('explains an unavailable backend instead of looking like an application with no handlers', async () => {
      const wrapper = await mountWithReport(
        restApiReport([], 0),
        errorContractReport([], {
          available: false,
          unavailableReason: 'Not available: the build-time error-contract capture is not wired in this launch mode.',
          total: 0,
          handlerCount: 0,
          componentCount: 0,
          exceptionTypeCount: 0
        })
      )

      expect(wrapper.text()).toContain('the build-time error-contract capture is not wired')
      expect(wrapper.text()).not.toContain('declares no exception handlers')
    })

    it('distinguishes an application with no declared handlers from an unavailable backend', async () => {
      const wrapper = await mountWithReport(restApiReport([], 0), errorContractReport([]))

      expect(wrapper.text()).toContain('This application declares no exception handlers')
      expect(wrapper.find('input[aria-label="Filter declared error contract"]').exists()).toBe(false)
    })

    it('warns that the catalogue is incomplete when the declaration count is truncated', async () => {
      const wrapper = await mountWithReport(
        restApiReport([], 0),
        errorContractReport([contractEntry()], {truncated: true, maxEntries: 500, total: 501})
      )

      expect(wrapper.text()).toContain('Only the first 500 declarations are catalogued')
    })

    it('renders a readable message when the contract endpoint fails', async () => {
      vi.stubGlobal(
        'fetch',
        vi.fn((url) =>
          Promise.resolve(
            String(url).includes('error-contract')
              ? new Response('', {status: 404})
              : new Response(JSON.stringify(restApiReport([], 0)), {status: 200})
          )
        )
      )

      const wrapper = mount(RestApi)
      await flushPromises()

      expect(wrapper.text()).toContain('Could not load the declared error contract: HTTP 404')
      expect(wrapper.text()).not.toContain('[object Object]')
    })
  })
})
