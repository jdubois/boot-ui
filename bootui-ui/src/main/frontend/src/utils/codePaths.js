import {shortName} from './format.js'

/** A method key's short label, {@code SimpleClass.method}, from {@code binary.Class#method(descriptor)}. */
export function methodLabel(key) {
  if (!key) return '—'
  const hash = key.indexOf('#')
  if (hash < 0) return key
  const paren = key.indexOf('(', hash)
  return `${shortName(key.slice(0, hash))}.${key.slice(hash + 1, paren < 0 ? key.length : paren)}`
}

/** A route's HTTP method and path, {@code GET /api/quote}; a route without a method keeps it all as its path. */
export function splitRoute(route) {
  const text = route ?? ''
  const space = text.indexOf(' ')
  return space > 0 ? {verb: text.slice(0, space), path: text.slice(space + 1)} : {verb: '', path: text}
}

/**
 * A key per tree node that survives a refresh: the node's chain of callers, kinds, methods, and phases. Node ids are
 * positions in one read, so they can shift when a route's tree grows; these keys do not.
 */
export function nodeKeys(nodes) {
  const keys = new Map()
  for (const node of nodes ?? []) {
    const parent = node.parent == null ? '' : (keys.get(node.parent) ?? `#${node.parent}`)
    keys.set(node.id, `${parent}/${node.kind}:${node.method ?? ''}:${node.phase ?? ''}${node.async ? ':async' : ''}`)
  }
  return keys
}

/**
 * The hot path: from the request, the child that took the most time per request at each level, down to a leaf.
 * Executor work is shown apart, beside the request rather than inside it, so the path never enters it.
 */
export function hotPath(nodes) {
  const children = new Map()
  for (const node of nodes ?? []) {
    if (node.parent == null || node.async || node.kind === 'ASYNC') continue
    if (!children.has(node.parent)) children.set(node.parent, [])
    children.get(node.parent).push(node)
  }
  const path = new Set()
  let current = (nodes ?? []).find((node) => node.parent == null && node.kind === 'REQUEST')
  while (current) {
    path.add(current.id)
    const next = children.get(current.id)
    current = next?.length ? next.reduce((top, node) => (node.totalMillis > top.totalMillis ? node : top)) : null
  }
  return path
}
