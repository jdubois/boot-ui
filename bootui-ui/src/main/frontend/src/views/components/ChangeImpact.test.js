import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

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

    expect(String(fetchMock.mock.calls[1][0])).toContain('symbol=productMapper')
    expect(wrapper.find('input').element.value).toBe('productMapper')
  })
})
