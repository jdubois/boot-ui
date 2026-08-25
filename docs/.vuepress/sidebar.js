import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {toDocLink} from './doc-links.js'

const docsRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const hiddenDocs = ['README.md']

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
    docs: ['TRY-SAMPLE-APP.md', 'SETUP.md']
  },
  {
    text: 'Features',
    docs: featureDocs
  },
  {
    text: 'Reference',
    docs: ['PROPERTIES.md', 'AI-AGENTS.md', 'WORKS-WITH.md']
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
      'JVM-TUNING-CHECKS.md',
      'PENTEST-CHECKS.md',
      'GRAALVM-READINESS-CHECKS.md',
      'CRAC-READINESS-CHECKS.md',
      'QUARKUS-ADVISOR-CHECKS.md',
      'QUARKUS-CHECKS.md'
    ]
  },
  {
    text: 'Framework support',
    docs: ['QUARKUS-SUPPORT.md', 'WEBFLUX-SUPPORT.md']
  },
  {
    text: 'Contributing',
    collapsed: true,
    docs: ['REPOSITORY.md', 'SPECIFICATION.md', 'PLAN.md']
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
      : [])
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
  const text = readTitle(file)
  return {
    text: trimChecksSuffix ? text.replace(/\s+checks$/i, '') : text,
    link: toDocLink(file)
  }
}

function readTitle(file) {
  const content = fs.readFileSync(path.join(docsRoot, file), 'utf8')
  const heading = content.match(/^#\s+(.+)$/m)
  return heading ? heading[1].trim() : file.replace(/\.md$/, '').replaceAll('-', ' ')
}
