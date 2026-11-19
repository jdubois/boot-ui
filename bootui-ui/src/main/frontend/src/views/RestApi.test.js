import {flushPromises, mount} from '@vue/test-utils'
import {reactive} from 'vue'
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
    evidence: {usable: true, coverageComplete: true, limitations: []},
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
    vi.useRealTimers()
    vi.unstubAllGlobals()
    currentRoute = {query: {}}
  })

  it.each([true, false])(
    'keeps score 91 and forwards scan notes independently of scan status (%s)',
    async (incomplete) => {
      const report = restApiReport([ruleResult('RAPI-1', 'Retained finding', 'MEDIUM', 'VIOLATION', 3)])
      report.evidence.coverageComplete = !incomplete
      report.evidence.limitations = incomplete ? ['Metadata unavailable.'] : []
      const wrapper = await mountWithReport(report)

      expect(wrapper.get('.advisor-summary__value').text()).toBe('91')
      expect(wrapper.get('.advisor-summary__metric--status .badge').text()).toBe('Results available')
      expect(wrapper.find('.advisor-summary__assessment').exists()).toBe(false)
      expect(wrapper.find('details.advisor-summary__notes').exists()).toBe(incomplete)
      if (incomplete) {
        expect(wrapper.get('details.advisor-summary__notes').element.open).toBe(false)
        expect(wrapper.get('details.advisor-summary__notes p').text()).toContain('Metadata unavailable.')
      }
    }
  )

  it('shows only violation results sorted by importance', async () => {
    const wrapper = await mountWithReport(
      restApiReport([
        ruleResult('RAPI-MAP-005', 'Low severity finding', 'LOW', 'VIOLATION', 1),
        ruleResult('RAPI-DOC-001', 'Passing informational rule', 'INFO', 'PASS'),
        ruleResult('RAPI-RESP-001', 'Medium severity finding', 'MEDIUM', 'VIOLATION', 3),
        ruleResult('RAPI-DTO-001', 'High severity finding', 'HIGH', 'VIOLATION', 1)
      ])
    )

    expect(wrapper.text()).toContain('Results available')
    expect(wrapper.text()).toContain('3 flagged rules, sorted by importance')
    expect(wrapper.text()).toContain('What happened:')
    expect(wrapper.text()).toContain('3 findings found for this rule.')
    expect(wrapper.text()).not.toContain('Passing informational rule')
    expect(wrapper.findAll('.advisor-rule-title').map((title) => title.text())).toEqual([
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

    it('synchronizes changing error-contract links without losing finding filters or loaded evidence', async () => {
      vi.useFakeTimers({toFake: ['setTimeout', 'clearTimeout']})
      currentRoute = reactive({query: {errorContract: ' AdviceA '}})
      const report = restApiReport([ruleResult('RAPI-1', 'Routing check', 'HIGH', 'VIOLATION', 4)])
      report.violationDetails = {scanId: 'scan-1', total: 4, retained: 4, truncated: false}
      const wrapper = await mountWithReport(report, errorContractReport([contractEntry()]))
      try {
        const catalogue = wrapper.get('input[aria-label="Filter declared error contract"]')
        const findingSearch = wrapper.get('.advisor-rule-results input')
        const severity = wrapper.get('.advisor-rule-results select')
        await wrapper.get('.advisor-rule-toggle').trigger('click')
        fetch.mockResolvedValueOnce(
          new Response(
            JSON.stringify({
              scanId: 'scan-1',
              ruleId: 'RAPI-1',
              violationCount: 4,
              retainedCount: 4,
              violations: ['explicitly loaded ******'],
              suppressed: [],
              page: {offset: 0, returned: 1, limit: 100, hasMore: false}
            }),
            {status: 200}
          )
        )
        await wrapper.get('button[aria-label="View violations for RAPI-1"]').trigger('click')
        await flushPromises()
        await findingSearch.setValue('Routing')
        await severity.setValue('HIGH')
        expect(fetch).toHaveBeenCalledTimes(3)
        const queries = () =>
          fetch.mock.calls.map(([url]) => String(url)).filter((url) => url.includes('error-contract'))
        expect(queries()).toHaveLength(1)
        expect(queries()[0]).toContain('q=AdviceA')
        for (const linked of ['AdviceB', 'AdviceA', 'AdviceB', undefined]) {
          currentRoute.query = linked ? {errorContract: linked, rule: 'RAPI-1'} : {rule: 'RAPI-1'}
          await flushPromises()
          await vi.advanceTimersByTimeAsync(250)
          await flushPromises()
          expect(catalogue.element.value).toBe(linked || '')
          expect(new URL(queries().at(-1), 'http://localhost').searchParams.get('q')).toBe(linked || null)
          expect(findingSearch.element.value).toBe('Routing')
          expect(severity.element.value).toBe('HIGH')
          expect(wrapper.get('.advisor-rule-violations').text()).toContain('explicitly loaded ******')
        }
        await catalogue.setValue('manual filter')
        await vi.advanceTimersByTimeAsync(250)
        await flushPromises()
        const requests = [...fetch.mock.calls]
        currentRoute.query = {rule: 'OTHER', keep: 'yes'}
        await flushPromises()
        await vi.advanceTimersByTimeAsync(250)
        expect(catalogue.element.value).toBe('manual filter')
        expect(fetch.mock.calls).toEqual(requests)
        expect(fetch.mock.calls.filter(([url]) => String(url).includes('/violations?'))).toHaveLength(1)
        expect(fetch.mock.calls.every(([, options]) => !options?.method || options.method === 'GET')).toBe(true)
      } finally {
        wrapper.unmount()
      }
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
