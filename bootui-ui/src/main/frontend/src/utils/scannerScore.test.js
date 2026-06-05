import {describe, expect, it} from 'vitest'

import {overallScore, scoreBand, scoreBandLabel, scoreBandTone, scoreFromSeverityCounts} from './scannerScore.js'

describe('scannerScore', () => {
  it('returns a perfect score when there are no findings', () => {
    expect(scoreFromSeverityCounts([])).toBe(100)
    expect(scoreFromSeverityCounts(null)).toBe(100)
  })

  it('applies weighted penalties per severity', () => {
    // 1 critical (-25), 2 high (-20), 3 medium (-9), 4 low (-4) => 100 - 58 = 42
    const counts = [
      {severity: 'CRITICAL', count: 1},
      {severity: 'HIGH', count: 2},
      {severity: 'MEDIUM', count: 3},
      {severity: 'LOW', count: 4},
      {severity: 'INFO', count: 9}
    ]
    expect(scoreFromSeverityCounts(counts)).toBe(42)
  })

  it('floors the score at zero', () => {
    expect(scoreFromSeverityCounts([{severity: 'CRITICAL', count: 10}])).toBe(0)
  })

  it('is case-insensitive and ignores unknown severities', () => {
    expect(scoreFromSeverityCounts([{severity: 'high', count: 1}])).toBe(90)
    expect(scoreFromSeverityCounts([{severity: 'BOGUS', count: 5}])).toBe(100)
  })

  it('averages individual scores for the overall score', () => {
    expect(overallScore([100, 80, 60])).toBe(80)
    expect(overallScore([])).toBeNull()
    expect(overallScore([90, undefined, 70])).toBe(80)
  })

  it('maps scores to qualitative bands', () => {
    expect(scoreBand(95)).toBe('good')
    expect(scoreBand(80)).toBe('good')
    expect(scoreBand(79)).toBe('fair')
    expect(scoreBand(50)).toBe('fair')
    expect(scoreBand(49)).toBe('poor')
    expect(scoreBand(null)).toBe('unknown')
  })

  it('exposes band labels and tones', () => {
    expect(scoreBandLabel(90)).toBe('Good')
    expect(scoreBandTone(90)).toBe('success')
    expect(scoreBandTone(60)).toBe('warning')
    expect(scoreBandTone(10)).toBe('danger')
    expect(scoreBandTone(null)).toBe('secondary')
  })
})
