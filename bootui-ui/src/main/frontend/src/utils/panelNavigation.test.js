import {describe, expect, it} from 'vitest'

import {groups, routes} from '../routes.js'
import {
  buildDocumentTitle,
  createPanelLookup,
  HOME_NAVIGATION_GROUP,
  NAVIGATION_GROUPS,
  navigationGroup,
  resolveRouteTitle,
  routeAvailabilityLabel,
  routeMovesToUnavailableGroup,
  routeNavigationGroup,
  routePanelState,
  routeUnavailable,
  UNAVAILABLE_NAVIGATION_GROUP
} from './panelNavigation.js'

const springRoute = {
  name: 'spring',
  meta: {
    group: 'advisors',
    title: 'Spring',
    titleByPlatform: {quarkus: 'Quarkus'}
  }
}

describe('panel navigation', () => {
  it('resolves platform-aware labels and browser titles with safe fallbacks', () => {
    expect(resolveRouteTitle(springRoute, 'spring-boot')).toBe('Spring')
    expect(resolveRouteTitle(springRoute, 'quarkus')).toBe('Quarkus')
    expect(buildDocumentTitle(springRoute, 'quarkus', 'orders')).toBe('Quarkus · orders · BootUI')
    expect(buildDocumentTitle({}, null, '  ')).toBe('BootUI · Application · BootUI')
  })

  it('moves disabled and unavailable panels into the same explicit group used by the sidebar', () => {
    const lookup = createPanelLookup({
      panels: [
        {
          id: 'spring',
          available: true,
          enabled: false
        }
      ]
    })

    expect(routeUnavailable(springRoute, lookup)).toBe(true)
    expect(routeNavigationGroup(springRoute, lookup)).toBe(UNAVAILABLE_NAVIGATION_GROUP)
    expect(routeNavigationGroup(springRoute, lookup).title).toBe('Disabled / unavailable')
    expect(routePanelState(springRoute, lookup)).toMatchObject({
      kind: 'disabled',
      label: 'Disabled',
      icon: 'bi-slash-circle'
    })
    expect(routeAvailabilityLabel(springRoute, lookup, 'quarkus')).toBe(
      'Quarkus - disabled: Panel is disabled via bootui.panels.spring.enabled=false'
    )
  })

  it('keeps an agent panel in its Instrumentation group without the agent, unless configuration disables it', () => {
    const codePathsRoute = {name: 'code-paths', meta: {group: 'agent', title: 'Code Paths', requiresAgent: true}}
    const withoutAgent = createPanelLookup({
      panels: [{id: 'code-paths', available: false, enabled: true, unavailableReason: 'The agent is not attached'}]
    })

    expect(routeUnavailable(codePathsRoute, withoutAgent)).toBe(true)
    expect(routeMovesToUnavailableGroup(codePathsRoute, withoutAgent)).toBe(false)
    expect(routeNavigationGroup(codePathsRoute, withoutAgent)).toMatchObject({key: 'agent', title: 'Instrumentation'})
    expect(routeAvailabilityLabel(codePathsRoute, withoutAgent)).toBe(
      'Code Paths - unavailable: The agent is not attached'
    )

    const disabled = createPanelLookup({panels: [{id: 'code-paths', available: false, enabled: false}]})
    expect(routeMovesToUnavailableGroup(codePathsRoute, disabled)).toBe(true)
    expect(routeNavigationGroup(codePathsRoute, disabled)).toBe(UNAVAILABLE_NAVIGATION_GROUP)

    const unavailableWithoutAgentFlag = {...codePathsRoute, meta: {...codePathsRoute.meta, requiresAgent: false}}
    expect(routeMovesToUnavailableGroup(unavailableWithoutAgentFlag, withoutAgent)).toBe(true)
  })

  it('defines one sidebar group for every route group key except Home, in route order', () => {
    expect([HOME_NAVIGATION_GROUP, ...NAVIGATION_GROUPS].map((group) => group.key)).toEqual(Object.values(groups))
    expect(navigationGroup('agent').title).toBe('Instrumentation')
    expect(navigationGroup('developer-tools').title).toBe('Developer tools')
    expect(navigationGroup('home').title).toBe('Home')
    expect(navigationGroup('unavailable')).toBe(UNAVAILABLE_NAVIGATION_GROUP)
    expect(navigationGroup('unknown')).toEqual({key: 'unknown', title: 'unknown'})
  })

  it('gives every sidebar group an icon no panel and no other group uses', () => {
    // In the collapsed rail a group icon is the only cue, so it must be unambiguous.
    const panelIcons = new Map(routes.filter((route) => route.name).map((route) => [route.meta.icon, route.meta.title]))
    const groupsWithIcons = [...NAVIGATION_GROUPS, UNAVAILABLE_NAVIGATION_GROUP]

    for (const group of groupsWithIcons) {
      expect(group.icon, `${group.title} group icon`).toMatch(/^bi-[a-z0-9-]+$/)
      expect(panelIcons.get(group.icon), `${group.title} group icon ${group.icon} is a panel icon`).toBeUndefined()
    }
    expect(new Set(groupsWithIcons.map((group) => group.icon)).size).toBe(groupsWithIcons.length)
  })
})
