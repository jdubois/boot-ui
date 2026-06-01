import {mount} from '@vue/test-utils'
import {createMemoryHistory, createRouter} from 'vue-router'
import {describe, expect, it} from 'vitest'

import {routes} from '../../routes.js'
import CommandPalette from './CommandPalette.vue'

const namedRoutes = routes.filter((route) => route.name && route.meta?.title)

async function mountPalette() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes
  })
  await router.push('/overview')
  await router.isReady()

  return mount(CommandPalette, {
    global: {
      plugins: [router]
    }
  })
}

describe('CommandPalette', () => {
  it('lists every navigable panel before filtering', async () => {
    const wrapper = await mountPalette()
    const items = wrapper.findAll('.cp-item')

    expect(items).toHaveLength(namedRoutes.length)
    expect(items.map((item) => item.find('.cp-item-title').text())).toEqual(
      namedRoutes.map((route) => route.meta.title)
    )
  })
})
