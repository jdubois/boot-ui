import {flushPromises, mount} from '@vue/test-utils'
import {createMemoryHistory, createRouter} from 'vue-router'
import {ref} from 'vue'
import {afterEach, describe, expect, it, vi} from 'vitest'

import {routes} from '../../routes.js'
import {safeLocalStorage} from '../../utils/safeStorage.js'
import CommandPalette from './CommandPalette.vue'

const namedRoutes = routes.filter((route) => route.name && route.meta?.title)

async function mountPalette(options = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: routes.map((route) => (route.redirect ? route : {...route, component: {template: '<section />'}}))
  })
  await router.push('/overview')
  await router.isReady()

  const wrapper = mount(CommandPalette, {
    attachTo: options.attachTo,
    global: {
      plugins: [router],
      provide: options.panels ? {panels: ref(options.panels)} : {}
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
  afterEach(() => {
    document.body.innerHTML = ''
    vi.restoreAllMocks()
  })

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
    expect(titles[0]).toBe('AI Framework')
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
    expect(await titlesFor('gc')).not.toContain('AI Framework')
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

  it('uses the manifest platform for the shared application-advisor label and search', async () => {
    const {wrapper} = await mountPalette({
      panels: {
        platform: 'quarkus',
        panels: namedRoutes.map((route) => ({id: route.name, available: true, enabled: true}))
      }
    })

    await setQuery(wrapper, 'quarkus')

    expect(wrapper.findAll('.cp-item-title').map((item) => item.text())).toContain('Quarkus')
    expect(wrapper.findAll('.cp-item-title').map((item) => item.text())).not.toContain('Spring')
  })

  it('shows each result under the group title the sidebar uses', async () => {
    const {wrapper} = await mountPalette()
    const groupFor = (title) =>
      wrapper
        .findAll('.cp-item')
        .find((item) => item.find('.cp-item-title').text() === title)
        .get('.cp-item-group')
        .text()

    expect(groupFor('Scorecard')).toBe('Home')
    expect(groupFor('Code Paths')).toBe('Instrumentation')
    expect(groupFor('MCP Server')).toBe('Developer tools')
    expect(groupFor('Health')).toBe('Runtime')
  })

  it('finds panels by their sidebar group title and still by the raw group key', async () => {
    const {wrapper} = await mountPalette()
    const agentTitles = namedRoutes.filter((route) => route.meta.group === 'agent').map((route) => route.meta.title)
    const toolTitles = namedRoutes
      .filter((route) => route.meta.group === 'developer-tools')
      .map((route) => route.meta.title)

    const titlesFor = async (q) => {
      await setQuery(wrapper, q)
      return wrapper.findAll('.cp-item-title').map((item) => item.text())
    }

    expect(await titlesFor('instrumentation')).toEqual(agentTitles)
    expect(await titlesFor('Developer tools')).toEqual(toolTitles)
    expect(await titlesFor('developer-tools')).toEqual(toolTitles)
    expect(await titlesFor('agent')).toEqual(expect.arrayContaining(agentTitles))
  })

  it('keeps unavailable panels discoverable in the explicit sidebar-equivalent group', async () => {
    const {wrapper} = await mountPalette({
      panels: {
        platform: 'spring-boot',
        panels: [
          {
            id: 'ai',
            available: false,
            enabled: true,
            unavailableReason: 'AI support is not installed'
          }
        ]
      }
    })

    await setQuery(wrapper, 'AI Framework')

    const result = wrapper.get('[role="option"]')
    expect(result.classes()).toContain('cp-item--unavailable')
    expect(result.get('.cp-item-group').text()).toBe('Disabled / unavailable')
    expect(result.attributes('aria-label')).toBe('AI Framework - unavailable: AI support is not installed')
    expect(result.get('.cp-item-status').attributes('title')).toBe('Unavailable')
  })

  it('keeps unavailable recent panels first without losing their status', async () => {
    vi.spyOn(safeLocalStorage, 'getJson').mockReturnValue(['ai'])
    const {wrapper} = await mountPalette({
      panels: {
        platform: 'spring-boot',
        panels: [{id: 'ai', available: false, enabled: true}]
      }
    })

    const firstResult = wrapper.get('[role="option"]')
    expect(firstResult.get('.cp-item-title').text()).toBe('AI Framework')
    expect(firstResult.find('.cp-item-recent').exists()).toBe(true)
    expect(firstResult.classes()).toContain('cp-item--unavailable')
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

  it('connects the combobox to its listbox and selected option', async () => {
    const {wrapper} = await mountPalette()
    const input = wrapper.find('.cp-input')
    const listbox = wrapper.find('[role="listbox"]')
    const options = wrapper.findAll('[role="option"]')

    expect(input.attributes('role')).toBe('combobox')
    expect(input.attributes('aria-expanded')).toBe('true')
    expect(input.attributes('aria-controls')).toBe(listbox.attributes('id'))
    expect(input.attributes('aria-activedescendant')).toBe(options[0].attributes('id'))
    expect(options[0].attributes('aria-selected')).toBe('true')

    await input.trigger('keydown', {key: 'ArrowDown'})

    expect(input.attributes('aria-activedescendant')).toBe(options[1].attributes('id'))
    expect(options[0].attributes('aria-selected')).toBe('false')
    expect(options[1].attributes('aria-selected')).toBe('true')

    await input.trigger('keydown', {key: 'End'})
    expect(input.attributes('aria-activedescendant')).toBe(options.at(-1).attributes('id'))

    await input.trigger('keydown', {key: 'Home'})
    expect(input.attributes('aria-activedescendant')).toBe(options[0].attributes('id'))
  })

  it('clears the active descendant when filtering has no results', async () => {
    const {wrapper} = await mountPalette()
    const input = await setQuery(wrapper, 'no-panel-has-this-name')

    expect(wrapper.findAll('[role="option"]')).toHaveLength(0)
    expect(wrapper.find('[role="listbox"]').exists()).toBe(true)
    expect(input.attributes('aria-activedescendant')).toBeUndefined()
  })

  it('contains focus and handles Escape once from anywhere inside the dialog', async () => {
    const {wrapper} = await mountPalette({attachTo: document.body})
    const input = wrapper.find('.cp-input')
    wrapper.vm.focusInput()

    expect(document.activeElement).toBe(input.element)

    await input.trigger('keydown', {key: 'Tab'})
    expect(document.activeElement).toBe(input.element)

    await input.trigger('keydown', {key: 'Tab', shiftKey: true})
    expect(document.activeElement).toBe(input.element)

    await input.trigger('keydown', {key: 'Escape'})
    expect(wrapper.emitted('close')).toEqual([['invoker']])
  })

  it('closes for a content-focus handoff after activating a result', async () => {
    const {wrapper, router} = await mountPalette()
    const input = await setQuery(wrapper, 'Architecture')

    await input.trigger('keydown', {key: 'Enter'})
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('architecture')
    expect(wrapper.emitted('close')).toEqual([['content']])
  })
})
