import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {parse as parseTemplate} from '@vue/compiler-dom'
import {parse as parseSfc} from '@vue/compiler-sfc'
import {describe, expect, it} from 'vitest'

const sourceRoot = path.dirname(fileURLToPath(import.meta.url))
const viewsRoot = path.join(sourceRoot, 'views')
const appSource = fs.readFileSync(path.join(sourceRoot, 'App.vue'), 'utf8')

function vueFiles(directory) {
  return fs.readdirSync(directory, {withFileTypes: true}).flatMap((entry) => {
    const entryPath = path.join(directory, entry.name)
    if (entry.isDirectory()) return vueFiles(entryPath)
    return entry.name.endsWith('.vue') ? [entryPath] : []
  })
}

function descriptorFor(file) {
  return parseSfc(fs.readFileSync(file, 'utf8'), {filename: file}).descriptor
}

function relative(file) {
  return path.relative(sourceRoot, file)
}

function staticClasses(node) {
  const classAttribute = node.props?.find((property) => property.type === 6 && property.name === 'class')
  return new Set(classAttribute?.value?.content.split(/\s+/).filter(Boolean) ?? [])
}

function walk(template, visit, lineOffset = 0) {
  const ast = parseTemplate(template)

  function descend(node, ancestors) {
    if (node.type === 1) {
      visit(node, ancestors, lineOffset + node.loc.start.line)
      node.children.forEach((child) => descend(child, [...ancestors, node]))
      return
    }
    node.children?.forEach((child) => descend(child, ancestors))
  }

  descend(ast, [])
}

describe('BootUI design system contracts', () => {
  // DESIGN.md "The Eyebrow-Containment Rule": the uppercase tracked label is a
  // sidebar nav-group affordance. Anywhere else it is the AI-SaaS-slop tell the
  // system explicitly rejects. Status badges may still normalise a machine-supplied
  // severity token such as "critical" to "CRITICAL".
  it('keeps uppercase tracked labels out of panel content', () => {
    const issues = []

    for (const file of vueFiles(viewsRoot)) {
      const {descriptor} = {descriptor: descriptorFor(file)}

      if (descriptor.template) {
        walk(
          descriptor.template.content,
          (node, _ancestors, line) => {
            const classes = staticClasses(node)
            if (classes.has('text-uppercase') && !classes.has('badge')) {
              issues.push(`${relative(file)}:${line}: content eyebrow via .text-uppercase`)
            }
          },
          descriptor.template.loc.start.line - 1
        )
      }

      const styles = descriptor.styles.map((style) => style.content).join('\n')
      for (const [, selector] of styles.matchAll(/([^{}]+)\{[^{}]*text-transform:\s*uppercase[^{}]*\}/g)) {
        const name = selector.trim().split('\n').pop().trim()
        // The command palette is the keyboard twin of the sidebar rail, so its
        // nav-group headers carry the same documented label treatment.
        if (name === '.cp-section-label') continue
        issues.push(`${relative(file)}: content eyebrow via text-transform: uppercase on ${name}`)
      }
    }

    expect(issues).toEqual([])
  })

  // DESIGN.md "Cards / Containers": cards never nest. The health tree is recursive,
  // so this also guards against a component re-entering itself as a card.
  it('never nests a card inside another card', () => {
    const issues = []

    for (const file of vueFiles(viewsRoot)) {
      const descriptor = descriptorFor(file)
      if (!descriptor.template) continue

      walk(
        descriptor.template.content,
        (node, ancestors, line) => {
          if (!staticClasses(node).has('card')) return
          if (ancestors.some((ancestor) => staticClasses(ancestor).has('card'))) {
            issues.push(`${relative(file)}:${line}: card nested inside another card`)
          }
        },
        descriptor.template.loc.start.line - 1
      )
    }

    expect(issues).toEqual([])
  })

  // DESIGN.md "Buttons": the primary action is solid Spring green. Bootstrap's own
  // blue is the default-admin-template failure state the system rejects.
  it('brands the primary button with Spring green in both themes', () => {
    const primary = appSource.slice(appSource.indexOf(':global(.btn-primary)'))
    const primaryBlock = primary.slice(0, primary.indexOf('}'))

    expect(primaryBlock).toContain('--bs-btn-bg: #198754')
    expect(primaryBlock).toContain('--bs-btn-hover-bg: #146c43')
    expect(appSource).toContain(":global(:root[data-bootui-theme='dark'] .btn-outline-primary)")
  })

  // Bootstrap's `.table-responsive` only sets `overflow-x`. Owning the containment
  // globally keeps a wide table from widening its flex parent or chaining its
  // scroll to the workspace on touch, on every panel rather than the ones that
  // remembered the companion class.
  it('contains table scrolling globally rather than per panel', () => {
    const rule = appSource.slice(appSource.indexOf(':global(.table-responsive)'))
    const block = rule.slice(0, rule.indexOf('}'))

    expect(block).toContain('overscroll-behavior-inline: contain')
    expect(block).toContain('max-width: 100%')
  })

  it('keeps nested technical content legible in selected master-list rows', () => {
    const activeRule = appSource.slice(appSource.indexOf(':global(.list-group-item-action.active)'))
    const activeBlock = activeRule.slice(0, activeRule.indexOf('}'))

    expect(activeBlock).toContain('--bs-list-group-active-bg: #0a53be')
    expect(appSource).toContain(':global(.list-group-item-action.active code)')
    expect(appSource).toContain(':global(.list-group-item-action.active .text-muted)')
  })

  // Every route-level panel must say something on first paint. Panels that own no
  // fetch of their own (static, streaming, or embedded sub-modes) are exempt.
  it('gives every data panel a first-paint loading affordance', () => {
    const exempt = new Set([
      'BeanGraph.vue', // canvas rendered by BeansGraphMode, which owns the state
      'BeansGraphMode.vue', // sub-mode of Beans.vue, which owns the PanelHeader
      'HttpProbe.vue', // request builder: nothing loads until the user probes
      'LiveFlowMode.vue', // sub-mode of Live Activity
      'LogTail.vue', // streams over EventSource and reports its connection state
      'NotFound.vue', // static route
      'Overview.vue' // scanner tiles carry their own idle/scanned state
    ])

    const missing = fs
      .readdirSync(viewsRoot)
      .filter((name) => name.endsWith('.vue') && !exempt.has(name))
      .filter((name) => !fs.readFileSync(path.join(viewsRoot, name), 'utf8').includes('PanelSkeleton'))

    expect(missing).toEqual([])
  })

  // Terminal output stays dark in both themes, but it must do so through the shared
  // token rather than a hard-coded hex repeated in each panel.
  it('routes machine-output panes through the shared code-pane tokens', () => {
    expect(appSource).toContain('--bootui-code-pane-bg: #111827')

    const offenders = vueFiles(viewsRoot).filter((file) =>
      descriptorFor(file)
        .styles.map((style) => style.content)
        .join('\n')
        .includes('#111827')
    )

    expect(offenders.map(relative)).toEqual([])
  })

  // DESIGN.md typography `icon`: 1.5rem is the glyph size of the 3rem round action
  // badge, and every Developer Tools panel renders the same badge. Each panel that
  // copies the literal instead of the token is a panel that can silently drift away
  // from its neighbours, and it re-opens the design finding on every edit.
  it('routes glyph badges through the shared icon-size token', () => {
    expect(appSource).toContain('--bootui-icon-size: 1.5rem')

    const offenders = []

    for (const file of [path.join(sourceRoot, 'App.vue'), ...vueFiles(viewsRoot)]) {
      for (const style of descriptorFor(file).styles) {
        style.content.split('\n').forEach((text, index) => {
          if (/font-size:\s*1\.5rem/.test(text)) {
            offenders.push(`${relative(file)}:${style.loc.start.line + index}`)
          }
        })
      }
    }

    expect(offenders).toEqual([])
  })

  // DESIGN.md "Tabs": every panel tab strip is PanelTabs. Bootstrap's `.nav-tabs`
  // paints unselected tabs in link blue and ignores every skin, and each hand-rolled
  // tablist re-implemented (or forgot) its own keyboard model and focus ring.
  it('routes every panel tab strip through the shared PanelTabs component', () => {
    const offenders = []

    for (const file of vueFiles(viewsRoot)) {
      if (path.basename(file) === 'PanelTabs.vue') continue
      const {template} = descriptorFor(file)
      if (!template) continue
      walk(
        template.content,
        (node, _ancestors, line) => {
          const classes = staticClasses(node)
          const role = node.props?.find((property) => property.type === 6 && property.name === 'role')?.value?.content
          if (
            role === 'tablist' ||
            role === 'tab' ||
            ['nav-tabs', 'nav-pills', 'nav-link'].some((name) => classes.has(name))
          ) {
            offenders.push(`${relative(file)}:${line}`)
          }
        },
        template.loc.start.line - 1
      )
    }

    expect(offenders).toEqual([])
  })
})
