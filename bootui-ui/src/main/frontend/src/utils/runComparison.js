import {formatClockTime, formatNumber} from './format.js'

const STATUS_LABELS = {
  COMPARED: 'Compared',
  INSUFFICIENT: 'Needs more traffic',
  NOT_COMPARABLE: 'Not comparable',
  NO_PREVIOUS_RUN: 'No previous run',
  UNAVAILABLE: 'Unavailable'
}

const STATUS_ICONS = {
  COMPARED: 'bi-check2',
  INSUFFICIENT: 'bi-hourglass-split',
  NOT_COMPARABLE: 'bi-slash-circle',
  NO_PREVIOUS_RUN: 'bi-clock-history',
  UNAVAILABLE: 'bi-dash-circle'
}

/** A comparison's status as the panel names it. */
export function comparisonStatusLabel(status) {
  return STATUS_LABELS[status] ?? status ?? ''
}

/** The Bootstrap icon that accompanies a status label; the label always carries the meaning. */
export function comparisonStatusIcon(status) {
  return STATUS_ICONS[status] ?? 'bi-circle'
}

const CHANGE_MARKERS = {
  INCREASED: {icon: 'bi-arrow-up-right', label: 'Up'},
  DECREASED: {icon: 'bi-arrow-down-right', label: 'Down'},
  ADDED: {icon: 'bi-plus-lg', label: 'New'},
  REMOVED: {icon: 'bi-dash-lg', label: 'Gone'}
}

/**
 * How a row moved, as a glyph and the word a screen reader announces. Neutral by design: more statements or a new
 * edge is a change to look at, not a verdict.
 */
export function changeMarker(change) {
  return CHANGE_MARKERS[change] ?? {icon: 'bi-dot', label: 'Changed'}
}

/** A kept run as the run picker and the header name it, such as "Run 4 · 120 requests · baseline file". */
export function runLabel(run, comparison) {
  if (!run) return ''
  const requestsHidden = comparison?.limitations?.some((limit) =>
    /^Facts are not compared because http-exchanges is (disabled|unavailable)\.$/.test(limit)
  )
  const parts = [`Run ${run.ordinal}`]
  parts.push(
    requestsHidden
      ? 'request count hidden'
      : `${formatNumber(run.requests)} ${run.requests === 1 ? 'request' : 'requests'}`
  )
  if (run.endedAt) parts.push(`ended ${formatClockTime(run.endedAt)}`)
  if (run.source === 'BASELINE_FILE') parts.push('baseline file')
  return parts.join(' · ')
}

/** Whether a response is a run comparison, rather than an error or another shape. */
export function isComparison(value) {
  return Boolean(value && typeof value.status === 'string' && Array.isArray(value.behavior))
}

/**
 * What the comparison found, in reading order: behavior, the runtime model's edges, then latency, each with the rows
 * it holds, leaving out the empty ones.
 */
export function comparisonSections(comparison) {
  if (!isComparison(comparison)) return []
  return [
    {id: 'behavior', title: 'What the routes and executions did', rows: comparison.behavior ?? []},
    {id: 'edges', title: 'Runtime model', rows: comparison.edges ?? []},
    {id: 'latency', title: 'Latency', note: 'noisy on a laptop', rows: comparison.latency ?? []}
  ].filter((section) => section.rows.length > 0)
}

/** The restart cost as one line, or null when it was not compared. */
export function restartCostText(cost) {
  if (!cost || cost.status !== 'COMPARED') return null
  const after = Math.round(cost.readyMsAfter ?? 0)
  const before = Math.round(cost.readyMsBefore ?? 0)
  return `Ready in ${formatNumber(after)} ms after this restart, ${formatNumber(before)} ms after the previous one.`
}

/** The comparison in a few words, for the run summary's link to it, or null before it loads. */
export function comparisonSummary(comparison) {
  if (!isComparison(comparison)) return null
  if (comparison.status === 'COMPARED' || comparison.status === 'INSUFFICIENT') {
    const changes = (comparison.behavior?.length ?? 0) + (comparison.edges?.length ?? 0)
    const run = comparison.previous ? `run ${comparison.previous.ordinal}` : 'the previous run'
    if (comparison.status === 'INSUFFICIENT') return `Compared with ${run}: needs more traffic`
    return changes === 0
      ? `No change in behavior since ${run}`
      : `${changes} ${changes === 1 ? 'change' : 'changes'} since ${run}`
  }
  return comparisonStatusLabel(comparison.status)
}
