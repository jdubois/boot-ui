import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {parse as parseTemplate} from '@vue/compiler-dom'
import {parse as parseSfc} from '@vue/compiler-sfc'
import {describe, expect, it} from 'vitest'

const sourceRoot = path.dirname(fileURLToPath(import.meta.url))
const formControlTags = new Set(['input', 'select', 'textarea'])
const nativeInteractiveTags = new Set(['a', 'button', 'router-link', 'summary'])
const staticallyEmptyExpressions = new Set(["''", '""', '``', 'null', 'undefined', 'false'])

function vueFiles(directory) {
  return fs.readdirSync(directory, {withFileTypes: true}).flatMap((entry) => {
    const entryPath = path.join(directory, entry.name)
    if (entry.isDirectory()) {
      return vueFiles(entryPath)
    }
    return entry.name.endsWith('.vue') ? [entryPath] : []
  })
}

function attributeKey(node, name) {
  for (const property of node.props) {
    if (property.type === 6 && property.name === name && property.value?.content) {
      return `static:${property.value.content}`
    }
    if (
      property.type === 7 &&
      property.name === 'bind' &&
      property.arg?.type === 4 &&
      property.arg.content === name &&
      property.exp?.type === 4 &&
      expressionCanProvideText(property.exp.content)
    ) {
      return `dynamic:${property.exp.content}`
    }
  }
  return null
}

function expressionCanProvideText(expression) {
  const normalized = expression?.trim()
  return Boolean(normalized) && !staticallyEmptyExpressions.has(normalized)
}

function hasNonEmptyAttribute(node, name) {
  return node.props.some(
    (property) =>
      (property.type === 6 && property.name === name && property.value?.content.trim()) ||
      (property.type === 7 &&
        property.name === 'bind' &&
        property.arg?.type === 4 &&
        property.arg.content === name &&
        expressionCanProvideText(property.exp?.content))
  )
}

function staticAttribute(node, name) {
  return node.props.find((property) => property.type === 6 && property.name === name)?.value?.content
}

function modifierNames(directive) {
  return directive.modifiers.map((modifier) => modifier.content ?? modifier)
}

function eventDirective(node, eventName, modifier) {
  return node.props.find(
    (property) =>
      property.type === 7 &&
      property.name === 'on' &&
      property.arg?.type === 4 &&
      property.arg.content === eventName &&
      (!modifier || modifierNames(property).includes(modifier))
  )
}

function hasButtonRole(node) {
  if (staticAttribute(node, 'role') === 'button') {
    return true
  }
  const dynamicRole = node.props.find(
    (property) =>
      property.type === 7 && property.name === 'bind' && property.arg?.type === 4 && property.arg.content === 'role'
  )
  return /['"]button['"]/.test(dynamicRole?.exp?.content ?? '')
}

function hasKeyboardReachableTabindex(node) {
  const tabindex = node.props.find(
    (property) =>
      (property.type === 6 && property.name === 'tabindex') ||
      (property.type === 7 &&
        property.name === 'bind' &&
        property.arg?.type === 4 &&
        property.arg.content === 'tabindex')
  )
  if (!tabindex) {
    return false
  }
  if (tabindex.type === 6) {
    return /^\d+$/.test(tabindex.value?.content.trim() ?? '')
  }
  const expression = tabindex.exp?.content.trim()
  return expressionCanProvideText(expression) && !/^(?:['"`])?-\d+(?:['"`])?$/.test(expression)
}

function hasKeyboardClickEquivalent(node) {
  const enter = eventDirective(node, 'keydown', 'enter')
  const space = eventDirective(node, 'keydown', 'space')
  return (
    hasButtonRole(node) &&
    hasAccessibleText(node) &&
    hasKeyboardReachableTabindex(node) &&
    enter &&
    space &&
    modifierNames(space).includes('prevent')
  )
}

function hasNativeInteractiveDescendant(node, delegatedExpression) {
  return node.children.some(
    (child) =>
      child.type === 1 &&
      ((nativeInteractiveTags.has(child.tag) &&
        hasAccessibleText(child) &&
        eventDirective(child, 'click')?.exp?.content.trim() === delegatedExpression) ||
        hasNativeInteractiveDescendant(child, delegatedExpression))
  )
}

function hasKeyboardDelegate(node) {
  const delegatedExpression = staticAttribute(node, 'data-keyboard-delegate')?.trim()
  return Boolean(delegatedExpression) && hasNativeInteractiveDescendant(node, delegatedExpression)
}

function hasAccessibleText(node) {
  if (node.type === 2) {
    return Boolean(node.content.trim())
  }
  if (node.type === 5) {
    return expressionCanProvideText(node.content.content)
  }
  if (node.type !== 1 || staticAttribute(node, 'aria-hidden') === 'true') {
    return false
  }
  if (hasNonEmptyAttribute(node, 'aria-label')) {
    return true
  }
  return node.children.some(hasAccessibleText)
}

function inspectTemplate(template, lineOffset = 0) {
  const labels = []
  const controls = []
  const elements = []
  const ast = parseTemplate(template)

  function visit(node, ancestors = []) {
    if (node.type === 1) {
      elements.push(node)
      if (node.tag === 'label') {
        labels.push(node)
      }
      if (formControlTags.has(node.tag)) {
        controls.push({node, ancestors})
      }
      node.children.forEach((child) => visit(child, [...ancestors, node]))
      return
    }
    node.children?.forEach((child) => visit(child, ancestors))
  }

  visit(ast)
  const elementsById = Map.groupBy(
    elements.filter((element) => attributeKey(element, 'id')),
    (element) => attributeKey(element, 'id')
  )
  const labelTargets = new Set(
    labels
      .filter(hasAccessibleText)
      .map((label) => attributeKey(label, 'for'))
      .filter(Boolean)
  )
  const duplicateIds = [...elementsById]
    .filter(([, matchingElements]) => matchingElements.length > 1)
    .map(([id, matchingElements]) => `line ${lineOffset + matchingElements[0].loc.start.line}: duplicate id ${id}`)

  function hasValidLabelledBy(node) {
    const labelledBy = node.props.find(
      (property) =>
        (property.type === 6 && property.name === 'aria-labelledby') ||
        (property.type === 7 &&
          property.name === 'bind' &&
          property.arg?.type === 4 &&
          property.arg.content === 'aria-labelledby')
    )
    if (!labelledBy) {
      return false
    }
    const targetIds =
      labelledBy.type === 6
        ? (labelledBy.value?.content.split(/\s+/).filter(Boolean) ?? []).map((id) => `static:${id}`)
        : [attributeKey(node, 'aria-labelledby')].filter(Boolean)
    return targetIds.some((id) => elementsById.get(id)?.some(hasAccessibleText))
  }

  const unnamedControls = controls
    .filter(({node}) => !(node.tag === 'input' && staticAttribute(node, 'type') === 'hidden'))
    .filter(({node, ancestors}) => {
      const id = attributeKey(node, 'id')
      return !(
        hasNonEmptyAttribute(node, 'aria-label') ||
        hasValidLabelledBy(node) ||
        ancestors.some((ancestor) => ancestor.tag === 'label' && hasAccessibleText(ancestor)) ||
        (id && labelTargets.has(id))
      )
    })
    .map(({node}) => `line ${lineOffset + node.loc.start.line}: unnamed <${node.tag}>`)

  const inaccessibleClickTargets = elements
    .filter((node) => {
      const click = eventDirective(node, 'click')
      if (!click?.exp || node.tagType !== 0 || nativeInteractiveTags.has(node.tag)) {
        return false
      }
      if (
        modifierNames(click).includes('self') ||
        staticAttribute(node, 'aria-hidden') === 'true' ||
        staticAttribute(node, 'role') === 'option' ||
        hasKeyboardDelegate(node) ||
        (node.tag === 'dialog' && (eventDirective(node, 'keydown', 'esc') || eventDirective(node, 'cancel')))
      ) {
        return false
      }
      return !hasKeyboardClickEquivalent(node)
    })
    .map((node) => `line ${lineOffset + node.loc.start.line}: keyboard-inaccessible click target <${node.tag}>`)

  return [...duplicateIds, ...unnamedControls, ...inaccessibleClickTargets]
}

describe('frontend accessibility', () => {
  it('gives every native form control an accessible name', () => {
    const unnamedControls = vueFiles(sourceRoot).flatMap((file) => {
      const {descriptor} = parseSfc(fs.readFileSync(file, 'utf8'), {filename: file})
      if (!descriptor.template) {
        return []
      }
      return inspectTemplate(descriptor.template.content, descriptor.template.loc.start.line - 1).map(
        (issue) => `${path.relative(sourceRoot, file)}:${issue}`
      )
    })

    expect(unnamedControls).toEqual([])
  }, 20_000)

  it.each([
    ['placeholder-only controls', '<input placeholder="Filter items">'],
    ['empty labels', '<label for="filter"></label><input id="filter">'],
    [
      'broken aria-labelledby references',
      '<span id="filter-label">Filter items</span><input aria-labelledby="missing-label">'
    ],
    ['statically empty dynamic aria-labels', `<input :aria-label="''">`],
    ['statically empty interpolated labels', `<label for="filter">{{ '' }}</label><input id="filter">`],
    [
      'statically empty dynamic aria-labelledby references',
      `<span :id="''">Filter items</span><input :aria-labelledby="''">`
    ]
  ])('rejects %s', (_description, template) => {
    expect(inspectTemplate(template)).toHaveLength(1)
  })

  it('rejects duplicate ids', () => {
    expect(
      inspectTemplate('<label for="filter">Filter</label><input id="filter"><span id="filter">Help</span>')
    ).toEqual(['line 1: duplicate id static:filter'])
  })

  it('accepts matching dynamic label associations', () => {
    expect(inspectTemplate('<label :for="inputId">Auto-refresh</label><input :id="inputId">')).toEqual([])
  })

  it('rejects non-semantic click targets without complete keyboard support', () => {
    expect(inspectTemplate('<div @click="select"></div>')).toEqual(['line 1: keyboard-inaccessible click target <div>'])
    expect(
      inspectTemplate('<tr role="button" tabindex="0" @click="select" @keydown.enter="select"></tr>')
    ).toHaveLength(1)
    expect(
      inspectTemplate(
        '<tr role="button" tabindex="0" @click="select" @keydown.enter="select" @keydown.space="select"></tr>'
      )
    ).toHaveLength(1)
    expect(
      inspectTemplate(
        '<div role="button" tabindex="0" @click="select" @keydown.enter="select" @keydown.space.prevent="select"></div>'
      )
    ).toHaveLength(1)
    expect(
      inspectTemplate(
        '<div role="button" tabindex="-1" @click="select" @keydown.enter="select" @keydown.space.prevent="select">Select</div>'
      )
    ).toHaveLength(1)
    expect(
      inspectTemplate(
        '<div role="button" tabindex @click="select" @keydown.enter="select" @keydown.space.prevent="select">Select</div>'
      )
    ).toHaveLength(1)
    expect(
      inspectTemplate('<tr data-keyboard-delegate="select" @click="select"><td>Missing action</td></tr>')
    ).toHaveLength(1)
    expect(
      inspectTemplate(
        '<tr data-keyboard-delegate="select" @click="select"><td><button type="button">Select</button></td></tr>'
      )
    ).toHaveLength(1)
    expect(
      inspectTemplate(
        '<tr data-keyboard-delegate="select" @click="select"><td><button type="button" @click.stop="help">Help</button></td></tr>'
      )
    ).toHaveLength(1)
  })

  it('accepts native controls and complete keyboard equivalents', () => {
    expect(inspectTemplate('<button type="button" @click="select">Select</button>')).toEqual([])
    expect(
      inspectTemplate(
        '<tr role="button" tabindex="0" aria-label="Select row" @click="select" @keydown.enter="select" @keydown.space.prevent="select"></tr>'
      )
    ).toEqual([])
    expect(
      inspectTemplate(
        '<div role="button" :tabindex="enabled ? 0 : undefined" @click="select" @keydown.enter="select" @keydown.space.prevent="select">Select</div>'
      )
    ).toEqual([])
    expect(
      inspectTemplate(
        '<tr data-keyboard-delegate="select" @click="select"><td><button type="button" @click.stop="select">Select</button></td></tr>'
      )
    ).toEqual([])
  })
})
