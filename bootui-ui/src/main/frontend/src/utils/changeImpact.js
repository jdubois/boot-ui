import {formatNumber} from './format.js'

/** Whether a response is a change impact, rather than an error or another shape. */
export function isImpact(value) {
  return Boolean(value && typeof value.status === 'string' && Array.isArray(value.observed))
}

/** Whether an impact's observed routes come from the route trees: a method's, read with the BootUI agent. */
export function fromRouteTrees(impact) {
  return impact?.observedFrom === 'ROUTE_TREES'
}

/**
 * The lists of a resolved impact, in reading order, each with how many rows it lists and holds. Lists with no route
 * are kept, so "nothing here" is said rather than implied. A method's impact read from the route trees adds the routes
 * that ran without their call trees showing it, which is never proof that they did not run it.
 */
export function impactLists(impact) {
  if (!isImpact(impact) || impact.status !== 'RESOLVED') return []
  const trees = fromRouteTrees(impact)
  const notObservedTotal = impact.notObservedTotal ?? 0
  const lists = [
    {
      id: 'observed',
      title: trees ? 'Ran it in this run' : 'Ran through it in this run',
      empty: trees ? "No request's call tree ran it in this run." : 'No route that reaches it ran in this run.',
      rows: impact.observed,
      total: impact.observedTotal
    }
  ]
  if (trees) {
    lists.push({
      id: 'not-observed',
      title: 'Ran without showing it',
      empty: 'Every route that reaches it and ran either ran it or is proven not to have.',
      rows: impact.notObserved ?? [],
      total: notObservedTotal
    })
  }
  lists.push(
    {
      id: 'not-exercised',
      title: impact.notExercisedUndetermined ? 'Not exercised (incomplete)' : 'Not exercised',
      empty: notExercisedEmpty(impact, trees, notObservedTotal),
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
  )
  return lists
}

function notExercisedEmpty(impact, trees, notObservedTotal) {
  if (trees && notObservedTotal > 0) {
    return 'No route is proven not to have run it: the routes that ran without showing it are listed above.'
  }
  if (impact.notExercisedUndetermined) {
    return 'Cannot determine whether every mapped route ran: the route aggregate exceeded its limit.'
  }
  return 'Every mapped route that reaches it ran.'
}

/** What Code Inventory says of a method in this run, as a short phrase, or null when it says nothing. */
export function methodStatusText(status) {
  switch (status) {
    case 'EXECUTED':
      return 'Code Inventory: ran in this run'
    case 'NEVER_EXECUTED':
      return 'Code Inventory: never ran in this run'
    case 'NOT_TRACKED':
      return 'Code Inventory: not tracked, so whether it ran is unknown'
    default:
      return null
  }
}

/**
 * A route's traffic in a few words, such as "12 requests · 2 anonymous · 1 error", led for a method's observed route by
 * how many of them ran it, such as "3 ran it of 12 requests", and ending with "partial" when its call trees may miss or
 * under-count what its requests ran.
 */
export function routeTraffic(route) {
  const requests = `${formatNumber(route.requests)} ${route.requests === 1 ? 'request' : 'requests'}`
  const parts = [
    route.executedRequests > 0 ? `${formatNumber(route.executedRequests)} ran it of ${requests}` : requests
  ]
  if (route.anonymous > 0) parts.push(`${formatNumber(route.anonymous)} anonymous`)
  if (route.errors > 0) parts.push(`${formatNumber(route.errors)} ${route.errors === 1 ? 'error' : 'errors'}`)
  if (route.partial) parts.push('partial')
  return parts.join(' · ')
}

/** {@code REPOSITORY productRepository} split into its kind and name, for a node label. */
export function nodeParts(node) {
  if (!node) return null
  const space = node.indexOf(' ')
  if (space < 0) return {kind: '', name: node}
  return {kind: kindLabel(node.slice(0, space)), name: node.slice(space + 1)}
}

/** A node kind as read, such as "graphql operation" for {@code GRAPHQL_OPERATION}. */
export function kindLabel(kind) {
  return String(kind ?? '')
    .toLowerCase()
    .replaceAll('_', ' ')
}

/** Whether a response is the impact box's symbol suggestions. */
export function isSymbols(value) {
  return Boolean(value && typeof value.available === 'boolean' && Array.isArray(value.symbols))
}

/**
 * A suggested symbol as the impact box lists it: the name to show, its kind, the class when the name does not already
 * say it, and the exact symbol to ask for, such as {@code TABLE sample_products}.
 */
export function symbolOption(symbol) {
  const simpleType = symbol.type ? symbol.type.slice(symbol.type.lastIndexOf('.') + 1) : ''
  const showType = simpleType && !symbol.name.toLowerCase().includes(simpleType.toLowerCase())
  return {
    name: symbol.name,
    kind: kindLabel(symbol.kind),
    type: showType ? simpleType : '',
    symbol: `${symbol.kind} ${symbol.name}`
  }
}
