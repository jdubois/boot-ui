import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'
import MySqlTable from './MySqlTable.vue'

const columns = [
  {key: 'name', label: 'Name'},
  {key: 'calls', label: 'Calls', type: 'counter'},
  {key: 'duration', label: 'Duration', type: 'ms'},
  {key: 'enabled', label: 'Enabled'}
]
function table(rows) {
  return mount(MySqlTable, {props: {label: 'statements', rows, columns}})
}
function names(wrapper) {
  return wrapper.findAll('tbody tr').map((row) => row.find('td').text())
}

describe('MySQL retained-row tables', () => {
  it('sorts huge counters exactly in both directions and never turns null into zero', async () => {
    const wrapper = table([
      {name: 'larger', calls: '9007199254740993', duration: null, enabled: null},
      {name: 'unknown', calls: null, duration: 0, enabled: false},
      {name: 'smaller', calls: '9007199254740992', duration: 1.25, enabled: true}
    ])
    expect(wrapper.text()).toContain('9,007,199,254,740,993')
    expect(wrapper.text()).toContain('9,007,199,254,740,992')
    expect(wrapper.text()).toContain('1.25 ms')
    expect(wrapper.text()).toContain('0 ms')
    const sort = wrapper.findAll('thead button')[1]
    await sort.trigger('click')
    expect(names(wrapper)).toEqual(['smaller', 'larger', 'unknown'])
    expect(wrapper.findAll('th')[1].attributes('aria-sort')).toBe('ascending')
    await sort.trigger('click')
    expect(names(wrapper)).toEqual(['larger', 'smaller', 'unknown'])
    expect(wrapper.findAll('th')[1].attributes('aria-sort')).toBe('descending')
    expect(wrapper.findAll('tbody tr')[0].findAll('td')[2].text()).toBe('—')
    expect(wrapper.text()).toContain('Yes')
    expect(wrapper.text()).toContain('No')
  })

  it('filters only allow-listed displayed values, not hidden raw properties', async () => {
    const rows = [
      {name: 'Orders', calls: '12', rawSql: 'never render or search this raw payload'},
      {name: 'Inventory', calls: '3'}
    ]
    const wrapper = table(rows)
    await wrapper.get('input').setValue('ORDERS')
    expect(names(wrapper)).toEqual(['Orders'])
    expect(wrapper.text()).toContain('1 of 2 retained rows · local filter only')
    await wrapper.get('input').setValue('raw payload')
    expect(wrapper.text()).toContain('No retained rows match this filter.')
    expect(wrapper.text()).not.toContain('never render')
    expect(rows.map((row) => row.name)).toEqual(['Orders', 'Inventory'])
  })

  it('sorts strings and numeric durations and exposes an accessible scroll region', async () => {
    const wrapper = table([
      {name: 'z', duration: 2},
      {name: 'a', duration: 12}
    ])
    await wrapper.findAll('thead button')[0].trigger('click')
    expect(names(wrapper)).toEqual(['a', 'z'])
    await wrapper.findAll('thead button')[2].trigger('click')
    expect(names(wrapper)).toEqual(['z', 'a'])
    expect(wrapper.get('[role="region"]').attributes('aria-label')).toBe('statements')
    expect(wrapper.get('[role="region"]').attributes('tabindex')).toBe('0')
    expect(wrapper.get('caption').text()).toContain('retained observations')
    expect(wrapper.get('label').text()).toContain('Filter retained statements')
  })

  it('renders an explicitly supplied empty observation without a fake table', () => {
    const wrapper = mount(MySqlTable, {
      props: {label: 'replication channels', rows: [], columns, emptyMessage: 'No local channels were observed.'}
    })
    expect(wrapper.text()).toContain('No local channels were observed.')
    expect(wrapper.find('table').exists()).toBe(false)
  })
})
