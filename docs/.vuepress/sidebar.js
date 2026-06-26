import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {toDocLink} from './doc-links.js'

const docsRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const hiddenDocs = ['README.md']
const coreDocs = [
  'TRY-SAMPLE-APP.md',
  'SETUP.md',
  'FEATURES.md',
  'PROPERTIES.md',
  'AI-AGENTS.md',
  'WORKS-WITH.md',
  'REPOSITORY.md',
  'SPECIFICATION.md',
  'PLAN.md',
  'QUARKUS-SUPPORT.md'
]
const checkDocs = [
  'ARCHITECTURE-CHECKS.md',
  'REST-API-CHECKS.md',
  'SPRING-CHECKS.md',
  'HIBERNATE-CHECKS.md',
  'SECURITY-CHECKS.md',
  'MEMORY-CHECKS.md',
  'PENTEST-CHECKS.md',
  'GRAALVM-READINESS-CHECKS.md',
  'CRAC-READINESS-CHECKS.md'
]

export function createDocsSidebar() {
  const markdownFiles = fs
    .readdirSync(docsRoot)
    .filter((file) => file.endsWith('.md'))
    .sort((left, right) => left.localeCompare(right))

  const remainingDocs = markdownFiles.filter(
    (file) => !hiddenDocs.includes(file) && !coreDocs.includes(file) && !checkDocs.includes(file)
  )

  return [
    {
      text: 'Project documentation',
      collapsible: true,
      children: coreDocs.filter((file) => markdownFiles.includes(file)).map(toSidebarItem)
    },
    {
      text: 'Diagnostic checks',
      collapsible: true,
      children: checkDocs.filter((file) => markdownFiles.includes(file)).map(toDiagnosticSidebarItem)
    },
    ...(remainingDocs.length
      ? [
          {
            text: 'Additional docs',
            collapsible: true,
            children: remainingDocs.map(toSidebarItem)
          }
        ]
      : [])
  ]
}

function toSidebarItem(file) {
  return {
    text: readTitle(file),
    link: toDocLink(file)
  }
}

function toDiagnosticSidebarItem(file) {
  const item = toSidebarItem(file)
  return {
    ...item,
    text: item.text.replace(/\s+checks$/i, '')
  }
}

function readTitle(file) {
  const content = fs.readFileSync(path.join(docsRoot, file), 'utf8')
  const heading = content.match(/^#\s+(.+)$/m)
  return heading ? heading[1].trim() : file.replace(/\.md$/, '').replaceAll('-', ' ')
}
