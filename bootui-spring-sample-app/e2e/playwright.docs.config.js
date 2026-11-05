// @ts-check
import {defineConfig, devices} from '@playwright/test'

const port = Number(process.env.BOOTUI_DOCS_PORT || 8091)
const baseURL = `http://127.0.0.1:${port}`

export default defineConfig({
  testDir: './tests-docs',
  forbidOnly: !!process.env.CI,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure'
  },
  projects: [
    {name: 'desktop', use: {...devices['Desktop Chrome']}},
    {name: 'mobile', use: {...devices['iPhone 13'], defaultBrowserType: 'chromium'}}
  ],
  webServer: {
    command:
      'VUEPRESS_BASE=/ npm run docs:build && ' +
      `npm exec -- serve docs/.vuepress/dist --listen tcp://127.0.0.1:${port}`,
    cwd: '../..',
    url: baseURL,
    reuseExistingServer: false,
    timeout: 60_000
  }
})
