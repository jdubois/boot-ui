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
  it.each([91, 100])('keeps score %s neutral with initially collapsed scan notes', (score) => {
    const wrapper = mount(AdvisorSummary, {
      props: {
        ...baseProps,
        score,
        scoreLabel: 'Results available',
        incomplete: true,
        scoreReason: 'Metadata unavailable.'
      }
    })
    const notes = wrapper.get('details.advisor-summary__notes')
    expect(notes.element.open).toBe(false)
    expect(notes.attributes('open')).toBeUndefined()
    expect(notes.get('summary').text()).toBe('Scan notes')
    expect(notes.get('p').text()).toBe('Metadata unavailable.')
    expect(wrapper.get('.advisor-summary__value').text()).toBe(String(score))
    expect(wrapper.findAll('[role="img"]')).toHaveLength(1)
    expect(wrapper.find('[role="img"]').attributes('aria-label')).toBe(
      `Known-findings score: ${score} out of 100 — Scan notes available`
    )
    expect(wrapper.text()).not.toContain('Good')
    expect(wrapper.find('.advisor-summary__score .badge').exists()).toBe(false)
    expect(wrapper.find('.advisor-summary__score').classes()).not.toContain('text-success')
    expect(wrapper.find('.advisor-summary__assessment').exists()).toBe(false)
    expect(wrapper.findAll('details.advisor-summary__notes')).toHaveLength(1)
  })
  it('renders a secondary known-findings score without a qualitative band', () => {
    const wrapper = mount(AdvisorSummary, {props: {...baseProps, score: 90}})
    expect(wrapper.find('.advisor-score-card').exists()).toBe(true)
    expect(wrapper.text()).toContain('Known-findings score')
    expect(wrapper.text()).toContain('90')
    expect(wrapper.text()).toContain('/ 100')
    expect(wrapper.text()).not.toContain('Good')
  })

  it('keeps score tone neutral even for low scores; findings carry severity cues', () => {
    const wrapper = mount(AdvisorSummary, {props: {...baseProps, score: 40}})
    expect(wrapper.text()).not.toContain('At risk')
    expect(wrapper.find('.advisor-summary__score.text-danger').exists()).toBe(false)
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
    expect(status.find('.badge').text()).toBe('Results available')
    expect(status.find('.badge').classes()).toContain('text-bg-secondary')
    expect(status.text()).toContain('Scanned at 10:30:00')
  })

  it('omits the score but keeps the metric strip when no score is available', () => {
    const wrapper = mount(AdvisorSummary, {props: {...baseProps, score: null}})
    expect(wrapper.find('.advisor-summary__score').exists()).toBe(false)
    expect(wrapper.findAll('[role="img"]')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('Known-findings score')
    expect(wrapper.text()).toContain('Rules evaluated')
    expect(wrapper.text()).toContain('Scan complete')
  })

  it('notes how many dismissed rules are excluded from the score', () => {
    const wrapper = mount(AdvisorSummary, {props: {...baseProps, score: 97, dismissedCount: 2}})
    expect(wrapper.text()).toContain('2 dismissed rule(s) excluded from active findings')
    expect(wrapper.text()).toContain('Dismissal changes score penalties, not application safety.')
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
        incomplete: true,
        scoreReason: 'Evidence unavailable.',
        dismissedCount: 2
      }
    })
    expect(wrapper.text()).toContain('Incomplete')
    expect(wrapper.text()).toContain('Evidence unavailable.')
    expect(wrapper.get('.advisor-summary__assessment').text()).toContain('Evidence unavailable.')
    expect(wrapper.find('details').exists()).toBe(false)
    expect(wrapper.text()).toContain('excluded from active findings')
    expect(wrapper.text()).not.toContain('this score')
    expect(wrapper.find('[role="img"]').exists()).toBe(false)
  })

  it('labels the score in sentence case rather than an uppercase tracked eyebrow', () => {
    const wrapper = mount(AdvisorSummary, {props: {...baseProps, score: 90}})
    const label = wrapper.find('.advisor-summary__score-label')

    expect(label.exists()).toBe(true)
    expect(label.text()).toBe('Known-findings score')
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

  it('leads with metrics and omits extra coverage paragraphs for a complete score', () => {
    const wrapper = mount(AdvisorSummary, {
      props: {
        ...baseProps,
        score: 100,
        scoreLabel: 'Results available',
        scoreReason: 'Scoped checks completed.'
      }
    })
    expect(wrapper.find('.advisor-summary__assessment').exists()).toBe(false)
    expect(wrapper.find('.advisor-summary__notes').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Scoped checks completed.')
    const sections = wrapper.find('.card-body').element.children
    expect(sections[0].tagName).toBe('DL')
    expect(sections[1].className).toContain('advisor-summary__score')
    expect(wrapper.findAll('.card-body > p')).toHaveLength(1)
    expect(wrapper.findAll('[role="img"]')).toHaveLength(1)
    expect(wrapper.get('[role="img"]').attributes('aria-label')).toBe('Known-findings score: 100 out of 100')
  })

  it.each([true, false])('uses the incomplete boolean, not label text, for notes (%s)', (incomplete) => {
    const wrapper = mount(AdvisorSummary, {
      props: {...baseProps, score: 91, scoreLabel: 'Partial assessment', scoreReason: 'Scan details.', incomplete}
    })
    expect(wrapper.find('details.advisor-summary__notes').exists()).toBe(incomplete)
    expect(wrapper.get('[role="img"]').attributes('aria-label')).toBe(
      `Known-findings score: 91 out of 100${incomplete ? ' — Scan notes available' : ''}`
    )
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
