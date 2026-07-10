import {describe, expect, it} from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {groups, routes} from './routes.js'

const namedRoutes = routes.filter((route) => route.name)
const repoRoot = findRepositoryRoot(path.dirname(fileURLToPath(import.meta.url)))

function parseBackendPanels() {
  const source = fs.readFileSync(
    path.join(repoRoot, 'bootui-engine/src/main/java/io/github/jdubois/bootui/engine/panel/BootUiPanels.java'),
    'utf8'
  )

  // These regexes intentionally parse the current BootUiPanels source shape:
  // - string constants declared as `public static final String ... = "...";`
  // - panel entries declared as `new Panel(CONSTANT, "Title", true|false, "..."/List.of(...))`
  // If BootUiPanels declaration style changes, this test should be updated with it.
  const constants = new Map(
    [...source.matchAll(/public static final String ([A-Z_]+) = "([^"]+)";/g)].map((match) => [match[1], match[2]])
  )

  const panels = [
    ...source.matchAll(/new Panel\(([^,]+),\s*"([^"]+)",\s*(true|false),\s*(List\.of\([^\)]*\)|"[^"]*")\)/g)
  ].map((match) => {
    const idToken = match[1].trim()
    const id = constants.get(idToken) ?? idToken.replace(/^"|"$/g, '')
    return {
      id,
      title: match[2],
      actionCapable: match[3] === 'true'
    }
  })

  expect(panels.length).toBeGreaterThan(0)
  return panels
}

function loadManifest(fileName) {
  return JSON.parse(
    fs.readFileSync(
      path.join(repoRoot, 'bootui-conformance/src/main/resources/io/github/jdubois/bootui/conformance', fileName),
      'utf8'
    )
  )
}

function findRepositoryRoot(startDirectory) {
  let current = path.resolve(startDirectory)
  while (true) {
    if (
      fs.existsSync(path.join(current, 'pom.xml')) &&
      fs.existsSync(path.join(current, 'bootui-engine')) &&
      fs.existsSync(path.join(current, 'bootui-ui'))
    ) {
      return current
    }
    const parent = path.dirname(current)
    if (parent === current) {
      throw new Error(`Unable to locate repository root from ${startDirectory}`)
    }
    current = parent
  }
}

describe('routes', () => {
  it('keeps the sidebar order aligned with the documented feature order', () => {
    expect(namedRoutes.map((route) => route.meta.title)).toEqual([
      'Overview',
      'Live Activity',
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
      'SQL Trace',
      'Spring Data',
      'Flyway',
      'Liquibase',
      'Spring Security',
      'Security Logs',
      'Scheduled Tasks',
      'REST Client',
      'Cache',
      'Email',
      'Kafka',
      'AI Usage',
      'Traces',
      'Log Tail',
      'Exceptions',
      'HTTP Exchanges',
      'HTTP Probe',
      'MCP Server',
      'DevTools',
      'Dev Services',
      'Copilot',
      'Claude Code'
    ])
  })

  it('keeps the UI routes aligned with the backend panel catalog', () => {
    const backendPanels = parseBackendPanels()
    const routeMetadata = Object.fromEntries(namedRoutes.map((route) => [route.name, {title: route.meta.title}]))
    const backendMetadata = Object.fromEntries(backendPanels.map((panel) => [panel.id, {title: panel.title}]))

    expect(routeMetadata).toEqual(backendMetadata)
  })

  it('keeps conformance manifests aligned with the backend panel catalog and order', () => {
    const backendPanels = parseBackendPanels()
    const expected = backendPanels.map(({id, title, actionCapable}) => ({id, title, actionCapable}))

    for (const manifestFile of [
      'expected-panels-spring.json',
      'expected-panels-quarkus.json',
      'expected-panels-webflux.json'
    ]) {
      const manifest = loadManifest(manifestFile)
      expect(manifest.panels).toEqual(expected)
    }
  })

  it('documents every panel from the backend panel catalog in docs/FEATURES.md', () => {
    const features = fs.readFileSync(path.join(repoRoot, 'docs/FEATURES.md'), 'utf8')

    for (const panel of parseBackendPanels()) {
      const headingLevel = panel.title === 'Overview' ? '##' : '###'
      const escapedTitle = panel.title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      const headingPattern = new RegExp(`^${headingLevel} ${escapedTitle}$`, 'm')
      expect(headingPattern.test(features)).toBe(true)
    }
  })

  it('defines complete and unique sidebar metadata for every navigable route', () => {
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
    expect(new Set(namedRoutes.map((route) => route.name)).size).toBe(namedRoutes.length)
    expect(new Set(namedRoutes.map((route) => route.path)).size).toBe(namedRoutes.length)
    expect(new Set(namedRoutes.map((route) => route.meta.icon)).size).toBe(namedRoutes.length)
    expect(new Set(namedRoutes.map((route) => route.meta.shortcut)).size).toBe(namedRoutes.length)

    for (const route of namedRoutes) {
      expect(route.path).toMatch(/^\/[a-z0-9-]+$/)
      expect(route.component).toBeTruthy()
      expect(route.meta).toMatchObject({
        title: expect.any(String),
        icon: expect.stringMatching(/^bi-/),
        group: expect.stringMatching(
          /^(overview|advisors|runtime|configuration|database|security|services|diagnostics|developer-tools)$/
        ),
        shortcut: expect.stringMatching(/^[a-z]{2,3}$/)
      })
    }
  })

  it('uses navigation group keys understood by the app shell', () => {
    expect(namedRoutes.map((route) => route.meta.group)).toEqual([
      groups.overview,
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
      groups.database,
      groups.security,
      groups.security,
      groups.services,
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
      groups.developerTools,
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
      {path: '/profiles', redirect: '/profile-diff'},
      {path: '/spring-cache', redirect: '/cache'}
    ])
  })
})
