import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {toDocLink} from './doc-links.js'

const docsRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
/* JVM-TUNING-CHECKS.md is excluded from the build in config.js, so it must not fall through to the
   "Additional docs" catch-all group either. PRIVACY.md is a site-legal page pinned to the bottom of
   the sidebar by hand, so it must not be picked up by the catch-all as well. */
const hiddenDocs = ['README.md', 'JVM-TUNING-CHECKS.md', 'PRIVACY.md']

/* Sidebar labels only. Page titles stay long-form; the group heading already supplies the context
   these labels would otherwise repeat. */
const sidebarLabels = {
  'features/README.md': 'All features',
  'setup/webflux.md': 'Spring WebFlux',
  'setup/quarkus.md': 'Quarkus',
  'QUARKUS-SUPPORT.md': 'Quarkus design notes',
  'WEBFLUX-SUPPORT.md': 'WebFlux design notes',
  'SPECIFICATION.md': 'Specification',
  'PLAN.md': 'Implementation plan',
  'PROPERTIES.md': 'Properties',
  'REPOSITORY.md': 'Repository',
  'WORKS-WITH.md': 'BootUI family'
}

const featureDocs = [
  'features/README.md',
  'features/overview.md',
  'features/advisors.md',
  'features/runtime.md',
  'features/configuration.md',
  'features/database.md',
  'features/security.md',
  'features/services.md',
  'features/diagnostics.md',
  'features/developer-tools.md'
]

const groups = [
  {
    text: 'Get started',
    docs: [
      'TRY-SAMPLE-APP.md',
      'SETUP.md',
      'setup/webflux.md',
      'setup/quarkus.md',
      'setup/activation.md',
      'setup/environments.md',
      'setup/troubleshooting.md'
    ]
  },
  {
    text: 'Features',
    docs: featureDocs
  },
  {
    text: 'Reference',
    docs: ['PROPERTIES.md', 'FRAMEWORK-SUPPORT.md', 'AI-AGENTS.md', 'WORKS-WITH.md']
  },
  {
    text: 'Diagnostic checks',
    collapsed: true,
    trimChecksSuffix: true,
    docs: [
      'ARCHITECTURE-CHECKS.md',
      'REST-API-CHECKS.md',
      'SPRING-CHECKS.md',
      'HIBERNATE-CHECKS.md',
      'DATABASE-ADVISOR-CHECKS.md',
      'SECURITY-CHECKS.md',
      'MEMORY-CHECKS.md',
      'PENTEST-CHECKS.md',
      'GRAALVM-READINESS-CHECKS.md',
      'CRAC-READINESS-CHECKS.md',
      'QUARKUS-ADVISOR-CHECKS.md',
      'QUARKUS-CHECKS.md'
    ]
  },
  {
    text: 'Contributing',
    collapsed: true,
    docs: ['REPOSITORY.md', 'SPECIFICATION.md', 'PLAN.md', 'QUARKUS-SUPPORT.md', 'WEBFLUX-SUPPORT.md']
  }
]

export function createDocsSidebar() {
  const markdownFiles = listMarkdownFiles(docsRoot)
  const routedDocs = new Set(groups.flatMap((group) => group.docs))
  const remainingDocs = markdownFiles.filter((file) => !hiddenDocs.includes(file) && !routedDocs.has(file))

  return [
    ...groups.map((group) => ({
      text: group.text,
      collapsible: true,
      ...(group.collapsed ? {collapsed: true} : {}),
      children: group.docs
        .filter((file) => markdownFiles.includes(file))
        .map((file) => toSidebarItem(file, group.trimChecksSuffix))
    })),
    ...(remainingDocs.length
      ? [
          {
            text: 'Additional docs',
            collapsible: true,
            children: remainingDocs.map((file) => toSidebarItem(file))
          }
        ]
      : []),
    // Consent stays withdrawable once the banner is gone, so the privacy page keeps a permanent
    // entry point. It sits last, on its own, because it is site-legal rather than documentation.
    toSidebarItem('PRIVACY.md')
  ]
}

function listMarkdownFiles(root, directory = '') {
  return fs
    .readdirSync(path.join(root, directory), {withFileTypes: true})
    .flatMap((entry) => {
      if (entry.name === '.vuepress' || entry.name === 'node_modules') {
        return []
      }

      const relativePath = directory ? `${directory}/${entry.name}` : entry.name
      if (entry.isDirectory()) {
        return listMarkdownFiles(root, relativePath)
      }

      return entry.isFile() && entry.name.endsWith('.md') ? [relativePath] : []
    })
    .sort((left, right) => left.localeCompare(right))
}

function toSidebarItem(file, trimChecksSuffix = false) {
  const text = sidebarLabels[file] ?? readTitle(file)
  return {
    text: trimChecksSuffix ? text.replace(/\s+checks$/i, '') : text,
    link: toDocLink(file)
  }
}

function readTitle(file) {
  const content = fs.readFileSync(path.join(docsRoot, file), 'utf8')
  // Frontmatter comments are also `#`-prefixed lines, so the title has to be looked for after the
  // frontmatter block rather than from the top of the file.
  const heading = stripFrontmatter(content).match(/^#\s+(.+)$/m)
  return heading ? heading[1].trim() : file.replace(/\.md$/, '').replaceAll('-', ' ')
}

function stripFrontmatter(content) {
  const match = content.match(/^---\r?\n[\s\S]*?\r?\n---\r?\n/)
  return match ? content.slice(match[0].length) : content
}
