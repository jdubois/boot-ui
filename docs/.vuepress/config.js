import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {viteBundler} from '@vuepress/bundler-vite'
import {slimsearchPlugin} from '@vuepress/plugin-slimsearch'
import {defaultTheme} from '@vuepress/theme-default'
import {defineUserConfig} from 'vuepress'
import {inferRoutePath} from 'vuepress/shared'
import {toDocLink} from './doc-links.js'
import {parseRuleCatalog} from './rule-catalog.js'
import {createDocsSidebar} from './sidebar.js'

const siteBase = normalizeBase(process.env.VUEPRESS_BASE || (process.argv.includes('dev') ? '/' : '/boot-ui/'))
const publicSiteUrl = 'https://www.julien-dubois.com/boot-ui'
const configDir = path.dirname(fileURLToPath(import.meta.url))

export default defineUserConfig({
  base: siteBase,
  // The JVM Tuning panel is a memory-budget calculator, not a rule catalog, so its design record has
  // no checks to publish alongside the other catalogs. It stays in the repository for contributors
  // and the Runtime page links to it there.
  pagePatterns: ['**/*.md', '!JVM-TUNING-CHECKS.md', '!.vuepress', '!node_modules'],
  lang: 'en-US',
  title: 'BootUI',
  description: 'A local-only developer console for Spring Boot 4 and Quarkus applications.',
  head: [
    ['link', {rel: 'icon', type: 'image/svg+xml', href: `${siteBase}favicon.svg`}],
    ['meta', {name: 'theme-color', content: '#198754'}],
    ['meta', {property: 'og:type', content: 'website'}],
    ['meta', {property: 'og:title', content: 'BootUI'}],
    [
      'meta',
      {
        property: 'og:description',
        content: 'A local-only developer console for Spring Boot 4 and Quarkus applications.'
      }
    ]
  ],
  bundler: viteBundler(),
  // The default theme renders home feature cards as plain divs, so swap in a version that can
  // link each card to the page it describes.
  alias: {
    '@theme/VPHomeFeatures.vue': path.resolve(configDir, './components/HomeFeatures.vue')
  },
  plugins: [
    cleanDocsPermalinksPlugin(),
    cleanMarkdownDocLinksPlugin(),
    lazyLoadMarkdownImagesPlugin(),
    ruleCatalogPlugin(),
    slimsearchPlugin({
      indexContent: true,
      suggestion: true,
      customFields: [
        {
          getter: (page) => collectRuleIds(page),
          formatter: 'Check: $content'
        }
      ]
    })
  ],
  theme: defaultTheme({
    hostname: 'https://www.julien-dubois.com',
    themePlugins: {
      seo: {
        canonical: toCanonicalUrl
      }
    },
    repo: 'jdubois/boot-ui',
    editLink: false,
    lastUpdated: false,
    contributors: false,
    logo: null,
    navbar: [
      {text: 'Try it', link: toDocLink('TRY-SAMPLE-APP.md')},
      {text: 'Setup', link: toDocLink('SETUP.md')},
      {text: 'Features', link: toDocLink('features/README.md')},
      {text: 'Properties', link: toDocLink('PROPERTIES.md')},
      {text: 'AI agents', link: toDocLink('AI-AGENTS.md')},
      {text: 'Ecosystem', link: toDocLink('WORKS-WITH.md')}
    ],
    sidebar: createDocsSidebar(),
    sidebarDepth: 2
  })
})

function collectRuleIds(page) {
  const matches = page.content.matchAll(/^###\s+([A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+)\s+[-–]\s+(.+)$/gm)
  return [...matches].map(([, id, title]) => `${id} ${title}`)
}

function normalizeBase(value) {
  if (!value || value === '/') {
    return '/'
  }
  const prefixed = value.startsWith('/') ? value : `/${value}`
  return prefixed.endsWith('/') ? prefixed : `${prefixed}/`
}

function toCanonicalUrl(page) {
  return page.path === '/' ? `${publicSiteUrl}/` : `${publicSiteUrl}${page.path}`
}

function cleanDocsPermalinksPlugin() {
  return {
    name: 'bootui-clean-docs-permalinks',
    extendsPageOptions(options, app) {
      if (!options.filePath) {
        return
      }

      const filePathRelative = path.relative(app.dir.source(), options.filePath)
      if (filePathRelative.startsWith('..') || !filePathRelative.endsWith('.md')) {
        return
      }

      options.frontmatter = {
        permalink: toDocLink(filePathRelative),
        ...options.frontmatter
      }
    },
    extendsPage(page) {
      if (!page.filePathRelative?.endsWith('.md')) {
        return
      }

      const cleanPath = toDocLink(page.filePathRelative)
      if (cleanPath !== '/') {
        page.pathInferred = `${cleanPath}.html`
      }
    }
  }
}

function lazyLoadMarkdownImagesPlugin() {
  return {
    name: 'bootui-lazy-load-markdown-images',
    extendsMarkdown(markdown) {
      const rawImageRule =
        markdown.renderer.rules.image ??
        ((tokens, index, options, _env, self) => self.renderToken(tokens, index, options))

      markdown.renderer.rules.image = (tokens, index, options, env, self) => {
        const token = tokens[index]
        // Each panel now opens with its screenshot, so the first image on a page is the likely
        // largest contentful paint and must not wait for the lazy-load pass.
        const isFirstOnPage = Boolean(env) && !env.bootuiSeenMarkdownImage
        if (env) {
          env.bootuiSeenMarkdownImage = true
        }
        if (token.attrIndex('loading') < 0) {
          token.attrPush(['loading', isFirstOnPage ? 'eager' : 'lazy'])
        }
        if (token.attrIndex('decoding') < 0) {
          token.attrPush(['decoding', 'async'])
        }
        if (isFirstOnPage && token.attrIndex('fetchpriority') < 0) {
          token.attrPush(['fetchpriority', 'high'])
        }
        return rawImageRule(tokens, index, options, env, self)
      }
    }
  }
}

function ruleCatalogPlugin() {
  return {
    name: 'bootui-rule-catalog',
    extendsPage(page) {
      const rules = parseRuleCatalog(page.content ?? '')
      if (rules.length === 0) {
        return
      }
      page.data.ruleCatalog = rules
    },
    extendsPageOptions(options) {
      const filePath = options.filePath ?? ''
      if (!/-CHECKS\.md$/.test(filePath)) {
        return
      }
      const content = options.content ?? fs.readFileSync(filePath, 'utf8')
      if (parseRuleCatalog(content).length === 0) {
        return
      }
      options.content = injectRuleIndex(content)
    }
  }
}

// Places the filter directly above the first rule group, so the page intro still reads as prose.
function injectRuleIndex(content) {
  const lines = content.split('\n')
  const firstRule = lines.findIndex((line) => /^### [A-Z][A-Z0-9]*(-[A-Z0-9]+)+\b/.test(line))
  if (firstRule < 0) {
    return content
  }
  let insertAt = firstRule
  for (let index = firstRule - 1; index >= 0; index -= 1) {
    if (/^## /.test(lines[index])) {
      insertAt = index
      break
    }
  }
  lines.splice(insertAt, 0, '<RuleIndex />', '')
  return lines.join('\n')
}

function cleanMarkdownDocLinksPlugin() {
  return {
    name: 'bootui-clean-markdown-doc-links',
    extendsMarkdown(markdown, app) {
      const cleanRouteByInferredRoute = createCleanRouteByInferredRoute(app.dir.source())
      const rawLinkOpenRule =
        markdown.renderer.rules.link_open ??
        ((tokens, index, options, _env, self) => self.renderToken(tokens, index, options))

      markdown.renderer.rules.link_open = (tokens, index, options, env, self) => {
        rawLinkOpenRule(tokens, index, options, env, self)
        rewriteMarkdownDocLink(tokens[index], cleanRouteByInferredRoute)
        return self.renderToken(tokens, index, options)
      }
    }
  }
}

function createCleanRouteByInferredRoute(docsRoot) {
  return listMarkdownFiles(docsRoot).reduce((cleanRouteByInferredRoute, file) => {
    const normalizedFile = file.replaceAll(path.sep, '/')
    cleanRouteByInferredRoute.set(inferRoutePath(`/${normalizedFile}`).toLowerCase(), toDocLink(normalizedFile))
    return cleanRouteByInferredRoute
  }, new Map())
}

function listMarkdownFiles(root, directory = '') {
  return fs.readdirSync(path.join(root, directory), {withFileTypes: true}).flatMap((entry) => {
    if (entry.name === '.vuepress') {
      return []
    }

    const relativePath = path.join(directory, entry.name)
    if (entry.isDirectory()) {
      return listMarkdownFiles(root, relativePath)
    }

    return entry.isFile() && entry.name.endsWith('.md') ? [relativePath] : []
  })
}

function rewriteMarkdownDocLink(token, cleanRouteByInferredRoute) {
  const routeAttrIndex = token.attrIndex('to')
  const hrefAttrIndex = token.attrIndex('href')
  const attrIndex = routeAttrIndex >= 0 ? routeAttrIndex : hrefAttrIndex

  if (attrIndex < 0) {
    return
  }

  const attr = token.attrs[attrIndex]
  const cleanRoute = toCleanMarkdownDocRoute(attr[1], cleanRouteByInferredRoute)
  if (cleanRoute) {
    attr[1] = cleanRoute
  }
}

function toCleanMarkdownDocRoute(route, cleanRouteByInferredRoute) {
  const match = route.match(/^([^#?]*)([#?].*)?$/)
  if (!match) {
    return null
  }

  const [, pathname, hashAndQuery = ''] = match
  const cleanRoute = cleanRouteByInferredRoute.get(pathname.toLowerCase())
  return cleanRoute ? `${cleanRoute}${hashAndQuery}` : null
}
