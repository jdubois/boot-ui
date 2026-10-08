import {mount} from '@vue/test-utils'
import {afterEach, describe, expect, it} from 'vitest'

import CodePathsTree from './CodePathsTree.vue'

const CONTROLLER = 'shop.QuoteController#quote()I'
const SERVICE = 'shop.QuoteService#quote()I'
const SLOW = 'shop.SlowPricingService#quote()I'
const AUDIT = 'shop.AuditService#record()V'

function node(id, parent, depth, kind, method, totalMillis, extra = {}) {
  return {
    id,
    parent,
    depth,
    kind,
    method,
    phase: kind === 'METHOD' ? 'HANDLER' : null,
    async: false,
    requests: 4,
    callsPerRequest: 1,
    totalMillis,
    selfMillis: 1,
    share: kind === 'REQUEST' ? null : totalMillis,
    p50Millis: totalMillis,
    p95Millis: totalMillis,
    children: 0,
    calls: [],
    ...extra
  }
}

const tree = {
  route: 'GET /api/quote',
  shareOf: 'handler',
  nodes: [
    node(0, null, 0, 'REQUEST', null, 100),
    node(1, 0, 1, 'METHOD', CONTROLLER, 90),
    node(2, 1, 2, 'METHOD', SERVICE, 80, {calls: [{kind: 'SQL', callsPerRequest: 2, totalMillis: 5}]}),
    node(3, 2, 3, 'METHOD', SLOW, 70),
    node(4, 1, 2, 'METHOD', AUDIT, 5),
    node(5, 4, 3, 'METHOD', 'shop.AuditLog#write()V', 4)
  ],
  methods: [
    {method: SLOW, callers: [SERVICE], routes: ['GET /api/quote', 'GET /api/other']},
    {method: SERVICE, callers: [CONTROLLER], routes: ['GET /api/quote']}
  ],
  page: {matched: 5, offset: 0, returned: 5, hasMore: false}
}

describe('CodePathsTree', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
  })

  function mountTree(props = {}) {
    wrapper = mount(CodePathsTree, {props: {tree, ...props}, attachTo: document.body})
    return wrapper
  }

  function rows() {
    return wrapper.findAll('tbody tr')
  }

  function labels() {
    return rows().map((row) => row.get('td').text().replace(', on the hot path', '').replace('handler', '').trim())
  }

  it('marks the hot path from the request to its slowest leaf', () => {
    mountTree()
    const hot = rows().filter((row) => row.classes('code-paths-node-hot'))
    expect(hot.map((row) => row.get('code').text())).toEqual([
      'QuoteController.quote',
      'QuoteService.quote',
      'SlowPricingService.quote'
    ])
    expect(hot[0].text()).toContain('on the hot path')
  })

  it('collapses every branch off the hot path, then expands them again', async () => {
    mountTree()
    await wrapper.get('.code-paths-hot-only').trigger('click')
    expect(labels()).toEqual([
      'Request',
      'QuoteController.quote',
      'QuoteService.quote',
      'SQL statements, issued while QuoteService.quote was open× 2',
      'SlowPricingService.quote',
      'AuditService.record'
    ])
    expect(rows()[5].attributes('aria-expanded')).toBe('false')
    await wrapper.get('.code-paths-expand-all').trigger('click')
    expect(rows()).toHaveLength(7)
  })

  it('moves through rows with the arrow keys and opens and closes branches with Right and Left', async () => {
    mountTree()
    const request = rows()[0]
    expect(request.attributes('tabindex')).toBe('0')
    expect(rows()[1].attributes('tabindex')).toBe('-1')

    await request.trigger('keydown', {key: 'ArrowDown'})
    expect(document.activeElement).toBe(rows()[1].element)
    expect(rows()[1].attributes('tabindex')).toBe('0')

    await rows()[1].trigger('keydown', {key: 'ArrowLeft'})
    expect(rows()[1].attributes('aria-expanded')).toBe('false')
    expect(rows()).toHaveLength(2)
    await rows()[1].trigger('keydown', {key: 'ArrowRight'})
    expect(rows()[1].attributes('aria-expanded')).toBe('true')
    expect(rows()).toHaveLength(7)

    // Left on a leaf goes to its caller.
    await rows()[4].trigger('keydown', {key: 'ArrowLeft'})
    expect(document.activeElement).toBe(rows()[2].element)
    await rows()[2].trigger('keydown', {key: 'End'})
    expect(document.activeElement).toBe(rows()[6].element)
  })

  it('selects a method with Enter and opens its detail under its row', async () => {
    mountTree()
    const slow = rows()[4]
    await slow.trigger('keydown', {key: 'Enter'})
    const [selection] = wrapper.emitted('select').at(-1)
    expect(selection.method).toBe(SLOW)

    await wrapper.setProps({selected: selection.key})
    expect(rows()[4].attributes('aria-selected')).toBe('true')
    const detail = rows()[5]
    expect(detail.classes()).toContain('code-paths-detail-row')
    expect(detail.get('td').attributes('colspan')).toBe('6')
    expect(detail.get('.code-paths-method-key').text()).toBe(SLOW)
    expect(detail.get('.code-paths-callers').text()).toBe('QuoteService.quote')
    expect(wrapper.emitted('action-target').at(-1)[0]).toBeInstanceOf(HTMLElement)

    await detail.findAll('.code-paths-reach button')[0].trigger('click')
    expect(wrapper.emitted('select-route').at(-1)).toEqual(['GET /api/other'])

    await detail.get('.code-paths-method-close').trigger('click')
    expect(wrapper.emitted('select').at(-1)).toEqual([null])
  })

  it('shows a selected method’s detail even while its branch is collapsed', async () => {
    mountTree()
    await rows()[2].trigger('keydown', {key: 'ArrowLeft'})
    expect(rows()[2].attributes('aria-expanded')).toBe('false')
    await rows()[2].trigger('keydown', {key: 'Enter'})
    await wrapper.setProps({selected: wrapper.emitted('select').at(-1)[0].key})

    expect(rows()[3].classes()).toContain('code-paths-detail-row')
    expect(rows()[3].get('.code-paths-method-key').text()).toBe(SERVICE)
    expect(rows()[4].text()).toContain('AuditService.record')
  })

  it('keeps a node’s collapse state when the tree is read again', async () => {
    mountTree()
    await rows()[2].trigger('click')
    await rows()[1].trigger('keydown', {key: 'ArrowLeft'})
    expect(rows()).toHaveLength(2)

    // A refresh reads the same tree with shifted ids.
    const shifted = {
      ...tree,
      nodes: tree.nodes.map((entry) => ({
        ...entry,
        id: entry.id + 10,
        parent: entry.parent == null ? null : entry.parent + 10
      }))
    }
    await wrapper.setProps({tree: shifted})
    expect(rows()).toHaveLength(2)
    expect(rows()[1].attributes('aria-expanded')).toBe('false')
  })
})
