import {describe, expect, it} from 'vitest'
import {
  advisorAssessment,
  githubSecurityScore,
  overallScore,
  scoreBandTone,
  scoreFromSeverityCounts
} from './scannerScore.js'

const report = (overrides = {}) => ({
  scan: {status: 'SCANNED'},
  severityCounts: [],
  evidence: {usable: true, coverageComplete: true, limitations: []},
  ...overrides
})
const evidence = (overrides = {}) => ({...report().evidence, ...overrides})

it.each([null, undefined, NaN, Infinity, -1, 101, '80'])('does not color an invalid score %s', (score) => {
  expect(scoreBandTone(score)).toBe('secondary')
})

describe('overallScore', () => {
  it('rounds the simple mean without replacing absent or invalid scores', () => {
    expect(overallScore([91, 80])).toBe(86)
    expect(overallScore([91, 100, null, undefined, NaN, Infinity, -1, 101, '100'])).toBe(96)
    expect(overallScore([0, 100])).toBe(50)
    expect(
      overallScore([
        advisorAssessment(report({scan: {status: 'PARTIAL'}, severityCounts: [{severity: 'MEDIUM', count: 3}]})).score
      ])
    ).toBe(91)
  })

  it.each([undefined, null, [], [null, NaN, '0']])('leaves an unscored collection unscored: %j', (scores) => {
    expect(overallScore(scores)).toBeNull()
  })
})

describe('githubSecurityScore', () => {
  const githubReport = () => ({
    available: true,
    connected: true,
    credential: {authenticated: true},
    securitySignals: ['Dependabot alerts', 'Code scanning alerts', 'Secret scanning alerts'].map((label) => ({
      label,
      status: 'AVAILABLE',
      count: 0
    }))
  })

  it('scores confirmed zero, two alerts, and clamps large counts without assigning severities', () => {
    const github = githubReport()
    expect(githubSecurityScore(github)).toBe(100)
    github.securitySignals[0].count = 1
    github.securitySignals[1].count = 1
    expect(githubSecurityScore(github)).toBe(80)
    github.securitySignals[2].count = Number.MAX_SAFE_INTEGER
    expect(githubSecurityScore(github)).toBe(0)
  })

  it.each([undefined, null, [], {}, [null]])('rejects missing or malformed signals: %j', (securitySignals) => {
    expect(githubSecurityScore({...githubReport(), securitySignals})).toBeNull()
  })

  it.each([null, undefined, -1, 0.5, '0', NaN, Infinity, Number.MAX_SAFE_INTEGER + 1])(
    'never interprets an invalid count as zero: %s',
    (count) => {
      const github = githubReport()
      github.securitySignals[1].count = count
      expect(githubSecurityScore(github)).toBeNull()
    }
  )

  it('requires exactly one available result for each canonical signal, even with known alerts', () => {
    const github = githubReport()
    github.securitySignals[0].count = 2
    github.securitySignals[1].status = 'UNAVAILABLE'
    expect(githubSecurityScore(github)).toBeNull()
    github.securitySignals[1].status = 'AVAILABLE'
    github.securitySignals[1].label = 'Dependabot alerts'
    expect(githubSecurityScore(github)).toBeNull()
    github.securitySignals.pop()
    expect(githubSecurityScore(github)).toBeNull()
  })

  it.each([
    {available: false},
    {available: undefined},
    {connected: false},
    {connected: 'true'},
    {credential: {authenticated: false}},
    {credential: null}
  ])('requires availability and an authenticated connection: %j', (overrides) => {
    expect(githubSecurityScore({...githubReport(), ...overrides})).toBeNull()
    expect(githubSecurityScore(null)).toBeNull()
  })
})

describe('advisorAssessment', () => {
  it.each(['SCANNED', 'PARTIAL'])('uses only explicit backend eligibility for %s, before dismissal', (status) => {
    const assessment = report({
      scan: {status},
      evidence: evidence({usable: true}),
      severityCounts: [
        {severity: 'CRITICAL', count: 1},
        {severity: 'HIGH', count: 2},
        {severity: 'MEDIUM', count: 3},
        {severity: 'LOW', count: 4},
        {severity: 'INFO', count: 7}
      ]
    })
    expect(advisorAssessment(assessment)).toMatchObject({score: 42, partial: status === 'PARTIAL'})
    assessment.severityCounts = []
    expect(advisorAssessment(assessment)).toMatchObject({score: 100, partial: status === 'PARTIAL'})
    assessment.severityCounts = [{severity: 'MEDIUM', count: 3}]
    expect(advisorAssessment(assessment).score).toBe(91)
  })

  it('keeps coverage independent of score, including zero and confirmed empty scope', () => {
    expect(advisorAssessment(report({severityCounts: [{severity: 'CRITICAL', count: 5}]}))).toMatchObject({
      score: 0,
      incomplete: false
    })
    expect(advisorAssessment(report({evidence: evidence({usable: false})}))).toMatchObject({
      score: null,
      incomplete: false,
      label: 'Not applicable'
    })
    expect(advisorAssessment(report({evidence: evidence({coverageComplete: false})}))).toMatchObject({
      score: 100,
      incomplete: true,
      label: 'Results available'
    })
  })

  it.each(['ERROR', 'DISABLED', 'NOT_SCANNED', undefined, 'UNRECOGNIZED'])(
    'never scores %s even with retained findings',
    (status) => {
      expect(advisorAssessment(report({scan: {status}, severityCounts: [{severity: 'HIGH', count: 1}]}))).toMatchObject(
        {score: null, incomplete: false}
      )
    }
  )

  it.each(['results', 'findings', 'dependencies'])('does not reconstruct eligibility from legacy %s', (field) => {
    const legacy = report({
      evidence: undefined,
      [field]: [{id: 'known-rule', status: 'VIOLATION', severity: 'HIGH', vulnerabilities: [{severity: 'HIGH'}]}],
      severityCounts: [{severity: 'HIGH', count: 1}],
      rulesEvaluated: 100,
      checksRun: 100
    })
    expect(advisorAssessment(legacy, {vulnerabilities: field === 'dependencies'})).toMatchObject({
      score: null,
      incomplete: true,
      label: 'Not scored',
      invalid: false
    })
    expect(advisorAssessment(legacy).reason).toContain('coverage is unknown')
    legacy.evidence = evidence({usable: false, coverageComplete: false})
    expect(advisorAssessment(legacy).score).toBeNull()
  })

  it('uses the same aggregate evidence for dependency and other advisors, without inspecting details', () => {
    const dependencyReport = report({
      evidence: evidence({
        usable: true,
        coverageComplete: false,
        limitations: ['Findings with unknown severity are excluded from score penalties.']
      }),
      severityCounts: [
        {severity: 'HIGH', count: 1},
        {severity: 'UNKNOWN', count: 1},
        {severity: 'NONE', count: 2}
      ]
    })
    expect(advisorAssessment(dependencyReport, {vulnerabilities: true})).toMatchObject({score: 90, partial: true})
    dependencyReport.severityCounts = []
    expect(advisorAssessment(dependencyReport, {vulnerabilities: true})).toMatchObject({score: 100, partial: true})
    dependencyReport.evidence.usable = false
    expect(advisorAssessment(dependencyReport, {vulnerabilities: true})).toMatchObject({
      score: null,
      incomplete: true,
      label: 'Not scored'
    })
    dependencyReport.evidence.usable = true
    expect(advisorAssessment(dependencyReport, {vulnerabilities: true})).toMatchObject({score: 100, partial: true})
  })

  it('distinguishes genuine INFO from informational limitations without rule identifiers', () => {
    const info = report({
      evidence: evidence({usable: false, coverageComplete: false}),
      severityCounts: [{severity: 'INFO', count: 1}]
    })
    expect(advisorAssessment(info).score).toBeNull()
    info.evidence.usable = true
    expect(advisorAssessment(info)).toMatchObject({score: 100, partial: true})
  })

  it.each([
    null,
    undefined,
    {},
    ['java.util.List', []],
    [null],
    [{severity: 'BOGUS', count: 1}],
    ...[-1, null, '1', NaN, Infinity].map((count) => [{severity: 'HIGH', count}]),
    [{severity: 'UNKNOWN', count: 0}],
    [{severity: 'NONE', count: 1}]
  ])('rejects malformed generic severity summary %j', (severityCounts) => {
    expect(advisorAssessment(report({severityCounts}))).toMatchObject({score: null, invalid: true})
  })

  it.each([
    null,
    [],
    {},
    ...[undefined, null, 0, 1, 'true', 'false', [], {}].map((usable) => evidence({usable})),
    evidence({coverageComplete: 'true'}),
    evidence({limitations: [null]}),
    evidence({limitations: 'failed'})
  ])('rejects malformed explicit evidence %j even with retained findings', (invalidEvidence) => {
    expect(
      advisorAssessment(report({evidence: invalidEvidence, severityCounts: [{severity: 'HIGH', count: 1}]}))
    ).toMatchObject({score: null, invalid: true})
  })

  it('keeps backend limitations and scan details, without claiming whole-application completeness', () => {
    const result = advisorAssessment(
      report({
        scan: {status: 'PARTIAL', message: 'Entity discovery failed.'},
        evidence: evidence({limitations: ['Schema unavailable.']})
      })
    )
    expect(result.reason).toContain('Entity discovery failed.')
    expect(result.reason).toContain('Schema unavailable.')
    expect(advisorAssessment(report())).toMatchObject({
      label: 'Results available',
      reason: '',
      incomplete: false,
      partial: false
    })
  })

  it.each(['SCANNED', 'PARTIAL'])('preserves score 91 with scan notes for %s evidence', (status) => {
    expect(
      advisorAssessment(
        report({
          scan: {status},
          severityCounts: [{severity: 'MEDIUM', count: 3}],
          evidence: evidence({coverageComplete: false, limitations: ['Metadata unavailable.']})
        })
      )
    ).toMatchObject({
      score: 91,
      label: 'Results available',
      reason: 'Metadata unavailable.',
      incomplete: true,
      partial: true,
      invalid: false
    })
  })
})

describe('scannerScore', () => {
  it('applies the fixed 25/10/3/1/0 penalties and clamps the result', () => {
    expect(scoreFromSeverityCounts([])).toBe(100)
    expect(scoreFromSeverityCounts([{severity: 'MEDIUM', count: 3}])).toBe(91)
    expect(scoreFromSeverityCounts([{severity: 'CRITICAL', count: 10}])).toBe(0)
    expect(scoreFromSeverityCounts([{severity: 'high', count: 1}])).toBe(90)
    expect(scoreFromSeverityCounts([{severity: 'UNKNOWN', count: 5}])).toBe(100)
  })
})
