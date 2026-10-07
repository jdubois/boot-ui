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

const CODE_STATUS = {
  EXECUTED: 'ran in this run',
  GENERATED: 'ran in this run',
  NEVER_EXECUTED: 'not run yet',
  NOT_TRACKED: 'not tracked'
}

/**
 * The code changes a comparison leads with (M5-7a), or null when the response carries none: whether they are listed,
 * why not, their counts in a few words, and each changed or added method as a row.
 */
export function codeChanges(comparison) {
  const changes = comparison?.codeChanges
  if (!changes || typeof changes.available !== 'boolean') return null
  if (!changes.available) return {available: false, reason: changes.unavailableReason ?? '', rows: [], counts: ''}
  return {
    available: true,
    reason: null,
    counts: codeChangeCounts(changes.counts),
    rows: (changes.methods ?? []).map(codeChangeRow),
    more: Math.max(0, (changes.methodsTotal ?? 0) - (changes.methods?.length ?? 0)),
    limitations: changes.limitations ?? []
  }
}

/** Code change counts, such as "2 changed · 1 added · 3 removed · 1 not run yet". */
export function codeChangeCounts(counts) {
  if (!counts) return ''
  const parts = [`${formatNumber(counts.changed)} changed`, `${formatNumber(counts.added)} added`]
  if (counts.removed != null) parts.push(`${formatNumber(counts.removed)} removed`)
  if (counts.notExecuted > 0) parts.push(`${formatNumber(counts.notExecuted)} not run yet`)
  return parts.join(' · ')
}

/**
 * Whether change impact can check a method: never a constructor, an initializer, or a synthetic method such as a
 * lambda's, which it cannot name (the engine's RuntimeInsightsAgentView.methodSymbol applies the same rule).
 */
export function checkableMethod(method) {
  const name = method?.name
  return Boolean(name && method.className && !name.startsWith('<') && !name.includes('$'))
}

/** One changed or added method as the comparison lists it: a short name, its change, whether it ran, and where. */
export function codeChangeRow(method) {
  const className = method.className ?? ''
  const simple = className.slice(className.lastIndexOf('.') + 1)
  return {
    key: method.key,
    checkable: checkableMethod(method),
    name: `${simple}#${method.name}`,
    change: method.change === 'ADDED' ? 'ADDED' : 'CHANGED',
    changeLabel: method.change === 'ADDED' ? 'Added' : 'Changed',
    ran: method.status === 'EXECUTED' || method.status === 'GENERATED',
    status: CODE_STATUS[method.status] ?? String(method.status ?? '').toLowerCase(),
    notTrackedReason: method.notTrackedReason ?? null,
    routes: method.routes ?? [],
    moreRoutes: Math.max(0, (method.routesTotal ?? 0) - (method.routes?.length ?? 0)),
    note: method.routesNote ?? null
  }
}

const SIDE_EFFECT_SENSORS = {
  network: 'Network',
  files: 'Files',
  processes: 'Processes',
  environment: 'Environment'
}

const SIDE_EFFECT_STATUS = {
  COMPARED: 'compared',
  PARTIAL: 'partly compared',
  NOT_COMPARED: 'not compared'
}

const SIDE_EFFECT_MARKERS = {
  ADDED: {icon: 'bi-plus-lg', label: 'New'},
  REMOVED: {icon: 'bi-dash-lg', label: 'Gone'},
  NOT_EXERCISED: {icon: 'bi-question-lg', label: 'Not exercised'}
}

/**
 * What changed outside the JVM (M5-7b), or null when the response carries none: whether side effects were compared,
 * why not, each sensor with its status and counts, and each new, gone, or not exercised key as a row.
 */
export function sideEffectChanges(comparison) {
  const changes = comparison?.sideEffects
  if (!changes || typeof changes.available !== 'boolean') return null
  if (!changes.available) return {available: false, reason: changes.unavailableReason ?? '', sensors: [], rows: []}
  return {
    available: true,
    reason: null,
    partial: Boolean(changes.partial),
    // "Nothing changed" only when a sensor was compared and no row was withheld.
    settled: !changes.partial && (changes.sensors ?? []).some((sensor) => sensor.status === 'COMPARED'),
    sensors: (changes.sensors ?? []).map(sideEffectSensor),
    rows: (changes.changes ?? []).map((change, index) => ({
      key: `${change.change}-${change.sensor}-${change.owner}-${change.kind}-${change.target}-${index}`,
      change: change.change,
      marker: SIDE_EFFECT_MARKERS[change.change] ?? changeMarker(change.change),
      sentence: change.sentence
    })),
    more: Math.max(0, (changes.changesTotal ?? 0) - (changes.changes?.length ?? 0)),
    limitations: changes.limitations ?? []
  }
}

/** One sensor of the side-effects comparison, such as "Network · compared · 1 new · 1 gone". */
export function sideEffectSensor(sensor) {
  const parts = [SIDE_EFFECT_STATUS[sensor.status] ?? String(sensor.status ?? '').toLowerCase()]
  if (sensor.status !== 'NOT_COMPARED') {
    if (sensor.added > 0) parts.push(`${formatNumber(sensor.added)} new`)
    if (sensor.removed > 0) parts.push(`${formatNumber(sensor.removed)} gone`)
    if (sensor.notExercised > 0) parts.push(`${formatNumber(sensor.notExercised)} not exercised`)
  }
  return {
    id: sensor.sensor,
    label: SIDE_EFFECT_SENSORS[sensor.sensor] ?? sensor.sensor,
    compared: sensor.status !== 'NOT_COMPARED',
    summary: parts.join(' · '),
    reason: sensor.reason ?? null
  }
}
