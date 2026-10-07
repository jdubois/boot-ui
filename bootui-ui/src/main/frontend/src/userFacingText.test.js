import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {parse as parseTemplate} from '@vue/compiler-dom'
import {babelParse, parse as parseSfc} from '@vue/compiler-sfc'
import {describe, expect, it} from 'vitest'

// Plan identifiers and the external validation (docs/PLAN-v2.md M4-20) stay in the plan, the validation report, and
// code comments: no string the panel can show may name them. Mirrors PLAN_JARGON in the engine's
// UserFacingPlanJargonTests.
const PLAN_JARGON = [
  /PLAN(-v2)?\.md|PLAN-v2|V2-VALIDATION|\bM[0-9]+-[0-9]+\b|\bD[0-9]{2}\b|(?<!RFC [0-9]{1,5} )§\s?[0-9]/,
  /validat\w* (on|against) real applications|external(ly)? validat|validation (application|run)|not (yet )?validated|reviewers?\b|adjudicat|per-kind gate|seeded case|counterexample|judged by|not judged yet|too few facts/i
]

const sourceRoot = path.dirname(fileURLToPath(import.meta.url))

function sources(directory) {
  return fs.readdirSync(directory, {withFileTypes: true}).flatMap((entry) => {
    const file = path.join(directory, entry.name)
    if (entry.isDirectory()) return entry.name === 'generated' ? [] : sources(file)
    return /\.(vue|js)$/.test(entry.name) && !entry.name.endsWith('.test.js') ? [file] : []
  })
}

// Every string literal and template chunk of a script, which is what can reach the screen; comments are left out.
function scriptStrings(code) {
  const strings = []
  const visit = (node) => {
    if (!node || typeof node.type !== 'string') return
    if (node.type === 'StringLiteral') strings.push(node.value)
    if (node.type === 'TemplateElement') strings.push(node.value.cooked ?? node.value.raw)
    for (const [key, value] of Object.entries(node)) {
      if (key === 'loc' || key === 'leadingComments' || key === 'trailingComments' || key === 'innerComments') continue
      if (Array.isArray(value)) value.forEach(visit)
      else if (value && typeof value === 'object') visit(value)
    }
  }
  visit(babelParse(code, {sourceType: 'module'}).program)
  return strings
}

// Every text node, attribute value, and expression of a template; HTML comments are left out.
function templateStrings(template) {
  const strings = []
  const visit = (node) => {
    if (node.type === 2) strings.push(node.content)
    if (node.type === 5) strings.push(node.content.content)
    for (const prop of node.props ?? []) {
      if (prop.type === 6 && prop.value) strings.push(prop.value.content)
      if (prop.type === 7 && prop.exp) strings.push(prop.exp.content)
    }
    ;(node.children ?? []).forEach(visit)
  }
  visit(parseTemplate(template, {comments: false}))
  return strings
}

function userFacingStrings(file) {
  const source = fs.readFileSync(file, 'utf8')
  if (file.endsWith('.js')) return scriptStrings(source)
  const {descriptor} = parseSfc(source, {filename: file})
  return [
    ...(descriptor.template ? templateStrings(descriptor.template.content) : []),
    ...[descriptor.script, descriptor.scriptSetup].filter(Boolean).flatMap((block) => scriptStrings(block.content))
  ]
}

describe('user-facing text', () => {
  it('catches the old validation notes and spares RFC sections', () => {
    const leaks = (text) => PLAN_JARGON.some((pattern) => pattern.test(text))
    expect(leaks('It found nothing on the seven validation applications (M4-20).')).toBe(true)
    expect(leaks('Not externally validated')).toBe(true)
    expect(leaks('Added after the validation run (D36).')).toBe(true)
    expect(leaks('Retry-After (RFC 9110 §10.2.3)')).toBe(false)
  })

  it('never names a plan identifier or the external validation', () => {
    const leaks = sources(sourceRoot).flatMap((file) =>
      userFacingStrings(file)
        .filter((text) => PLAN_JARGON.some((pattern) => pattern.test(text)))
        .map((text) => `${path.relative(sourceRoot, file)}: ${text.trim()}`)
    )
    expect(leaks).toEqual([])
  })
})
