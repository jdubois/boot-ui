/**
 * Helpers for Runtime Insights' Profile resources session (docs/PLAN-v2.md §5.11): an opt-in JFR recording whose CPU
 * and allocation samples are joined to the requests that ran during it.
 */

/** Whether a response is a resource profile rather than an error body. */
export function isResourceProfile(value) {
  return typeof value?.state === 'string' && Array.isArray(value?.routes)
}

/** The sampler that ran, in words. */
export function samplerLabel(sampler) {
  if (sampler === 'jdk.CPUTimeSample') return 'CPU-time sampler'
  if (sampler === 'jdk.ExecutionSample') return 'execution sampler'
  return null
}

/** The share, in whole percent, of the session's CPU samples taken while a request ran, or null without samples. */
export function requestShare(profile) {
  const total = profile?.cpuSamples ?? 0
  if (total <= 0) return null
  return Math.round((100 * Math.max(0, total - (profile.outsideSamples ?? 0))) / total)
}

/**
 * Each route with its share of the CPU samples joined to requests, which sizes its bar, and whether it took the most.
 * Shares are rounded, and a route with samples never shows 0 %.
 */
export function profileRows(profile) {
  const routes = profile?.routes ?? []
  const joined = routes.reduce((sum, route) => sum + (route.cpuSamples ?? 0), 0)
  const most = Math.max(0, ...routes.map((route) => route.cpuSamples ?? 0))
  return routes.map((route) => {
    const share = joined > 0 ? (100 * (route.cpuSamples ?? 0)) / joined : 0
    return {
      ...route,
      share,
      shareLabel: route.cpuSamples > 0 ? `${Math.max(1, Math.round(share))} %` : '0 %',
      top: most > 0 && route.cpuSamples === most
    }
  })
}

/** The whole seconds left before a running session ends on its own, never negative. */
export function remainingSeconds(profile, now = Date.now()) {
  if (profile?.state !== 'RUNNING' || profile.endsAt == null) return 0
  return Math.max(0, Math.ceil((profile.endsAt - now) / 1000))
}

/** How far a running session has gone, in percent. */
export function elapsedPercent(profile, now = Date.now()) {
  if (profile?.state !== 'RUNNING' || profile.startedAt == null || profile.endsAt == null) return 0
  const span = profile.endsAt - profile.startedAt
  if (span <= 0) return 100
  return Math.min(100, Math.max(0, Math.round((100 * (now - profile.startedAt)) / span)))
}

/** A session length in words, such as "30 seconds" or "2 minutes". */
export function durationLabel(seconds) {
  const value = Number(seconds) || 0
  if (value >= 60 && value % 60 === 0) {
    const minutes = value / 60
    return `${minutes} ${minutes === 1 ? 'minute' : 'minutes'}`
  }
  return `${value} ${value === 1 ? 'second' : 'seconds'}`
}

/** A frame without its package, such as "OrderService.price:42", which the full name explains on hover. */
export function frameLabel(frame) {
  const value = String(frame ?? '')
  const method = value.lastIndexOf('.', value.includes(':') ? value.lastIndexOf(':') : value.length)
  const type = method > 0 ? value.lastIndexOf('.', method - 1) : -1
  return type >= 0 ? value.substring(type + 1) : value
}
