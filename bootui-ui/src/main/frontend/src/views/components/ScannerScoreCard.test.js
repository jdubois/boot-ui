import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'
import ScannerScoreCard from './ScannerScoreCard.vue'

describe('ScannerScoreCard', () => {
  it.each([
    [100, 'success'],
    [80, 'success'],
    [79, 'warning'],
    [50, 'warning'],
    [49, 'danger'],
    [0, 'danger']
  ])('colors score %i with the historical %s band', (score, tone) => {
    const wrapper = mount(ScannerScoreCard, {
      global: {stubs: {RouterLink: true}},
      props: {title: 'Architecture', state: 'done', hasReport: true, score}
    })
    expect(wrapper.get('.scanner-score').classes()).toContain(`text-${tone}-emphasis`)
    expect(wrapper.get('[role="img"]').attributes('aria-label')).toBe(
      `Architecture known-findings score: ${score} out of 100`
    )
  })

  it('shows a prominent numeric partial 100 without repeated scan notes copy', () => {
    const wrapper = mount(ScannerScoreCard, {
      global: {stubs: {RouterLink: true}},
      props: {
        title: 'Database',
        state: 'done',
        hasReport: true,
        score: 100,
        scoreLabel: 'Results available',
        incomplete: true,
        to: '/database-advisor'
      }
    })
    expect(wrapper.find('.scanner-assessment').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Scan notes available in panel.')
    expect(wrapper.get('.scanner-body').element.firstElementChild.classList.contains('scanner-score-summary')).toBe(
      true
    )
    expect(wrapper.find('router-link-stub').attributes('to')).toBe('/database-advisor')
    expect(wrapper.findAll('[role="img"]')).toHaveLength(1)
    expect(wrapper.find('[role="img"]').attributes('aria-label')).toContain(
      'Database known-findings score: 100 out of 100 — Scan notes available'
    )
    expect(wrapper.text()).toContain('No retained findings in the assessed evidence')
    expect(wrapper.text()).not.toContain('Good')
    expect(wrapper.find('.scanner-score').classes()).toContain('text-success-emphasis')
    expect(wrapper.findAll('.scanner-assessment')).toHaveLength(0)
  })
  it('keeps incomplete findings visible without a score or idle/clean claim', () => {
    const wrapper = mount(ScannerScoreCard, {
      global: {stubs: {RouterLink: true}},
      props: {
        title: 'Security',
        state: 'done',
        hasReport: true,
        score: null,
        scoreLabel: 'Incomplete',
        scoreReason: 'No usable assessment evidence.',
        incomplete: true,
        severityCounts: [
          {severity: 'HIGH', count: 2},
          {severity: 'UNKNOWN', count: 1}
        ]
      }
    })
    expect(wrapper.find('.scanner-score').exists()).toBe(false)
    expect(wrapper.text()).toContain('Incomplete')
    expect(wrapper.get('.scanner-assessment').text()).toContain('No usable assessment evidence.')
    expect(wrapper.find('details').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Scan notes available in panel.')
    expect(wrapper.text()).toContain('2 high')
    expect(wrapper.text()).toContain('1 unknown')
    expect(wrapper.text()).not.toContain('Run this scanner')
    expect(wrapper.text()).not.toContain('No findings')
  })

  it.each([true, false])('keeps score 91 and derives notes only from the incomplete boolean (%s)', (incomplete) => {
    const wrapper = mount(ScannerScoreCard, {
      global: {stubs: {RouterLink: true}},
      props: {
        title: 'Architecture',
        state: 'done',
        hasReport: true,
        score: 91,
        scoreLabel: 'Partial assessment',
        scoreReason: 'Detailed coverage explanation.',
        incomplete
      }
    })
    expect(wrapper.get('.scanner-score').text()).toBe('91')
    expect(wrapper.get('[role="img"]').attributes('aria-label')).toBe(
      `Architecture known-findings score: 91 out of 100${incomplete ? ' — Scan notes available' : ''}`
    )
    expect(wrapper.text()).not.toContain('Scan notes available in panel.')
    expect(wrapper.find('.scanner-assessment').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Detailed coverage explanation.')
  })

  it.each(['running', 'error'])('retains the last report while request state is %s', (state) => {
    const wrapper = mount(ScannerScoreCard, {
      global: {stubs: {RouterLink: true}},
      props: {
        title: 'Security',
        state,
        hasReport: true,
        score: 90,
        errorMessage: 'Offline'
      }
    })
    expect(wrapper.find('.scanner-score').text()).toBe('90')
    expect(wrapper.text()).toContain('Showing the last report')
    expect(wrapper.text()).toContain(state === 'running' ? 'Scanning' : 'Offline')
  })
})
