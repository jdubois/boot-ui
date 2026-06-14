// Simple weighted-penalty scoring model shared by the Overview dashboard.
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
