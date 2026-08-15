import {describe, expect, it} from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {groups, routes} from './routes.js'

const namedRoutes = routes.filter((route) => route.name)
const repoRoot = findRepositoryRoot(path.dirname(fileURLToPath(import.meta.url)))

function parseBackendPanels() {
  const source = readRepositoryFile(
    'bootui-engine/src/main/java/io/github/jdubois/bootui/engine/panel/BootUiPanels.java'
  )

  // These regexes intentionally parse the current BootUiPanels source shape:
  // - string constants declared as `public static final String ... = "...";`
  // - panel entries declared as `new Panel(CONSTANT, "Title", true|false, "..."/List.of(...))`
  // If BootUiPanels declaration style changes, this test should be updated with it.
  const constants = parseBackendPanelConstants(source)

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
    readRepositoryFile(
      path.join('bootui-conformance/src/main/resources/io/github/jdubois/bootui/conformance', fileName)
    )
  )
}

function readRepositoryFile(relativePath) {
  return fs.readFileSync(path.join(repoRoot, relativePath), 'utf8')
}

function parseBackendPanelConstants(source) {
  return new Map(
    [...source.matchAll(/public static final String ([A-Z_]+) = "([^"]+)";/g)].map((match) => [match[1], match[2]])
  )
}

function extractParenthesizedInitializer(source, marker) {
  const markerIndex = source.indexOf(marker)
  if (markerIndex < 0) {
    throw new Error(`Missing Java initializer marker: ${marker}`)
  }

  const openIndex = source.indexOf('(', markerIndex + marker.length)
  let depth = 0
  let inString = false
  let escaped = false

  for (let index = openIndex; index < source.length; index += 1) {
    const character = source[index]
    if (inString) {
      if (escaped) {
        escaped = false
      } else if (character === '\\') {
        escaped = true
      } else if (character === '"') {
        inString = false
      }
      continue
    }
    if (character === '"') {
      inString = true
    } else if (character === '(') {
      depth += 1
    } else if (character === ')') {
      depth -= 1
      if (depth === 0) {
        return source.slice(openIndex + 1, index)
      }
    }
  }

  throw new Error(`Unterminated Java initializer marker: ${marker}`)
}

function parsePanelReferences(source, marker, constants) {
  const initializer = extractParenthesizedInitializer(source, marker)
  return new Set(
    [...initializer.matchAll(/BootUiPanels\.([A-Z_]+)/g)].map((match) => {
      const panelId = constants.get(match[1])
      if (!panelId) {
        throw new Error(`Unknown BootUiPanels constant: ${match[1]}`)
      }
      return panelId
    })
  )
}

function parseQuarkusAvailability() {
  const backendSource = readRepositoryFile(
    'bootui-engine/src/main/java/io/github/jdubois/bootui/engine/panel/BootUiPanels.java'
  )
  const constants = parseBackendPanelConstants(backendSource)
  const source = readRepositoryFile(
    'bootui-quarkus/src/main/java/io/github/jdubois/bootui/quarkus/QuarkusPanelAvailability.java'
  )
  const staticPanels = parsePanelReferences(
    source,
    'private static final Set<String> AVAILABLE_PANELS = Set.of',
    constants
  )
  const capabilityGated = parsePanelReferences(source, 'this.dynamicAvailability = Map.ofEntries', constants)
  const capabilityReasons = parsePanelReferences(
    source,
    'private static final Map<String, String> CAPABILITY_ABSENT = Map.ofEntries',
    constants
  )
  const notApplicable = parsePanelReferences(
    source,
    'private static final Map<String, String> NOT_APPLICABLE = Map.of',
    constants
  )
  const notYetAvailable = parsePanelReferences(
    source,
    'private static final Map<String, String> NOT_YET_AVAILABLE_REASONS = Map.of',
    constants
  )
  const detectorMatch = source.match(/BootUiPanels\.([A-Z_]+)\.equals\(panelId\)\s*&&\s*githubAvailable\(\)/)
  if (!detectorMatch || !constants.has(detectorMatch[1])) {
    throw new Error('Unable to parse the dynamic GitHub panel detector')
  }
  const detectorGated = new Set([constants.get(detectorMatch[1])])

  return {
    staticPanels,
    capabilityGated,
    capabilityReasons,
    detectorGated,
    notApplicable,
    notYetAvailable
  }
}

function parseQuarkusSupportTable(markdown) {
  const appendix = markdown.split('## 11. Appendix — full panel disposition')[1]
  if (!appendix) {
    throw new Error('Missing Quarkus support appendix')
  }

  return appendix
    .split('\n')
    .map((line) => line.match(/^\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|/))
    .filter((match) => match && match[1] !== 'Panel' && !match[1].startsWith('-'))
    .map((match) => ({
      title: match[1].replaceAll('`', '').trim(),
      classification: match[3].replaceAll('*', '').replaceAll('`', '').trim()
    }))
}

function parseSpecificationGroupInventory(markdown) {
  const navigation = markdown.split('### 7.1 Navigation')[1]?.split('### 7.2 UI principles')[0]
  if (!navigation) {
    throw new Error('Missing specification navigation section')
  }

  const inventory = new Map()
  let currentGroup = null
  for (const line of navigation.split('\n')) {
    const group = line.match(/^- ([^:]+):$/)
    if (group) {
      currentGroup = group[1]
      inventory.set(currentGroup, [])
      continue
    }
    const panel = line.match(/^  - (.+)\.$/)
    if (panel && currentGroup) {
      inventory.get(currentGroup).push(panel[1])
    }
  }
  return inventory
}

function sorted(values) {
  return [...values].sort()
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
  it('keeps the final catch-all route outside the panel catalog', () => {
    const catchAll = routes.at(-1)

    expect(catchAll.path).toBe('/:pathMatch(.*)*')
    expect(catchAll.name).toBeUndefined()
    expect(catchAll.meta.title).toBe('Not Found')
  })

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
      'Transactions',
      'SQL Trace',
      'Spring Data',
      'Flyway',
      'Liquibase',
      'Database Advisor',
      'Spring Security',
      'Security Logs',
      'Scheduled Tasks',
      'REST Client',
      'AI Framework',
      'Cache',
      'Email',
      'Kafka',
      'RabbitMQ',
      'JMS',
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
    const features = readRepositoryFile('docs/FEATURES.md')

    for (const panel of parseBackendPanels()) {
      const headingLevel = panel.title === 'Overview' ? '##' : '###'
      const escapedTitle = panel.title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      const headingPattern = new RegExp(`^${headingLevel} ${escapedTitle}$`, 'm')
      expect(headingPattern.test(features)).toBe(true)
    }
  })

  it('keeps Quarkus availability counts and support-table classifications derived from code', () => {
    const backendPanels = parseBackendPanels()
    const backendIds = new Set(backendPanels.map((panel) => panel.id))
    const titlesById = new Map(backendPanels.map((panel) => [panel.id, panel.title]))
    const availability = parseQuarkusAvailability()
    const conditionalPanels = new Set([...availability.capabilityGated, ...availability.detectorGated])
    const shippedPanels = new Set([...availability.staticPanels, ...conditionalPanels])
    const classifiedPanels = [
      ...availability.staticPanels,
      ...conditionalPanels,
      ...availability.notApplicable,
      ...availability.notYetAvailable
    ]

    expect(sorted(availability.capabilityReasons)).toEqual(sorted(availability.capabilityGated))
    expect(new Set(classifiedPanels).size).toBe(classifiedPanels.length)
    expect(sorted(classifiedPanels)).toEqual(sorted(backendIds))

    const support = readRepositoryFile('docs/QUARKUS-SUPPORT.md')
    const result = support.match(/\*\*Result:\*\*\s*(\d+) of the (\d+) panels ship on Quarkus/)
    const availableCounts = support.match(/(\d+) are statically available and (\d+) are capability\/detector-gated/)
    const unavailableCounts = support.match(
      /remaining (\d+) panels do not ship:\s*(\d+) are intentionally not applicable[\s\S]*?and (\d+) \(`JMS`\) is not yet available/
    )

    expect(result?.slice(1).map(Number)).toEqual([shippedPanels.size, backendIds.size])
    expect(availableCounts?.slice(1).map(Number)).toEqual([availability.staticPanels.size, conditionalPanels.size])
    expect(unavailableCounts?.slice(1).map(Number)).toEqual([
      availability.notApplicable.size + availability.notYetAvailable.size,
      availability.notApplicable.size,
      availability.notYetAvailable.size
    ])

    const table = parseQuarkusSupportTable(support)
    const rowsByTitle = new Map(table.map((row) => [row.title, row]))
    expect(rowsByTitle.size).toBe(table.length)
    expect(sorted(rowsByTitle.keys())).toEqual(sorted(backendPanels.map((panel) => panel.title)))

    const shippedClassifications = new Set(['Port', 'Adapt', 'Rebuild', 'Replace'])
    expect(
      sorted(table.filter((row) => shippedClassifications.has(row.classification)).map((row) => row.title))
    ).toEqual(sorted([...shippedPanels].map((id) => titlesById.get(id))))
    expect(sorted(table.filter((row) => row.classification === 'Drop').map((row) => row.title))).toEqual(
      sorted([...availability.notApplicable].map((id) => titlesById.get(id)))
    )
    expect(sorted(table.filter((row) => row.classification === 'Not yet').map((row) => row.title))).toEqual(
      sorted([...availability.notYetAvailable].map((id) => titlesById.get(id)))
    )

    for (const [classification, heading] of [
      ['Port', /### 5\.1 .+ \((\d+)\)/],
      ['Adapt', /### 5\.2 .+ \((\d+)\)/],
      ['Rebuild', /### 5\.3 .+ \((\d+)\)/],
      ['Replace', /### 5\.4 .+ \((\d+)\)/],
      ['Drop', /### 5\.5 .+ \((\d+)\)/],
      ['Not yet', /### 5\.6 .+ \((\d+)\)/]
    ]) {
      const documentedCount = Number(support.match(heading)?.[1])
      expect(documentedCount, `${classification} heading count`).toBe(
        table.filter((row) => row.classification === classification).length
      )
    }
  })

  it('keeps documented navigation groups aligned with route metadata', () => {
    const expectedByGroup = new Map(
      Object.entries({
        Overview: groups.overview,
        Advisors: groups.advisors,
        Runtime: groups.runtime,
        Configuration: groups.configuration,
        Database: groups.database,
        Security: groups.security,
        Services: groups.services,
        Diagnostics: groups.diagnostics,
        'Developer Tools': groups.developerTools
      }).map(([title, group]) => [
        title,
        namedRoutes.filter((route) => route.meta.group === group).map((route) => route.meta.title)
      ])
    )

    const specification = parseSpecificationGroupInventory(readRepositoryFile('docs/SPECIFICATION.md'))
    for (const [group, expectedTitles] of expectedByGroup) {
      const specificationGroup = group === 'Developer Tools' ? 'Developer tools' : group
      expect(specification.get(specificationGroup), `docs/SPECIFICATION.md ${group}`).toEqual(expectedTitles)
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
