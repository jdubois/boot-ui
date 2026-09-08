import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'
import ScannerScoreCard from './ScannerScoreCard.vue'

describe('ScannerScoreCard', () => {
  it('qualifies a high partial score without a clean-health claim', () => {
    const wrapper = mount(ScannerScoreCard, {
      global: {stubs: {RouterLink: true}},
      props: {
        title: 'Database',
        state: 'done',
        hasReport: true,
        score: 100,
        scoreCompleteness: 'partial',
        scoreLabel: 'Partial assessment',
        scoreReason: 'Some index metadata was unavailable.',
        severityCounts: []
      }
    })
    expect(wrapper.find('.scanner-score').text()).toBe('100')
    expect(wrapper.text()).toContain('Partial assessment')
    expect(wrapper.text()).toContain('Some index metadata')
    expect(wrapper.text()).toContain('No findings in evaluated evidence')
    expect(wrapper.find('[role="img"]').attributes('aria-label')).toContain('Partial assessment')
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
        scoreReason: 'Evidence unavailable.',
        severityCounts: [
          {severity: 'HIGH', count: 2},
          {severity: 'UNKNOWN', count: 1}
        ]
      }
    })
    expect(wrapper.find('.scanner-score').exists()).toBe(false)
    expect(wrapper.text()).toContain('Incomplete')
    expect(wrapper.text()).toContain('2 high')
    expect(wrapper.text()).toContain('1 unknown')
    expect(wrapper.text()).not.toContain('Run this scanner')
    expect(wrapper.text()).not.toContain('No findings')
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
