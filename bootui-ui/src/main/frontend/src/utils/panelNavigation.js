export const HOME_NAVIGATION_GROUP = Object.freeze({key: 'home', title: 'Home'})

/**
 * The sidebar's collapsible groups, in menu order: the route `meta.group` key, the visible title, and the group icon.
 * A group icon is the only cue in the collapsed rail, so it must not reuse a panel icon or another group's icon.
 */
export const NAVIGATION_GROUPS = Object.freeze(
  [
    {key: 'advisors', title: 'Advisors', icon: 'bi-clipboard2-check'},
    {key: 'runtime', title: 'Runtime', icon: 'bi-motherboard'},
    {key: 'configuration', title: 'Configuration', icon: 'bi-gear'},
    {key: 'database', title: 'Database', icon: 'bi-table'},
    {key: 'security', title: 'Security', icon: 'bi-key'},
    {key: 'services', title: 'Services', icon: 'bi-puzzle'},
    {key: 'diagnostics', title: 'Diagnostics', icon: 'bi-search'},
    {key: 'agent', title: 'Instrumentation', icon: 'bi-radar'},
    {key: 'developer-tools', title: 'Developer tools', icon: 'bi-tools'}
  ].map((group) => Object.freeze(group))
)

export const UNAVAILABLE_NAVIGATION_GROUP = Object.freeze({
  key: 'unavailable',
  title: 'Disabled / unavailable',
  icon: 'bi-slash-circle'
})

const NAVIGATION_GROUPS_BY_KEY = new Map(
  [HOME_NAVIGATION_GROUP, ...NAVIGATION_GROUPS, UNAVAILABLE_NAVIGATION_GROUP].map((group) => [group.key, group])
)

/** The group a key names, or a title-less fallback that keeps an unknown key visible rather than hiding it. */
export function navigationGroup(key) {
  return NAVIGATION_GROUPS_BY_KEY.get(key) ?? {key, title: key}
}

export function createPanelLookup(manifest) {
  return new Map((manifest?.panels ?? []).map((panel) => [panel.id, panel]))
}

export function resolveRouteTitle(route, platform) {
  return route?.meta?.titleByPlatform?.[platform] || route?.meta?.title || null
}

function panelForRoute(route, panelLookup) {
  return route?.name ? panelLookup.get(route.name) : null
}

export function panelDisabledReason(panel) {
  return `Panel is disabled via bootui.panels.${panel?.id || 'panel'}.enabled=false`
}

export function routePanelState(route, panelLookup) {
  const panel = panelForRoute(route, panelLookup)
  if (panel?.enabled === false) {
    return {
      icon: 'bi-slash-circle',
      kind: 'disabled',
      label: 'Disabled',
      reason: panelDisabledReason(panel)
    }
  }
  if (panel?.available === false) {
    return {
      icon: 'bi-slash-circle',
      kind: 'unavailable',
      label: 'Unavailable',
      reason: panel.unavailableReason || 'required support is unavailable'
    }
  }
  if (panel?.readOnly === true) {
    return {
      icon: 'bi-lock',
      kind: 'read-only',
      label: 'Read-only',
      reason: panel.readOnlyReason || 'mutating actions are disabled'
    }
  }
  return null
}

export function routeUnavailable(route, panelLookup) {
  const kind = routePanelState(route, panelLookup)?.kind
  return kind === 'disabled' || kind === 'unavailable'
}

export function routeStatusIcon(route, panelLookup) {
  return routePanelState(route, panelLookup)?.icon ?? null
}

export function routeAvailabilityLabel(route, panelLookup, platform) {
  const title = resolveRouteTitle(route, platform) || 'Panel'
  const state = routePanelState(route, panelLookup)
  return state ? `${title} - ${state.kind}: ${state.reason}` : title
}

/**
 * Whether the sidebar files a route under "Disabled / unavailable" instead of its own group. A panel disabled by
 * configuration always moves there. A panel that only needs the BootUI Java agent (`meta.requiresAgent`) stays in its
 * Instrumentation group while unavailable, so it remains discoverable before the agent is attached.
 */
export function routeMovesToUnavailableGroup(route, panelLookup) {
  const kind = routePanelState(route, panelLookup)?.kind
  if (kind === 'disabled') return true
  return kind === 'unavailable' && route?.meta?.requiresAgent !== true
}

/** The sidebar group a route is filed under right now: its own group, or "Disabled / unavailable". */
export function routeNavigationGroup(route, panelLookup) {
  return routeMovesToUnavailableGroup(route, panelLookup)
    ? UNAVAILABLE_NAVIGATION_GROUP
    : navigationGroup(route?.meta?.group)
}

export function buildDocumentTitle(route, platform, applicationName) {
  const panelTitle = cleanLabel(resolveRouteTitle(route, platform), 'BootUI')
  const applicationTitle = cleanLabel(applicationName, 'Application')
  return `${panelTitle} · ${applicationTitle} · BootUI`
}

function cleanLabel(value, fallback) {
  return typeof value === 'string' && value.trim() ? value.trim() : fallback
}
