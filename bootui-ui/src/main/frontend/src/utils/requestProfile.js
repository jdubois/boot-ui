// Presentation helpers for the Live Activity request profile. Every value comes from the already-masked
// profile payload, so nothing here reads, captures, or sends anything.

const TIER_LABELS = {
  REQUEST_ID: 'request id',
  PROPAGATED: 'propagated',
  TRACE_ID: 'trace id',
  SERVING_THREAD: 'serving thread',
  TIME_WINDOW: 'time window'
}

const TIER_TITLES = {
  REQUEST_ID: 'Correlated exactly by the BootUI request id stamped when the signal was recorded',
  PROPAGATED:
    'Correlated exactly by the BootUI request id, recorded in a task the BootUI agent propagated from this request to an executor',
  TRACE_ID: 'Correlated exactly by a trace id that no other captured request carries',
  SERVING_THREAD: "Correlated exactly by the request's serving thread within its window",
  TIME_WINDOW: 'Matched by time window only, so it may include or miss signals under concurrent requests'
}

const SECTION_NOUNS = {
  SQL: 'statements',
  EXCEPTION: 'exception occurrences',
  SECURITY: 'security events',
  REST_CLIENT: 'REST client calls',
  CACHE: 'cache accesses'
}

export function tierLabel(tier) {
  return TIER_LABELS[tier] ?? ''
}

export function tierTitle(tier) {
  return TIER_TITLES[tier] ?? ''
}

// Section metadata keyed by section type. Profiles from an older server carry no sections, so every
// lookup degrades to an empty object and the drawer renders exactly as it did before.
/**
 * @param {any} profile
 * @returns {Record<string, any>}
 */
export function profileSections(profile) {
  /** @type {Record<string, any>} */
  const sections = {}
  for (const section of profile?.sections ?? []) {
    const shown = Math.max(0, (section.total ?? 0) - (section.truncated ?? 0))
    const childTiers = section.childTiers ?? []
    sections[section.type] = {
      ...section,
      tierLabel: tierLabel(section.tier),
      tierTitle: tierTitle(section.tier),
      // Per-child labels only add information when shown children differ from each other or from the
      // section tier, which also counts children the bound left out.
      mixedTiers: childTiers.some((tier) => tier !== section.tier),
      truncationText: truncationText(section, shown)
    }
  }
  return sections
}

/**
 * @param {any} section
 * @param {number} shown
 * @returns {string}
 */
function truncationText(section, shown) {
  if (!section.truncated) return ''
  // The drawer shows SQL as groups whose execution counts and timing cover every correlated statement;
  // only the raw statement list the profile carries is cut at the bound.
  if (section.type === 'SQL') {
    return `Execution counts and timing cover all ${section.total} correlated statements; the profile lists the first ${shown}.`
  }
  return `Showing the first ${shown} of ${section.total} ${SECTION_NOUNS[section.type] ?? 'items'}.`
}

/**
 * The tier label of the child at `index`, or '' when the section does not mix tiers.
 *
 * @param {any} section
 * @param {number | string} index
 * @returns {string}
 */
/**
 * Whether a profiled security event belongs to the request exactly: emitted on its serving thread, or carrying its
 * BootUI request id.
 * @param {any} section
 * @param {any} event
 * @param {number | string} index
 * @returns {boolean}
 */
export function securityEventExact(section, event, index) {
  const tier = section?.childTiers?.[Number(index)]
  return Boolean(event?.threadMatched) || tier === 'REQUEST_ID' || tier === 'PROPAGATED'
}

export function childTierLabel(section, index) {
  if (!section?.mixedTiers) return ''
  return tierLabel(section.childTiers?.[Number(index)])
}

/**
 * @param {any} profile
 * @returns {string}
 */
export function unavailableTiersText(profile) {
  const tiers = (profile?.correlationTiers ?? []).filter((tier) => !tier.available)
  if (!tiers.length) return ''
  // One sentence per reason: the adapter's own limits, and the BootUI agent's propagation, which every adapter can have.
  const groups = new Map()
  for (const tier of tiers) {
    const reason = tier.unavailableReason ?? ''
    groups.set(reason, [...(groups.get(reason) ?? []), tier])
  }
  return [...groups].map(([reason, group]) => unavailableTiersSentence(group, reason)).join(' ')
}

function unavailableTiersSentence(tiers, reason) {
  const labels = tiers.map((tier) => tierLabel(tier.tier) || tier.tier)
  const joined = labels.length === 1 ? labels[0] : `${labels.slice(0, -1).join(', ')} and ${labels[labels.length - 1]}`
  const subject = joined.charAt(0).toUpperCase() + joined.slice(1)
  if (tiers.every((tier) => tier.tier === 'PROPAGATED')) {
    // "Requires the BootUI agent's executors sensor: …" reads as the rest of the sentence.
    if (reason.startsWith('Requires ')) return `${subject} correlation r${reason.slice(1)}`
    return reason ? `${subject} correlation is unavailable: ${reason}` : `${subject} correlation is unavailable.`
  }
  const text = `${subject} correlation ${tiers.length === 1 ? 'is' : 'are'} unavailable on this adapter`
  return reason ? `${text}: ${reason}` : `${text}.`
}

export function restCallSummary(call) {
  const method = call.method ? `${call.method} ` : ''
  const outcome = call.success ? (call.status ?? '') : 'failed'
  return `${method}${callAuthority(call)}${call.path ?? ''} → ${outcome}`
}

// The host, with the port when the captured URI states one, so two local services on different ports stay distinct.
function callAuthority(call) {
  const host = call.host ?? ''
  if (!host || !call.uri) return host
  try {
    const port = new URL(call.uri).port
    return port ? `${host}:${port}` : host
  } catch {
    return host
  }
}

export function cacheAccessSummary(access) {
  return `${access.operation ?? ''} ${access.cacheName ?? ''}`.trim()
}
