import {viteBundler} from '@vuepress/bundler-vite'
import {defaultTheme} from '@vuepress/theme-default'
import {defineUserConfig} from 'vuepress'
import {createDocsSidebar} from './sidebar.js'

const siteBase = normalizeBase(process.env.VUEPRESS_BASE || (process.argv.includes('dev') ? '/' : '/boot-ui/'))

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
  theme: defaultTheme({
    repo: 'jdubois/boot-ui',
    docsRepo: 'https://github.com/jdubois/boot-ui',
    docsBranch: 'main',
    docsDir: 'docs',
    editLink: true,
    lastUpdated: true,
    contributors: false,
    logo: null,
    navbar: [
      {text: 'Try sample app', link: '/TRY-SAMPLE-APP.html'},
      {text: 'Setup', link: '/SETUP.html'},
      {text: 'Features', link: '/FEATURES.html'},
      {text: 'Properties', link: '/PROPERTIES.html'},
      {text: 'Specification', link: '/SPECIFICATION.html'},
      {text: 'Roadmap', link: '/PLAN.html'}
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
