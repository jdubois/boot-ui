import {describe, expect, it} from 'vitest'
import {groups, routes} from './routes.js'

const namedRoutes = routes.filter((route) => route.name)

describe('routes', () => {
  it('keeps the sidebar order aligned with the documented feature order', () => {
    expect(namedRoutes.map((route) => route.meta.title)).toEqual([
      'Overview',
      'Health',
      'Metrics',
      'Memory',
      'Startup Timeline',
      'Configuration',
      'Profile Diff',
      'Loggers',
      'Beans',
      'Conditions',
      'Mappings',
      'Scheduled Tasks',
      'Data',
      'Cache',
      'Security',
      'AI Usage',
      'Traces',
      'Log Tail',
      'HTTP Probe',
      'Pentesting',
      'Vulnerabilities',
      'Heap Dump',
      'DevTools',
      'Dev Services',
      'Copilot',
      'Claude Code'
    ])
  })

  it('defines complete and unique sidebar metadata for every navigable route', () => {
    expect(new Set(namedRoutes.map((route) => route.name)).size).toBe(namedRoutes.length)
    expect(new Set(namedRoutes.map((route) => route.path)).size).toBe(namedRoutes.length)
    expect(new Set(namedRoutes.map((route) => route.meta.icon)).size).toBe(namedRoutes.length)

    for (const route of namedRoutes) {
      expect(route.path).toMatch(/^\/[a-z0-9-]+$/)
      expect(route.component).toBeTruthy()
      expect(route.meta).toMatchObject({
        title: expect.any(String),
        icon: expect.stringMatching(/^bi-/),
        group: expect.stringMatching(/^(overview|runtime|configuration|services|diagnostics|developer-tools)$/)
      })
    }
  })

  it('uses navigation group keys understood by the app shell', () => {
    expect(Object.values(groups)).toEqual([
      'overview',
      'runtime',
      'configuration',
      'services',
      'diagnostics',
      'developer-tools'
    ])

    expect(namedRoutes.map((route) => route.meta.group)).toEqual([
      groups.overview,
      groups.runtime,
      groups.runtime,
      groups.runtime,
      groups.runtime,
      groups.configuration,
      groups.configuration,
      groups.configuration,
      groups.configuration,
      groups.configuration,
      groups.configuration,
      groups.services,
      groups.services,
      groups.services,
      groups.services,
      groups.services,
      groups.diagnostics,
      groups.diagnostics,
      groups.diagnostics,
      groups.diagnostics,
      groups.diagnostics,
      groups.diagnostics,
      groups.developerTools,
      groups.developerTools,
      groups.developerTools,
      groups.developerTools
    ])
  })

  it('keeps redirect aliases out of sidebar navigation', () => {
    expect(routes.filter((route) => route.redirect)).toEqual([
      {path: '/', redirect: '/overview'},
      {path: '/dependencies', redirect: '/vulnerabilities'}
    ])
  })
})
