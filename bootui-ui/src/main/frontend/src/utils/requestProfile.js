// Presentation helpers for the Live Activity request profile. Every value comes from the already-masked
// profile payload, so nothing here reads, captures, or sends anything.

const TIER_LABELS = {
  TRACE_ID: 'trace id',
  SERVING_THREAD: 'serving thread',
  TIME_WINDOW: 'time window'
}

const TIER_TITLES = {
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
    sections[section.type] = {
      ...section,
      tierLabel: tierLabel(section.tier),
      tierTitle: tierTitle(section.tier),
      truncationText: section.truncated
        ? `Showing the first ${shown} of ${section.total} ${SECTION_NOUNS[section.type] ?? 'items'}.`
        : ''
    }
  }
  return sections
}

/**
 * @param {any} profile
 * @returns {string}
 */
export function unavailableTiersText(profile) {
  const tiers = (profile?.correlationTiers ?? []).filter((tier) => !tier.available)
  if (!tiers.length) return ''
  const labels = tiers.map((tier) => tierLabel(tier.tier) || tier.tier)
  const joined = labels.length === 1 ? labels[0] : `${labels.slice(0, -1).join(', ')} and ${labels[labels.length - 1]}`
  const subject = joined.charAt(0).toUpperCase() + joined.slice(1)
  const text = `${subject} correlation ${tiers.length === 1 ? 'is' : 'are'} unavailable on this adapter`
  const reason = tiers.find((tier) => tier.unavailableReason)?.unavailableReason
  return reason ? `${text}: ${reason}` : `${text}.`
}

export function restCallSummary(call) {
  const method = call.method ? `${call.method} ` : ''
  const outcome = call.success ? (call.status ?? '') : 'failed'
  return `${method}${call.host ?? ''}${call.path ?? ''} → ${outcome}`
}

export function cacheAccessSummary(access) {
  return `${access.operation ?? ''} ${access.cacheName ?? ''}`.trim()
}
