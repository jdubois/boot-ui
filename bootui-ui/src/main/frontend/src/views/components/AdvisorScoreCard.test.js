import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'

import AdvisorScoreCard from './AdvisorScoreCard.vue'

describe('AdvisorScoreCard', () => {
  it('renders the score, /100, and qualitative band when a score is provided', () => {
    const wrapper = mount(AdvisorScoreCard, {props: {score: 90}})
    expect(wrapper.find('.advisor-score-card').exists()).toBe(true)
    expect(wrapper.text()).toContain('Advisor score')
    expect(wrapper.text()).toContain('90')
    expect(wrapper.text()).toContain('/ 100')
    expect(wrapper.text()).toContain('Good')
  })

  it('uses the at-risk band for low scores', () => {
    const wrapper = mount(AdvisorScoreCard, {props: {score: 40}})
    expect(wrapper.text()).toContain('At risk')
    expect(wrapper.find('.advisor-score-gauge--danger').exists()).toBe(true)
  })

  it('renders nothing until a scan has produced a score', () => {
    const wrapper = mount(AdvisorScoreCard, {props: {score: null}})
    expect(wrapper.find('.advisor-score-card').exists()).toBe(false)
    expect(wrapper.text()).toBe('')
  })

  it('notes how many dismissed rules are excluded from the score', () => {
    const wrapper = mount(AdvisorScoreCard, {props: {score: 97, dismissedCount: 2}})
    expect(wrapper.text()).toContain('2 dismissed rule(s) excluded from this score')
  })

  it('omits the dismissed note when nothing is dismissed', () => {
    const wrapper = mount(AdvisorScoreCard, {props: {score: 100, dismissedCount: 0}})
    expect(wrapper.text()).not.toContain('excluded from this score')
  })
})
