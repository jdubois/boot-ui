import {formatNumber} from './format.js'

/** Whether a response is a change impact, rather than an error or another shape. */
export function isImpact(value) {
  return Boolean(value && typeof value.status === 'string' && Array.isArray(value.observed))
}

/**
 * The three lists of a resolved impact, in reading order, each with how many rows it lists and holds. Lists with no
 * route are kept, so "nothing here" is said rather than implied.
 */
export function impactLists(impact) {
  if (!isImpact(impact) || impact.status !== 'RESOLVED') return []
  return [
    {
      id: 'observed',
      title: 'Ran through it in this run',
      empty: 'No route that reaches it ran in this run.',
      rows: impact.observed,
      total: impact.observedTotal
    },
    {
      id: 'not-exercised',
      title: 'Not exercised',
      empty: 'Every mapped route that reaches it ran.',
      rows: impact.notExercised,
      total: impact.notExercisedTotal
    },
    {
      id: 'shared',
      title: 'Shares a resource with it',
      empty: 'No other route uses what the routes through it touched.',
      rows: impact.sharedResources,
      total: impact.sharedResourcesTotal
    }
  ]
}

/** A route's traffic in a few words, such as "12 requests · 2 anonymous · 1 error". */
export function routeTraffic(route) {
  const parts = [`${formatNumber(route.requests)} ${route.requests === 1 ? 'request' : 'requests'}`]
  if (route.anonymous > 0) parts.push(`${formatNumber(route.anonymous)} anonymous`)
  if (route.errors > 0) parts.push(`${formatNumber(route.errors)} ${route.errors === 1 ? 'error' : 'errors'}`)
  return parts.join(' · ')
}

/** {@code REPOSITORY productRepository} split into its kind and name, for a node label. */
export function nodeParts(node) {
  if (!node) return null
  const space = node.indexOf(' ')
  if (space < 0) return {kind: '', name: node}
  return {kind: node.slice(0, space).toLowerCase().replace('_', ' '), name: node.slice(space + 1)}
}
