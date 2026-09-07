import {describe, expect, it} from 'vitest'

import {
  advisorAssessment,
  overallScore,
  scoreBand,
  scoreBandLabel,
  scoreBandTone,
  scoreFromSeverityCounts
} from './scannerScore.js'

const completeReport = (overrides = {}) => ({
  scan: {status: 'SCANNED'},
  severityCounts: [],
  coverage: {status: 'COMPLETE'},
  ...overrides
})

describe('advisorAssessment', () => {
  it.each(['PARTIAL', 'ERROR', 'DISABLED', 'NOT_SCANNED', undefined, 'UNRECOGNIZED'])(
    'never scores %s even with retained findings',
    (status) => {
      const report = completeReport({scan: {status}, severityCounts: [{severity: 'HIGH', count: 1}]})
      expect(advisorAssessment(report).score).toBeNull()
      expect(advisorAssessment(report).reason).toBeTruthy()
      expect(report.severityCounts).toEqual([{severity: 'HIGH', count: 1}])
    }
  )

  it('scores complete applicable evaluation without treating skipped rules as failures', () => {
    const report = completeReport({results: [{status: 'SKIPPED'}], rulesEvaluated: 0})
    expect(advisorAssessment(report).score).toBe(100)
    report.severityCounts = [{severity: 'CRITICAL', count: 5}]
    expect(advisorAssessment(report).score).toBe(0)
  })

  it('keeps diagnostic details visible beside an incomplete assessment', () => {
    expect(
      advisorAssessment(completeReport({scan: {status: 'PARTIAL', message: 'Entity discovery was incomplete.'}})).reason
    ).toContain('Entity discovery was incomplete.')
  })

  it.each([
    null,
    undefined,
    {},
    ['java.util.List', []],
    [null],
    [{severity: 'BOGUS', count: 1}],
    [{severity: 'HIGH', count: -1}],
    [{severity: 'HIGH', count: null}],
    [{severity: 'HIGH', count: '1'}],
    [{severity: 'HIGH', count: NaN}],
    [{severity: 'HIGH', count: Infinity}],
    [{severity: 'UNKNOWN', count: 0}],
    [{severity: 'NONE', count: 1}]
  ])('rejects malformed generic summary %j instead of generating a score', (severityCounts) => {
    expect(advisorAssessment(completeReport({severityCounts}))).toMatchObject({score: null, invalid: true})
  })

  it.each(['INCOMPLETE', 'UNAVAILABLE', 'OTHER', undefined])(
    'requires complete vulnerability coverage: %s',
    (status) => {
      const report = completeReport({coverage: status ? {status} : undefined})
      expect(advisorAssessment(report, {vulnerabilities: true})).toMatchObject({score: null, label: 'Incomplete'})
    }
  )

  it('scores NONE, but active UNKNOWN prevents a vulnerability score', () => {
    const report = completeReport({
      severityCounts: [
        {severity: 'NONE', count: 2},
        {severity: 'HIGH', count: 1}
      ]
    })
    expect(advisorAssessment(report, {vulnerabilities: true}).score).toBe(90)
    report.severityCounts.push({severity: 'UNKNOWN', count: 1})
    expect(advisorAssessment(report, {vulnerabilities: true}).score).toBeNull()
    report.severityCounts[2].count = 0
    expect(advisorAssessment(report, {vulnerabilities: true}).score).toBe(90)
    report.severityCounts[2] = {severity: 'unknown', count: 1}
    expect(advisorAssessment(report, {vulnerabilities: true}).score).toBeNull()
  })
})

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
