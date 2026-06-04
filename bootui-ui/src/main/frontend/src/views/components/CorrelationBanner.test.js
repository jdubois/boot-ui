import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'

import CorrelationBanner from './CorrelationBanner.vue'

const targets = [
  {name: 'log-tail', label: 'Logs', icon: 'bi-terminal'},
  {name: 'http-exchanges', label: 'HTTP Exchanges', icon: 'bi-arrow-left-right'}
]

describe('CorrelationBanner', () => {
  it('renders nothing when no trace id is present', () => {
    const wrapper = mount(CorrelationBanner, {props: {traceId: null, targets}})
    expect(wrapper.find('.correlation-banner').exists()).toBe(false)
  })

  it('renders pivot buttons and emits pivot and clear events', async () => {
    const wrapper = mount(CorrelationBanner, {
      props: {traceId: '4bf92f3577b34da6a3ce929d0e0e4736', targets}
    })

    expect(wrapper.find('.correlation-banner').exists()).toBe(true)
    // Long trace ids are shortened for display.
    expect(wrapper.text()).toContain('id: 4bf92f3577b3…')

    const pivots = wrapper.findAll('.correlation-pivot')
    expect(pivots).toHaveLength(2)
    expect(pivots[0].text()).toContain('Logs')

    await pivots[1].trigger('click')
    expect(wrapper.emitted('pivot')[0]).toEqual(['http-exchanges'])

    await wrapper.find('.btn-outline-secondary').trigger('click')
    expect(wrapper.emitted('clear')).toHaveLength(1)
  })
})
