import {defineConfig} from 'vitepress'

// BootUI documentation website.
// Published as a GitHub Pages project site at https://jdubois.github.io/boot-ui/,
// so the base path must match the repository name.
export default defineConfig({
  title: 'BootUI',
  description:
    'BootUI is a Spring Boot 4 starter that adds an embedded, local-only developer console to your application.',
  lang: 'en-US',
  base: '/boot-ui/',
  lastUpdated: true,
  cleanUrls: true,
  // The local console runs at http://localhost:8080/bootui; it is intentionally
  // referenced in setup instructions and is not reachable at build time.
  ignoreDeadLinks: [/^https?:\/\/localhost/],
  // The website README documents how to run the site; it is not a published page.
  srcExclude: ['README.md'],
  head: [
    ['link', {rel: 'icon', href: '/boot-ui/favicon.svg', type: 'image/svg+xml'}],
    ['meta', {name: 'theme-color', content: '#198754'}],
    ['meta', {property: 'og:type', content: 'website'}],
    ['meta', {property: 'og:title', content: 'BootUI — local developer console for Spring Boot'}],
    [
      'meta',
      {
        property: 'og:description',
        content:
          'An embedded, local-only developer console for Spring Boot 4 applications. No Node.js, no external service.'
      }
    ]
  ],
  themeConfig: {
    logo: '/logo.svg',
    siteTitle: 'BootUI',
    nav: [
      {text: 'Home', link: '/'},
      {text: 'Getting started', link: '/guide/getting-started'},
      {text: 'Features', link: '/guide/features'},
      {text: 'Configuration', link: '/guide/configuration'},
      {
        text: 'v0.1',
        items: [
          {text: 'Changelog', link: 'https://github.com/jdubois/boot-ui/blob/main/CHANGELOG.md'},
          {text: 'Contributing', link: 'https://github.com/jdubois/boot-ui/blob/main/CONTRIBUTING.md'},
          {text: 'Security', link: 'https://github.com/jdubois/boot-ui/blob/main/SECURITY.md'}
        ]
      }
    ],
    sidebar: {
      '/guide/': [
        {
          text: 'Introduction',
          items: [
            {text: 'What is BootUI?', link: '/guide/introduction'},
            {text: 'Getting started', link: '/guide/getting-started'}
          ]
        },
        {
          text: 'Reference',
          items: [
            {text: 'Features', link: '/guide/features'},
            {text: 'Configuration & safety', link: '/guide/configuration'},
            {text: 'Property reference', link: '/guide/properties'},
            {text: 'Troubleshooting', link: '/guide/troubleshooting'}
          ]
        }
      ]
    },
    socialLinks: [{icon: 'github', link: 'https://github.com/jdubois/boot-ui'}],
    search: {
      provider: 'local'
    },
    editLink: {
      pattern: 'https://github.com/jdubois/boot-ui/edit/main/website/:path',
      text: 'Edit this page on GitHub'
    },
    footer: {
      message: 'Released under the Apache License 2.0.',
      copyright:
        'BootUI · embedded in your Spring Boot app · no external service required'
    }
  }
})
