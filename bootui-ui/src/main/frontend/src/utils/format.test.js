import {describe, expect, it} from 'vitest'
import {formatBytes, formatClockTime, formatNumber, formatRelative} from './format.js'

describe('formatNumber', () => {
  it('returns an em dash for null or undefined', () => {
    expect(formatNumber(null)).toBe('—')
    expect(formatNumber(undefined)).toBe('—')
  })

  it('formats numbers using locale grouping', () => {
    expect(formatNumber(1234567)).toBe((1234567).toLocaleString())
    expect(formatNumber(0)).toBe('0')
  })
})

describe('formatBytes', () => {
  it('returns an em dash for null, undefined, empty, or negative values', () => {
    expect(formatBytes(null)).toBe('—')
    expect(formatBytes(undefined)).toBe('—')
    expect(formatBytes('')).toBe('—')
    expect(formatBytes(-1)).toBe('—')
    expect(formatBytes('not-a-number')).toBe('—')
  })

  it('scales bytes into B, KB, MB, GB, and TB', () => {
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(1536)).toBe('1.5 KB')
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.0 MB')
    expect(formatBytes(232806322176)).toBe('216.82 GB')
    expect(formatBytes(3 * 1024 ** 4)).toBe('3.00 TB')
  })

  it('accepts numeric strings', () => {
    expect(formatBytes('1024')).toBe('1.0 KB')
  })
})

describe('formatClockTime', () => {
  it('formats an epoch millisecond value as a 24h HH:MM:SS clock', () => {
    // 2021-01-01T13:05:09Z expressed against the local timezone the runtime uses.
    const millis = new Date(2021, 0, 1, 13, 5, 9).getTime()
    expect(formatClockTime(millis)).toBe('13:05:09')
  })
})

describe('formatRelative', () => {
  const now = 1_000_000_000_000

  it('returns an em dash for null or undefined', () => {
    expect(formatRelative(null, now)).toBe('—')
    expect(formatRelative(undefined, now)).toBe('—')
  })

  it('reports "just now" within five seconds', () => {
    expect(formatRelative(now - 2_000, now)).toBe('just now')
  })

  it('reports seconds, minutes, and hours ago', () => {
    expect(formatRelative(now - 30_000, now)).toBe('30s ago')
    expect(formatRelative(now - 5 * 60_000, now)).toBe('5m ago')
    expect(formatRelative(now - 2 * 3_600_000, now)).toBe('2h ago')
  })
})
