import {describe, expect, it, vi} from 'vitest'

import {
  canDeriveLookupId,
  correlationLookupId,
  LOOKUP_ID_LENGTH,
  MAX_VALUE_LENGTH,
  normalizeCorrelationValue
} from './correlationId.js'

describe('normalizeCorrelationValue', () => {
  it('trims and preserves case, because matching is exact', () => {
    expect(normalizeCorrelationValue('  Corr-1  ')).toBe('Corr-1')
  })

  it('refuses values that cannot be a correlation identifier', () => {
    expect(normalizeCorrelationValue('')).toBe('')
    expect(normalizeCorrelationValue('   ')).toBe('')
    expect(normalizeCorrelationValue(null)).toBe('')
    expect(normalizeCorrelationValue(undefined)).toBe('')
    expect(normalizeCorrelationValue('bad\nvalue')).toBe('')
    expect(normalizeCorrelationValue('bad\u007fvalue')).toBe('')
  })

  it('bounds an over-long value exactly like the server does', () => {
    const overlong = 'z'.repeat(MAX_VALUE_LENGTH + 40)
    expect(normalizeCorrelationValue(overlong)).toHaveLength(MAX_VALUE_LENGTH)
  })
})

describe('correlationLookupId', () => {
  it('derives the identities the server derives', async () => {
    // Pinned against CorrelationIdPolicy so the two derivations can never drift apart.
    await expect(correlationLookupId('corr-1')).resolves.toBe('88b87faa5f574f9b')
    await expect(correlationLookupId('req-1')).resolves.toBe('74a2f8fde4aec9c7')
    await expect(correlationLookupId('flow-1')).resolves.toBe('4fdac0bf3032d5c6')
  })

  it('normalizes before hashing, so padding and over-long input match the captured identity', async () => {
    await expect(correlationLookupId('  corr-1 ')).resolves.toBe('88b87faa5f574f9b')
    const overlong = 'z'.repeat(MAX_VALUE_LENGTH + 40)
    await expect(correlationLookupId(overlong)).resolves.toBe(await correlationLookupId('z'.repeat(MAX_VALUE_LENGTH)))
  })

  it('is opaque, fixed-length and value-specific', async () => {
    const id = await correlationLookupId('corr-1')
    expect(id).toHaveLength(LOOKUP_ID_LENGTH)
    expect(id).not.toContain('corr-1')
    expect(id).not.toBe(await correlationLookupId('CORR-1'))
  })

  it('returns nothing for a value that is not an identifier', async () => {
    await expect(correlationLookupId('   ')).resolves.toBe('')
    await expect(correlationLookupId('bad\nvalue')).resolves.toBe('')
  })

  it('reports rather than guesses when Web Crypto is unavailable', async () => {
    const original = globalThis.crypto
    vi.stubGlobal('crypto', undefined)
    try {
      expect(canDeriveLookupId()).toBe(false)
      await expect(correlationLookupId('corr-1')).rejects.toThrow(/Web Crypto/)
    } finally {
      vi.stubGlobal('crypto', original)
    }
  })

  it('never leaves half a surrogate pair behind when bounding an over-long value', () => {
    // A cut inside a surrogate pair would encode as U+FFFD here and as '?' in Java, deriving two
    // different identities for the same identifier.
    const bounded = normalizeCorrelationValue('a'.repeat(MAX_VALUE_LENGTH - 1) + '\u{1F680}' + 'tail')

    expect(bounded).toHaveLength(MAX_VALUE_LENGTH - 1)
    expect(bounded).toBe('a'.repeat(MAX_VALUE_LENGTH - 1))
    expect(/[\uD800-\uDBFF]$/.test(bounded)).toBe(false)
  })

  it('keeps a surrogate pair that fits inside the bound', () => {
    const bounded = normalizeCorrelationValue('a'.repeat(MAX_VALUE_LENGTH - 2) + '\u{1F680}')

    expect(bounded).toHaveLength(MAX_VALUE_LENGTH)
    expect(bounded.endsWith('\u{1F680}')).toBe(true)
  })
})
