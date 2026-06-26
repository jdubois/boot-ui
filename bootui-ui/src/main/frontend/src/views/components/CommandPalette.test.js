import {flushPromises, mount} from '@vue/test-utils'
import {createMemoryHistory, createRouter} from 'vue-router'
import {describe, expect, it, vi} from 'vitest'

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

  const wrapper = mount(CommandPalette, {
    global: {
      plugins: [router]
    }
  })

  return {wrapper, router}
}

async function setQuery(wrapper, value) {
  const input = wrapper.find('.cp-input')
  await input.setValue(value)
  return input
}

describe('CommandPalette', () => {
  it('lists every navigable panel before filtering', async () => {
    const {wrapper} = await mountPalette()
    const items = wrapper.findAll('.cp-item')

    expect(items).toHaveLength(namedRoutes.length)
    expect(items.map((item) => item.find('.cp-item-title').text())).toEqual(
      namedRoutes.map((route) => route.meta.title)
    )
  })

  it('shows number badges only while browsing the unfiltered list', async () => {
    const {wrapper} = await mountPalette()
    expect(wrapper.findAll('.cp-item-num').length).toBeGreaterThan(0)

    await setQuery(wrapper, 'security')
    expect(wrapper.findAll('.cp-item-num')).toHaveLength(0)
  })

  it('ranks a panel by its shortcut even when the title does not match', async () => {
    const {wrapper} = await mountPalette()
    await setQuery(wrapper, 'ai')

    const titles = wrapper.findAll('.cp-item-title').map((item) => item.text())
    expect(titles[0]).toBe('AI Usage')
  })

  it('finds panels by keyword synonyms absent from the title', async () => {
    const {wrapper} = await mountPalette()

    const titlesFor = async (q) => {
      await setQuery(wrapper, q)
      return wrapper.findAll('.cp-item-title').map((item) => item.text())
    }

    // Developer shorthand that never appears in a panel title.
    expect(await titlesFor('csrf')).toContain('Security')
    expect(await titlesFor('gc')).toContain('Memory')
    // Word-prefix matching: "gc" must not match the "gc" inside "langchain4j".
    expect(await titlesFor('gc')).not.toContain('AI Usage')
    expect(await titlesFor('env')).toContain('Configuration')
    expect(await titlesFor('hikari')).toContain('Database Connection Pools')
    expect(await titlesFor('cron')).toContain('Scheduled Tasks')
    expect(await titlesFor('n+1')).toEqual(expect.arrayContaining(['SQL Trace', 'Hibernate']))
  })

  it('keeps a title match ranked above a keyword-only match', async () => {
    const {wrapper} = await mountPalette()
    // "Spring" is a title prefix for several panels and a keyword ("spring beans")
    // for Beans; the title matches must rank above the keyword-only match.
    await setQuery(wrapper, 'spring')

    const titles = wrapper.findAll('.cp-item-title').map((item) => item.text())
    expect(titles[0]).toBe('Spring')
    expect(titles).toContain('Beans')
    expect(titles.indexOf('Spring')).toBeLessThan(titles.indexOf('Beans'))
  })

  it('navigates with a number key while browsing the unfiltered list', async () => {
    const {wrapper, router} = await mountPalette()
    const push = vi.spyOn(router, 'push')
    const second = wrapper.findAll('.cp-item').at(1)

    await wrapper.find('.cp-input').trigger('keydown', {key: '2'})
    await flushPromises()

    expect(second.find('.cp-item-num').text()).toBe('2')
    expect(push).toHaveBeenCalledWith(namedRoutes[1].path)
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('does not hijack number keys while a query is active', async () => {
    const {wrapper, router} = await mountPalette()
    await setQuery(wrapper, 'security')
    const push = vi.spyOn(router, 'push')

    await wrapper.find('.cp-input').trigger('keydown', {key: '1'})
    await flushPromises()

    expect(push).not.toHaveBeenCalled()
    expect(wrapper.emitted('close')).toBeUndefined()
  })
})
