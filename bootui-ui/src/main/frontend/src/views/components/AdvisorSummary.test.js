import {readFileSync} from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {flushPromises, mount} from '@vue/test-utils'
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

  describe('changes since the previous scan', () => {
    it('announces the first scan run from an unscanned panel, then an identical rescan again', async () => {
      const wrapper = mount(AdvisorSummary, {props: {...baseProps, score: null, changesEnabled: true, changes: null}})
      const status = wrapper.get('[role="status"]')
      expect(status.text()).toBe('')
      await wrapper.setProps({scanned: true})
      await flushPromises()
      expect(status.text()).toContain('First scan: nothing to compare yet.')

      const same = (previousScanId) => ({
        previousScanId,
        complete: true,
        newCount: 0,
        resolvedCount: 0,
        unchangedCount: 2,
        unkeyedCount: 0,
        resolved: [],
        notes: [],
        unverifiedCount: 0,
        rules: []
      })
      await wrapper.setProps({changes: same('scan-1')})
      await flushPromises()
      expect(status.text()).toBe('No changes since the previous scan. 2 unchanged.')
      const seen = []
      await wrapper.setProps({changes: same('scan-2')})
      seen.push(status.text())
      await flushPromises()
      seen.push(status.text())
      expect(seen[0]).toBe('')
      expect(seen[1]).toBe('No changes since the previous scan. 2 unchanged.')
    })

    const tracked = {...baseProps, score: 90, changesEnabled: true, scanned: true}
    const changes = (overrides = {}) => ({
      previousScanId: 'scan-1',
      complete: true,
      newCount: 0,
      resolvedCount: 0,
      unchangedCount: 4,
      unkeyedCount: 0,
      resolved: [],
      notes: [],
      unverifiedCount: 0,
      rules: [],
      ...overrides
    })
    const region = (wrapper) => wrapper.get('[data-testid="advisor-changes"]')

    it('stays out of the way for advisors that do not track changes and before any scan', () => {
      expect(
        mount(AdvisorSummary, {props: {...baseProps, score: 90}})
          .find('[data-testid="advisor-changes"]')
          .exists()
      ).toBe(false)
      const unscanned = mount(AdvisorSummary, {props: {...tracked, scanned: false, score: null}})
      expect(unscanned.find('[data-testid="advisor-changes"]').exists()).toBe(false)
      expect(unscanned.find('[role="status"]').exists()).toBe(true)
    })

    it('says plainly that a first scan has nothing to compare', () => {
      const wrapper = mount(AdvisorSummary, {props: {...tracked, changes: null}})
      expect(region(wrapper).text()).toContain('First scan: nothing to compare yet.')
      expect(region(wrapper).find('details').exists()).toBe(false)
    })

    it('shows an all-unchanged scan as a calm sentence with an icon, not three zero counters', () => {
      const wrapper = mount(AdvisorSummary, {props: {...tracked, changes: changes()}})
      expect(region(wrapper).text()).toContain('No changes since the previous scan. 4 unchanged.')
      expect(region(wrapper).find('i.bi-check-circle').attributes('aria-hidden')).toBe('true')
      expect(region(wrapper).find('.advisor-summary__change-counts').exists()).toBe(false)
    })

    it('shows new, resolved and unchanged counts as text beside their icons', () => {
      const wrapper = mount(AdvisorSummary, {
        props: {
          ...tracked,
          changes: changes({
            newCount: 2,
            resolvedCount: 1,
            unchangedCount: 5,
            resolved: [{ruleId: 'HIB-FETCH-001', text: 'Order#lines is mapped as FetchType.EAGER.'}]
          })
        }
      })
      const counts = region(wrapper).get('.advisor-summary__change-counts')
      expect(counts.findAll('.advisor-summary__change').map((entry) => entry.text())).toEqual([
        '2 new',
        '1 resolved',
        '5 unchanged'
      ])
      expect(counts.findAll('i').map((icon) => icon.classes()[1])).toEqual([
        'bi-plus-circle',
        'bi-check-circle',
        'bi-dash-circle'
      ])
      const resolved = region(wrapper).get('details.advisor-summary__resolved')
      expect(resolved.element.open).toBe(false)
      expect(resolved.get('summary').text()).toBe('Resolved findings (1)')
      expect(resolved.text()).toContain('HIB-FETCH-001')
      expect(resolved.text()).toContain('Order#lines is mapped as FetchType.EAGER.')
    })

    it('says how many resolved findings the list leaves out', () => {
      const wrapper = mount(AdvisorSummary, {
        props: {
          ...tracked,
          changes: changes({resolvedCount: 60, resolved: [{ruleId: 'R', text: 'x'}], unchangedCount: 0})
        }
      })
      expect(region(wrapper).text()).toContain('Showing 1 of 60 resolved findings.')
    })

    it('never presents an incomplete comparison as complete and explains why', () => {
      const wrapper = mount(AdvisorSummary, {
        props: {
          ...tracked,
          changes: changes({
            complete: false,
            newCount: 1,
            unverifiedCount: 3,
            notes: ['3 finding(s) were not compared because a scan did not fully evaluate their rule.']
          })
        }
      })
      expect(region(wrapper).text()).toContain('Comparison incomplete: 3 could not be compared.')
      expect(region(wrapper).text()).not.toContain('No changes')
      const why = region(wrapper).get('details')
      expect(why.get('summary').text()).toBe('Why is this comparison incomplete?')
      expect(why.text()).toContain('did not fully evaluate their rule')
    })

    it('does not call a comparison of nothing a clean scan', () => {
      const wrapper = mount(AdvisorSummary, {
        props: {
          ...tracked,
          changes: changes({
            complete: false,
            unchangedCount: 0,
            notes: ['This scan could not evaluate any rule, so it was not compared with the previous scan.']
          })
        }
      })
      expect(region(wrapper).text()).toContain('Not compared with the previous scan.')
      expect(region(wrapper).text()).not.toContain('No changes')
    })

    it('announces a new result once, politely, and stays silent for the report it was mounted with', async () => {
      const wrapper = mount(AdvisorSummary, {props: {...tracked, changes: changes()}})
      const status = wrapper.get('[role="status"]')
      expect(status.attributes('aria-live')).toBe('polite')
      expect(wrapper.findAll('[role="status"]')).toHaveLength(1)
      expect(status.text()).toBe('')

      await wrapper.setProps({changes: changes({previousScanId: 'scan-2', newCount: 1, unchangedCount: 3})})
      await flushPromises()
      expect(status.text()).toBe('1 new, 0 resolved, 3 unchanged since the previous scan.')

      // The same scan read again (a dismissal reloads the report) is not announced twice.
      await wrapper.setProps({changes: changes({previousScanId: 'scan-2', newCount: 1, unchangedCount: 3})})
      expect(status.text()).toBe('1 new, 0 resolved, 3 unchanged since the previous scan.')
    })
  })
})
