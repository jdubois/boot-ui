import {scanStatusLabel} from './scanStatus.js'

// Simple weighted-penalty scoring model shared by the advisor panels and Overview.
// Each finding subtracts a fixed number of points from a perfect score of 100.
const SEVERITY_WEIGHTS = {
  CRITICAL: 25,
  HIGH: 10,
  MEDIUM: 3,
  LOW: 1,
  INFO: 0
}

const MAX_SCORE = 100
const MIN_SCORE = 0

export function scoreBandTone(score) {
  if (!Number.isFinite(score) || score < MIN_SCORE || score > MAX_SCORE) return 'secondary'
  return score >= 80 ? 'success' : score >= 50 ? 'warning' : 'danger'
}

// Eligibility is established by each report, never by substituting a default number.
export function overallScore(scores) {
  const valid = (Array.isArray(scores) ? scores : []).filter(
    (score) => Number.isFinite(score) && score >= MIN_SCORE && score <= MAX_SCORE
  )
  return valid.length ? Math.round(valid.reduce((sum, score) => sum + score, 0) / valid.length) : null
}

// These are the three count-only security signals in the shared GitHub DTO.
// Ten points per alert is a heuristic, not an inferred HIGH severity.
export function githubSecurityScore(report) {
  if (report?.available !== true || report.connected !== true || report.credential?.authenticated !== true) return null
  const labels = ['Dependabot alerts', 'Code scanning alerts', 'Secret scanning alerts']
  const signals = report.securitySignals
  if (!Array.isArray(signals) || signals.length !== labels.length) return null
  if (
    !labels.every(
      (label) =>
        signals.filter(
          (signal) =>
            signal?.label === label &&
            signal.status === 'AVAILABLE' &&
            Number.isSafeInteger(signal.count) &&
            signal.count >= 0
        ).length === 1
    )
  )
    return null
  return Math.max(MIN_SCORE, MAX_SCORE - signals.reduce((sum, signal) => sum + Math.min(signal.count, 10) * 10, 0))
}

function severityWeight(severity) {
  if (!severity) return 0
  return SEVERITY_WEIGHTS[severity.toString().toUpperCase()] ?? 0
}

function isKnownSeverity(severity) {
  return typeof severity === 'string' && Object.hasOwn(SEVERITY_WEIGHTS, severity.toUpperCase())
}

export function advisorAssessment(report, {vulnerabilities = false} = {}) {
  const status = report?.scan?.status
  const unscored = (label, reason, invalid = false, incomplete = false) => ({
    score: null,
    partial: false,
    incomplete,
    label,
    reason,
    invalid
  })
  const message = typeof report?.scan?.message === 'string' ? report.scan.message.trim() : ''
  const withDetails = (reason) => (message ? `${reason} ${message}` : reason)
  if (!['SCANNED', 'PARTIAL'].includes(status)) {
    return unscored(
      'Not scored',
      withDetails(
        status === 'NOT_SCANNED'
          ? 'Run a scan to calculate a score.'
          : `${scanStatusLabel(status)}. Usable scan evidence is required to calculate a score.`
      )
    )
  }
  const counts = report?.severityCounts
  if (!isValidSeveritySummary(counts, {vulnerabilities})) {
    return unscored('Not scored', 'Scanner returned an invalid severity summary.', true)
  }
  const evidence = report.evidence
  if (evidence === undefined) {
    return unscored('Not scored', 'Assessment coverage is unknown: completion evidence is unavailable.', false, true)
  }
  if (!isValidEvidence(evidence)) {
    return unscored('Not scored', 'Scanner returned invalid assessment evidence.', true)
  }
  const reasons = []
  reasons.push(...evidence.limitations.filter((reason) => reason.trim()))
  if ((status === 'PARTIAL' || !evidence.coverageComplete) && message) reasons.push(message)
  if ((status === 'PARTIAL' || !evidence.coverageComplete) && !reasons.length) {
    reasons.push('Some applicable checks lack complete evidence.')
  }
  // Coverage and score eligibility are independent: confirmed empty scope is complete but unscored.
  const incomplete = reasons.length > 0
  if (!evidence.usable) {
    if (!incomplete) {
      return unscored(
        'Not applicable',
        'No applicable checks or observed findings within this scanner’s scope. No score is calculated.'
      )
    }
    return unscored(
      'Not scored',
      [
        'No usable assessment evidence. Skipped, failed, or unknown-only results cannot establish a score.',
        ...reasons
      ].join(' '),
      false,
      incomplete
    )
  }
  const partial = reasons.length > 0
  return {
    score: scoreFromSeverityCounts(counts),
    partial,
    incomplete,
    label: 'Results available',
    reason: partial ? [...new Set(reasons)].join(' ') : '',
    invalid: false
  }
}

function isRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function isValidEvidence(evidence) {
  return (
    isRecord(evidence) &&
    typeof evidence.usable === 'boolean' &&
    typeof evidence.coverageComplete === 'boolean' &&
    Array.isArray(evidence.limitations) &&
    evidence.limitations.every((reason) => typeof reason === 'string')
  )
}

function hasCompletedDependency(dependency) {
  return dependency.assessment?.queryComplete === true && dependency.assessment?.detailAssessmentComplete === true
}

export function hasCompletedEmptyDependency(dependency) {
  return (
    hasCompletedDependency(dependency) &&
    Array.isArray(dependency.vulnerabilities) &&
    dependency.vulnerabilities.length === 0
  )
}

export function isValidSeveritySummary(counts, {vulnerabilities = false} = {}) {
  return (
    Array.isArray(counts) &&
    !counts.some(
      (entry) =>
        !entry ||
        typeof entry !== 'object' ||
        Array.isArray(entry) ||
        !(
          isKnownSeverity(entry.severity) ||
          (vulnerabilities &&
            typeof entry.severity === 'string' &&
            ['UNKNOWN', 'NONE'].includes(entry.severity.toUpperCase()))
        ) ||
        typeof entry.count !== 'number' ||
        !Number.isFinite(entry.count) ||
        entry.count < 0
    )
  )
}

// Computes a 0-100 score from a list of {severity, count} entries.
export function scoreFromSeverityCounts(severityCounts) {
  if (!Array.isArray(severityCounts)) return MAX_SCORE
  const penalty = severityCounts.reduce((total, entry) => {
    const count = Number(entry?.count) || 0
    return total + severityWeight(entry?.severity) * count
  }, 0)
  return clampScore(MAX_SCORE - penalty)
}

function clampScore(value) {
  if (!Number.isFinite(value)) return MIN_SCORE
  return Math.max(MIN_SCORE, Math.min(MAX_SCORE, Math.round(value)))
}
