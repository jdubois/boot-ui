import {formatClockTime, formatNumber} from './format.js'

const STATUS_LABELS = {
  COMPARED: 'Compared',
  INSUFFICIENT: 'Needs more traffic',
  NOT_COMPARABLE: 'Not comparable',
  NO_PREVIOUS_RUN: 'No previous run',
  UNAVAILABLE: 'Unavailable'
}

/** A comparison's status as the panel names it. */
export function comparisonStatusLabel(status) {
  return STATUS_LABELS[status] ?? status ?? ''
}

/** A kept run as the run picker and the header name it, such as "Run 4 · 120 requests · baseline file". */
export function runLabel(run) {
  if (!run) return ''
  const parts = [`Run ${run.ordinal}`, `${formatNumber(run.requests)} ${run.requests === 1 ? 'request' : 'requests'}`]
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
    {id: 'behavior', title: 'What the routes did', rows: comparison.behavior ?? []},
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
