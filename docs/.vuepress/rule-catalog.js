import {slugify} from '@mdit-vue/shared'

const RULE_HEADING = /^###\s+([A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+)\s+(?:—|--|-)\s+(.+?)\s*$/
const SEVERITY_BULLET = /^[-*]\s+\*\*Severity(?:\s*\/\s*confidence)?:?\*\*:?\s*(.+?)\s*$/i
const TRAILING_SEVERITY = /\s*\((CRITICAL|HIGH|MEDIUM|LOW|INFO)(?:\s+or\s+(?:CRITICAL|HIGH|MEDIUM|LOW|INFO))?\)\s*$/
const KNOWN_SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO']
// Some catalogs open a rule's first paragraph with its severity in bold, such as `**HIGH**, or ...` or `**MEDIUM.**`.
const LEADING_SEVERITY = /^\*\*(CRITICAL|HIGH|MEDIUM|LOW|INFO)\b/
const RETIRED = /^(?:\*\*)?Retired\b/

/**
 * Extracts the rule catalog from a `*-CHECKS.md` page.
 *
 * The markdown stays the source of truth — several engine tests assert these exact headings and bullets — so this
 * only reads it. Pages without rule headings return an empty catalog and render unchanged.
 */
export function parseRuleCatalog(content) {
  const lines = content.split('\n')
  const rules = []
  let category = null
  let current = null
  let fenced = false

  for (const line of lines) {
    if (/^```/.test(line)) {
      fenced = !fenced
      continue
    }
    if (fenced) {
      continue
    }

    const heading = line.match(RULE_HEADING)
    if (heading) {
      const [, id, rawTitle] = heading
      const trailing = rawTitle.match(TRAILING_SEVERITY)
      current = {
        id,
        // Matches the anchor markdown-it generates for the same heading.
        slug: slugify(line.replace(/^###\s+/, '').trim()),
        title: rawTitle.replace(TRAILING_SEVERITY, '').trim(),
        severity: trailing ? trailing[1] : null,
        category
      }
      rules.push(current)
      continue
    }

    if (/^##\s+(?!#)/.test(line)) {
      category = line.replace(/^##\s+/, '').trim()
      current = null
      continue
    }

    if (current && !current.severity && !current.retired) {
      const severity = line.match(SEVERITY_BULLET) ?? line.match(LEADING_SEVERITY)
      if (severity) {
        current.severity = normalizeSeverity(severity[1])
      } else if (RETIRED.test(line)) {
        current.retired = true
      }
    }
  }

  // Retired IDs keep their headings for existing links but are no longer rules the advisor runs.
  return rules.filter((rule) => !rule.retired).map(({retired, ...rule}) => rule)
}

function normalizeSeverity(value) {
  const upper = value.toUpperCase()
  return KNOWN_SEVERITIES.find((severity) => upper.startsWith(severity)) ?? 'INFO'
}
