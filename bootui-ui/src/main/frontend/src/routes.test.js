import {describe, expect, it} from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {groups, routes} from './routes.js'

const namedRoutes = routes.filter((route) => route.name)
const repoRoot = findRepositoryRoot(path.dirname(fileURLToPath(import.meta.url)))
const catalog = JSON.parse(
  fs.readFileSync(
    path.join(
      repoRoot,
      'bootui-conformance/src/main/resources/io/github/jdubois/bootui/conformance/panel-catalog.json'
    ),
    'utf8'
  )
)

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
    const apiPrefixes = [...match[4].matchAll(/"([^"]+)"/g)].map((prefixMatch) => prefixMatch[1])
    return {
      id,
      title: match[2],
      actionCapable: match[3] === 'true',
      apiPrefixes
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

function asPanelMetadataById(panels) {
  return Object.fromEntries(panels.map(({id, title, actionCapable}) => [id, {title, actionCapable}]))
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
  it('keeps the sidebar catalog aligned with the shared panel catalog', () => {
    expect(namedRoutes.map((route) => route.name)).toEqual(catalog.panels.map((panel) => panel.id))
    expect(namedRoutes.map((route) => route.meta.title)).toEqual(catalog.panels.map((panel) => panel.title))
    expect(namedRoutes.map((route) => route.meta.group)).toEqual(catalog.panels.map((panel) => panel.group))
  })

  it('keeps backend panel metadata aligned with the shared panel catalog', () => {
    const backendPanels = parseBackendPanels()

    expect(backendPanels.map((panel) => panel.id)).toEqual(
      expect.arrayContaining(catalog.panels.map((panel) => panel.id))
    )
    expect(backendPanels).toHaveLength(catalog.panels.length)
    expect(asPanelMetadataById(backendPanels)).toEqual(asPanelMetadataById(catalog.panels))

    for (const panel of backendPanels.filter((entry) => entry.actionCapable)) {
      expect(panel.apiPrefixes.length).toBeGreaterThan(0)
    }
  })

  it('keeps conformance manifests aligned with the shared panel catalog', () => {
    for (const manifestFile of [
      'expected-panels-spring.json',
      'expected-panels-quarkus.json',
      'expected-panels-webflux.json'
    ]) {
      const manifest = loadManifest(manifestFile)
      expect(manifest.panels).toHaveLength(catalog.panels.length)
      expect(manifest.panels.map((panel) => panel.id)).toEqual(
        expect.arrayContaining(catalog.panels.map((panel) => panel.id))
      )
      expect(asPanelMetadataById(manifest.panels)).toEqual(asPanelMetadataById(catalog.panels))
    }
  })

  it('documents every panel from the shared panel catalog in docs/FEATURES.md', () => {
    const features = fs.readFileSync(path.join(repoRoot, 'docs/FEATURES.md'), 'utf8')

    for (const panel of catalog.panels) {
      const headingLevel = panel.title === 'Overview' ? '##' : '###'
      const escapedTitle = panel.title.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      const headingPattern = new RegExp(`^${headingLevel} ${escapedTitle}$`, 'm')
      expect(headingPattern.test(features)).toBe(true)
    }
  })

  it('defines complete and unique sidebar metadata for every navigable route', () => {
    expect(Object.values(groups)).toEqual(catalog.groups)
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
