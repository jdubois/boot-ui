import {mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it} from 'vitest'
import SourceLocationPreference from './SourceLocationPreference.vue'
import {OPEN_IN_STORAGE_KEY, resetOpenInPreference, useOpenInPreference} from '../../utils/sourceLocation.js'

beforeEach(() => {
  window.localStorage.clear()
  resetOpenInPreference()
})
afterEach(() => {
  window.localStorage.clear()
  resetOpenInPreference()
})

describe('SourceLocationPreference', () => {
  it('is a labelled select that defaults to None and offers only the fixed editors', () => {
    const wrapper = mount(SourceLocationPreference)
    const select = wrapper.get('select')
    expect(wrapper.get(`label[for="${select.attributes('id')}"]`).text()).toBe('Open locations in')
    expect(select.element.value).toBe('none')
    expect(wrapper.findAll('option').map((option) => option.text())).toEqual(['None', 'VS Code', 'IntelliJ IDEA'])
  })

  it('stores the chosen editor per browser and shares it with every location', async () => {
    const wrapper = mount(SourceLocationPreference)
    await wrapper.get('select').setValue('vscode')
    expect(window.localStorage.getItem(OPEN_IN_STORAGE_KEY)).toBe('vscode')
    expect(useOpenInPreference().openIn.value).toBe('vscode')
  })

  it('lists why some locations have no source path', () => {
    const wrapper = mount(SourceLocationPreference, {
      props: {notes: ['2 class(es) were loaded from an archive, so they have no local source path.', null]}
    })
    expect(wrapper.findAll('li').map((item) => item.text())).toEqual([
      '2 class(es) were loaded from an archive, so they have no local source path.'
    ])
  })
})
