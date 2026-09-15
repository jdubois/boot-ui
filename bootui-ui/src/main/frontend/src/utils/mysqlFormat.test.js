import {describe, expect, it} from 'vitest'
import {compareCounters, exactInteger, formatCounter, formatDuration, formatRatio} from './mysqlFormat.js'

describe('MySQL exact numeric contract', () => {
  it('formats unsigned 64-bit counters without losing their final digits', () => {
    expect(formatCounter('18446744073709551615')).toBe('18,446,744,073,709,551,615')
    expect(formatCounter('9007199254740993')).toBe('9,007,199,254,740,993')
    expect(formatCounter('0')).toBe('0')
    expect(formatCounter('-1234567')).toBe('-1,234,567')
  })

  it('sorts adjacent huge integers and keeps unknown distinct from zero', () => {
    expect(['9007199254740993', null, '9007199254740992', '0'].sort(compareCounters)).toEqual([
      '0',
      '9007199254740992',
      '9007199254740993',
      null
    ])
    expect(compareCounters(null, undefined)).toBe(0)
    expect(compareCounters('01', '1')).toBe(0)
    expect(compareCounters('2', '1')).toBe(1)
  })

  it('fails closed for missing, malformed, fractional or already imprecise counters', () => {
    for (const value of [null, undefined, '', '1e9', '1.2', 'unknown', NaN, Infinity, 9007199254740992]) {
      expect(formatCounter(value)).toBe('—')
      expect(exactInteger(value)).toBeNull()
    }
    expect(formatCounter(12)).toBe('12')
    expect(formatCounter(12n)).toBe('12')
  })

  it('uses numeric durations and finite ratios without inventing missing timing', () => {
    expect(formatDuration(1.234)).toBe('1.23 ms')
    expect(formatDuration(0, 's')).toBe('0 s')
    expect(formatRatio(0.985)).toBe('98.5%')
    expect(formatRatio(0)).toBe('0.0%')
    for (const value of [null, undefined, '123', NaN, Infinity]) {
      expect(formatDuration(value)).toBe('—')
      expect(formatRatio(value)).toBe('—')
    }
  })
})
