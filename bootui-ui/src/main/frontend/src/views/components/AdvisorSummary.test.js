import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'

import AdvisorSummary from './AdvisorSummary.vue'

const baseProps = {
  scanStatusLabel: 'Scan complete',
  scanStatusClass: 'text-bg-success',
  scanTime: '10:30:00',
  metrics: [
    {label: 'Rules evaluated', value: 12},
    {label: 'Advisor findings', value: 3},
    {label: 'Beans analysed', value: 87, hint: '40 singletons'}
  ]
}

describe('AdvisorSummary', () => {
  it('renders the score gauge, /100, and qualitative band when a score is provided', () => {
    const wrapper = mount(AdvisorSummary, {props: {...baseProps, score: 90}})
    expect(wrapper.find('.advisor-score-card').exists()).toBe(true)
    expect(wrapper.text()).toContain('Advisor score')
    expect(wrapper.text()).toContain('90')
    expect(wrapper.text()).toContain('/ 100')
    expect(wrapper.text()).toContain('Good')
  })

  it('tones the gauge danger for low scores', () => {
    const wrapper = mount(AdvisorSummary, {props: {...baseProps, score: 40}})
    expect(wrapper.text()).toContain('At risk')
    expect(wrapper.find('.advisor-summary__gauge.text-danger').exists()).toBe(true)
  })

  it('renders the metric cluster with labels, values, and hints', () => {
    const wrapper = mount(AdvisorSummary, {props: {...baseProps, score: 90}})
    const text = wrapper.text()
    expect(text).toContain('Rules evaluated')
    expect(text).toContain('Advisor findings')
    expect(text).toContain('Beans analysed')
    expect(text).toContain('87')
    expect(text).toContain('40 singletons')
  })

  it('shows the scan status badge and scanned-at time', () => {
    const wrapper = mount(AdvisorSummary, {props: {...baseProps, score: 90}})
    const status = wrapper.find('.advisor-summary__metric--status')
    expect(status.find('.badge').text()).toBe('Scan complete')
    expect(status.text()).toContain('Scanned at 10:30:00')
  })

  it('omits the gauge but keeps the metric strip when no score is available', () => {
    const wrapper = mount(AdvisorSummary, {props: {...baseProps, score: null}})
    expect(wrapper.find('.advisor-summary__gauge').exists()).toBe(false)
    expect(wrapper.find('.advisor-summary__divider').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Advisor score')
    expect(wrapper.text()).toContain('Rules evaluated')
    expect(wrapper.text()).toContain('Scan complete')
  })

  it('notes how many dismissed rules are excluded from the score', () => {
    const wrapper = mount(AdvisorSummary, {props: {...baseProps, score: 97, dismissedCount: 2}})
    expect(wrapper.text()).toContain('2 dismissed rule(s) excluded from this score')
  })

  it('omits the dismissed note when nothing is dismissed', () => {
    const wrapper = mount(AdvisorSummary, {props: {...baseProps, score: 100, dismissedCount: 0}})
    expect(wrapper.text()).not.toContain('excluded from this score')
  })
})
