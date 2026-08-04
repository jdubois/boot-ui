// @ts-check
import {defineConfig, devices} from '@playwright/test'

const MVC_PORT = Number(process.env.BOOTUI_CUSTOM_MVC_PORT || 8083)
const WEBFLUX_PORT = Number(process.env.BOOTUI_CUSTOM_WEBFLUX_PORT || 8084)
const MAVEN_REPO = process.env.BOOTUI_MAVEN_REPO_LOCAL
const REPO_ARG = MAVEN_REPO ? ` -Dmaven.repo.local=${MAVEN_REPO}` : ''
const COMMON_ARGUMENTS =
  '--bootui.path=/dev-console/ --bootui.api-path=/internal/bootui-api/ --bootui.show-banner=false'

export default defineConfig({
  testDir: './tests-custom-path',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  outputDir: 'test-results-custom-path',
  reporter: process.env.CI
    ? [
        ['list'],
        ['html', {open: 'never', outputFolder: 'playwright-report-custom-path'}],
        ['junit', {outputFile: 'test-results-custom-path/junit/results.xml'}]
      ]
    : 'list',
  timeout: 60_000,
  expect: {timeout: 10_000},

  use: {
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    extraHTTPHeaders: {'X-Forwarded-For': '127.0.0.1'}
  },

  projects: [
    {
      name: 'spring-mvc',
      use: {
        ...devices['Desktop Chrome'],
        baseURL: `http://localhost:${MVC_PORT}`
      }
    },
    {
      name: 'spring-webflux',
      use: {
        ...devices['Desktop Chrome'],
        baseURL: `http://localhost:${WEBFLUX_PORT}`
      }
    }
  ],

  webServer: process.env.BOOTUI_SKIP_WEBSERVER
    ? undefined
    : [
        {
          command:
            `../../mvnw${REPO_ARG} -f ../pom.xml -q spring-boot:run ` +
            '-Dspring-boot.run.jvmArguments=-Dspring.devtools.restart.enabled=false ' +
            `-Dspring-boot.run.arguments="--server.port=${MVC_PORT} --server.servlet.context-path=/host ${COMMON_ARGUMENTS}"`,
          url: `http://localhost:${MVC_PORT}/host/internal/bootui-api/overview`,
          reuseExistingServer: !process.env.CI,
          stdout: 'pipe',
          stderr: 'pipe',
          timeout: 240_000
        },
        {
          command:
            `../../mvnw${REPO_ARG} -f ../../bootui-spring-webflux-sample-app/pom.xml -q spring-boot:run ` +
            '-Dspring-boot.run.jvmArguments=-Dspring.devtools.restart.enabled=false ' +
            `-Dspring-boot.run.arguments="--server.port=${WEBFLUX_PORT} --spring.webflux.base-path=/host ${COMMON_ARGUMENTS}"`,
          url: `http://localhost:${WEBFLUX_PORT}/host/internal/bootui-api/overview`,
          reuseExistingServer: !process.env.CI,
          stdout: 'pipe',
          stderr: 'pipe',
          timeout: 180_000
        }
      ]
})
