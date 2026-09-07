import {isCompleteScan, scanStatusLabel} from './scanStatus.js'

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

function severityWeight(severity) {
  if (!severity) return 0
  return SEVERITY_WEIGHTS[severity.toString().toUpperCase()] ?? 0
}

export function isKnownSeverity(severity) {
  return typeof severity === 'string' && Object.hasOwn(SEVERITY_WEIGHTS, severity.toUpperCase())
}

export function advisorAssessment(report, {vulnerabilities = false} = {}) {
  const status = report?.scan?.status
  const unscored = (label, reason, invalid = false) => ({score: null, label, reason, invalid})
  if (!isCompleteScan(status)) {
    const message = typeof report?.scan?.message === 'string' ? report.scan.message.trim() : ''
    const withDetails = (reason) => (message ? `${reason} ${message}` : reason)
    if (status === 'PARTIAL') {
      return unscored(
        'Incomplete',
        withDetails('The scan is incomplete. Available findings are retained, but no score is calculated.')
      )
    }
    return unscored(
      'Not scored',
      withDetails(
        status === 'NOT_SCANNED'
          ? 'Run a scan to calculate a score.'
          : `${scanStatusLabel(status)}. A completed scan is required to calculate a score.`
      )
    )
  }
  const counts = report?.severityCounts
  if (!isValidSeveritySummary(counts, {vulnerabilities})) {
    return unscored('Not scored', 'Scanner returned an invalid severity summary.', true)
  }
  if (vulnerabilities) {
    if (report.coverage?.status !== 'COMPLETE') {
      return unscored(
        'Incomplete',
        report.coverage?.status === 'INCOMPLETE'
          ? 'Dependency inventory coverage is incomplete. Available findings are retained, but no score is calculated.'
          : 'Dependency inventory coverage is unknown. Complete coverage is required to calculate a score.'
      )
    }
    if (counts.some((entry) => entry.severity.toUpperCase() === 'UNKNOWN' && entry.count > 0)) {
      return unscored(
        'Incomplete',
        'Active findings have unknown severity. No score is calculated until their severity is known or they are dismissed.'
      )
    }
  }
  return {score: scoreFromSeverityCounts(counts), label: '', reason: '', invalid: false}
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

// Aggregates individual scanner scores into a single overall score (mean).
export function overallScore(scores) {
  const valid = (scores || []).filter((score) => Number.isFinite(score))
  if (!valid.length) return null
  const sum = valid.reduce((total, score) => total + score, 0)
  return clampScore(sum / valid.length)
}

// Maps a score to a qualitative band used for color + label.
export function scoreBand(score) {
  if (!Number.isFinite(score)) return 'unknown'
  if (score >= 80) return 'good'
  if (score >= 50) return 'fair'
  return 'poor'
}

const BAND_LABELS = {
  good: 'Good',
  fair: 'Needs attention',
  poor: 'At risk',
  unknown: 'Not scored'
}

export function scoreBandLabel(score) {
  return BAND_LABELS[scoreBand(score)]
}

const BAND_TONES = {
  good: 'success',
  fair: 'warning',
  poor: 'danger',
  unknown: 'secondary'
}

export function scoreBandTone(score) {
  return BAND_TONES[scoreBand(score)]
}
