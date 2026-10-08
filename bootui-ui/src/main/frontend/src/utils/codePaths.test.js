import {describe, expect, it} from 'vitest'

import {hotPath, methodLabel, nodeKeys, splitRoute} from './codePaths.js'

function node(id, parent, depth, kind, method, totalMillis, extra = {}) {
  return {id, parent, depth, kind, method, phase: 'HANDLER', async: false, totalMillis, ...extra}
}

describe('codePaths utilities', () => {
  it('labels a method key by its simple class and name', () => {
    expect(methodLabel('shop.pricing.SlowPricingService#quote(Ljava/lang/String;)I')).toBe('SlowPricingService.quote')
    expect(methodLabel('shop.Money#amount')).toBe('Money.amount')
    expect(methodLabel(null)).toBe('—')
  })

  it('splits a route into its HTTP method and path', () => {
    expect(splitRoute('GET /api/quotes/{sku}')).toEqual({verb: 'GET', path: '/api/quotes/{sku}'})
    expect(splitRoute('/plain')).toEqual({verb: '', path: '/plain'})
  })

  it('keys nodes by their chain of callers, so a key survives ids shifting between reads', () => {
    const first = nodeKeys([node(0, null, 0, 'REQUEST', null, 10), node(1, 0, 1, 'METHOD', 'a#x', 9)])
    const second = nodeKeys([
      node(0, null, 0, 'REQUEST', null, 10),
      node(1, 0, 1, 'METHOD', 'b#y', 1),
      node(2, 0, 1, 'METHOD', 'a#x', 9)
    ])
    expect(second.get(2)).toBe(first.get(1))
    expect(second.get(1)).not.toBe(first.get(1))
  })

  it('follows the child with the most time at each level, never into executor work', () => {
    const nodes = [
      node(0, null, 0, 'REQUEST', null, 100),
      node(1, 0, 1, 'METHOD', 'a#x', 30),
      node(2, 0, 1, 'METHOD', 'b#y', 60),
      node(3, 2, 2, 'METHOD', 'c#z', 50),
      node(4, 0, 1, 'ASYNC', null, 90, {async: true})
    ]
    expect([...hotPath(nodes)]).toEqual([0, 2, 3])
  })
})
