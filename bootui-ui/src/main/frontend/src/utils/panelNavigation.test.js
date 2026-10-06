import {describe, expect, it} from 'vitest'

import {
  buildDocumentTitle,
  createPanelLookup,
  resolveRouteTitle,
  routeAvailabilityLabel,
  routeMovesToUnavailableGroup,
  routeNavigationGroup,
  routePanelState,
  routeUnavailable
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
    expect(routeNavigationGroup(springRoute, lookup)).toBe('Disabled / unavailable')
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
    expect(routeNavigationGroup(codePathsRoute, withoutAgent)).toBe('agent')
    expect(routeAvailabilityLabel(codePathsRoute, withoutAgent)).toBe(
      'Code Paths - unavailable: The agent is not attached'
    )

    const disabled = createPanelLookup({panels: [{id: 'code-paths', available: false, enabled: false}]})
    expect(routeMovesToUnavailableGroup(codePathsRoute, disabled)).toBe(true)
    expect(routeNavigationGroup(codePathsRoute, disabled)).toBe('Disabled / unavailable')

    const unavailableWithoutAgentFlag = {...codePathsRoute, meta: {...codePathsRoute.meta, requiresAgent: false}}
    expect(routeMovesToUnavailableGroup(unavailableWithoutAgentFlag, withoutAgent)).toBe(true)
  })
})
