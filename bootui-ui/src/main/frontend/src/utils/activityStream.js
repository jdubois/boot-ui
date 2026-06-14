// Pure helpers for the Live Activity stream so the grouping/filtering logic can be unit-tested
// independently of the Vue component.

/**
 * Filter activity entries by type and severity (case-insensitive). Empty/null filters match all.
 *
 * @param {Array<object>} entries
 * @param {{type?: string, severity?: string}} filters
 * @returns {Array<object>}
 */
export function filterEntries(entries, {type = '', severity = ''} = {}) {
  const typeFilter = (type || '').toUpperCase()
  const severityFilter = (severity || '').toUpperCase()
  return (entries || []).filter((entry) => {
    if (typeFilter && (entry.type || '').toUpperCase() !== typeFilter) return false
    if (severityFilter && (entry.severity || '').toUpperCase() !== severityFilter) return false
    return true
  })
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
