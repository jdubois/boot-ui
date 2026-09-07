import {readFileSync} from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
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

  it('explains an incomplete assessment without implying a dismissed score exists', () => {
    const wrapper = mount(AdvisorSummary, {
      props: {
        ...baseProps,
        score: null,
        scoreLabel: 'Incomplete',
        scoreReason: 'Evidence unavailable.',
        dismissedCount: 2
      }
    })
    expect(wrapper.text()).toContain('Incomplete')
    expect(wrapper.text()).toContain('Evidence unavailable.')
    expect(wrapper.text()).toContain('excluded from active findings')
    expect(wrapper.text()).not.toContain('this score')
    expect(wrapper.find('[role="img"]').exists()).toBe(false)
  })

  it('labels the score in sentence case rather than an uppercase tracked eyebrow', () => {
    const wrapper = mount(AdvisorSummary, {props: {...baseProps, score: 90}})
    const label = wrapper.find('.advisor-summary__band-label')

    expect(label.exists()).toBe(true)
    expect(label.text()).toBe('Advisor score')
    expect(wrapper.find('.advisor-summary__eyebrow').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('text-uppercase')
  })

  it('keeps every metric label sentence case in the order the panel supplied', () => {
    const wrapper = mount(AdvisorSummary, {props: {...baseProps, score: 90}})

    expect(wrapper.findAll('.advisor-summary__metric dt').map((dt) => dt.text())).toEqual([
      'Scan status',
      'Rules evaluated',
      'Advisor findings',
      'Beans analysed'
    ])
  })

  it('renders machine counters in the monospace stack and keeps labels sans', () => {
    const source = readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), 'AdvisorSummary.vue'), 'utf8')
    const metricValue = source.slice(source.indexOf('.advisor-summary__metric dd {'))
    const metricLabel = source.slice(source.indexOf('.advisor-summary__metric dt {'))

    expect(metricValue.slice(0, metricValue.indexOf('}'))).toContain('font-family: var(--bs-font-monospace)')
    expect(metricLabel.slice(0, metricLabel.indexOf('}'))).not.toContain('text-transform')
    expect(source).not.toContain('text-transform: uppercase')
  })
})
