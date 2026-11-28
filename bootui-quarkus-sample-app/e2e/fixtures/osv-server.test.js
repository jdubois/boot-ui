import assert from 'node:assert/strict'
import {spawn} from 'node:child_process'
import {once} from 'node:events'
import {createInterface} from 'node:readline'
import {test} from 'node:test'
import {fileURLToPath} from 'node:url'

const ENVIRONMENT = ['BOOTUI_QUARKUS_GENERATION', 'BOOTUI_OSV_FIXTURE_PORT', 'BOOTUI_SKIP_WEBSERVER', 'BOOTUI_OSV_LIVE']

test('the configured OSV fixture starts at its readiness URL for both Quarkus generations', async (suite) => {
  for (const generation of ['3', '4']) {
    await suite.test(`Quarkus ${generation}`, {timeout: 10_000}, async (context) => {
      const saved = ENVIRONMENT.map((name) => [name, process.env[name]])
      let child
      let exited
      let output
      try {
        for (const name of ENVIRONMENT) delete process.env[name]
        process.env.BOOTUI_QUARKUS_GENERATION = generation
        const {default: config} = await import(`../playwright.config.js?fixture-generation=${generation}`)
        const fixture = config.webServer[0]
        child = spawn(process.execPath, ['fixtures/osv-server.js'], {
          cwd: fileURLToPath(new URL('../', import.meta.url)),
          env: {...process.env, ...fixture.env},
          stdio: ['ignore', 'pipe', 'pipe']
        })
        let errors = ''
        child.stderr.on('data', (chunk) => (errors += chunk))
        exited = once(child, 'exit')
        output = createInterface({input: child.stdout})
        const [line] = await Promise.race([
          once(output, 'line', {signal: context.signal}),
          exited.then(([code]) => {
            throw new Error(`OSV fixture exited before readiness (${code}): ${errors}`)
          })
        ])
        assert.equal(line, `Deterministic OSV fixture listening on ${new URL(fixture.url).origin}`)
        const response = await fetch(fixture.url, {signal: context.signal})
        assert.equal(response.status, 200)
        assert.deepEqual(await response.json(), {status: 'ready'})
      } finally {
        output?.close()
        if (child && child.exitCode === null && child.signalCode === null) child.kill('SIGTERM')
        if (exited) await exited
        for (const [name, value] of saved) {
          if (value === undefined) delete process.env[name]
          else process.env[name] = value
        }
      }
    })
  }
})
