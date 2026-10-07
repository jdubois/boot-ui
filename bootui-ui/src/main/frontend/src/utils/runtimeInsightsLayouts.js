// Runtime Insights layout proposals (temporary, ?insightsLayout=a|b|c): pure helpers the three proposed layouts use to
// split the same report into tabs. They never fetch anything. Once a layout is chosen the others and the query
// parameter go away.
import {availableThemes, themeOf, validationOf} from './runtimeInsights.js'

/** The proposed layouts, by query value. An absent or unknown value keeps the current layout. */
export const INSIGHT_LAYOUTS = ['a', 'b', 'c']

/** The layout a route query asks for, or '' for the current one. */
export function insightsLayout(query) {
  const value = typeof query?.insightsLayout === 'string' ? query.insightsLayout.toLowerCase() : ''
  return INSIGHT_LAYOUTS.includes(value) ? value : ''
}

/** Proposal B's areas, in tab order, and the observation kinds each gathers. */
export const AREAS = [
  {
    id: 'data',
    label: 'Data access',
    icon: 'bi-database',
    kinds: [
      'repeated-selects',
      'safe-method-dml',
      'lazy-sql-after-handler',
      'orm-auto-flush',
      'large-persistence-context',
      'connections-per-request',
      'split-transaction-writes',
      'transaction-across-remote-call',
      'transactional-listener-skipped',
      'after-commit-writes',
      'proxy-bypass'
    ]
  },
  {
    id: 'errors',
    label: 'Errors',
    icon: 'bi-bug',
    kinds: ['exception-hotspots', 'errors-behind-2xx', 'framework-warnings-by-route']
  },
  {
    id: 'performance',
    label: 'Performance',
    icon: 'bi-speedometer2',
    kinds: [
      'route-time-breakdown',
      'event-loop-blocking',
      'work-after-response',
      'gc-inflated-latency',
      'heap-growth-after-gc',
      'ai-usage-by-route'
    ]
  },
  {
    id: 'security',
    label: 'Security',
    icon: 'bi-shield-lock',
    kinds: ['anonymous-data-reach', 'anonymous-success-on-restricted-route']
  },
  {id: 'changes', label: 'Changes', icon: 'bi-git', kinds: ['changed-code-not-executed']}
]

/** The area an observation kind belongs to, or null for a kind no area names. */
export function areaOf(kind) {
  return AREAS.find((area) => area.kinds.includes(kind))?.id ?? null
}

/**
 * Proposal B's tabs: everything, then each area the report's checks cover with the number of observations it lists,
 * then the run's coverage. Changes is always there, since it also holds the comparison and the change impact.
 */
export function areaTabs(report, observations) {
  const kinds = new Set((report?.checks ?? []).map((check) => check.kind))
  const count = (area) => observations.filter((observation) => areaOf(observation.kind) === area.id).length
  return [
    {id: 'all', label: 'All', icon: 'bi-list-ul', count: observations.length},
    ...AREAS.filter((area) => area.id === 'changes' || area.kinds.some((kind) => kinds.has(kind))).map((area) => ({
      id: area.id,
      label: area.label,
      icon: area.icon,
      count: count(area)
    })),
    {id: 'coverage', label: 'Coverage', icon: 'bi-bullseye', count: null}
  ]
}

/**
 * Proposal C's filter tabs: every observation, then each theme the report's checks cover with the number of
 * observations it lists.
 */
export function themeTabs(report, observations) {
  return [
    {id: 'all', label: 'All', count: observations.length},
    ...availableThemes(report).map((theme) => ({
      id: theme.id,
      label: theme.label,
      count: observations.filter((observation) => themeOf(observation.kind) === theme.id).length
    }))
  ]
}

/**
 * How many of the listed observations belong to a kind that passed its external validation, and how many to one that
 * did not, so a summary can say how much of the list is proven before a row is opened.
 */
export function validationCounts(report, observations) {
  const checks = new Map((report?.checks ?? []).map((check) => [check.kind, check]))
  let unvalidated = 0
  for (const observation of observations) {
    if (validationOf(checks.get(observation.kind))) unvalidated += 1
  }
  return {total: observations.length, validated: observations.length - unvalidated, unvalidated}
}
