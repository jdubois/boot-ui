import {describe, expect, it} from 'vitest'

import {
  advisorAssessment,
  overallAssessment,
  overallScore,
  scoreBand,
  scoreBandLabel,
  scoreBandTone,
  scoreFromSeverityCounts
} from './scannerScore.js'

const completeReport = (overrides = {}) => ({
  scan: {status: 'SCANNED'},
  severityCounts: [],
  assessmentEvidence: {usable: true, incomplete: false},
  coverage: {status: 'COMPLETE'},
  ...overrides
})

describe('advisorAssessment', () => {
  it.each(['ERROR', 'DISABLED', 'NOT_SCANNED', undefined, 'UNRECOGNIZED'])(
    'never scores %s even with retained findings',
    (status) => {
      const report = completeReport({scan: {status}, severityCounts: [{severity: 'HIGH', count: 1}]})
      expect(advisorAssessment(report).score).toBeNull()
      expect(advisorAssessment(report).reason).toBeTruthy()
      expect(report.severityCounts).toEqual([{severity: 'HIGH', count: 1}])
    }
  )

  it('scores completed applicable evaluation without treating wrong-dialect skips as failures', () => {
    const report = completeReport({results: [], rulesEvaluated: 10, rulesSkipped: 9, rulesErrored: 0})
    expect(advisorAssessment(report).score).toBe(100)
    report.severityCounts = [{severity: 'CRITICAL', count: 5}]
    expect(advisorAssessment(report).score).toBe(0)
  })

  it.each([
    [[], 100],
    [[{severity: 'HIGH', count: 1}], 90],
    [[{severity: 'INFO', count: 2}], 100]
  ])('scores usable partial evidence %j with explicit qualification', (severityCounts, score) => {
    expect(advisorAssessment(completeReport({scan: {status: 'PARTIAL'}, severityCounts}))).toMatchObject({
      score,
      completeness: 'partial',
      label: 'Partial assessment'
    })
  })

  it.each(['SCANNED', 'PARTIAL'])(
    'does not turn all-skipped, zero, or failed evaluations into %s success',
    (status) => {
      for (const rulesEvaluated of [0, 70]) {
        expect(
          advisorAssessment(
            completeReport({
              scan: {status},
              rulesEvaluated,
              assessmentEvidence: {usable: false, incomplete: status === 'PARTIAL'}
            })
          )
        ).toMatchObject({score: null, completeness: 'none'})
      }
    }
  )

  it('qualifies incomplete collection even when the execution status is SCANNED', () => {
    expect(advisorAssessment(completeReport({assessmentEvidence: {usable: true, incomplete: true}}))).toMatchObject({
      score: 100,
      completeness: 'partial'
    })
  })

  it('does not infer completed checks from a legacy attempted-rule count', () => {
    expect(advisorAssessment(completeReport({assessmentEvidence: null, rulesEvaluated: 70})).score).toBeNull()
    expect(
      advisorAssessment(
        completeReport({
          assessmentEvidence: null,
          severityCounts: [{severity: 'HIGH', count: 1}]
        })
      )
    ).toMatchObject({score: 90, completeness: 'partial'})
  })

  it.each([-1, NaN, Infinity, 0.5, '1', null, Number.MAX_SAFE_INTEGER + 1])(
    'rejects invalid evidence count %s',
    (rulesEvaluated) => {
      expect(advisorAssessment(completeReport({rulesEvaluated}))).toMatchObject({score: null, invalid: true})
    }
  )

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
    [{severity: 'HIGH', count: 0.5}],
    [{severity: 'HIGH', count: Number.MAX_SAFE_INTEGER + 1}],
    [
      {severity: 'HIGH', count: 1},
      {severity: 'high', count: 1}
    ],
    [{severity: {toUpperCase: 1}, count: 1}],
    [{severity: 'UNKNOWN', count: 0}],
    [{severity: 'NONE', count: 1}]
  ])('rejects malformed generic summary %j instead of generating a score', (severityCounts) => {
    expect(advisorAssessment(completeReport({severityCounts}))).toMatchObject({score: null, invalid: true})
  })

  it.each(['INCOMPLETE', 'UNAVAILABLE', 'OTHER', undefined])(
    'scores usable vulnerability evidence with qualified inventory coverage: %s',
    (status) => {
      const report = completeReport({
        coverage: status ? {status} : undefined,
        dependencies: [{assessmentComplete: true, vulnerabilities: []}]
      })
      expect(advisorAssessment(report, {vulnerabilities: true})).toMatchObject({
        score: 100,
        label: 'Partial assessment'
      })
    }
  )

  it('scores known severities including NONE while explicitly excluding active UNKNOWN', () => {
    const report = completeReport({
      severityCounts: [
        {severity: 'NONE', count: 2},
        {severity: 'HIGH', count: 1}
      ]
    })
    expect(advisorAssessment(report, {vulnerabilities: true}).score).toBe(90)
    report.severityCounts.push({severity: 'UNKNOWN', count: 1})
    expect(advisorAssessment(report, {vulnerabilities: true})).toMatchObject({score: 90, completeness: 'partial'})
    report.severityCounts[2].count = 0
    expect(advisorAssessment(report, {vulnerabilities: true}).score).toBe(90)
    report.severityCounts[2] = {severity: 'unknown', count: 1}
    expect(advisorAssessment(report, {vulnerabilities: true})).toMatchObject({score: 90, completeness: 'partial'})
  })

  it('distinguishes wholly UNKNOWN from UNKNOWN alongside an independently assessed package', () => {
    const report = completeReport({
      severityCounts: [{severity: 'UNKNOWN', count: 1}],
      dependencies: [{assessmentComplete: true, vulnerabilities: [{severity: 'UNKNOWN'}]}]
    })
    expect(advisorAssessment(report, {vulnerabilities: true}).score).toBeNull()
    report.dependencies.push({assessmentComplete: true, vulnerabilities: []})
    expect(advisorAssessment(report, {vulnerabilities: true})).toMatchObject({score: 100, completeness: 'partial'})
  })

  it('does not mistake completed query pagination for successful advisory detail assessment', () => {
    const report = completeReport({
      scan: {status: 'PARTIAL', packagesScanned: 5},
      dependencies: [{assessmentComplete: false, vulnerabilities: []}]
    })
    expect(advisorAssessment(report, {vulnerabilities: true}).score).toBeNull()
    report.dependencies[0].assessmentComplete = true
    expect(advisorAssessment(report, {vulnerabilities: true})).toMatchObject({score: 100, completeness: 'partial'})
  })

  it('rejects inconsistent vulnerability counts instead of losing a known finding', () => {
    const report = completeReport({
      dependencies: [{assessmentComplete: true, vulnerabilities: [{severity: 'HIGH'}]}]
    })
    expect(advisorAssessment(report, {vulnerabilities: true})).toMatchObject({score: null, invalid: true})
  })

  it.each(['false', 1, null])(
    'rejects malformed dismissal %s instead of treating UNKNOWN as dismissed',
    (dismissed) => {
      const report = completeReport({
        dependencies: [{assessmentComplete: true, vulnerabilities: [{severity: 'UNKNOWN', dismissed}]}]
      })
      expect(advisorAssessment(report, {vulnerabilities: true})).toMatchObject({
        score: null,
        invalid: true,
        reasonCodes: ['INVALID_EVIDENCE']
      })
    }
  )

  it('aggregates numeric partial contributors without changing weights or including unusable reports', () => {
    expect(
      overallAssessment([
        {score: 90, completeness: 'complete'},
        {score: 70, completeness: 'partial'},
        {score: null, completeness: 'none'}
      ])
    ).toEqual({score: 80, completeness: 'partial', partialCount: 1, scoredCount: 2})
    expect(overallAssessment([])).toEqual({score: null, completeness: 'none', partialCount: 0, scoredCount: 0})
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
