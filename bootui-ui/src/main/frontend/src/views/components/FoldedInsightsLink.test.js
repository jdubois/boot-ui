import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'
import {ref} from 'vue'

import FoldedInsightsLink from './FoldedInsightsLink.vue'

const stubs = {RouterLink: {props: ['to'], template: '<a :data-to="JSON.stringify(to)"><slot /></a>'}}

function mountLink(panels) {
  return mount(FoldedInsightsLink, {
    props: {theme: 'errors', what: 'Exception groups per route'},
    global: {stubs, provide: panels === undefined ? {} : {panels: ref(panels)}}
  })
}

describe('FoldedInsightsLink', () => {
  it('links to every row of the folded kind, which is not listed by default, without naming the validation (M4-20)', () => {
    const wrapper = mountLink()

    expect(wrapper.text()).toContain('Exception groups per route, from the runtime journal, are in Runtime Insights')
    expect(wrapper.text()).toContain('which does not list them by default.')
    expect(wrapper.text()).not.toMatch(/validat/i)
    expect(JSON.parse(wrapper.find('a').attributes('data-to'))).toEqual({
      path: '/runtime-insights',
      query: {theme: 'errors', all: '1'}
    })
  })

  it('is absent while Runtime Insights cannot be opened', () => {
    const wrapper = mountLink({panels: [{id: 'runtime-insights', available: false}]})

    expect(wrapper.find('.folded-insights').exists()).toBe(false)
  })
})
