// Shared, dependency-free helpers for the worksheet and the scorer: the distinct-fact rule, the seeded hidden-row
// sample, and a small CSV reader and writer. The rules here are the registered protocol (docs/V2-VALIDATION-REPORT.md,
// "Protocol for the rerun"); changing them after a rerun's evidence is collected changes the protocol.

export const USEFUL = ['Actionable', 'Informative']
export const JUDGMENTS = ['Actionable', 'Informative', 'Noise', 'Misleading']
export const HONESTY = ['Honest', 'Hides', 'Misleading']
export const NOT_EVALUATED = ['INSUFFICIENT', 'PARTIAL', 'NOT_APPLICABLE', 'UNAVAILABLE']

/**
 * A sentence with its run-specific parts removed: ids (eight or more hexadecimal digits, UUIDs) become `<id>`, and
 * numbers become `#`. Two rows whose sentences differ only by counts, timings, or ids state the same fact.
 */
export function normalizeSentence(sentence) {
  return String(sentence ?? '')
    .toLowerCase()
    .replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/g, '<id>')
    .replace(/\b(?=[0-9a-f]*[a-f])(?=[0-9a-f]*\d)[0-9a-f]{8,}\b/g, '<id>')
    .replace(/\d+(?:[.,]\d+)*/g, '#')
    .replace(/\s+/g, ' ')
    .trim()
}

/**
 * The distinct-fact key: application, service, kind, subject, and normalized sentence. Rows of one application that
 * share it are one fact, judged once; rows on different subjects are different facts, even with one root cause.
 */
export function factKey(app, service, observation) {
  return [app, service || '', observation.kind, observation.subject, normalizeSentence(observation.sentence)].join(
    '\u241f'
  )
}

/** Whether a row is in the default list. A build without M4-19 has no `listed` flag: every row is listed. */
export const isListed = (observation) => observation.listed !== false

/** FNV-1a, 32 bits: a short, stable hash for ids and seeds. */
export function hash(text) {
  let h = 0x811c9dc5
  for (const ch of String(text)) {
    h ^= ch.codePointAt(0)
    h = Math.imul(h, 0x01000193) >>> 0
  }
  return h >>> 0
}

export const shortId = (text) => hash(text).toString(16).padStart(8, '0')

/** Mulberry32: a seeded generator, so the same seed and the same evidence always draw the same sample. */
export function random(seed) {
  let a = hash(seed)
  return () => {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

export function shuffle(items, rng) {
  const copy = [...items]
  for (let i = copy.length - 1; i > 0; i--) {
    const j = Math.floor(rng() * (i + 1))
    ;[copy[i], copy[j]] = [copy[j], copy[i]]
  }
  return copy
}

/**
 * The worst latency a `route-time-breakdown` sentence states, in milliseconds, or -1: the larger of its warm median and
 * its cold first request, so a slow route with too few warm requests still ranks.
 */
export function worstLatencyMillis(sentence) {
  const text = String(sentence ?? '')
  const values = [...text.matchAll(/(?:warm median|first request) ([\d.,]+) (ms|s)\b/gi)].map(
    ([, value, unit]) => Number(value.replace(/,/g, '')) * (unit === 's' ? 1000 : 1)
  )
  return values.length ? Math.max(...values) : -1
}

const CAUGHT_IN_EXECUTION = /scheduled run|message that recorded it completed|completed.*(run|message)/i

/**
 * The hidden-row sample of one application, stratified and seeded. `hidden` holds distinct facts left out of the
 * default list. Strata, in order: the slowest hidden route (by its worst stated latency); up to two exception groups caught in completed scheduled
 * runs or messages; one row for every other (kind, reason) left out; then seeded draws until `size` rows. Mandatory
 * strata are never dropped, so a sample may exceed `size`; with `size` rows or fewer, every hidden row is sampled. Throws
 * when hidden routes exist but none states a latency, since the slowest-route stratum would vanish.
 */
export function sampleHidden(hidden, {seed, size = 10}) {
  const rows = [...hidden].sort((a, b) => a.key.localeCompare(b.key))
  if (rows.length <= size) return rows.map((row) => ({...row, stratum: 'all hidden rows'}))
  const rng = random(seed)
  const picked = new Map()
  const take = (row, stratum) => {
    if (row && !picked.has(row.key)) picked.set(row.key, {...row, stratum})
  }
  const routes = rows.filter((r) => r.kind === 'route-time-breakdown')
  const slowest = routes.reduce(
    (best, r) => (worstLatencyMillis(r.sentence) > worstLatencyMillis(best?.sentence) ? r : best),
    null
  )
  if (routes.length && !(slowest && worstLatencyMillis(slowest.sentence) >= 0)) {
    // The mandatory stratum must never disappear silently, for instance after a rewording of the sentence.
    throw new Error(
      `no hidden route-time-breakdown sentence of ${seed} states a latency, so the slowest route is unknown`
    )
  }
  if (slowest) take(slowest, 'slowest hidden route')
  const caught = shuffle(
    rows.filter((r) => r.kind === 'exception-hotspots' && CAUGHT_IN_EXECUTION.test(r.unlistedReason || '')),
    rng
  )
  caught.slice(0, 2).forEach((r) => take(r, 'exception caught in a completed scheduled run or message'))
  const byReason = new Map()
  for (const r of rows) {
    const stratum = `${r.kind}: ${normalizeSentence(r.unlistedReason || 'no reason given')}`
    if (!byReason.has(stratum)) byReason.set(stratum, [])
    byReason.get(stratum).push(r)
  }
  for (const [stratum, members] of [...byReason.entries()].sort(([a], [b]) => a.localeCompare(b))) {
    if (members.some((m) => picked.has(m.key))) continue
    take(shuffle(members, rng)[0], `reason: ${stratum}`)
  }
  for (const r of shuffle(rows, rng)) {
    if (picked.size >= size) break
    take(r, 'seeded draw')
  }
  return [...picked.values()]
}

export function parseCsv(text) {
  const rows = []
  let row = []
  let field = ''
  let quoted = false
  for (let i = 0; i < text.length; i++) {
    const ch = text[i]
    if (quoted) {
      if (ch === '"' && text[i + 1] === '"') {
        field += '"'
        i++
      } else if (ch === '"') quoted = false
      else field += ch
    } else if (ch === '"') quoted = true
    else if (ch === ',') {
      row.push(field)
      field = ''
    } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && text[i + 1] === '\n') i++
      row.push(field)
      field = ''
      if (row.some((cell) => cell !== '')) rows.push(row)
      row = []
    } else field += ch
  }
  row.push(field)
  if (row.some((cell) => cell !== '')) rows.push(row)
  const [header = [], ...body] = rows
  const names = header.map((h) => h.trim())
  return body.map((cells) => Object.fromEntries(names.map((name, i) => [name, (cells[i] ?? '').trim()])))
}

export function toCsv(records, columns) {
  const escape = (value) => {
    const text = value === null || value === undefined ? '' : String(value)
    return /[",\n\r]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
  }
  return [columns.join(','), ...records.map((r) => columns.map((c) => escape(r[c])).join(','))].join('\n') + '\n'
}

/** A share for display only, rounded to 0.1; gates compare exact fractions (`atLeast`, `below`). */
export const percent = (part, whole) => (whole ? Math.round((1000 * part) / whole) / 10 : null)

/** Whether part / whole ≥ threshold %, exactly, without rounding. */
export const atLeast = (part, whole, threshold) => whole > 0 && part * 100 >= threshold * whole

/** Whether part / whole < threshold %, exactly, without rounding. */
export const below = (part, whole, threshold) => whole > 0 && part * 100 < threshold * whole

/**
 * The exact (Clopper-Pearson) 95 % upper bound of a proportion with `hits` in `n` draws, as a percentage: how much
 * hidden value a sample that found `hits` can still leave undetected.
 */
export function upperBound95(hits, n) {
  if (n === 0) return 100
  if (hits >= n) return 100
  const cdf = (p) => {
    let term = Math.pow(1 - p, n)
    let sum = term
    for (let k = 1; k <= hits; k++) {
      term *= ((n - k + 1) / k) * (p / (1 - p))
      sum += term
    }
    return sum
  }
  let lo = 0
  let hi = 1
  for (let i = 0; i < 60; i++) {
    const mid = (lo + hi) / 2
    if (cdf(mid) > 0.025) lo = mid
    else hi = mid
  }
  return Math.round(lo * 1000) / 10
}
