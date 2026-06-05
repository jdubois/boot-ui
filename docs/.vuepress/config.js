import path from 'node:path'
import {viteBundler} from '@vuepress/bundler-vite'
import {defaultTheme} from '@vuepress/theme-default'
import {defineUserConfig} from 'vuepress'
import {toDocLink} from './doc-links.js'
import {createDocsSidebar} from './sidebar.js'

const siteBase = normalizeBase(process.env.VUEPRESS_BASE || (process.argv.includes('dev') ? '/' : '/boot-ui/'))
const publicSiteUrl = 'https://www.julien-dubois.com/boot-ui'

export default defineUserConfig({
  base: siteBase,
  lang: 'en-US',
  title: 'BootUI',
  description: 'A local-only developer console for Spring Boot 4 applications.',
  head: [
    ['meta', {name: 'theme-color', content: '#198754'}],
    ['meta', {property: 'og:type', content: 'website'}],
    ['meta', {property: 'og:title', content: 'BootUI'}],
    ['meta', {property: 'og:description', content: 'A local-only developer console for Spring Boot 4 applications.'}]
  ],
  bundler: viteBundler(),
  plugins: [cleanDocsPermalinksPlugin()],
  theme: defaultTheme({
    hostname: 'https://www.julien-dubois.com',
    themePlugins: {
      seo: {
        canonical: toCanonicalUrl
      }
    },
    repo: 'jdubois/boot-ui',
    docsRepo: 'https://github.com/jdubois/boot-ui',
    docsBranch: 'main',
    docsDir: 'docs',
    editLink: true,
    lastUpdated: true,
    contributors: false,
    logo: null,
    navbar: [
      {text: 'Try sample app', link: toDocLink('TRY-SAMPLE-APP.md')},
      {text: 'Setup', link: toDocLink('SETUP.md')},
      {text: 'Features', link: toDocLink('FEATURES.md')},
      {text: 'Properties', link: toDocLink('PROPERTIES.md')},
      {text: 'Specification', link: toDocLink('SPECIFICATION.md')},
      {text: 'Roadmap', link: toDocLink('PLAN.md')}
    ],
    sidebar: createDocsSidebar()
  })
})

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
