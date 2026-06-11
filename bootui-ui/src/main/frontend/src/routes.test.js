import {describe, expect, it} from 'vitest'
import {groups, routes} from './routes.js'

const namedRoutes = routes.filter((route) => route.name)

describe('routes', () => {
  it('keeps the sidebar order aligned with the documented feature order', () => {
    expect(namedRoutes.map((route) => route.meta.title)).toEqual([
      'Overview',
      'GitHub',
      'Architecture',
      'REST API',
      'Spring',
      'Hibernate',
      'Memory',
      'Security',
      'Pentesting',
      'Vulnerabilities',
      'Health',
      'HTTP Sessions',
      'Metrics',
      'Live Memory',
      'JVM Tuning',
      'Heap Dump',
      'Threads',
      'Startup Timeline',
      'GraalVM',
      'CRaC',
      'Configuration',
      'Profile Diff',
      'Loggers',
      'Beans',
      'Conditions',
      'Mappings',
      'Database Connection Pools',
      'Spring Data',
      'Flyway',
      'Liquibase',
      'Spring Security',
      'Security Logs',
      'Scheduled Tasks',
      'Spring Cache',
      'AI Usage',
      'Traces',
      'Log Tail',
      'Exceptions',
      'HTTP Exchanges',
      'HTTP Probe',
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
        group: expect.stringMatching(
          /^(overview|advisors|runtime|configuration|database|security|services|diagnostics|developer-tools)$/
        )
      })
    }
  })

  it('uses navigation group keys understood by the app shell', () => {
    expect(Object.values(groups)).toEqual([
      'overview',
      'advisors',
      'runtime',
      'configuration',
      'database',
      'security',
      'services',
      'diagnostics',
      'developer-tools'
    ])

    expect(namedRoutes.map((route) => route.meta.group)).toEqual([
      groups.overview,
      groups.overview,
      groups.advisors,
      groups.advisors,
      groups.advisors,
      groups.advisors,
      groups.advisors,
      groups.advisors,
      groups.advisors,
      groups.advisors,
      groups.runtime,
      groups.runtime,
      groups.runtime,
      groups.runtime,
      groups.runtime,
      groups.runtime,
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
      groups.database,
      groups.database,
      groups.database,
      groups.database,
      groups.security,
      groups.security,
      groups.services,
      groups.services,
      groups.services,
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
      {path: '/tuning-advisor', redirect: '/jvm-tuning'},
      {path: '/pentest', redirect: '/pentesting'},
      {path: '/dependencies', redirect: '/vulnerabilities'},
      {path: '/rest-advisor', redirect: '/rest-api'},
      {path: '/spring-advisor', redirect: '/spring'},
      {path: '/hibernate-advisor', redirect: '/hibernate'},
      {path: '/memory-advisor', redirect: '/memory'},
      {path: '/security-advisor', redirect: '/security'},
      {path: '/profiles', redirect: '/profile-diff'}
    ])
  })
})
