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

function severityWeight(severity) {
  if (!severity) return 0
  return SEVERITY_WEIGHTS[severity.toString().toUpperCase()] ?? 0
}

export function isKnownSeverity(severity) {
  return typeof severity === 'string' && Object.hasOwn(SEVERITY_WEIGHTS, severity.toUpperCase())
}

export function advisorAssessment(report, {vulnerabilities = false} = {}) {
  const status = report?.scan?.status
  const message = typeof report?.scan?.message === 'string' ? report.scan.message.trim() : ''
  const withDetails = (reason) => (message ? `${reason} ${message}` : reason)
  const unscored = (reason, code, invalid = false, evidenceReasons = []) => ({
    score: null,
    completeness: 'none',
    label: 'Not scored',
    reason: withDetails(reason),
    reasonCodes: [code, ...evidenceReasons],
    invalid
  })
  if (!['SCANNED', 'PARTIAL'].includes(status) || (vulnerabilities && report.scanningEnabled === false)) {
    return unscored(
      status === 'NOT_SCANNED'
        ? 'Run a scan to calculate a score.'
        : `${report?.scanningEnabled === false ? 'Scanning disabled' : scanStatusLabel(status)}. No usable assessment is available.`,
      'SCAN_UNAVAILABLE'
    )
  }
  const counts = report?.severityCounts
  if (!isValidSeveritySummary(counts, {vulnerabilities})) {
    return unscored('Scanner returned an invalid severity summary.', 'INVALID_SUMMARY', true)
  }
  if (!validEvidenceCounts(report)) {
    return unscored('Scanner returned invalid assessment counts.', 'INVALID_COUNTS', true)
  }

  const reasons = []
  const reasonCodes = []
  const addReason = (code, reason) => {
    reasonCodes.push(code)
    reasons.push(reason)
  }
  if (status === 'PARTIAL') addReason('PARTIAL_SCAN', 'The scan is incomplete.')
  const knownFindings = counts.some((entry) => entry.severity.toUpperCase() !== 'UNKNOWN' && entry.count > 0)
  let usable = false
  if (vulnerabilities) {
    const dependencies = report.dependencies
    if (
      dependencies != null &&
      (!Array.isArray(dependencies) ||
        dependencies.some(
          (dependency) =>
            !dependency ||
            (dependency.assessmentComplete != null && typeof dependency.assessmentComplete !== 'boolean') ||
            (Object.hasOwn(dependency, 'vulnerabilityCount') && !validCount(dependency.vulnerabilityCount)) ||
            !Array.isArray(dependency.vulnerabilities) ||
            dependency.vulnerabilities.some(
              (finding) =>
                !finding ||
                (Object.hasOwn(finding, 'dismissed') && typeof finding.dismissed !== 'boolean') ||
                typeof finding.severity !== 'string' ||
                !(isKnownSeverity(finding.severity) || ['UNKNOWN', 'NONE'].includes(finding.severity.toUpperCase()))
            )
        ))
    ) {
      return unscored('Scanner returned invalid dependency assessment evidence.', 'INVALID_EVIDENCE', true)
    }
    const activeFindings = (dependencies || []).flatMap((dependency) =>
      dependency.vulnerabilities.filter((finding) => !finding.dismissed)
    )
    if (dependencies && !matchingVulnerabilityCounts(counts, activeFindings)) {
      return unscored('Scanner returned an inconsistent severity summary.', 'INVALID_SUMMARY', true)
    }
    const unknownCount = counts
      .filter((entry) => entry.severity.toUpperCase() === 'UNKNOWN')
      .reduce((total, entry) => total + entry.count, 0)
    const completedKnownPackage = (dependencies || []).some(
      (dependency) =>
        dependency.assessmentComplete === true &&
        !dependency.vulnerabilities.some(
          (finding) => !finding.dismissed && finding.severity.toUpperCase() === 'UNKNOWN'
        )
    )
    usable = knownFindings || completedKnownPackage
    if (report.coverage?.status !== 'COMPLETE') {
      addReason(
        'INVENTORY_COVERAGE',
        report.coverage?.status === 'INCOMPLETE'
          ? 'Dependency inventory coverage is incomplete.'
          : 'Dependency inventory coverage is unknown.'
      )
    }
    if (unknownCount > 0) {
      addReason(
        'UNKNOWN_SEVERITY',
        `${unknownCount} active finding(s) have unknown severity. They remain visible but are not included in the score or considered safe.`
      )
    }
    if (!dependencies || dependencies.some((dependency) => dependency.assessmentComplete !== true)) {
      addReason('DEPENDENCY_EVIDENCE', 'Some package queries or advisory details were not fully assessed.')
    }
    if (report.scan?.packagesSkipped > 0) {
      addReason('SKIPPED_PACKAGES', `${report.scan.packagesSkipped} package(s) were not scanned.`)
    }
  } else {
    const evidence = report.assessmentEvidence
    if (evidence != null && (typeof evidence.usable !== 'boolean' || typeof evidence.incomplete !== 'boolean')) {
      return unscored('Scanner returned invalid assessment evidence.', 'INVALID_EVIDENCE', true)
    }
    usable = evidence ? evidence.usable : knownFindings
    if (!evidence || evidence.incomplete) {
      addReason('INCOMPLETE_EVIDENCE', 'Some required checks could not be assessed, or their coverage is unknown.')
    }
  }
  if (!usable) {
    return unscored(
      `No usable assessment is available. Skipped, failed, or wholly unknown results do not establish a score.${reasons.length ? ` ${reasons.join(' ')}` : ''}`,
      'NO_USABLE_EVIDENCE',
      false,
      reasonCodes
    )
  }
  const partial = reasonCodes.length > 0
  return {
    score: scoreFromSeverityCounts(counts.filter((entry) => entry.severity.toUpperCase() !== 'UNKNOWN')),
    completeness: partial ? 'partial' : 'complete',
    label: partial ? 'Partial assessment' : '',
    reason: partial
      ? withDetails(`Score covers evaluated evidence only; missing checks are not passes. ${reasons.join(' ')}`)
      : '',
    reasonCodes,
    invalid: false
  }
}

function validEvidenceCounts(report) {
  const fields = [
    'rulesEvaluated',
    'rulesSkipped',
    'rulesErrored',
    'checksRun',
    'violationsFound',
    'findingsFound',
    'total',
    'vulnerable'
  ]
  if (fields.some((field) => Object.hasOwn(report, field) && !validCount(report[field]))) return false
  for (const field of [...fields, 'packagesScanned', 'packagesSkipped', 'vulnerabilitiesFound']) {
    if (Object.hasOwn(report.scan, field) && !validCount(report.scan[field])) return false
  }
  if (
    Number.isInteger(report.rulesSkipped) &&
    Number.isInteger(report.rulesErrored) &&
    report.rulesSkipped + report.rulesErrored > report.rulesEvaluated
  ) {
    return false
  }
  const coverage = report.coverage
  return ['archivesFound', 'archivesIdentified', 'archivesUnidentified'].every(
    (field) => !coverage || !Object.hasOwn(coverage, field) || validCount(coverage[field])
  )
}

function validCount(count) {
  return Number.isSafeInteger(count) && count >= 0
}

function matchingVulnerabilityCounts(counts, findings) {
  const actual = new Map()
  for (const finding of findings) {
    const severity = finding.severity.toUpperCase()
    actual.set(severity, (actual.get(severity) || 0) + 1)
  }
  return (
    counts.every((entry) => entry.count === (actual.get(entry.severity.toUpperCase()) || 0)) &&
    [...actual].every(([severity, count]) =>
      counts.some((entry) => entry.severity.toUpperCase() === severity && entry.count === count)
    )
  )
}

export function isValidSeveritySummary(counts, {vulnerabilities = false} = {}) {
  return (
    Array.isArray(counts) &&
    new Set(counts.map((entry) => (typeof entry?.severity === 'string' ? entry.severity.toUpperCase() : null))).size ===
      counts.length &&
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
        !validCount(entry.count)
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

export function overallAssessment(assessments) {
  const contributors = assessments.filter((assessment) => Number.isFinite(assessment.score))
  const partialCount = contributors.filter((assessment) => assessment.completeness === 'partial').length
  return {
    score: overallScore(contributors.map((assessment) => assessment.score)),
    completeness: contributors.length === 0 ? 'none' : partialCount > 0 ? 'partial' : 'complete',
    partialCount,
    scoredCount: contributors.length
  }
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
