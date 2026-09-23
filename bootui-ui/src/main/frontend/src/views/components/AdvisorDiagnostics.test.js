import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'

import AdvisorDiagnostics from './AdvisorDiagnostics.vue'

describe('AdvisorDiagnostics', () => {
  it('renders nothing without diagnostics', () => {
    const wrapper = mount(AdvisorDiagnostics, {props: {diagnostics: []}})

    expect(wrapper.find('.card').exists()).toBe(false)
  })

  it('toggles a labelled list with level badges and optional units', async () => {
    const wrapper = mount(AdvisorDiagnostics, {
      props: {
        diagnostics: [
          {source: 'HIB-FETCH-003', unit: 'default', level: 'ERROR', message: 'Rule evaluation failed.'},
          {source: 'HIB-POOL-001', unit: 'default', level: 'INFO', message: 'Not observed by design.'},
          {source: 'reporting', level: 'WARNING', message: 'Datasource unreadable.'}
        ]
      }
    })

    expect(wrapper.text()).toContain('3 notes — not counted as findings')
    const toggle = wrapper.get('button')
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('ul').exists()).toBe(false)

    await toggle.trigger('click')

    const list = wrapper.get('ul')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(toggle.attributes('aria-controls')).toBe(list.attributes('id'))
    const items = wrapper.findAll('li')
    expect(items).toHaveLength(3)
    expect(items[0].get('.badge').classes()).toContain('text-bg-danger')
    expect(items[0].text()).toContain('[default]')
    expect(items[1].get('.badge').classes()).toContain('text-bg-secondary')
    expect(items[2].get('.badge').classes()).toContain('text-bg-warning')
    expect(items[2].text()).not.toContain('[')
  })

  it('uses the singular for one note', () => {
    const wrapper = mount(AdvisorDiagnostics, {
      props: {diagnostics: [{source: 'discovery', level: 'WARNING', message: 'Gap.'}]}
    })

    expect(wrapper.text()).toContain('1 note — not counted as findings')
  })
})
