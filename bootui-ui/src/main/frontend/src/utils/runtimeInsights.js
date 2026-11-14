// Runtime Insights (docs/PLAN-v2.md §5.5): pure helpers the panel uses to group, filter, and summarize the report the
// engine projects from the runtime journal. They never fetch anything.

/** The garbage collection and heap kinds the Memory panel counts and links to (docs/PLAN-v2.md M4-19). */
export const MEMORY_KINDS = ['gc-inflated-latency', 'heap-growth-after-gc']

/** The topics, in display order, and the observation kinds each one gathers. */
export const THEMES = [
  {
    id: 'time',
    label: 'Time',
    icon: 'bi-stopwatch',
    kinds: ['route-time-breakdown', 'route-latency-outliers', 'event-loop-blocking', 'work-after-response']
  },
  // Garbage collection and heap rows are reached from the Memory panel, which links here (docs/PLAN-v2.md M4-19).
  {id: 'memory', label: 'Memory', icon: 'bi-memory', kinds: MEMORY_KINDS},
  {
    id: 'queries',
    label: 'Queries',
    icon: 'bi-database',
    kinds: [
      'repeated-selects',
      'safe-method-dml',
      'unbatched-write-loop',
      'lazy-sql-after-handler',
      'orm-auto-flush',
      'large-persistence-context',
      'paged-without-order',
      'slow-flush'
    ]
  },
  {id: 'errors', label: 'Errors', icon: 'bi-exclamation-octagon', kinds: ['exception-hotspots', 'errors-behind-2xx']},
  {
    id: 'transactions',
    label: 'Transactions',
    icon: 'bi-arrow-repeat',
    kinds: [
      'connections-per-request',
      'connection-reacquisition',
      'split-transaction-writes',
      'transaction-across-remote-call',
      'transactional-listener-skipped',
      'after-commit-writes',
      'readonly-candidate-transaction'
    ]
  },
  {
    id: 'access',
    label: 'Access',
    icon: 'bi-shield-lock',
    kinds: ['anonymous-data-reach', 'anonymous-success-on-restricted-route']
  },
  {id: 'framework', label: 'Framework', icon: 'bi-boxes', kinds: ['framework-warnings-by-route', 'proxy-bypass']},
  {id: 'ai', label: 'AI', icon: 'bi-cpu', kinds: ['ai-usage-by-route']}
]

const STATUS_ORDER = {OBSERVED: 0, PARTIAL: 1, INSUFFICIENT: 2}

// Browser presentation order, not severity, evidence quality, or the engine's default agent selection.
const GROUP_SUMMARIES = {
  'errors-behind-2xx': 'Successful responses with recorded failures in the same request.',
  'anonymous-success-on-restricted-route': 'Anonymous success on a route that also restricted requests.',
  'after-commit-writes': 'Database writes recorded after transaction completion.',
  'transactional-listener-skipped': 'Transactional listeners skipped without an active transaction.',
  'safe-method-dml': 'GET or HEAD requests with recorded database write activity.',
  'proxy-bypass': 'Recorded calls that bypassed proxy advice.',
  'paged-without-order': 'Paged database reads without an explicit ORDER BY.',
  'event-loop-blocking': 'JDBC statements started on an event-loop thread.',
  'transaction-across-remote-call': 'Transactions and their connections span outbound calls.',
  'split-transaction-writes': 'A request committed its writes in separate transactions.',
  'repeated-selects': 'The same SELECT repeated after another statement.',
  'unbatched-write-loop': 'Repeated database writes without JDBC batching.',
  'lazy-sql-after-handler': 'SQL executed after the request handler returned.',
  'connections-per-request': 'A request held more than one database connection together.',
  'connection-reacquisition': 'A session released and re-acquired its database connection.',
  'slow-flush': 'Hibernate flushes took a substantial part of recorded ORM time.',
  'large-persistence-context': 'Recorded persistence contexts with many managed entities.',
  'gc-inflated-latency': 'Slow requests that overlapped recorded garbage collection pauses.',
  'heap-growth-after-gc': 'Old-generation occupancy rising after collections.',
  'work-after-response': 'Executor work still running after a response started.',
  'orm-auto-flush': 'Hibernate flushed changes before executing a query.',
  'exception-hotspots': 'Recorded exceptions grouped by the route or execution that encountered them.',
  'readonly-candidate-transaction': 'Read-write transactions with reads and no recorded writes.',
  'framework-warnings-by-route': 'Framework warnings and errors recorded during this run.',
  'anonymous-data-reach': 'Database write activity reached by anonymous callers; this may be intended.',
  'ai-usage-by-route': 'Recorded model calls and token usage by route, job, or listener.',
  'route-latency-outliers': 'Slow requests compared with a retained warm 2xx baseline on the same route.',
  'route-time-breakdown': 'Per-route measurements and phase evidence, not a defect verdict.'
}
const GROUP_ORDER = new Map(Object.keys(GROUP_SUMMARIES).map((kind, index) => [kind, index]))

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
 * The observations matching the search text and theme, grouped by check in suggested review order. The search
 * matches the check's title and kind, the route or subject, the sentence, and the evidence a sentence names, such as a
 * table or a logger. Every retained observation is shown unless the developer filters it out.
 */
export function groupObservations(report, {query = '', theme = ''} = {}) {
  const needle = query.trim().toLowerCase()
  const titles = new Map((report?.checks ?? []).map((check) => [check.kind, check.title]))
  const groups = new Map()
  for (const check of report?.checks ?? []) {
    groups.set(check.kind, {kind: check.kind, title: check.title, observations: []})
  }
  for (const observation of report?.observations ?? []) {
    if (theme && themeOf(observation.kind) !== theme) continue
    const searched = `${titles.get(observation.kind) ?? ''} ${observation.kind} ${observation.subject} ${observation.sentence} ${(observation.limitations ?? []).join(' ')}`
    if (needle && !searched.toLowerCase().includes(needle)) continue
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
    .sort((a, b) => (GROUP_ORDER.get(a.kind) ?? GROUP_ORDER.size) - (GROUP_ORDER.get(b.kind) ?? GROUP_ORDER.size))
    .map((group) => {
      const observations = [...group.observations].sort(
        (a, b) => (STATUS_ORDER[a.status] ?? 9) - (STATUS_ORDER[b.status] ?? 9) || b.affected - a.affected
      )
      const subjects = [
        ...new Set(observations.map((observation) => observation.subject).filter((subject) => Boolean(subject?.trim())))
      ]
      return {
        ...group,
        summary: GROUP_ORDER.has(group.kind) ? GROUP_SUMMARIES[group.kind] : '',
        subjects,
        subjectPreview: subjects.slice(0, 2),
        moreSubjects: Math.max(0, subjects.length - 2),
        observations
      }
    })
}

/**
 * Symmetric subject-first grouping ("Group by: Subject"): groups matching observations by their route, job, bean, or
 * run-wide subject. Subjects that carry specific review candidates appear before subjects that only carry broad timing
 * or usage summaries; inside each subject, observations follow the same suggested review order (GROUP_ORDER).
 */
export function groupObservationsBySubject(report, {query = '', theme = ''} = {}) {
  const checkGroups = groupObservations(report, {query, theme})
  const titles = new Map((report?.checks ?? []).map((check) => [check.kind, check.title]))
  const bySubject = new Map()

  for (const group of checkGroups) {
    for (const observation of group.observations) {
      const subject = observation.subject?.trim() || 'No subject recorded'
      if (!bySubject.has(subject)) {
        bySubject.set(subject, {
          kind: `subject:${subject}`,
          subject,
          title: subject,
          observations: []
        })
      }
      bySubject.get(subject).observations.push({
        ...observation,
        checkTitle: titles.get(observation.kind) ?? group.title ?? observation.kind
      })
    }
  }

  const rankOf = (kind) => GROUP_ORDER.get(kind) ?? GROUP_ORDER.size
  return [...bySubject.values()]
    .map((entry) => {
      const observations = [...entry.observations].sort(
        (a, b) =>
          rankOf(a.kind) - rankOf(b.kind) ||
          (STATUS_ORDER[a.status] ?? 9) - (STATUS_ORDER[b.status] ?? 9) ||
          b.affected - a.affected
      )
      const checkTitles = [...new Set(observations.map((obs) => obs.checkTitle).filter(Boolean))]
      const bestRank = observations.reduce((min, obs) => Math.min(min, rankOf(obs.kind)), GROUP_ORDER.size)
      return {
        ...entry,
        summary: checkTitles.slice(0, 2).join(' · '),
        checkTitles,
        subjectPreview: checkTitles.slice(0, 2),
        moreSubjects: Math.max(0, checkTitles.length - 2),
        bestRank,
        observations
      }
    })
    .sort(
      (a, b) =>
        a.bestRank - b.bestRank || b.observations.length - a.observations.length || a.title.localeCompare(b.title)
    )
}

/**
 * Extracts a concise summary for an exemplar request ID when the loaded evidence table includes a Request column.
 */
export function exemplarSummary(detail, requestId) {
  if (!detail?.columns || !detail?.rows || !requestId) return ''
  const reqCol = detail.columns.indexOf('Request')
  if (reqCol < 0) return ''
  const row = detail.rows.find((candidate) => candidate?.cells?.[reqCol] === requestId)
  if (!row) return ''
  const parts = []
  detail.columns.forEach((col, index) => {
    if (index === reqCol) return
    const val = String(row.cells?.[index] ?? '').trim()
    if (!val || val === '—' || val.length > 36) return
    if (parts.length < 2) {
      parts.push(`${col}: ${val}`)
    }
  })
  return parts.join(' · ')
}

/**
 * Topics represented by retained observations, keeping the selected topic available even when it has no rows.
 */
export function themeFilters(report, {theme = ''} = {}) {
  const observations = report?.observations ?? []
  return [
    {id: '', label: 'Any topic', icon: 'bi-grid', count: observations.length},
    ...THEMES.map((entry) => ({
      id: entry.id,
      label: entry.label,
      icon: entry.icon ?? 'bi-circle',
      count: observations.filter((observation) => themeOf(observation.kind) === entry.id).length
    })).filter((entry) => entry.count > 0 || entry.id === theme)
  ]
}

/** Semantic icon and tone for a check group or subject group in Runtime Insights. */
export function groupVisual(kind) {
  if (String(kind).startsWith('subject:')) {
    const subject = String(kind).slice('subject:'.length)
    if (/^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\s/.test(subject)) {
      return {icon: 'bi-signpost-split', tone: 'primary'}
    }
    if (subject.startsWith('Scheduled:') || subject.startsWith('@Scheduled')) {
      return {icon: 'bi-clock-history', tone: 'info'}
    }
    return {icon: 'bi-bullseye', tone: 'secondary'}
  }
  const t = themeOf(kind)
  return (
    {
      errors: {icon: 'bi-exclamation-octagon', tone: 'danger'},
      queries: {icon: 'bi-database', tone: 'success'},
      transactions: {icon: 'bi-arrow-repeat', tone: 'warning'},
      time: {icon: 'bi-stopwatch', tone: 'primary'},
      memory: {icon: 'bi-memory', tone: 'info'},
      access: {icon: 'bi-shield-lock', tone: 'warning'},
      framework: {icon: 'bi-boxes', tone: 'secondary'},
      ai: {icon: 'bi-cpu', tone: 'primary'}
    }[t] ?? {icon: 'bi-lightbulb', tone: 'secondary'}
  )
}

/** Splits a route subject like "POST /api/orders" into {method: "POST", rest: "/api/orders"} when applicable. */
export function splitSubjectBadge(subject) {
  const text = String(subject ?? '').trim()
  const match = /^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\s+(.+)$/.exec(text)
  if (match) {
    return {method: match[1], rest: match[2]}
  }
  return {method: null, rest: text}
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
