import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'

import TraceIdTag from './TraceIdTag.vue'

const traceId = '4bf92f3577b34da6a3ce929d0e0e4736'

describe('TraceIdTag', () => {
  it('renders a shortened clickable trace id tag and emits the trace id', async () => {
    const wrapper = mount(TraceIdTag, {props: {traceId}})

    expect(wrapper.element.tagName).toBe('BUTTON')
    expect(wrapper.classes()).toContain('correlation-id-btn')
    expect(wrapper.text()).toContain('id: 4bf92f3577b3…')
    expect(wrapper.attributes('title')).toBe(`Correlate by trace ${traceId}`)

    await wrapper.trigger('click')

    expect(wrapper.emitted('correlate')).toEqual([[traceId]])
  })

  it('can render a non-clickable full trace id tag', () => {
    const wrapper = mount(TraceIdTag, {
      props: {traceId, clickable: false, short: false}
    })

    expect(wrapper.element.tagName).toBe('SPAN')
    expect(wrapper.classes()).not.toContain('correlation-id-btn')
    expect(wrapper.text()).toContain(`id: ${traceId}`)
  })
})
