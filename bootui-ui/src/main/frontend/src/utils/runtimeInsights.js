// Runtime Insights (docs/PLAN-v2.md §5.5): pure helpers the panel uses to group, filter, and summarize the report the
// engine projects from the runtime journal. They never fetch anything.

/** The garbage collection and heap kinds the Memory panel counts and links to (docs/PLAN-v2.md M4-19). */
export const MEMORY_KINDS = ['gc-inflated-latency', 'heap-growth-after-gc']

/** The theme chips, in display order, and the observation kinds each one gathers. */
export const THEMES = [
  {
    id: 'time',
    label: 'Time',
    kinds: ['route-time-breakdown', 'event-loop-blocking', 'work-after-response']
  },
  // Garbage collection and heap rows are reached from the Memory panel, which links here (docs/PLAN-v2.md M4-19).
  {id: 'memory', label: 'Memory', kinds: MEMORY_KINDS},
  {
    id: 'queries',
    label: 'Queries',
    kinds: [
      'repeated-selects',
      'safe-method-dml',
      'lazy-sql-after-handler',
      'orm-auto-flush',
      'large-persistence-context'
    ]
  },
  {id: 'errors', label: 'Errors', kinds: ['exception-hotspots', 'errors-behind-2xx']},
  {
    id: 'transactions',
    label: 'Transactions',
    kinds: [
      'connections-per-request',
      'split-transaction-writes',
      'transaction-across-remote-call',
      'transactional-listener-skipped',
      'after-commit-writes'
    ]
  },
  {id: 'access', label: 'Access', kinds: ['anonymous-data-reach', 'anonymous-success-on-restricted-route']},
  {id: 'framework', label: 'Framework', kinds: ['framework-warnings-by-route', 'proxy-bypass']},
  {id: 'ai', label: 'AI', kinds: ['ai-usage-by-route']}
]

const STATUS_ORDER = {OBSERVED: 0, PARTIAL: 1, INSUFFICIENT: 2}

/** The theme an observation kind belongs to, or null. */
export function themeOf(kind) {
  return THEMES.find((theme) => theme.kinds.includes(kind))?.id ?? null
}

/** The themes the report's checks cover, so a chip never filters to nothing by construction. */
export function availableThemes(report) {
  const kinds = new Set((report?.checks ?? []).map((check) => check.kind))
  return THEMES.filter((theme) => theme.kinds.some((kind) => kinds.has(kind)))
}

/**
 * Whether the default list shows an observation (docs/PLAN-v2.md M4-19). A server that predates the flag lists them
 * all.
 */
export function isListed(observation) {
  return observation?.listed !== false
}

/**
 * Whether an observation is shown under the current filters: listed by default, or every row is asked for with
 * **Show all routes** or a search, which is explicit intent.
 */
function shown(observation, {query = '', all = false, selectedId = null} = {}) {
  return all || query.trim() !== '' || isListed(observation) || observation.id === selectedId
}

/**
 * The observations matching the search text and theme, grouped by check in the report's check order. The search
 * matches the check's title and kind, the route or subject, the sentence, and the evidence a sentence names, such as a
 * table or a logger. Without
 * `all` or a search, only the observations listed by default are shown, and the selected one, so a refresh that leaves
 * it out never takes it away from the developer; with them, the listed ones stay first.
 */
export function groupObservations(report, {query = '', theme = '', all = false, selectedId = null} = {}) {
  const needle = query.trim().toLowerCase()
  const titles = new Map((report?.checks ?? []).map((check) => [check.kind, check.title]))
  const groups = new Map()
  for (const check of report?.checks ?? []) {
    groups.set(check.kind, {kind: check.kind, title: check.title, observations: []})
  }
  for (const observation of report?.observations ?? []) {
    if (theme && themeOf(observation.kind) !== theme) continue
    const searched = `${titles.get(observation.kind) ?? ''} ${observation.kind} ${observation.subject} ${observation.sentence}`
    if (needle && !searched.toLowerCase().includes(needle)) continue
    if (!shown(observation, {query, all, selectedId})) continue
    if (!groups.has(observation.kind)) {
      groups.set(observation.kind, {
        kind: observation.kind,
        title: titles.get(observation.kind) ?? observation.kind,
        observations: []
      })
    }
    groups.get(observation.kind).observations.push(observation)
  }
  return [...groups.values()]
    .filter((group) => group.observations.length > 0)
    .map((group) => ({
      ...group,
      observations: [...group.observations].sort(
        (a, b) =>
          Number(!isListed(a)) - Number(!isListed(b)) ||
          (STATUS_ORDER[a.status] ?? 9) - (STATUS_ORDER[b.status] ?? 9) ||
          b.affected - a.affected
      )
    }))
}

/**
 * The theme filters above the list, each with the number of rows it would show under the current search and listing,
 * led by every theme. A theme with nothing to show is left out unless it is the one selected, so no filter leads to an
 * empty list by construction.
 */
export function themeFilters(report, {query = '', theme = '', all = false, selectedId = null} = {}) {
  const observations = groupObservations(report, {query, all, selectedId}).flatMap((group) => group.observations)
  return [
    {id: '', label: 'All', count: observations.length},
    ...availableThemes(report)
      .map((entry) => ({
        id: entry.id,
        label: entry.label,
        count: observations.filter((observation) => themeOf(observation.kind) === entry.id).length
      }))
      .filter((entry) => entry.count > 0 || entry.id === theme)
  ]
}

/**
 * The observations the current filters leave out only because the default list does not show them, counted per check
 * in the report's check order, so the panel can say what **Show all routes** would add. Empty when every row is shown.
 */
export function unlistedSummary(report, {query = '', theme = '', all = false, selectedId = null} = {}) {
  if (all || query.trim() !== '') return {total: 0, groups: []}
  const titles = new Map((report?.checks ?? []).map((check) => [check.kind, check.title]))
  const counts = new Map()
  for (const observation of report?.observations ?? []) {
    if (isListed(observation) || observation.id === selectedId) continue
    if (theme && themeOf(observation.kind) !== theme) continue
    counts.set(observation.kind, (counts.get(observation.kind) ?? 0) + 1)
  }
  const order = (report?.checks ?? []).map((check) => check.kind)
  const groups = [...counts.entries()]
    .sort(([a], [b]) => order.indexOf(a) - order.indexOf(b))
    .map(([kind, count]) => ({kind, title: titles.get(kind) ?? kind, count}))
  return {total: groups.reduce((sum, group) => sum + group.count, 0), groups}
}

// Garbage collections belong to the JVM, never to one request: a request's pauses are joined to it by collection id.
// Lifecycle events, such as the run's start, belong to the run.
const RUN_LEVEL_SOURCES = new Set(['gc', 'lifecycle'])

/**
 * How the retained events are linked to their request, summed over every request-level source: by request id, by trace
 * id, by a scheduled run or consumed message, and outside any request, such as startup work. Shares are whole percents
 * of the total.
 */
export function coverageSummary(report) {
  const totals = {byRequestId: 0, byTraceId: 0, byExecutionId: 0, unlinked: 0}
  for (const source of coverageSources(report)) {
    totals.byRequestId += source.byRequestId ?? 0
    totals.byTraceId += source.byTraceId ?? 0
    totals.byExecutionId += source.byExecutionId ?? 0
    totals.unlinked += source.unlinked ?? 0
  }
  const events = totals.byRequestId + totals.byTraceId + totals.byExecutionId + totals.unlinked
  const share = (count) => (events === 0 ? 0 : Math.round((count * 100) / events))
  return {
    events,
    segments: [
      {id: 'request', label: 'request id', count: totals.byRequestId, share: share(totals.byRequestId)},
      {id: 'trace', label: 'trace id', count: totals.byTraceId, share: share(totals.byTraceId)},
      {
        id: 'execution',
        label: 'scheduled run or message',
        count: totals.byExecutionId,
        share: share(totals.byExecutionId)
      },
      {id: 'none', label: 'outside any request', count: totals.unlinked, share: share(totals.unlinked)}
    ]
  }
}

/** The coverage of each request-level source, most events first. */
export function coverageSources(report) {
  return (report?.coverage ?? [])
    .filter((source) => !RUN_LEVEL_SOURCES.has(source.source))
    .sort((a, b) => b.events - a.events || a.source.localeCompare(b.source))
}

/**
 * Splits engine text into plain and code parts: the engine marks routes, statements, and classes with backticks, which
 * the panel renders as code rather than showing the marks.
 */
export function textParts(text) {
  return String(text ?? '')
    .split('`')
    .map((value, index) => ({value, code: index % 2 === 1}))
    .filter((part) => part.value !== '')
}

const MACHINE_COLUMNS = new Set([
  'Request',
  'Call site',
  'Statement',
  'Thread',
  'Logger',
  'Exception group',
  'Call',
  'Models',
  'Collection'
])

/** Whether an evidence column holds machine output, shown in monospace. */
export function isMachineColumn(column) {
  return MACHINE_COLUMNS.has(column)
}

/** Checks that did not fully run and evaluated checks with explanations an empty or short list must never hide. */
export function checksWithReasons(report) {
  return (report?.checks ?? []).filter((check) => check.status !== 'EVALUATED' || check.reason)
}

/** The state the panel shows before any observation. */
export function emptyState(report) {
  if (!report) return null
  if (!report.available) return 'disabled'
  if ((report.observations ?? []).length > 0) return null
  if ((report.window?.requests ?? 0) === 0) return 'no-requests'
  if ((report.observations ?? []).length === 0) return 'nothing-observed'
  return null
}

const SHARE = /^(\d+(?:\.\d+)?)\s*%$/

/**
 * The evidence's share column, such as a route time breakdown's "Share", as each row's share in percent and the row
 * with the largest one, or null when the evidence has none. Rows without a share, such as overlapping calls, get null.
 */
export function evidenceShares(detail) {
  const column = detail?.columns?.indexOf('Share') ?? -1
  if (column < 0) return null
  const shares = (detail.rows ?? []).map((row) => {
    const match = SHARE.exec(String(row?.cells?.[column] ?? '').trim())
    return match ? Math.min(100, Number(match[1])) : null
  })
  let top = -1
  shares.forEach((share, index) => {
    if (share != null && share > 0 && (top < 0 || share > shares[top])) top = index
  })
  return {column, shares, top}
}

const NUMBER = /^-?\d[\d,]*(?:\.\d+)?$/

/**
 * The indexes of the evidence columns that hold only numbers, such as a total in milliseconds, so they align on their
 * last digit. A share column is drawn as bars instead, and a column with no value at all is not numeric.
 */
export function numericColumns(detail) {
  const numeric = new Set()
  const rows = detail?.rows ?? []
  ;(detail?.columns ?? []).forEach((column, index) => {
    if (column === 'Share') return
    const values = rows.map((row) => String(row?.cells?.[index] ?? '').trim()).filter((value) => value !== '')
    if (values.length && values.every((value) => NUMBER.test(value))) numeric.add(index)
  })
  return numeric
}
