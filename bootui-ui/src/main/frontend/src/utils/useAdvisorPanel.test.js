import {flushPromises, mount} from '@vue/test-utils'
import {defineComponent, h} from 'vue'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {useAdvisorPanel} from './useAdvisorPanel.js'

const finding = {id: 'TEST-1', status: 'VIOLATION', severity: 'HIGH', violationCount: 1}
const report = (status) => ({
  scan: {status},
  severityCounts: [{severity: 'HIGH', count: 1}],
  results: [finding],
  rulesEvaluated: 1
})
let panel
const Host = defineComponent({
  setup() {
    panel = useAdvisorPanel({}, {apiPath: 'api/architecture', scanErrorMessage: 'Unable to scan'})
    return () => h('div')
  }
})

describe('advisor panel scoring', () => {
  afterEach(() => vi.unstubAllGlobals())

  it.each(['PARTIAL', 'ERROR', 'DISABLED'])('keeps findings from %s without a score', async (status) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(report(status)))))
    const wrapper = mount(Host)
    await flushPromises()
    expect(panel.hasScanData).toBe(true)
    expect(panel.score).toBeNull()
    expect(panel.visibleResults).toEqual([finding])
    expect(panel.emptyRuleResultsTitle).toBe('No findings in the available results')
    wrapper.unmount()
  })

  it('replaces accepted scores only when a new report is received', async () => {
    let body = report('SCANNED')
    let failure = false
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        failure ? Promise.reject(new TypeError('offline')) : Promise.resolve(new Response(JSON.stringify(body)))
      )
    )
    const wrapper = mount(Host)
    await flushPromises()
    expect(panel.score).toBe(90)
    failure = true
    await panel.runScan()
    expect(panel.score).toBe(90)
    expect(panel.error).toBeTruthy()
    failure = false
    body = report('PARTIAL')
    await panel.runScan()
    expect(panel.score).toBeNull()
    expect(panel.visibleResults).toEqual([finding])
    body = report('SCANNED')
    await panel.runScan()
    expect(panel.score).toBe(90)
    wrapper.unmount()
  })
})
