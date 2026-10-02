// One Markdown export behind "Copy profile" and "Copy for AI" in Live Activity and Exceptions.
//
// Every function here is pure and works only on DTOs the browser already holds or loads through the
// existing read endpoints, so the export never contains anything the panels do not show, and identical
// DTOs produce identical text on every adapter: no locale, time zone, or clock is consulted. Captured
// strings are escaped or fenced so that Markdown in a message, path, or SQL statement cannot break the
// document structure. Preparing an export may read; copying it sends nothing.

import {childTierLabel, profileSections, restCallSummary, tierLabel, unavailableTiersText} from './requestProfile.js'

/** The value the backend substitutes for masked data. */
export const MASKED_VALUE = '******'

/** At most this many exception groups are loaded for one profile export. */
export const MAX_EXCEPTION_DETAILS = 5

/** At most this many frames are exported per throwable in a stack trace. */
export const MAX_FRAMES = 50

/** At most this many recent occurrences are exported per exception group. */
export const MAX_OCCURRENCES = 10

const INTRO =
  'Exported from BootUI, a local developer console. Values appear exactly as the panel showed them, masked values included.'

const APPLICATION_FRAME_MARKER = '→ '
const OTHER_FRAME_MARKER = '  '

const STATUS_LABELS = {OPEN: 'Open', ACKNOWLEDGED: 'Acknowledged', RESOLVED: 'Resolved'}

// ---------------------------------------------------------------------------------------------------
// Markdown primitives
// ---------------------------------------------------------------------------------------------------

function oneLine(value) {
  return String(value ?? '').replace(/\r\n?|\n/g, ' ')
}

function longestRun(text, character) {
  let longest = 0
  let current = 0
  for (const c of text) {
    current = c === character ? current + 1 : 0
    if (current > longest) longest = current
  }
  return longest
}

/**
 * Escapes captured text for use inside a line of Markdown prose. Newlines collapse to spaces, inline
 * syntax is backslash-escaped, and a leading block marker (list, heading, quote, ordered list) is
 * neutralised so the text can never open a new block.
 *
 * @param {unknown} value
 * @returns {string}
 */
export function escapeMarkdown(value) {
  const text = oneLine(value)
    .trimStart()
    .replace(/[\\`*_[\]<>|~&]/g, '\\$&')
  return text.replace(/^([-+#>=])/, '\\$1').replace(/^(\d+)([.)])/, '$1\\$2')
}

/**
 * Renders captured text as an inline code span whose delimiter is longer than any backtick run inside
 * it, so the text cannot close the span early.
 *
 * @param {unknown} value
 * @returns {string}
 */
export function inlineCode(value) {
  const text = oneLine(value)
  if (!text) return ''
  const fence = '`'.repeat(longestRun(text, '`') + 1)
  const pad = text.startsWith('`') || text.endsWith('`') || (text.startsWith(' ') && text.endsWith(' ')) ? ' ' : ''
  return `${fence}${pad}${text}${pad}${fence}`
}

/**
 * Renders captured text as a fenced code block whose fence is longer than any backtick run inside it.
 *
 * @param {unknown} value
 * @param {string} [language]
 * @returns {string}
 */
export function codeBlock(value, language = '') {
  const text = String(value ?? '')
    .replace(/\r\n?/g, '\n')
    .replace(/\n+$/, '')
  const fence = '`'.repeat(Math.max(3, longestRun(text, '`') + 1))
  return `${fence}${language}\n${text}\n${fence}`
}

function heading(level, text) {
  return `${'#'.repeat(Math.min(6, level))} ${text}`
}

function bullet(label, value) {
  return `- **${label}:** ${value}`
}

// ---------------------------------------------------------------------------------------------------
// Deterministic formatting
// ---------------------------------------------------------------------------------------------------

function formatNumber(value) {
  const n = Number(value)
  if (!Number.isFinite(n)) return ''
  return Number.isInteger(n) ? String(n) : String(Math.round(n * 10) / 10)
}

/**
 * @param {unknown} ms
 * @returns {string}
 */
export function formatDuration(ms) {
  if (ms == null || ms === '') return ''
  const n = Number(ms)
  if (!Number.isFinite(n)) return ''
  if (n < 1) return '<1 ms'
  if (n < 1000) return `${formatNumber(n)} ms`
  return `${(n / 1000).toFixed(2)} s`
}

/**
 * @param {unknown} epochMillis
 * @returns {string}
 */
export function formatTimestamp(epochMillis) {
  if (epochMillis == null || epochMillis === '') return ''
  const date = new Date(typeof epochMillis === 'number' ? epochMillis : String(epochMillis))
  return Number.isNaN(date.getTime()) ? '' : date.toISOString()
}

function plural(count, singular, pluralForm = `${singular}s`) {
  return `${count} ${count === 1 ? singular : pluralForm}`
}

// ---------------------------------------------------------------------------------------------------
// Document assembly
// ---------------------------------------------------------------------------------------------------

function newDocument() {
  return {blocks: [], omissions: []}
}

function push(doc, ...blocks) {
  for (const block of blocks) {
    if (block != null && block !== '') doc.blocks.push(block)
  }
}

function omit(doc, text) {
  if (text && !doc.omissions.includes(text)) doc.omissions.push(text)
}

function countMasked(text) {
  return text.split(MASKED_VALUE).length - 1
}

// The body is assembled first so masked values can be counted in exactly the text being exported; no
// BootUI-generated sentence contains the mask token, so every occurrence is a captured value.
function finish(doc) {
  const body = doc.blocks.join('\n\n')
  const omissions = [...doc.omissions]
  const masked = countMasked(body)
  if (masked > 0) {
    omissions.unshift(
      `${plural(masked, 'value')} ${masked === 1 ? 'was' : 'were'} masked by BootUI and ${masked === 1 ? 'appears' : 'appear'} as ${MASKED_VALUE}.`
    )
  }
  const blocks = [body]
  if (omissions.length) {
    const lines = omissions.map((text) =>
      text.endsWith(` ${MASKED_VALUE}.`)
        ? `- ${escapeMarkdown(text.slice(0, -MASKED_VALUE.length - 1))}${inlineCode(MASKED_VALUE)}.`
        : `- ${escapeMarkdown(text)}`
    )
    blocks.push(heading(2, 'Omitted from this export'), lines.join('\n'))
  }
  return {markdown: `${blocks.join('\n\n')}\n`, omissions}
}

// ---------------------------------------------------------------------------------------------------
// Request profile sections
// ---------------------------------------------------------------------------------------------------

/**
 * @param {any} timing
 * @returns {string}
 */
export function timingText(timing) {
  if (!timing) return ''
  let text = `${plural(timing.sqlCount ?? 0, 'SQL statement')}, ${formatDuration(timing.sqlMs ?? 0)} in SQL`
  if (timing.sqlPercent != null) text += ` (${formatNumber(timing.sqlPercent)}% of the request)`
  if (timing.restCallCount) {
    text += `; ${plural(timing.restCallCount, 'REST client call')}, ${formatDuration(timing.restCallMs)} outbound`
  }
  return text
}

function sectionTitle(name, section) {
  const label = tierLabel(section?.tier)
  return label ? `${name} (${label})` : name
}

function sqlTitle(profile, section) {
  if (!section) return `SQL (${profile.sqlCorrelationApproximate ? 'approximate, time window' : 'exact'})`
  if (!section.available) return 'SQL (unavailable)'
  if (!section.tier) return 'SQL'
  return `SQL (${section.tier === 'TIME_WINDOW' ? 'approximate' : 'exact'}, ${tierLabel(section.tier)})`
}

function unavailable(doc, name, section) {
  const reason = section.unavailableReason || 'This source is not available.'
  push(doc, `Unavailable: ${escapeMarkdown(reason)}`)
  omit(doc, `${name} unavailable: ${reason}`)
}

function truncated(doc, name, section) {
  if (!section?.truncationText) return
  push(doc, escapeMarkdown(section.truncationText))
  omit(doc, `${name}: ${section.truncationText}`)
}

function requestSection(doc, profile, level) {
  const request = profile.request ?? {}
  const lines = []
  lines.push(bullet('Request', inlineCode(`${request.method ?? ''} ${request.path ?? ''}`.trim())))
  if (request.id) lines.push(bullet('Activity entry id', inlineCode(request.id)))
  if (request.status != null) lines.push(bullet('Status', escapeMarkdown(request.status)))
  if (request.durationMs != null) lines.push(bullet('Duration', formatDuration(request.durationMs)))
  if (request.principal) lines.push(bullet('Principal', inlineCode(request.principal)))
  if (request.traceId) lines.push(bullet('Trace id', inlineCode(request.traceId)))
  if (profile.timing) lines.push(bullet('Timing', timingText(profile.timing)))
  if (profile.approximate) {
    lines.push(bullet('Correlation', 'approximate; some signals were matched by time window only'))
  }
  push(doc, heading(level, 'Request'), lines.join('\n'))
}

function sqlSection(doc, profile, sections, level) {
  const section = sections.SQL
  push(doc, heading(level, sqlTitle(profile, section)))
  if (section && !section.available) {
    unavailable(doc, 'SQL', section)
    return
  }
  const groups = profile.sqlGroups ?? []
  if (!groups.length) {
    push(doc, 'No SQL was correlated to this request.')
  }
  groups.forEach((group, index) => {
    const title = group.potentialNPlusOne
      ? `Statement group ${index + 1}: N+1 suspected`
      : `Statement group ${index + 1}`
    const facts = [bullet('Executions', formatNumber(group.executions))]
    if (group.totalDurationMillis != null) {
      const max = group.maxDurationMillis != null ? ` (slowest ${formatDuration(group.maxDurationMillis)})` : ''
      facts.push(bullet('Total time', `${formatDuration(group.totalDurationMillis)}${max}`))
    }
    if (group.category) facts.push(bullet('Category', escapeMarkdown(group.category)))
    push(doc, heading(level + 1, title), facts.join('\n'), codeBlock(group.sql, 'sql'))
    if (group.callSites?.length) {
      push(doc, 'Call sites:', group.callSites.map((site) => `- ${inlineCode(site)}`).join('\n'))
    }
  })
  truncated(doc, 'SQL', section)
}

function frameText(frame) {
  const file = frame.fileName
  const position = file ? (frame.lineNumber != null ? `${file}:${frame.lineNumber}` : file) : 'Unknown Source'
  return `at ${frame.declaringClass}.${frame.methodName}(${position})`
}

function frameLines(frames, indent, doc, owner) {
  const shown = (frames ?? []).slice(0, MAX_FRAMES)
  const lines = shown.map(
    (frame) => `${frame.applicationFrame ? APPLICATION_FRAME_MARKER : OTHER_FRAME_MARKER}${indent}${frameText(frame)}`
  )
  const hidden = (frames?.length ?? 0) - shown.length
  if (hidden > 0) {
    lines.push(`${OTHER_FRAME_MARKER}${indent}... ${hidden} more frames not exported`)
    omit(doc, `${owner}: ${plural(hidden, 'stack frame')} beyond the first ${MAX_FRAMES} of a throwable.`)
  }
  return lines
}

function stackTraceSection(doc, detail, level) {
  const group = detail.group ?? {}
  const owner = `Stack trace of ${group.exceptionClassName ?? 'the exception'}`
  const lines = [group.exceptionClassName ?? '']
  lines.push(...frameLines(detail.frames, '', doc, owner))
  for (const cause of detail.causes ?? []) {
    const message = cause.message ? `: ${cause.message}` : ''
    lines.push(`${OTHER_FRAME_MARKER}Caused by: ${cause.exceptionClassName}${message}`)
    lines.push(...frameLines(cause.frames, '    ', doc, owner))
    if (cause.commonFrames > 0) lines.push(`${OTHER_FRAME_MARKER}    ... ${cause.commonFrames} more`)
  }
  push(
    doc,
    heading(level, 'Stack trace'),
    `Lines starting with ${APPLICATION_FRAME_MARKER.trim()} are application frames.`,
    codeBlock(lines.join('\n'), 'text')
  )
}

function requestLabel(item) {
  const path = item.lastRequestPath || item.requestPath
  if (!path) return ''
  const method = item.lastRequestMethod || item.requestMethod
  return method ? `${method} ${path}` : path
}

function occurrenceLine(occurrence) {
  const parts = [formatTimestamp(occurrence.timestamp) || 'unknown time']
  if (occurrence.source) parts.push(escapeMarkdown(occurrence.source))
  const request = requestLabel(occurrence)
  if (request) parts.push(`request ${inlineCode(request)}`)
  if (occurrence.thread) parts.push(`thread ${inlineCode(occurrence.thread)}`)
  if (occurrence.handler) parts.push(`handler ${inlineCode(occurrence.handler)}`)
  if (occurrence.traceId) parts.push(`trace id ${inlineCode(occurrence.traceId)}`)
  return `- ${parts.join(' · ')}`
}

function occurrencesSection(doc, detail, level) {
  const occurrences = detail.occurrences ?? []
  if (!occurrences.length) return
  const shown = occurrences.slice(0, MAX_OCCURRENCES)
  const title =
    shown.length < occurrences.length
      ? `Recent occurrences (${shown.length} of ${occurrences.length} retained)`
      : `Recent occurrences (${occurrences.length})`
  push(doc, heading(level, title), shown.map(occurrenceLine).join('\n'))
  if (shown.length < occurrences.length) {
    omit(
      doc,
      `Recent occurrences of ${detail.group?.exceptionClassName ?? 'the exception'}: only the ${MAX_OCCURRENCES} newest of ${occurrences.length} retained are exported.`
    )
  }
}

function messageBlock(doc, message, className, level) {
  if (message) {
    push(doc, heading(level, 'Message'), codeBlock(message, 'text'))
  } else {
    omit(
      doc,
      `No message for ${className ?? 'an exception'}: none was captured, or bootui.expose-values is METADATA_ONLY.`
    )
  }
}

function exceptionsSection(doc, profile, sections, exceptionDetails, level) {
  const section = sections.EXCEPTION
  const exceptions = profile.exceptions ?? []
  if (!exceptions.length && !(section && !section.available)) return
  push(doc, heading(level, sectionTitle('Exceptions', section)))
  if (section && !section.available) {
    unavailable(doc, 'Exceptions', section)
    return
  }
  const rendered = new Map()
  exceptions.forEach((exception, index) => {
    const number = index + 1
    push(doc, heading(level + 1, `Exception ${number}: ${inlineCode(exception.exceptionClassName)}`))
    const facts = []
    const time = formatTimestamp(exception.timestamp)
    if (time) facts.push(bullet('Time', time))
    if (exception.location) facts.push(bullet('Location', inlineCode(exception.location)))
    if (exception.thread) facts.push(bullet('Thread', inlineCode(exception.thread)))
    if (exception.handler) facts.push(bullet('Handler', inlineCode(exception.handler)))
    if (exception.source) facts.push(bullet('Source', escapeMarkdown(exception.source)))
    if (exception.exceptionGroupId) facts.push(bullet('Exception group id', inlineCode(exception.exceptionGroupId)))
    const tier = childTierLabel(section, index)
    if (tier) facts.push(bullet('Correlation', tier))
    push(doc, facts.join('\n'))
    messageBlock(doc, exception.message, exception.exceptionClassName, level + 2)

    if (!exceptionDetails) return
    const groupId = exception.exceptionGroupId
    if (!groupId) {
      omit(doc, 'Stack traces and occurrences: this server does not report exception group ids.')
      return
    }
    if (rendered.has(groupId)) {
      push(doc, `Stack trace and occurrences: see exception ${rendered.get(groupId)}, the same exception group.`)
      return
    }
    rendered.set(groupId, number)
    const loaded = exceptionDetails[groupId]
    if (loaded?.detail) {
      stackTraceSection(doc, loaded.detail, level + 2)
      occurrencesSection(doc, loaded.detail, level + 2)
    } else {
      omit(doc, `Stack trace and occurrences of ${exception.exceptionClassName}: ${loaded?.error ?? 'not loaded.'}`)
    }
  })
  truncated(doc, 'Exceptions', section)
}

function securitySection(doc, profile, sections, level) {
  const section = sections.SECURITY
  const events = profile.security ?? []
  if (!events.length && !(section && !section.available)) return
  push(doc, heading(level, sectionTitle('Security events', section)))
  if (section && !section.available) {
    unavailable(doc, 'Security events', section)
    return
  }
  const lines = events.map((event, index) => {
    const parts = [inlineCode(event.type)]
    if (event.principal) parts.push(`principal ${inlineCode(event.principal)}`)
    if (event.threadMatched) parts.push('exact serving-thread match')
    else if (event.principalMatched) parts.push('principal matches the request')
    const tier = childTierLabel(section, index)
    if (tier) parts.push(tier)
    return `- ${parts.join(' · ')}`
  })
  push(doc, lines.join('\n'))
  truncated(doc, 'Security events', section)
}

function restCallsSection(doc, profile, sections, level) {
  const section = sections.REST_CLIENT
  if (!section) return
  push(doc, heading(level, sectionTitle('REST client calls', section)))
  if (!section.available) {
    unavailable(doc, 'REST client calls', section)
    return
  }
  const calls = profile.restCalls ?? []
  if (!calls.length) {
    push(doc, 'No REST client calls were correlated to this request.')
    return
  }
  const lines = []
  calls.forEach((call, index) => {
    const parts = [inlineCode(restCallSummary(call)), formatDuration(call.durationMillis)].filter(Boolean)
    const tier = childTierLabel(section, index)
    if (tier) parts.push(tier)
    lines.push(`- ${parts.join(' · ')}`)
    if (!call.success && call.errorMessage) lines.push(`  - Error: ${escapeMarkdown(call.errorMessage)}`)
    if (call.callSite) lines.push(`  - Call site: ${inlineCode(call.callSite)}`)
  })
  push(doc, lines.join('\n'))
  truncated(doc, 'REST client calls', section)
}

function cacheSection(doc, profile, sections, level) {
  const section = sections.CACHE
  if (!section) return
  push(doc, heading(level, sectionTitle('Cache accesses', section)))
  if (!section.available) {
    unavailable(doc, 'Cache accesses', section)
    return
  }
  const accesses = profile.cacheAccesses ?? []
  if (!accesses.length) {
    push(doc, 'No cache accesses were correlated to this request.')
    return
  }
  const lines = accesses.map((access, index) => {
    const parts = [inlineCode(`${access.operation ?? ''} ${access.cacheName ?? ''}`.trim())]
    if (access.keyHash) parts.push(`key hash ${inlineCode(access.keyHash)}`)
    const tier = childTierLabel(section, index)
    if (tier) parts.push(tier)
    return `- ${parts.join(' · ')}`
  })
  push(doc, lines.join('\n'))
  truncated(doc, 'Cache accesses', section)
}

function traceSection(doc, profile, level) {
  const spans = profile.trace?.spans ?? []
  if (!spans.length) return
  push(doc, heading(level, 'Trace spans'), spans.map((span) => `- ${inlineCode(span.name)}`).join('\n'))
}

function notesSection(doc, profile, level) {
  const notes = [...(profile.notes ?? [])]
  const tiers = unavailableTiersText(profile)
  if (tiers) notes.push(tiers)
  if (!notes.length) return
  push(doc, heading(level, 'Notes'), notes.map((note) => `- ${escapeMarkdown(note)}`).join('\n'))
}

function profileBody(doc, profile, exceptionDetails, level) {
  const sections = profileSections(profile)
  requestSection(doc, profile, level)
  sqlSection(doc, profile, sections, level)
  exceptionsSection(doc, profile, sections, exceptionDetails, level)
  securitySection(doc, profile, sections, level)
  restCallsSection(doc, profile, sections, level)
  cacheSection(doc, profile, sections, level)
  traceSection(doc, profile, level)
  notesSection(doc, profile, level)
}

/**
 * Renders a request profile as Markdown.
 *
 * Without `exceptionDetails` this is the "Copy profile" document. With them — the result of
 * {@link loadProfileExceptionDetails} — it is the "Copy for AI" document, which adds each correlated
 * exception's stack trace and recent occurrences.
 *
 * @param {any} profile a `RequestProfileDto`
 * @param {{exceptionDetails?: Record<string, {detail?: any, error?: string}> | null, omissions?: string[]}} [options]
 * @returns {{markdown: string, omissions: string[]}}
 */
export function profileMarkdown(profile, {exceptionDetails = null, omissions = []} = {}) {
  const doc = newDocument()
  const request = profile?.request
  const title = request ? `${request.method ?? ''} ${request.path ?? ''}`.trim() : ''
  push(doc, heading(1, title ? `BootUI request profile: ${inlineCode(title)}` : 'BootUI request profile'), INTRO)
  if (!profile?.available) {
    push(doc, `The profile is unavailable: ${escapeMarkdown(profile?.unavailableReason || 'no reason was given.')}`)
    return finish(doc)
  }
  profileBody(doc, profile, exceptionDetails, 2)
  for (const text of omissions) omit(doc, text)
  return finish(doc)
}

/**
 * Renders an exception group's detail as the "Copy for AI" Markdown document, followed by the SQL and
 * timing of the request its latest occurrence belongs to, when one could be profiled.
 *
 * @param {any} detail an `ExceptionDetailDto`
 * @param {{correlated?: {requestId: string, profile: any} | null, correlationUnavailableReason?: string | null}} [options]
 * @returns {{markdown: string, omissions: string[]}}
 */
export function exceptionMarkdown(detail, {correlated = null, correlationUnavailableReason = null} = {}) {
  const doc = newDocument()
  const group = detail?.group ?? {}
  push(doc, heading(1, `BootUI exception: ${inlineCode(group.exceptionClassName)}`), INTRO)

  const facts = [bullet('Exception', inlineCode(group.exceptionClassName))]
  if (group.id) facts.push(bullet('Exception group id', inlineCode(group.id)))
  if (group.status) {
    const reopened = group.regressionCount > 0 ? `, reopened ${plural(group.regressionCount, 'time')}` : ''
    facts.push(bullet('Status', `${STATUS_LABELS[group.status] ?? escapeMarkdown(group.status)}${reopened}`))
  }
  if (group.count != null) {
    const seen = [
      formatTimestamp(group.firstSeen) && `first seen ${formatTimestamp(group.firstSeen)}`,
      formatTimestamp(group.lastSeen) && `last seen ${formatTimestamp(group.lastSeen)}`
    ].filter(Boolean)
    facts.push(bullet('Occurrences', [formatNumber(group.count), ...seen].join(', ')))
  }
  facts.push(bullet('Origin', group.applicationException ? 'application code' : 'framework or library code'))
  if (group.location) facts.push(bullet('Location', inlineCode(group.location)))
  const lastRequest = requestLabel(group)
  if (lastRequest) facts.push(bullet('Last request', inlineCode(lastRequest)))
  if (group.lastHandler) facts.push(bullet('Last handler', inlineCode(group.lastHandler)))
  if (group.lastSource) facts.push(bullet('Last source', escapeMarkdown(group.lastSource)))
  if (group.errorContract) {
    const contract = group.errorContract
    const status = contract.status ? ` → ${escapeMarkdown(contract.status)}` : ''
    facts.push(
      bullet('Declared error contract', `${inlineCode(`${contract.componentSimpleName}#${contract.method}`)}${status}`)
    )
  }
  push(doc, heading(2, 'Summary'), facts.join('\n'))

  messageBlock(doc, group.message, group.exceptionClassName, 2)
  stackTraceSection(doc, detail ?? {}, 2)
  occurrencesSection(doc, detail ?? {}, 2)

  if (correlated?.profile?.available) {
    const profile = correlated.profile
    const request = profile.request ?? {}
    const status = request.status != null ? ` → ${request.status}` : ''
    push(
      doc,
      heading(2, `Correlated request: ${inlineCode(`${request.method ?? ''} ${request.path ?? ''}`.trim())}${status}`),
      'The request the latest occurrence belongs to, from its Live Activity profile.'
    )
    const sections = profileSections(profile)
    requestSection(doc, {...profile, request: {...request, id: request.id ?? correlated.requestId}}, 3)
    sqlSection(doc, profile, sections, 3)
    notesSection(doc, profile, 3)
  } else {
    omit(doc, `Correlated SQL: ${correlationUnavailableReason || 'no correlated request was found.'}`)
  }
  return finish(doc)
}

// ---------------------------------------------------------------------------------------------------
// Preparing an export: bounded reads through existing endpoints only
// ---------------------------------------------------------------------------------------------------

function loadFailure(error, panel) {
  const status = error?.status
  if (status === 404) return `the ${panel} panel no longer retains it.`
  if (status === 403) return `the ${panel} panel is disabled or not permitted.`
  return `it could not be loaded${status ? ` (HTTP ${status})` : ''}.`
}

/**
 * Loads the detail of every exception group a profile references, through `GET api/exceptions/{id}`,
 * at most {@link MAX_EXCEPTION_DETAILS} groups.
 *
 * @param {any} profile
 * @param {(url: string) => Promise<any>} fetchJson
 * @returns {Promise<{exceptionDetails: Record<string, {detail?: any, error?: string}>, omissions: string[]}>}
 */
export async function loadProfileExceptionDetails(profile, fetchJson) {
  const ids = [...new Set((profile?.exceptions ?? []).map((e) => e.exceptionGroupId).filter(Boolean))]
  const loaded = ids.slice(0, MAX_EXCEPTION_DETAILS)
  /** @type {Record<string, {detail?: any, error?: string}>} */
  const exceptionDetails = {}
  for (const id of loaded) {
    try {
      exceptionDetails[id] = {detail: await fetchJson(`api/exceptions/${encodeURIComponent(id)}`)}
    } catch (error) {
      exceptionDetails[id] = {error: loadFailure(error, 'Exceptions')}
    }
  }
  const omissions = []
  for (const id of ids.slice(MAX_EXCEPTION_DETAILS)) {
    exceptionDetails[id] = {error: `only the first ${MAX_EXCEPTION_DETAILS} exception groups are loaded.`}
  }
  if (ids.length > MAX_EXCEPTION_DETAILS) {
    omissions.push(
      `Stack traces of ${ids.length - MAX_EXCEPTION_DETAILS} more exception groups: only the first ${MAX_EXCEPTION_DETAILS} are loaded.`
    )
  }
  return {exceptionDetails, omissions}
}

/**
 * Finds and loads the profile of the request an exception group's latest occurrence belongs to, through
 * `GET api/activity` and `GET api/activity/request/{id}`.
 *
 * @param {any} detail an `ExceptionDetailDto`
 * @param {(url: string) => Promise<any>} fetchJson
 * @returns {Promise<{correlated: {requestId: string, profile: any} | null, correlationUnavailableReason: string | null}>}
 */
export async function loadExceptionCorrelation(detail, fetchJson) {
  const groupId = detail?.group?.id
  const none = (reason) => ({correlated: null, correlationUnavailableReason: reason})
  if (!groupId) return none('the exception group has no id.')
  let activity
  try {
    activity = await fetchJson('api/activity')
  } catch (error) {
    return none(`Live Activity could not be read: ${loadFailure(error, 'Live Activity')}`)
  }
  if (!activity?.available) return none('Live Activity is not capturing.')
  const entries = activity.entries ?? []
  const entry = entries.find((candidate) => candidate.id === `exc-${groupId}`)
  if (!entry) return none('Live Activity no longer lists this exception.')
  const parent = entry.parentId ? entries.find((candidate) => candidate.id === entry.parentId) : null
  if (!parent || parent.type !== 'REQUEST') {
    return none('the latest occurrence is not correlated to a captured HTTP request.')
  }
  if (!parent.profileable) return none('the correlated request cannot be profiled.')
  try {
    const profile = await fetchJson(`api/activity/request/${encodeURIComponent(parent.id)}`)
    if (!profile?.available) return none(profile?.unavailableReason || 'the request profile is unavailable.')
    return {correlated: {requestId: parent.id, profile}, correlationUnavailableReason: null}
  } catch (error) {
    return none(`the request profile could not be loaded: ${loadFailure(error, 'Live Activity')}`)
  }
}
