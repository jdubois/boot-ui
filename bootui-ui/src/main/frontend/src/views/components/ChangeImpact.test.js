import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import ChangeImpact from './ChangeImpact.vue'

const resolved = {
  status: 'RESOLVED',
  reason: null,
  symbol: 'ProductRepository',
  node: 'REPOSITORY productRepository',
  candidates: [],
  structuralReach: 4,
  observed: [
    {
      route: 'GET /api/products',
      requests: 12,
      anonymous: 2,
      errors: 1,
      exemplarRequestIds: ['r2', 'r1'],
      reads: ['TABLE sample_products'],
      writes: [],
      shared: [],
      check: null
    }
  ],
  observedTotal: 1,
  notExercised: [
    {
      route: 'GET /api/products/{id}',
      requests: 0,
      anonymous: 0,
      errors: 0,
      exemplarRequestIds: [],
      reads: [],
      writes: [],
      shared: [],
      check: 'Exercise `GET /api/products/{id}` before relying on this change: no request reached it in this run.'
    }
  ],
  notExercisedTotal: 1,
  sharedResources: [],
  sharedResourcesTotal: 0,
  limitations: [
    'A route listed as observed ran in this run; its traffic does not prove that a request went through it.'
  ]
}

function jsonResponse(body) {
  return {ok: true, status: 200, json: () => Promise.resolve(body)}
}

function mountImpact(props = {}) {
  return mount(ChangeImpact, {props, global: {stubs: {'router-link': {template: '<a><slot /></a>'}}}})
}

describe('ChangeImpact', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    vi.unstubAllGlobals()
  })

  it('reads nothing until a symbol is asked for, then lists what ran, what did not, and what shares a resource', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(resolved))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountImpact()
    await flushPromises()
    expect(fetchMock).not.toHaveBeenCalled()

    await wrapper.find('input').setValue('ProductRepository')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(String(fetchMock.mock.calls[0][0])).toContain('api/runtime-insights/impact?symbol=ProductRepository')
    expect(wrapper.find('.insight-impact-node').text().replace(/\s+/g, ' ')).toBe('repository productRepository')
    const observed = wrapper.find('[data-list="observed"]')
    expect(observed.text()).toContain('12 requests · 2 anonymous · 1 error')
    expect(observed.text()).toContain('Reads TABLE sample_products')
    expect(wrapper.find('[data-list="not-exercised"] code').text()).toBe('GET /api/products/{id}')
    expect(wrapper.find('[data-list="shared"]').text()).toContain(
      'No other route uses what the routes through it touched.'
    )
  })

  it('shows a failed read as its message, never as an object', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Request failed with status 403')))
    wrapper = mountImpact()
    await wrapper.find('input').setValue('ProductRepository')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toBe('Unable to read the change impact: Request failed with status 403')
    expect(alert.text()).not.toContain('{')
  })

  it('jumps to a list in place rather than through the hash router', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(resolved)))
    wrapper = mount(ChangeImpact, {
      props: {initialSymbol: 'ProductRepository'},
      attachTo: document.body,
      global: {stubs: {'router-link': {template: '<a><slot /></a>'}}}
    })
    await flushPromises()

    const links = wrapper.findAll('.insight-impact-summary-link')
    expect(links.map((link) => link.element.tagName)).toEqual(['BUTTON', 'BUTTON', 'BUTTON'])
    expect(links.some((link) => link.attributes('href'))).toBe(false)
    const target = wrapper.find('#insight-impact-not-exercised').element
    target.scrollIntoView = vi.fn()

    await links[1].trigger('click')

    expect(target.scrollIntoView).toHaveBeenCalledWith({block: 'start', behavior: 'smooth'})
    expect(document.activeElement).toBe(target)
  })

  it('offers the candidates of an ambiguous symbol and checks the one chosen', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({
          ...resolved,
          status: 'AMBIGUOUS',
          reason: '`Mapper` names 2 nodes: name one of them.',
          node: null,
          candidates: ['BEAN orderMapper', 'BEAN productMapper'],
          observed: [],
          notExercised: [],
          limitations: []
        })
      )
      .mockResolvedValueOnce(jsonResponse(resolved))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mountImpact({initialSymbol: 'Mapper'})
    await flushPromises()

    expect(wrapper.find('.insight-impact-reason code').text()).toBe('Mapper')
    await wrapper.findAll('.insight-impact-candidate')[1].trigger('click')
    await flushPromises()

    expect(String(fetchMock.mock.calls[1][0])).toContain('symbol=BEAN%20productMapper')
    expect(wrapper.find('input').element.value).toBe('productMapper')
  })

  describe('suggestions', () => {
    const symbols = {
      available: true,
      unavailableReason: null,
      query: 'product',
      symbols: [
        {kind: 'REPOSITORY', name: 'productRepository', type: 'com.example.ProductRepository'},
        {kind: 'BEAN', name: 'catalog', type: 'com.example.ProductCatalog'},
        {kind: 'ROUTE', name: 'GET /api/products', type: null},
        {kind: 'TABLE', name: 'sample_products', type: null}
      ],
      total: 9
    }

    beforeEach(() => {
      vi.useFakeTimers({toFake: ['setTimeout', 'clearTimeout']})
    })

    afterEach(() => {
      vi.useRealTimers()
    })

    async function type(value) {
      await wrapper.find('input').setValue(value)
      vi.advanceTimersByTime(200)
      await flushPromises()
    }

    it('lists the matching symbols with their kind as you type, and reads nothing for an empty field', async () => {
      const fetchMock = vi.fn().mockResolvedValue(jsonResponse(symbols))
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mountImpact()

      await type('   ')
      expect(fetchMock).not.toHaveBeenCalled()

      await type('product')
      expect(fetchMock).toHaveBeenCalledTimes(1)
      expect(String(fetchMock.mock.calls[0][0])).toContain('api/runtime-insights/impact/symbols?query=product')
      const input = wrapper.find('input')
      expect(input.attributes('role')).toBe('combobox')
      expect(input.attributes('aria-expanded')).toBe('true')
      const options = wrapper.findAll('[role="option"]')
      expect(options.map((option) => option.find('.insight-impact-option-kind').text())).toEqual([
        'repository',
        'bean',
        'route',
        'table'
      ])
      expect(options[0].find('code').text()).toBe('productRepository')
      expect(options[0].find('.insight-impact-option-type').exists()).toBe(false)
      expect(options[1].find('.insight-impact-option-type').text()).toBe('ProductCatalog')
      expect(wrapper.find('[role="status"]').text()).toBe('4 suggestions, 5 more: keep typing')
    })

    it('checks exactly the symbol picked with the keyboard, showing its name', async () => {
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(symbols))
        .mockResolvedValueOnce(jsonResponse(resolved))
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mountImpact()
      await type('product')

      const input = wrapper.find('input')
      await input.trigger('keydown', {key: 'ArrowDown'})
      await input.trigger('keydown', {key: 'ArrowDown'})
      await input.trigger('keydown', {key: 'ArrowDown'})
      expect(input.attributes('aria-activedescendant')).toBe('insight-impact-option-2')
      expect(wrapper.findAll('[role="option"]')[2].attributes('aria-selected')).toBe('true')
      await input.trigger('keydown', {key: 'ArrowUp'})
      await input.trigger('keydown', {key: 'Enter'})
      await flushPromises()

      expect(String(fetchMock.mock.calls[1][0])).toContain('symbol=BEAN%20catalog')
      expect(input.element.value).toBe('catalog')
      expect(input.attributes('aria-expanded')).toBe('false')
      expect(wrapper.find('[role="listbox"]').exists()).toBe(false)
    })

    it('checks a symbol picked with the mouse, and Escape closes the list without checking', async () => {
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(symbols))
        .mockResolvedValueOnce(jsonResponse(symbols))
        .mockResolvedValueOnce(jsonResponse(resolved))
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mountImpact()
      await type('product')

      await wrapper.find('input').trigger('keydown', {key: 'Escape'})
      expect(wrapper.find('input').attributes('aria-expanded')).toBe('false')
      expect(fetchMock).toHaveBeenCalledTimes(1)

      await type('products')
      await wrapper.findAll('[role="option"]')[3].trigger('click')
      await flushPromises()
      expect(String(fetchMock.mock.calls[2][0])).toContain('symbol=TABLE%20sample_products')
      expect(wrapper.find('input').element.value).toBe('sample_products')
    })

    it('says when nothing matches or the suggestions cannot be read', async () => {
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce(jsonResponse({...symbols, query: 'zzz', symbols: [], total: 0}))
        .mockResolvedValueOnce({ok: false, status: 500, json: () => Promise.resolve({message: 'boom'})})
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mountImpact()

      await type('zzz')
      expect(wrapper.find('[role="listbox"]').exists()).toBe(false)
      expect(wrapper.find('input').attributes('aria-expanded')).toBe('false')
      expect(wrapper.find('[role="status"]').text()).toBe("Nothing in this run's model matches “zzz”.")

      await type('zzzz')
      expect(wrapper.find('[role="status"]').text()).not.toBe('')
      expect(wrapper.find('.insight-impact-popup').isVisible()).toBe(true)
    })

    it('ignores suggestions that arrive after a newer query', async () => {
      let resolveFirst
      const fetchMock = vi
        .fn()
        .mockReturnValueOnce(new Promise((resolve) => (resolveFirst = resolve)))
        .mockResolvedValueOnce(jsonResponse({...symbols, query: 'products', symbols: symbols.symbols.slice(3)}))
      vi.stubGlobal('fetch', fetchMock)
      wrapper = mountImpact()

      await wrapper.find('input').setValue('product')
      vi.advanceTimersByTime(200)
      await type('products')
      resolveFirst(jsonResponse(symbols))
      await flushPromises()

      expect(wrapper.findAll('[role="option"]').map((option) => option.find('code').text())).toEqual([
        'sample_products'
      ])
    })
  })
})
