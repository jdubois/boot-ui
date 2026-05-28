import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'

import ProgressiveListFooter from './ProgressiveListFooter.vue'

function mountFooter(props = {}) {
  return mount(ProgressiveListFooter, {
    props: {
      shown: 200,
      total: 450,
      hidden: 250,
      chunkSize: 200,
      itemLabel: 'beans',
      ...props
    }
  })
}

describe('ProgressiveListFooter', () => {
  it('renders progressive list counts and emits reveal actions', async () => {
    const wrapper = mountFooter()

    expect(wrapper.text()).toContain('Showing 200 of 450 beans. Filters search the full list.')
    expect(wrapper.text()).toContain('Show next 200')
    expect(wrapper.text()).toContain('Show all')

    const buttons = wrapper.findAll('button')
    await buttons[0].trigger('click')
    await buttons[1].trigger('click')

    expect(wrapper.emitted('showMore')).toHaveLength(1)
    expect(wrapper.emitted('showAll')).toHaveLength(1)
  })

  it('uses the remaining hidden count when it is smaller than the chunk size', () => {
    const wrapper = mountFooter({hidden: 25})

    expect(wrapper.text()).toContain('Show next 25')
  })

  it('does not render when every item is already visible', () => {
    const wrapper = mountFooter({shown: 450, hidden: 0})

    expect(wrapper.find('[aria-live="polite"]').exists()).toBe(false)
  })
})
