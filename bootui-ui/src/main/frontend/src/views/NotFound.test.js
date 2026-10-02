import {mount} from '@vue/test-utils'
import {describe, expect, it, vi} from 'vitest'

import NotFound from './NotFound.vue'

describe('NotFound', () => {
  it('offers overview and command-search recovery actions', async () => {
    const openCommandPalette = vi.fn()
    const wrapper = mount(NotFound, {
      global: {
        provide: {openCommandPalette},
        stubs: {
          RouterLink: {
            props: ['to'],
            template: '<a :href="to"><slot /></a>'
          }
        }
      }
    })

    expect(wrapper.get('h2').text()).toBe('Page not found')
    expect(wrapper.get('a').attributes('href')).toBe('/scorecard')

    await wrapper.get('button').trigger('click')
    expect(openCommandPalette).toHaveBeenCalledOnce()
  })
})
