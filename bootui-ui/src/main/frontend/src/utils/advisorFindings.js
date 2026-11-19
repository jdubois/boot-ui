import {formatLocation} from './sourceLocation.js'

// The UI and export consume this same aligned, already-loaded evidence projection.
export const advisorEvidenceKey = Symbol('advisor-evidence')

export function advisorRows(rule, page = null) {
  const texts = page ? page.violations || [] : rule.sampleViolations || []
  const aligned = (values) => (Array.isArray(values) && values.length === texts.length ? values : [])
  const locations = aligned(page ? page.locations : rule.sampleLocations)
  const changes = aligned(page?.changes)
  const identities = aligned(page?.identities)
  const suppressed = aligned(page?.suppressed)
  return texts.map((text, index) => {
    const label = formatLocation(locations[index])
    return {
      text,
      location: label ? locations[index] : null,
      label,
      isNew: changes[index] === 'NEW',
      identity: identities[index] || null,
      dismissed: suppressed[index] === true
    }
  })
}

export function matchesAdvisorRule(rule, search, severity, rows = advisorRows(rule)) {
  if (severity && rule.severity !== severity) return false
  const text = [
    rule.id,
    rule.name,
    rule.description,
    rule.recommendation,
    ...(rule.sampleViolations || []),
    ...rows.map((row) => row.text)
  ]
    .filter(Boolean)
    .join(' ')
    .toLocaleLowerCase()
  return text.includes(search.trim().toLocaleLowerCase())
}
