import {mount} from '@vue/test-utils'
import {afterEach, describe, expect, it} from 'vitest'
import PanelTabs from './PanelTabs.vue'

const TABS = [
  {id: 'one', label: 'One', icon: 'bi-1-circle'},
  {id: 'two', label: 'Two'},
  {id: 'three', label: 'Three'}
]

describe('PanelTabs', () => {
  let wrapper

  afterEach(() => wrapper?.unmount())

  function mountTabs(props = {}, slots = {}) {
    wrapper = mount(PanelTabs, {
      attachTo: document.body,
      props: {tabs: TABS, selected: 'one', label: 'Example views', idPrefix: 'example', ...props},
      slots
    })
    return wrapper
  }

  const tabs = () => wrapper.findAll('[role="tab"]')

  it('renders a labelled tablist whose tabs point at their panels', () => {
    mountTabs()

    const list = wrapper.get('[role="tablist"]')
    expect(list.attributes('aria-label')).toBe('Example views')
    expect(list.classes()).toContain('bootui-tabs')
    expect(wrapper.findAll('li').every((item) => item.attributes('role') === 'presentation')).toBe(true)
    expect(tabs()[0].attributes()).toMatchObject({
      id: 'example-tab-one',
      'aria-controls': 'example-panel-one',
      'aria-selected': 'true',
      tabindex: '0',
      type: 'button'
    })
    expect(tabs()[0].classes()).toEqual(expect.arrayContaining(['bootui-tabs__tab', 'active']))
    expect(tabs()[1].attributes()).toMatchObject({'aria-selected': 'false', tabindex: '-1'})
    expect(tabs()[0].find('i.bi-1-circle').attributes('aria-hidden')).toBe('true')
  })

  it('never renders Bootstrap nav link styling, which paints inactive tabs as blue links', () => {
    mountTabs()

    expect(wrapper.find('.nav-link, .nav-tabs, .nav-pills').exists()).toBe(false)
  })

  it('builds ids from custom functions when a panel already owns its id scheme', () => {
    mountTabs({tabId: (id) => `custom-${id}-tab`, panelId: (id) => `custom-${id}-panel`})

    expect(tabs()[1].attributes('id')).toBe('custom-two-tab')
    expect(tabs()[1].attributes('aria-controls')).toBe('custom-two-panel')
  })

  it('emits a selection on click, but not for the tab that is already selected', async () => {
    mountTabs()

    await tabs()[0].trigger('click')
    await tabs()[2].trigger('click')

    expect(wrapper.emitted('select')).toEqual([['three']])
  })

  it.each([
    ['ArrowRight', 0, 'two', 1],
    ['ArrowLeft', 0, 'three', 2],
    ['ArrowRight', 2, 'one', 0],
    ['End', 0, 'three', 2],
    ['Home', 2, 'one', 0]
  ])('moves selection and focus with %s from tab %i', async (key, from, expected, focused) => {
    mountTabs({selected: TABS[from].id})

    await tabs()[from].trigger('keydown', {key})

    expect(wrapper.emitted('select')).toEqual([[expected]])
    expect(document.activeElement).toBe(tabs()[focused].element)
  })

  it('ignores keys that are not tab navigation', async () => {
    mountTabs()

    await tabs()[0].trigger('keydown', {key: 'ArrowDown'})
    await tabs()[0].trigger('keydown', {key: 'a'})

    expect(wrapper.emitted('select')).toBeUndefined()
  })

  it('skips disabled tabs in both pointer and keyboard selection', async () => {
    mountTabs({tabs: [TABS[0], {...TABS[1], disabled: true}, TABS[2]]})

    expect(tabs()[1].attributes('disabled')).toBeDefined()
    await tabs()[0].trigger('keydown', {key: 'ArrowRight'})

    expect(wrapper.emitted('select')).toEqual([['three']])
    expect(document.activeElement).toBe(tabs()[2].element)
  })

  it('keeps exactly one tab in the tab order when the remembered selection is gone', () => {
    mountTabs({selected: 'missing'})

    expect(tabs().map((tab) => tab.attributes('tabindex'))).toEqual(['0', '-1', '-1'])
    expect(wrapper.findAll('[aria-selected="true"]')).toHaveLength(1)
  })

  it('lets the owner render rich tab content such as count chips', () => {
    mountTabs(
      {},
      {
        tab: `<template #tab="{tab, selected}"><span>{{ tab.label }}</span><span class="bootui-tabs__count">{{ selected ? 1 : 0 }}</span></template>`
      }
    )

    expect(tabs()[0].text()).toBe('One1')
    expect(tabs()[1].text()).toBe('Two0')
  })
})
