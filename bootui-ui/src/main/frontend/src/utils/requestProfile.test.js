import {describe, expect, it} from 'vitest'

import {
  cacheAccessSummary,
  childTierLabel,
  securityEventExact,
  profileSections,
  restCallSummary,
  tierLabel,
  tierTitle,
  unavailableTiersText
} from './requestProfile.js'

describe('requestProfile helpers', () => {
  it('labels the request-id tier and treats a request-id security match as exact', () => {
    expect(tierLabel('REQUEST_ID')).toBe('request id')
    const section = {childTiers: ['REQUEST_ID', 'TIME_WINDOW']}
    expect(securityEventExact(section, {threadMatched: false}, 0)).toBe(true)
    expect(securityEventExact(section, {threadMatched: false}, 1)).toBe(false)
    expect(securityEventExact(section, {threadMatched: true}, 1)).toBe(true)
    expect(securityEventExact(undefined, {threadMatched: false}, 0)).toBe(false)
  })

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
    expect(sections.SQL.truncationText).toBe(
      'Execution counts and timing cover all 250 correlated statements; the profile lists the first 200.'
    )
    expect(sections.CACHE.available).toBe(false)
    expect(sections.CACHE.tierLabel).toBe('')
    expect(sections.CACHE.truncationText).toBe('')
  })

  it('labels each child only when a section mixes tiers', () => {
    const sections = profileSections({
      sections: [
        {type: 'REST_CLIENT', tier: 'SERVING_THREAD', childTiers: ['SERVING_THREAD', 'TRACE_ID'], total: 2},
        {type: 'CACHE', tier: 'TRACE_ID', childTiers: ['TRACE_ID', 'TRACE_ID'], total: 2},
        {type: 'SECURITY', tier: 'TIME_WINDOW', childTiers: ['TIME_WINDOW', 'TIME_WINDOW'], total: 3, truncated: 1}
      ]
    })

    expect(childTierLabel(sections.REST_CLIENT, 1)).toBe('trace id')
    const truncatedWeaker = profileSections({
      sections: [{type: 'SQL', tier: 'SERVING_THREAD', childTiers: ['TRACE_ID', 'TRACE_ID'], total: 3, truncated: 1}]
    })
    expect(childTierLabel(truncatedWeaker.SQL, 0)).toBe('trace id')
    expect(childTierLabel(sections.CACHE, 0)).toBe('')
    expect(childTierLabel(undefined, 0)).toBe('')
    expect(sections.SECURITY.truncationText).toBe('Showing the first 2 of 3 security events.')
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
