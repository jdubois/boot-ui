import {describe, expect, it} from 'vitest'

import {
  cacheAccessSummary,
  profileSections,
  restCallSummary,
  tierLabel,
  tierTitle,
  unavailableTiersText
} from './requestProfile.js'

describe('requestProfile helpers', () => {
  it('labels every correlation tier and ignores unknown ones', () => {
    expect(tierLabel('TRACE_ID')).toBe('trace id')
    expect(tierLabel('SERVING_THREAD')).toBe('serving thread')
    expect(tierLabel('TIME_WINDOW')).toBe('time window')
    expect(tierLabel(null)).toBe('')
    expect(tierTitle('TIME_WINDOW')).toContain('time window only')
  })

  it('indexes sections by type with their tier label and truncation text', () => {
    const sections = profileSections({
      sections: [
        {type: 'SQL', available: true, tier: 'SERVING_THREAD', total: 250, truncated: 50, ambiguous: 0},
        {type: 'CACHE', available: false, unavailableReason: 'No seam', tier: null, total: 0, truncated: 0}
      ]
    })

    expect(sections.SQL.tierLabel).toBe('serving thread')
    expect(sections.SQL.truncationText).toBe('Showing the first 200 of 250 statements.')
    expect(sections.CACHE.available).toBe(false)
    expect(sections.CACHE.tierLabel).toBe('')
    expect(sections.CACHE.truncationText).toBe('')
  })

  it('degrades to no sections for a profile from an older server', () => {
    expect(profileSections({})).toEqual({})
    expect(profileSections(null)).toEqual({})
    expect(unavailableTiersText({})).toBe('')
  })

  it('summarizes the tiers an adapter cannot provide in one sentence', () => {
    expect(
      unavailableTiersText({
        correlationTiers: [
          {tier: 'TRACE_ID', available: true, unavailableReason: null},
          {tier: 'SERVING_THREAD', available: false, unavailableReason: 'Event loop.'},
          {tier: 'TIME_WINDOW', available: false, unavailableReason: 'Event loop.'}
        ]
      })
    ).toBe('Serving thread and time window correlation are unavailable on this adapter: Event loop.')
    expect(
      unavailableTiersText({correlationTiers: [{tier: 'TIME_WINDOW', available: false, unavailableReason: null}]})
    ).toBe('Time window correlation is unavailable on this adapter.')
  })

  it('summarizes REST client calls and cache accesses from the masked payload only', () => {
    expect(restCallSummary({method: 'GET', host: 'api.example', path: '/items', success: true, status: 200})).toBe(
      'GET api.example/items → 200'
    )
    expect(restCallSummary({method: 'POST', host: 'localhost', path: '/x', success: false, status: null})).toBe(
      'POST localhost/x → failed'
    )
    expect(cacheAccessSummary({operation: 'MISS', cacheName: 'orders', keyHash: 'abc'})).toBe('MISS orders')
  })
})
