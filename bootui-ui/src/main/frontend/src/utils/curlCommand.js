/**
 * Pure helpers behind the HTTP Exchanges "Copy as cURL" action.
 *
 * Everything here is framework-free and side-effect-free: the panel only wires these functions to a
 * clipboard button. Nothing in this module reads, replays, or sends anything — it re-reads the
 * already-retained `HttpExchangeDto` the panel is displaying and renders a *template* command.
 *
 * The policy is deliberately stricter than the panel's own masking/exposure state, which is treated
 * as an extra restriction and never as permission:
 *
 *   - query-parameter names survive, every value becomes a placeholder;
 *   - only a small, explicit allowlist of boring request headers is copied, and only while their
 *     values are actually exposed and unmasked;
 *   - bodies never exist in the first place, because BootUI does not capture them.
 *
 * Everything that reaches the command text is POSIX single-quoted, so shell metacharacters, quotes,
 * newlines and option-like text captured from a request cannot escape their argument.
 */

/** Placeholder substituted for every query-parameter value. */
export const QUERY_VALUE_PLACEHOLDER = 'VALUE'

/** The value the backend substitutes for masked data; never copied. */
const MASKED_VALUE = '******'

/**
 * The only request headers that may be copied, mapped to their canonical spelling so the generated
 * text is byte-identical regardless of the casing an adapter happened to record.
 *
 * Authorization, cookies, proxy credentials, API keys, forwarding headers, tracing headers and every
 * unknown or custom header are absent on purpose and stay absent under every exposure mode.
 */
export const SAFE_REQUEST_HEADERS = Object.freeze(
  // A null prototype matters: `constructor`, `__proto__` and `toString` are valid header names, and a
  // plain object literal would resolve them through Object.prototype and copy a non-allowlisted header.
  Object.assign(Object.create(null), {
    accept: 'Accept',
    'accept-language': 'Accept-Language',
    'cache-control': 'Cache-Control',
    'content-type': 'Content-Type',
    'user-agent': 'User-Agent'
  })
)

/** Human-readable list used in the action feedback. */
const SAFE_REQUEST_HEADER_LABEL = Object.values(SAFE_REQUEST_HEADERS)
  .map((name, index, all) => (index === all.length - 1 ? `and ${name}` : name))
  .join(', ')

/** RFC 9110 token characters; anything else is not a header name BootUI will copy. */
const HEADER_NAME_PATTERN = /^[A-Za-z0-9!#$%&'*+.^_`|~-]+$/

/** C0 controls and DEL. Real header values cannot contain these; smuggling attempts can. */
const CONTROL_CHARACTER_PATTERN = /[\u0000-\u001f\u007f]/

/** Anything outside printable ASCII, plus characters that are unsafe in a URL or a shell word. */
const UNSAFE_URL_CHARACTER = /[^\u0021-\u007e]|["'`<>\\^{}|#]/

const METHOD_PATTERN = /^[A-Za-z]{1,20}$/

const BODY_CARRYING_METHODS = new Set(['POST', 'PUT', 'PATCH'])

/**
 * Quotes a value as a single POSIX shell word.
 *
 * Single quotes suppress every expansion, so the only character needing care is the single quote
 * itself: the string is closed, an escaped quote is spliced in, and the string reopened.
 *
 * @param {string} value raw text
 * @returns {string} a single, fully quoted shell word
 */
export function shellQuote(value) {
  return `'${String(value ?? '')
    .split("'")
    .join(`'\\''`)}'`
}

function percentEncode(character) {
  return Array.from(new TextEncoder().encode(character))
    .map((byte) => `%${byte.toString(16).toUpperCase().padStart(2, '0')}`)
    .join('')
}

/**
 * Percent-encodes characters that must not appear literally in a copied URL. Already-encoded text is
 * left alone (`%` is safe), so recorded paths and parameter names keep their original encoding.
 *
 * @param {string} text raw URL fragment
 * @returns {string} URL-safe text
 */
export function encodeUrlText(text) {
  let encoded = ''
  for (const character of String(text ?? '')) {
    encoded += UNSAFE_URL_CHARACTER.test(character) ? percentEncode(character) : character
  }
  return encoded
}

function parseRequestUrl(exchange) {
  const raw = typeof exchange?.uri === 'string' ? exchange.uri.trim() : ''
  if (!raw || !raw.includes('://')) {
    // Without an explicit authority separator the raw path cannot be located, and guessing one would
    // risk copying a command that targets a different resource than the recorded exchange.
    return null
  }
  let parsed
  try {
    parsed = new URL(raw)
  } catch (_) {
    return null
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    return null
  }
  if (!parsed.host) {
    return null
  }
  return {url: parsed, raw}
}

/**
 * Extracts the path exactly as recorded, without WHATWG normalization.
 *
 * `URL.pathname` resolves dot segments — including percent-encoded ones — so `/a/%2e%2e/admin` would
 * silently become `/admin` and the copied command would target a different resource than the exchange
 * it came from. The raw substring keeps traversal probes, doubled slashes and encoded segments intact.
 *
 * @param {string} rawUri the recorded absolute URI
 * @returns {string} the raw path, or an empty string when the URI carries none
 */
export function rawUriPath(rawUri) {
  const raw = String(rawUri ?? '')
  const schemeEnd = raw.indexOf('://')
  if (schemeEnd < 0) {
    return ''
  }
  const authorityStart = schemeEnd + 3
  let pathStart = raw.length
  for (let index = authorityStart; index < raw.length; index++) {
    const character = raw[index]
    if (character === '/' || character === '?' || character === '#') {
      pathStart = index
      break
    }
  }
  if (pathStart >= raw.length || raw[pathStart] !== '/') {
    return ''
  }
  let pathEnd = raw.length
  for (let index = pathStart; index < raw.length; index++) {
    if (raw[index] === '?' || raw[index] === '#') {
      pathEnd = index
      break
    }
  }
  return raw.slice(pathStart, pathEnd)
}

/**
 * Rebuilds the query string with every usable name preserved and every value replaced.
 *
 * The raw string is split rather than parsed through `URLSearchParams` so repeated, empty, encoded
 * and malformed parameters survive exactly as recorded instead of being silently re-encoded. A
 * segment carrying no `=` has no name/value structure to trust — it can just as easily be a bare
 * token as a parameter name — so it is never copied, and neither is a masked name.
 *
 * @param {string|null|undefined} rawQuery recorded query string, without the leading `?`
 * @returns {{query: string, count: number, omitted: number}} placeholder query, kept and dropped counts
 */
export function placeholderQuery(rawQuery) {
  const raw = typeof rawQuery === 'string' ? rawQuery.replace(/^\?/, '') : ''
  if (!raw) {
    return {query: '', count: 0, omitted: 0}
  }
  const parameters = []
  let omitted = 0
  for (const segment of raw.split('&')) {
    if (!segment) {
      continue
    }
    const equalsIndex = segment.indexOf('=')
    const rawName = equalsIndex > 0 ? segment.slice(0, equalsIndex) : ''
    if (!rawName || rawName === MASKED_VALUE) {
      omitted++
      continue
    }
    parameters.push(`${encodeUrlText(rawName)}=${QUERY_VALUE_PLACEHOLDER}`)
  }
  return {query: parameters.join('&'), count: parameters.length, omitted}
}

/**
 * Selects the request headers that may be copied.
 *
 * A header is copied only when it is allowlisted, is not flagged as masked, carries a value that is
 * currently exposed, and contains neither a masked placeholder nor a control character. Everything
 * else is counted as omitted so the UI can explain the difference.
 *
 * @param {Array<{name: string, values: string[], masked: boolean}>|null|undefined} requestHeaders
 * @returns {{headers: Array<{name: string, value: string}>, omitted: number, omittedValues: number}}
 */
export function safeCurlHeaders(requestHeaders) {
  const headers = []
  let omitted = 0
  let omittedValues = 0
  if (!Array.isArray(requestHeaders)) {
    return {headers, omitted, omittedValues}
  }
  for (const header of requestHeaders) {
    const rawName = typeof header?.name === 'string' ? header.name.trim() : ''
    const canonical = SAFE_REQUEST_HEADERS[rawName.toLowerCase()]
    if (typeof canonical !== 'string' || !HEADER_NAME_PATTERN.test(rawName) || header?.masked === true) {
      omitted++
      continue
    }
    const values = Array.isArray(header.values) ? header.values : []
    const usable = values.filter(
      (value) => typeof value === 'string' && value !== MASKED_VALUE && !CONTROL_CHARACTER_PATTERN.test(value)
    )
    if (!usable.length) {
      omitted++
      continue
    }
    omittedValues += values.length - usable.length
    for (const value of usable) {
      headers.push({name: canonical, value})
    }
  }
  headers.sort((left, right) => left.name.localeCompare(right.name, 'en'))
  return {headers, omitted, omittedValues}
}

function resolveMethod(exchange) {
  const raw = typeof exchange?.method === 'string' ? exchange.method.trim() : ''
  if (!raw) {
    return null
  }
  return METHOD_PATTERN.test(raw) ? raw.toUpperCase() : null
}

/**
 * Builds the copyable cURL command for one recorded exchange.
 *
 * @param {object|null|undefined} exchange an `HttpExchangeDto` as served by any adapter
 * @returns {{command: string|null, unavailableReason: string|null, notes: string[]}}
 *   `command` is null when the retained metadata cannot produce an honest command, in which case
 *   `unavailableReason` explains why. `notes` always describes what the command deliberately leaves out.
 */
export function buildCurlCommand(exchange) {
  const method = resolveMethod(exchange)
  if (!method) {
    return {
      command: null,
      unavailableReason: 'This exchange has no recognizable HTTP method, so BootUI cannot build a reliable command.',
      notes: []
    }
  }
  const parsed = parseRequestUrl(exchange)
  if (!parsed) {
    return {
      command: null,
      unavailableReason:
        'This exchange has no recorded absolute http(s) request URL, so BootUI cannot build a reliable command.',
      notes: []
    }
  }
  const {url, raw} = parsed

  const rawQuery = typeof exchange?.query === 'string' && exchange.query ? exchange.query : url.search
  const {query, count: queryCount, omitted: omittedQuery} = placeholderQuery(rawQuery)
  // `url.host` deliberately excludes any userinfo, so recorded credentials can never be copied, while
  // the path comes from the raw URI so WHATWG dot-segment resolution cannot retarget the command.
  const target = `${url.protocol}//${url.host}${encodeUrlText(rawUriPath(raw))}${query ? `?${query}` : ''}`

  const {headers, omitted, omittedValues} = safeCurlHeaders(exchange?.requestHeaders)

  // `--globoff` keeps curl from treating recorded `[` / `]` as a range or list and firing extra requests.
  const methodArgument = method === 'GET' ? '' : method === 'HEAD' ? ' -I' : ` -X ${shellQuote(method)}`
  // One shell line per argument group: `curl [method] URL` first, then one `-H` line per header.
  const lines = [`curl --globoff${methodArgument} ${shellQuote(target)}`]
  for (const header of headers) {
    lines.push(`  -H ${shellQuote(`${header.name}: ${header.value}`)}`)
  }

  const notes = ['BootUI never captures request bodies, so the command sends no body, form data, or file.']
  if (queryCount) {
    notes.push(
      `${queryCount} query parameter ${queryCount === 1 ? 'name is' : 'names are'} kept, but every value is replaced with the ${QUERY_VALUE_PLACEHOLDER} placeholder.`
    )
  } else {
    notes.push(
      'This exchange has no query parameter in the retained metadata. Parameters hidden by masking or by the value exposure setting never reach the command either.'
    )
  }
  if (omittedQuery) {
    notes.push(
      `${omittedQuery} query ${omittedQuery === 1 ? 'parameter was' : 'parameters were'} omitted because BootUI could not copy ${omittedQuery === 1 ? 'its name' : 'their names'} safely.`
    )
  }
  if (omitted) {
    notes.push(
      `${omitted} request ${omitted === 1 ? 'header was' : 'headers were'} omitted. Only ${SAFE_REQUEST_HEADER_LABEL} are copied, and never when their values are masked or hidden.`
    )
  }
  if (omittedValues) {
    notes.push(
      `${omittedValues} header ${omittedValues === 1 ? 'value was' : 'values were'} dropped because ${omittedValues === 1 ? 'it was' : 'they were'} masked or contained control characters.`
    )
  }
  if (BODY_CARRYING_METHODS.has(method)) {
    notes.push(
      `A ${method} request may have carried a body BootUI did not record: add your own --data before running the command.`
    )
  }

  return {command: lines.join(' \\\n'), unavailableReason: null, notes}
}
