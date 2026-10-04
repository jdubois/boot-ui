// @ts-check
import {existsSync, readdirSync} from 'node:fs'
import {join, resolve} from 'node:path'
import {defineConfig} from '@playwright/test'
import base from './playwright.config.js'

/**
 * Playwright configuration for the Quarkus sample with the BootUI agent attached (docs/PLAN-v2.md M5-12).
 *
 * Runs the whole default suite (`tests/`) against `quarkus:dev` started with `-Djvm.args=-javaagent:...`, so every spec
 * must hold with the agent attached as without it, plus the agent-only specs in `tests-agent/`. The Java Agent, Code
 * Inventory, and Code Paths specs read the `agentAttached` fixture option, which only this configuration sets, and
 * assert the armed claim, the run's inventory, and the seeded route's tree instead of the unavailable state. The agent
 * jar is `BOOTUI_AGENT_JAR`, or the one `./mvnw install -pl bootui-agent -am` built in `bootui-agent/target`. Every
 * other setting, including the deterministic OSV fixture and `BOOTUI_SAMPLE_PORT`, comes from playwright.config.js.
 */
function agentJar() {
  if (process.env.BOOTUI_AGENT_JAR) {
    if (!existsSync(process.env.BOOTUI_AGENT_JAR)) {
      throw new Error(`BOOTUI_AGENT_JAR names no file: ${process.env.BOOTUI_AGENT_JAR}`)
    }
    return process.env.BOOTUI_AGENT_JAR
  }
  const target = resolve('../../bootui-agent/target')
  const jar = existsSync(target)
    ? readdirSync(target).find(
        (name) => /^bootui-agent-\d[\w.-]*\.jar$/.test(name) && !/-(tests|sources|javadoc)\.jar$/.test(name)
      )
    : undefined
  if (!jar) {
    throw new Error(
      `No bootui-agent-*.jar in ${target}: run ./mvnw install -pl bootui-agent -am, or set BOOTUI_AGENT_JAR`
    )
  }
  return join(target, jar)
}

const JVM_ARGS = ` "-Djvm.args=-javaagent:${agentJar()}"`
const webServers = Array.isArray(base.webServer) ? base.webServer : base.webServer ? [base.webServer] : []

export default defineConfig({
  ...base,
  testDir: '.',
  testMatch: ['tests/**/*.spec.js', 'tests-agent/**/*.spec.js'],
  outputDir: 'test-results-agent',
  reporter: process.env.CI
    ? [
        ['list'],
        ['html', {open: 'never', outputFolder: 'playwright-report-agent'}],
        ['junit', {outputFile: 'test-results-agent/junit/results.xml'}]
      ]
    : 'list',
  use: {...base.use, agentAttached: true},
  webServer: process.env.BOOTUI_SKIP_WEBSERVER
    ? undefined
    : webServers.map((server) =>
        server.command.includes('quarkus:dev') ? {...server, command: server.command + JVM_ARGS} : server
      )
})
