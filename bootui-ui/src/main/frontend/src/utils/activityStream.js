// Pure helpers for the Live Activity stream so the grouping/filtering logic can be unit-tested
// independently of the Vue component.

/**
 * Nest correlated child signals (SQL, REST client calls, exceptions, security events) under the HTTP
 * request entry they belong to, using the server-computed {@code parentId}. Top-level entries keep their
 * newest-first input order; each request's {@code children} are ordered chronologically (oldest first) so
 * they read as the sequence of things that happened while the request was handled.
 *
 * An entry whose {@code parentId} is absent, self-referential, or not present in the supplied list
 * stays top-level (so nothing is hidden when its parent has scrolled out of the window). Every returned
 * entry carries a {@code children} array (possibly empty).
 *
 * @param {Array<object>} entries flat entries, newest-first, each optionally carrying `parentId`
 * @returns {Array<object & {children: Array<object>}>}
 */
export function nestEntries(entries) {
  const list = entries || []
  const byId = new Map(list.map((entry) => [entry.id, entry]))
  const childrenByParent = new Map()
  const topLevel = []
  for (const entry of list) {
    const parentId = entry.parentId
    if (parentId && parentId !== entry.id && byId.has(parentId)) {
      const bucket = childrenByParent.get(parentId) || []
      bucket.push(entry)
      childrenByParent.set(parentId, bucket)
    } else {
      topLevel.push(entry)
    }
  }
  return topLevel.map((entry) => {
    const kids = childrenByParent.get(entry.id) || []
    const children = [...kids].sort((a, b) => (a.timestamp || 0) - (b.timestamp || 0))
    return {...entry, children}
  })
}

/**
 * Filter activity entries by type, severity and a free-text needle (all case-insensitive). An
 * {@code errorsOnly} flag keeps only ERROR-severity entries. Empty/false filters match all.
 *
 * The free-text needle is matched against the entry summary, detail, path, method and type so a
 * developer can quickly narrow to a path fragment, status, exception class or SQL snippet.
 *
 * @param {Array<object>} entries
 * @param {{type?: string, severity?: string, text?: string, errorsOnly?: boolean}} filters
 * @returns {Array<object>}
 */
export function filterEntries(entries, {type = '', severity = '', text = '', errorsOnly = false} = {}) {
  const typeFilter = (type || '').toUpperCase()
  const severityFilter = (severity || '').toUpperCase()
  const needle = (text || '').trim().toLowerCase()
  return (entries || []).filter((entry) => {
    if (errorsOnly && (entry.severity || '').toUpperCase() !== 'ERROR') return false
    if (typeFilter && (entry.type || '').toUpperCase() !== typeFilter) return false
    if (severityFilter && (entry.severity || '').toUpperCase() !== severityFilter) return false
    if (needle && !matchesText(entry, needle)) return false
    return true
  })
}

function matchesText(entry, needle) {
  const haystack = [entry.summary, entry.detail, entry.path, entry.method, entry.type]
    .filter((value) => value != null)
    .join(' ')
    .toLowerCase()
  return haystack.includes(needle)
}

/**
 * Collapse runs of adjacent entries that share the same type, severity and summary into a single
 * row carrying an occurrence count, to cut noise from repeated activity. Order is preserved and the
 * first entry of each run is kept as the representative (so its id/timestamp drive interactions).
 *
 * @param {Array<object>} entries already sorted newest-first
 * @returns {Array<object & {count: number}>}
 */
export function groupEntries(entries) {
  const grouped = []
  for (const entry of entries || []) {
    const previous = grouped[grouped.length - 1]
    if (
      previous &&
      previous.type === entry.type &&
      previous.severity === entry.severity &&
      previous.summary === entry.summary
    ) {
      previous.count += 1
      continue
    }
    grouped.push({...entry, count: 1})
  }
  return grouped
}

/**
 * Bucket entries into a fixed number of equal-width time slots spanning the observed time range, to
 * drive a requests-over-time sparkline. Each bucket reports the total number of entries and how many
 * were ERROR severity, so spikes and error bursts are visible at a glance. Returns an empty array
 * when there are no entries.
 *
 * @param {Array<object>} entries entries carrying a numeric `timestamp` (epoch ms)
 * @param {number} bucketCount number of buckets to produce (clamped to at least 1)
 * @returns {Array<{start: number, end: number, count: number, errors: number}>} oldest bucket first
 */
export function bucketEntries(entries, bucketCount = 24) {
  const list = (entries || []).filter((entry) => typeof entry.timestamp === 'number')
  const buckets = Math.max(1, Math.floor(bucketCount))
  if (!list.length) return []
  let min = Infinity
  let max = -Infinity
  for (const entry of list) {
    if (entry.timestamp < min) min = entry.timestamp
    if (entry.timestamp > max) max = entry.timestamp
  }
  const span = max - min
  const width = span <= 0 ? 0 : span / buckets
  const result = Array.from({length: buckets}, (_, index) => ({
    start: min + index * (width || 0),
    end: min + (index + 1) * (width || 0),
    count: 0,
    errors: 0
  }))
  for (const entry of list) {
    let index = width <= 0 ? buckets - 1 : Math.floor((entry.timestamp - min) / width)
    if (index >= buckets) index = buckets - 1
    if (index < 0) index = 0
    result[index].count += 1
    if ((entry.severity || '').toUpperCase() === 'ERROR') result[index].errors += 1
  }
  return result
}

/**
 * Build a deep link from a merged stream entry to the dedicated panel that owns that signal, so the
 * Live Activity view is a launchpad rather than a dead end. The link prefills the target panel's
 * free-text filter (read from {@code ?q=}) to surface the originating record.
 *
 * Returns {@code null} for entry types without a dedicated drill-down (for example SECURITY) or when
 * there is no useful needle to filter by.
 *
 * @param {object} entry a merged activity entry
 * @returns {{path: string, query: object, label: string}|null}
 */
export function deepLink(entry) {
  if (!entry) return null
  switch (entry.type) {
    case 'REQUEST':
      return entry.path ? {path: '/http-exchanges', query: {q: entry.path}, label: 'Open in HTTP Exchanges'} : null
    case 'SQL': {
      const needle = sqlNeedle(entry.summary)
      return needle ? {path: '/sql-trace', query: {q: needle}, label: 'Open in SQL Trace'} : null
    }
    case 'REST':
      return entry.path
        ? {path: '/rest-client-trace', query: {q: entry.path}, label: 'Open in REST Client Trace'}
        : null
    case 'EXCEPTION': {
      const needle = exceptionNeedle(entry.summary)
      return needle ? {path: '/exceptions', query: {q: needle}, label: 'Open in Exceptions'} : null
    }
    default:
      return null
  }
}

function sqlNeedle(summary) {
  return (summary || '').replace(/…$/, '').trim()
}

function exceptionNeedle(summary) {
  const text = (summary || '').trim()
  if (!text) return ''
  const colon = text.indexOf(': ')
  return colon > 0 ? text.slice(0, colon) : text
}

/**
 * Merge the live head page (the always-refreshing first page) with any additional "older" pages
 * fetched via cursor pagination, dropping ids already present in the head. This only matters when
 * activity persistence is enabled server-side: the default in-memory mode never accumulates an
 * older page, so this is a no-op identity pass-through for it.
 *
 * A duplicate can happen because the head page is re-fetched on every refresh tick and its oldest
 * boundary can drift forward over time; de-duplicating keeps the combined list stable instead of
 * showing the same entry twice. Order is preserved: head entries first (newest-first), then any
 * non-duplicate older entries, so the combined list stays newest-first overall.
 *
 * @param {Array<object>} head newest-first entries from the current (page-1) response
 * @param {Array<object>} older accumulated newest-first entries from "load older" pages
 * @returns {Array<object>}
 */
export function mergeActivityPages(head, older) {
  const headList = head || []
  const olderList = older || []
  if (!olderList.length) return headList
  const seen = new Set(headList.map((entry) => entry.id))
  const rest = olderList.filter((entry) => !seen.has(entry.id))
  return [...headList, ...rest]
}

/**
 * Append a newly fetched "load older" page to the already-accumulated older-entries list, dropping
 * any entries already visible in the live head or already accumulated. Keeps insertion order, so
 * repeated "load older" clicks page monotonically further back in history.
 *
 * @param {Array<object>} head the current live head entries
 * @param {Array<object>} older the already-accumulated older entries
 * @param {Array<object>} newEntries the entries from the freshly fetched older page
 * @returns {Array<object>}
 */
export function appendOlderPage(head, older, newEntries) {
  const seen = new Set([...(head || []), ...(older || [])].map((entry) => entry.id))
  const fresh = (newEntries || []).filter((entry) => !seen.has(entry.id))
  return [...(older || []), ...fresh]
}

/**
 * Build the server-side query parameters for a durable-store-backed activity fetch from the
 * dashboard's current filter selections, so filtering/search is pushed down to the store (SQL WHERE
 * clause, when persistence is enabled) instead of only ever operating on whichever page already
 * happens to be loaded in the browser. Blank/falsy filters are omitted.
 *
 * `errorsOnly` takes priority over an explicit severity selection, mirroring {@link filterEntries}
 * which always requires ERROR severity when `errorsOnly` is set.
 *
 * @param {{type?: string, severity?: string, text?: string, errorsOnly?: boolean}} filters
 * @returns {Record<string, string>}
 */
export function buildActivityQueryParams({type = '', severity = '', text = '', errorsOnly = false} = {}) {
  /** @type {Record<string, string>} */
  const params = {}
  if (type) params.type = type
  const effectiveSeverity = errorsOnly ? 'ERROR' : severity
  if (effectiveSeverity) params.severity = effectiveSeverity
  const needle = (text || '').trim()
  if (needle) params.q = needle
  return params
}
