const UNAVAILABLE_GROUP_LABEL = 'Disabled / unavailable'

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

export function routeNavigationGroup(route, panelLookup) {
  return routeUnavailable(route, panelLookup) ? UNAVAILABLE_GROUP_LABEL : route.meta?.group
}

export function buildDocumentTitle(route, platform, applicationName) {
  const panelTitle = cleanLabel(resolveRouteTitle(route, platform), 'BootUI')
  const applicationTitle = cleanLabel(applicationName, 'Application')
  return `${panelTitle} · ${applicationTitle} · BootUI`
}

function cleanLabel(value, fallback) {
  return typeof value === 'string' && value.trim() ? value.trim() : fallback
}
