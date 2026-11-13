import {QUERY_VALUE_PLACEHOLDER, placeholderQuery, rawUriPath, safeCurlHeaders} from './curlCommand.js'

export const PROBE_METHODS = Object.freeze(['GET', 'HEAD', 'POST', 'PUT', 'DELETE', 'PATCH', 'OPTIONS'])

const PROBE_METHOD_SET = new Set(PROBE_METHODS)

export const BODY_CARRYING_METHODS = new Set(['POST', 'PUT', 'PATCH'])

/**
 * Resolves a mapping or exchange method string to a concrete HTTP Probe method, defaulting to GET
 * when the route is declared as ANY, blank, or otherwise non-specific.
 *
 * @param {string|null|undefined} rawMethod
 * @returns {string}
 */
export function resolveProbeMethod(rawMethod) {
  const trimmed = typeof rawMethod === 'string' ? rawMethod.trim() : ''
  if (!trimmed || trimmed.toUpperCase() === 'ANY') {
    return 'GET'
  }
  for (const token of trimmed.split(/[\s,|/]+/)) {
    const candidate = token.trim().toUpperCase()
    if (PROBE_METHOD_SET.has(candidate)) {
      return candidate
    }
  }
  return 'GET'
}

/**
 * Normalizes an application base path (such as server.servlet.context-path, spring.mvc.servlet.path,
 * spring.webflux.base-path, or quarkus.http.root-path + quarkus.rest.path) to a leading-slash prefix
 * without a trailing slash, or '' for the root mount.
 *
 * @param {string|null|undefined} rawBasePath
 * @returns {string}
 */
export function normalizeBasePath(rawBasePath) {
  let trimmed = typeof rawBasePath === 'string' ? rawBasePath.trim() : ''
  while (trimmed.endsWith('*')) {
    trimmed = trimmed.slice(0, -1)
  }
  trimmed = trimmed.trim()
  if (!trimmed) {
    return ''
  }
  const withLeadingSlash = trimmed.startsWith('/') ? trimmed : `/${trimmed}`
  const collapsed = withLeadingSlash.replace(/\/{2,}/g, '/').replace(/\/+$/, '')
  return !collapsed || collapsed === '/' ? '' : collapsed
}

/**
 * Composes the application's effective basePath with a declared MappingDto pattern.
 * Declared mapping patterns are always relative to the application's basePath, whereas recorded
 * HTTP Exchanges paths already carry the mounted prefix and do not call this helper.
 *
 * @param {string|null|undefined} basePath
 * @param {string|null|undefined} pattern
 * @returns {string}
 */
export function composeProbePath(basePath, pattern) {
  const prefix = normalizeBasePath(basePath)
  const rawPattern = typeof pattern === 'string' ? pattern.trim() : ''
  const normalizedPattern = !rawPattern ? '/' : rawPattern.startsWith('/') ? rawPattern : `/${rawPattern}`
  if (!prefix) {
    return normalizedPattern
  }
  return normalizedPattern === '/' ? prefix : `${prefix}${normalizedPattern}`
}

/**
 * Extracts a concrete media type from a comma-separated produces/consumes string, ignoring negated
 * (!text/plain) and wildcard media-type entries and preferring application/json when JSON is named.
 *
 * @param {string|null|undefined} rawMediaTypes
 * @returns {string}
 */
export function preferredMediaType(rawMediaTypes) {
  if (typeof rawMediaTypes !== 'string' || !rawMediaTypes.trim()) {
    return ''
  }
  const candidates = []
  for (const part of rawMediaTypes.split(',')) {
    const token = part.split(';')[0].trim()
    if (!token || token.startsWith('!') || token === '*' || token === '*/*') {
      continue
    }
    if (token.endsWith('/*')) {
      continue
    }
    candidates.push(token)
  }
  if (!candidates.length) {
    return ''
  }
  const jsonCandidate = candidates.find((item) => item.toLowerCase().includes('json'))
  if (jsonCandidate) {
    return jsonCandidate.toLowerCase() === 'application/json' || jsonCandidate.includes('*')
      ? 'application/json'
      : jsonCandidate
  }
  return candidates[0]
}

export function preferredAccept(producesRaw) {
  return preferredMediaType(producesRaw)
}

export function preferredContentType(consumesRaw, method) {
  const resolvedMethod = resolveProbeMethod(method)
  if (!BODY_CARRYING_METHODS.has(resolvedMethod)) {
    return ''
  }
  return preferredMediaType(consumesRaw)
}

/**
 * Extracts `{param}` (including `{id:[0-9]+}` and nested quantifier `{id:[0-9]{1,6}}` regex forms)
 * path placeholders from the path portion of a request path.
 *
 * @param {string|null|undefined} pathValue
 * @returns {string[]}
 */
export function extractPathPlaceholders(pathValue) {
  const raw = typeof pathValue === 'string' ? pathValue : ''
  const seen = new Set()
  let depth = 0
  let start = -1
  for (let i = 0; i < raw.length; i++) {
    const ch = raw[i]
    if (depth === 0 && (ch === '?' || ch === '#')) {
      break
    }
    if (ch === '{') {
      if (depth === 0) {
        start = i
      }
      depth++
    } else if (ch === '}' && depth > 0) {
      depth--
      if (depth === 0 && start >= 0) {
        const token = raw.slice(start, i + 1)
        if (token.length > 2) {
          seen.add(token)
        }
        start = -1
      }
    }
  }
  return [...seen]
}

/**
 * Checks whether the query string of `pathValue` carries `=VALUE` placeholders from HTTP Exchanges.
 *
 * @param {string|null|undefined} pathValue
 * @returns {boolean}
 */
export function hasQueryValuePlaceholders(pathValue) {
  const raw = typeof pathValue === 'string' ? pathValue : ''
  const queryStart = raw.indexOf('?')
  if (queryStart < 0) {
    return false
  }
  const query = raw.slice(queryStart + 1).split('#')[0]
  return query.split('&').some((segment) => {
    const eq = segment.indexOf('=')
    return eq > 0 && segment.slice(eq + 1) === QUERY_VALUE_PLACEHOLDER
  })
}

/**
 * Whether the `http-probe` panel is enabled, available, and writable according to the `/bootui/api/panels`
 * manifest. Defaults to true when no manifest array is provided (standalone component tests).
 *
 * @param {object|null|undefined} panelsReport
 * @returns {boolean}
 */
export function isHttpProbeWritable(panelsReport) {
  const list = panelsReport?.panels
  if (!Array.isArray(list)) {
    return true
  }
  const panel = list.find((item) => item?.id === 'http-probe')
  if (!panel) {
    return false
  }
  return panel.enabled !== false && panel.available !== false && panel.readOnly !== true
}

/**
 * Whether the `mappings` panel is enabled and available in the `/bootui/api/panels` manifest.
 *
 * @param {object|null|undefined} panelsReport
 * @returns {boolean}
 */
export function isMappingsAvailable(panelsReport) {
  const list = panelsReport?.panels
  if (!Array.isArray(list)) {
    return false
  }
  const panel = list.find((item) => item?.id === 'mappings')
  if (!panel) {
    return false
  }
  return panel.enabled !== false && panel.available !== false
}

/**
 * Builds the passive `#/http-probe` router target for a declared `MappingDto` row.
 *
 * @param {object|null|undefined} mapping
 * @param {string|null|undefined} basePath
 * @returns {{path: string, query: Record<string, string>}|null}
 */
export function buildMappingProbeLink(mapping, basePath = '') {
  const rawPattern = typeof mapping?.pattern === 'string' ? mapping.pattern.trim() : ''
  if (!rawPattern || !rawPattern.startsWith('/')) {
    return null
  }
  const method = resolveProbeMethod(mapping?.method)
  const path = composeProbePath(basePath, rawPattern)
  const accept = preferredAccept(mapping?.produces)
  const contentType = preferredContentType(mapping?.consumes, method)
  const query = {
    method,
    path,
    ...(accept ? {accept} : {}),
    ...(contentType ? {contentType} : {})
  }
  return {path: '/http-probe', query}
}

/**
 * Builds the passive `#/http-probe` router target for a recorded `HttpExchangeDto` row.
 * Recorded exchange paths already include the application's context-path / root-path, so no basePath
 * is prepended here. Query values are replaced with `VALUE` placeholders and only safe allowlisted
 * headers (`Accept`, `Accept-Language`, `Content-Type`) are carried over.
 *
 * @param {object|null|undefined} exchange
 * @returns {{path: string, query: Record<string, string>}|null}
 */
export function buildExchangeProbeLink(exchange) {
  const rawMethod = typeof exchange?.method === 'string' ? exchange.method.trim().toUpperCase() : ''
  if (!rawMethod || !PROBE_METHOD_SET.has(rawMethod)) {
    return null
  }
  let recordedPath = ''
  let urlSearch = ''
  if (typeof exchange?.uri === 'string' && exchange.uri.includes('://')) {
    try {
      const parsed = new URL(exchange.uri.trim())
      if ((parsed.protocol === 'http:' || parsed.protocol === 'https:') && parsed.host) {
        recordedPath = rawUriPath(exchange.uri.trim())
        urlSearch = parsed.search
      }
    } catch {
      recordedPath = ''
    }
  }
  if (!recordedPath && typeof exchange?.path === 'string' && exchange.path.trim().startsWith('/')) {
    recordedPath = exchange.path.trim()
  }
  if (!recordedPath) {
    return null
  }

  const rawQuery = typeof exchange?.query === 'string' && exchange.query ? exchange.query : urlSearch
  const {query: sanitizedQuery, omitted: omittedQuery} = placeholderQuery(rawQuery)
  const targetPath = `${recordedPath}${sanitizedQuery ? `?${sanitizedQuery}` : ''}`

  const {headers} = safeCurlHeaders(exchange?.requestHeaders)
  let accept = ''
  let contentType = ''
  for (const header of headers) {
    if (header.name === 'Accept' && !accept) {
      accept = header.value
    } else if (header.name === 'Content-Type' && !contentType) {
      contentType = header.value
    }
  }

  const query = {
    method: rawMethod,
    path: targetPath,
    ...(accept ? {accept} : {}),
    ...(contentType ? {contentType} : {}),
    source: 'http-exchanges',
    ...(omittedQuery > 0 ? {omittedQuery: String(omittedQuery)} : {})
  }
  return {path: '/http-probe', query}
}
