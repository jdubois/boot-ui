// Pure helpers for the Live Activity stream so the grouping/filtering logic can be unit-tested
// independently of the Vue component.

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
