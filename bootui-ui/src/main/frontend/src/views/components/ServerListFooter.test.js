import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'

import ServerListFooter from './ServerListFooter.vue'

function mountFooter(props = {}) {
  return mount(ServerListFooter, {
    props: {
      shown: 200,
      matched: 450,
      total: 900,
      pageSize: 200,
      itemLabel: 'beans',
      ...props
    }
  })
}

describe('ServerListFooter', () => {
  it('renders server-side counts and emits load more', async () => {
    const wrapper = mountFooter()

    expect(wrapper.text()).toContain('Showing 200 of 450 matching beans (900 total). Filters run on the server.')
    expect(wrapper.text()).toContain('Load next 200')

    await wrapper.find('button').trigger('click')

    expect(wrapper.emitted('loadMore')).toHaveLength(1)
  })

  it('uses the remaining hidden count when it is smaller than the page size', () => {
    const wrapper = mountFooter({shown: 430})

    expect(wrapper.text()).toContain('Load next 20')
  })

  it('hides the button when every matching item is loaded', () => {
    const wrapper = mountFooter({shown: 450})

    expect(wrapper.find('button').exists()).toBe(false)
  })
})
