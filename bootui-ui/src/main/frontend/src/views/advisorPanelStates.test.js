import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Spring from './Spring.vue'
import Architecture from './Architecture.vue'
import Memory from './Memory.vue'
import Security from './Security.vue'
import RestApi from './RestApi.vue'
import Hibernate from './Hibernate.vue'
import DatabaseAdvisor from './DatabaseAdvisor.vue'
import Pentesting from './Pentesting.vue'

const report = {
  scan: {status: 'COMPLETED', scannedAt: '2024-05-01T10:15:00Z'},
  rulesEvaluated: 12,
  violationsFound: 2,
  componentsAnalyzed: 87,
  disclaimer: 'Heuristic checks only.',
  severityCounts: [{severity: 'HIGH', count: 2}],
  inspected: ['org.example.App'],
  results: [],
  analysisErrors: []
}

function mountSpring() {
  return mount(Spring, {
    global: {
      stubs: {AutoRefreshToggle: true},
      provide: {panels: {value: {platform: 'spring-boot'}}}
    }
  })
}

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('advisor panel states', () => {
  it('shows the shared skeleton until the mount-time report settles', async () => {
    let resolveReport
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise((resolve) => (resolveReport = resolve)))
    )

    const wrapper = mountSpring()
    await flushPromises()

    expect(wrapper.find('.skeleton-wrapper').exists()).toBe(true)
    expect(wrapper.find('.advisor-score-card').exists()).toBe(false)

    resolveReport({ok: true, status: 200, headers: new Headers(), json: () => Promise.resolve(report)})
    await flushPromises()

    expect(wrapper.find('.skeleton-wrapper').exists()).toBe(false)
    expect(wrapper.find('.advisor-score-card').exists()).toBe(true)
  })

  const advisorComponents = [
    ['Architecture', Architecture, 'architecture'],
    ['Memory', Memory, 'memory'],
    ['Security', Security, 'security'],
    ['Spring', Spring, 'spring'],
    ['RestApi', RestApi, 'rest-api'],
    ['Hibernate', Hibernate, 'hibernate'],
    ['DatabaseAdvisor', DatabaseAdvisor, 'database-advisor'],
    ['Pentesting', Pentesting, 'pentesting']
  ]

  function actionableReport() {
    const findings = [false, true].map((dismissed, index) => ({
      id: `TEST-${index + 1}`,
      name: `Retained rule ${index + 1}`,
      title: `Retained finding ${index + 1}`,
      severity: 'HIGH',
      status: 'VIOLATION',
      category: 'TEST',
      confidence: 'High',
      target: 'Application metadata',
      description: 'Retained evidence.',
      evidence: 'Retained evidence.',
      recommendation: 'Review the finding.',
      violationCount: 1,
      sampleViolations: [],
      dismissed
    }))
    return {
      ...report,
      scan: {status: 'SCANNED', scannedAt: 1700000000000},
      evidence: {usable: true, coverageComplete: true, limitations: []},
      checksRun: 2,
      findingsFound: 1,
      violationsFound: 1,
      severityCounts: [{severity: 'HIGH', count: 1}],
      findings,
      results: findings
    }
  }

  function actionButtons(wrapper) {
    return [
      wrapper.get('.panel-header__actions button'),
      ...wrapper
        .findAll('.list-group-item button')
        .filter((button) => ['Dismiss', 'Dismiss rule', 'Restore'].includes(button.text()))
    ]
  }

  async function mountAdvisor(component, id) {
    document.cookie = 'XSRF-TOKEN=test-token; path=/'
    const fetchMock = vi.fn((url) =>
      Promise.resolve(
        new Response(
          JSON.stringify(url === `api/${id}` ? actionableReport() : {available: false, entries: [], total: 0})
        )
      )
    )
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(component, {props: {panel: {id, available: true, enabled: true, readOnly: false}}})
    await flushPromises()
    expect(actionButtons(wrapper)).toHaveLength(3)
    expect(actionButtons(wrapper).every((button) => !button.element.disabled)).toBe(true)
    return {wrapper, fetchMock}
  }

  describe.each(advisorComponents)('%s rendered advisor actions', (name, component, id) => {
    it.each([true, false])('disables scans until the initial GET settles (success %s)', async (success) => {
      let finish
      const fetchMock = vi.fn((url) =>
        url === `api/${id}`
          ? new Promise((resolve) => (finish = resolve))
          : Promise.resolve(new Response(JSON.stringify({available: false, entries: [], total: 0})))
      )
      vi.stubGlobal('fetch', fetchMock)
      const wrapper = mount(component, {props: {panel: {id, available: true, enabled: true, readOnly: false}}})
      await flushPromises()
      const scan = wrapper.get('.panel-header__actions button')
      expect(scan.element.disabled).toBe(true)
      await scan.trigger('click')
      expect(fetchMock.mock.calls.some(([url]) => url === `api/${id}/scan`)).toBe(false)

      finish(new Response(JSON.stringify(success ? actionableReport() : {}), {status: success ? 200 : 500}))
      await flushPromises()
      expect(scan.element.disabled).toBe(false)
      expect(wrapper.find('.skeleton-wrapper').exists()).toBe(false)
      wrapper.unmount()
    })

    it.each([
      ['read-only', {readOnly: true}],
      ['unavailable', {available: false}],
      ['disabled', {enabled: false}]
    ])('disables actions under %s policy and reenables when it is lifted', async (state, policy) => {
      const {wrapper, fetchMock} = await mountAdvisor(component, id)
      const initialRequests = fetchMock.mock.calls.length
      await wrapper.setProps({panel: {id, ...policy}})
      expect(actionButtons(wrapper).length).toBeGreaterThan(0)
      expect(actionButtons(wrapper).every((button) => button.element.disabled)).toBe(true)
      for (const button of actionButtons(wrapper)) await button.trigger('click')
      expect(fetchMock).toHaveBeenCalledTimes(initialRequests)
      await wrapper.setProps({panel: {id, available: true, enabled: true, readOnly: false}})
      expect(actionButtons(wrapper)).toHaveLength(3)
      expect(actionButtons(wrapper).every((button) => !button.element.disabled)).toBe(true)
      wrapper.unmount()
    })

    it.each(['scan', 'Dismiss', 'Restore'])(
      'disables all rendered actions during pending %s, including cached refresh',
      async (action) => {
        const {wrapper, fetchMock} = await mountAdvisor(component, id)
        let finishAction
        let finishRefresh
        fetchMock.mockImplementationOnce(() => new Promise((resolve) => (finishAction = resolve)))
        if (action !== 'scan') {
          fetchMock.mockImplementationOnce(() => new Promise((resolve) => (finishRefresh = resolve)))
        }
        const button =
          action === 'scan'
            ? actionButtons(wrapper)[0]
            : actionButtons(wrapper).find((button) => button.text().replace(/ rule$/, '') === action)
        await button.trigger('click')
        await flushPromises()
        const pendingRequests = fetchMock.mock.calls.length
        expect(actionButtons(wrapper)).toHaveLength(3)
        expect(actionButtons(wrapper).every((button) => button.element.disabled)).toBe(true)
        for (const disabled of actionButtons(wrapper)) await disabled.trigger('click')
        expect(fetchMock).toHaveBeenCalledTimes(pendingRequests)
        const [url, init] = fetchMock.mock.calls.at(-1)
        expect(url).toBe(
          action === 'scan' ? `api/${id}/scan` : `api/dismissed-rules/TEST-${action === 'Dismiss' ? 1 : 2}`
        )
        expect(init.method).toBe(action === 'Restore' ? 'DELETE' : 'POST')

        finishAction(new Response(JSON.stringify(action === 'scan' ? actionableReport() : {dismissed: []})))
        await flushPromises()
        if (action !== 'scan') {
          expect(actionButtons(wrapper).every((button) => button.element.disabled)).toBe(true)
          expect(fetchMock.mock.calls.at(-1)[0]).toBe(`api/${id}`)
          expect(fetchMock.mock.calls.at(-1)[1]?.method).toBeUndefined()
          finishRefresh(new Response(JSON.stringify(actionableReport())))
          await flushPromises()
        }
        expect(actionButtons(wrapper)).toHaveLength(3)
        expect(actionButtons(wrapper).every((button) => !button.element.disabled)).toBe(true)
        wrapper.unmount()
      }
    )
  })

  it('clears the first-paint state even when the report request fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')))

    const wrapper = mountSpring()
    await flushPromises()

    expect(wrapper.find('.skeleton-wrapper').exists()).toBe(false)
    expect(wrapper.text()).toContain('Unable to load Spring Advisor report')
  })

  it('renders rule identifiers and categories in the monospace stack', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        headers: new Headers(),
        json: () =>
          Promise.resolve({
            ...report,
            results: [
              {
                id: 'SPRING-001',
                name: 'Field injection',
                category: 'BEANS',
                severity: 'HIGH',
                status: 'VIOLATION',
                violationCount: 3,
                description: 'Field injection hides dependencies.',
                recommendation: 'Use constructor injection.',
                sampleViolations: ['org.example.OrderService'],
                dismissed: false
              }
            ]
          })
      })
    )

    const wrapper = mountSpring()
    await flushPromises()

    const ruleId = wrapper.findAll('.font-monospace').filter((node) => node.text() === 'SPRING-001')
    const category = wrapper.findAll('.badge.font-monospace').filter((node) => node.text() === 'BEANS')

    expect(ruleId).toHaveLength(1)
    expect(category).toHaveLength(1)
  })

  it('titles the rule results section with a heading below the panel h2', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ok: true, status: 200, headers: new Headers(), json: () => Promise.resolve(report)})
    )

    const wrapper = mountSpring()
    await flushPromises()

    expect(wrapper.findAll('h2')).toHaveLength(1)
    expect(wrapper.findAll('h3').map((heading) => heading.text())).toContain('Rule results')
  })
})
