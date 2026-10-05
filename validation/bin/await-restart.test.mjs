// Self-check of the restart wait of a code-change run: node --test validation/bin/
import assert from 'node:assert/strict'
import {createServer} from 'node:http'
import {spawn} from 'node:child_process'
import {test} from 'node:test'
import {dirname, join} from 'node:path'
import {fileURLToPath} from 'node:url'
import {initial, observe} from './await-restart.mjs'

const STABLE = 5000

function play(samples, before = 'run-0') {
  let state = initial(before)
  for (const [now, runId, ready] of samples) {
    const outcome = observe(state, {runId, ready}, now, STABLE)
    state = outcome.state
    if (outcome.done || outcome.failed) return outcome
  }
  return {state}
}

test('one restart that stays stable and ready for the registered time is accepted', () => {
  const outcome = play([
    [0, 'run-0', true],
    [1000, '', false],
    [2000, 'run-1', false],
    [3000, 'run-1', true],
    [7000, 'run-1', true],
    [8000, 'run-1', true]
  ])
  assert.equal(outcome.done, 'run-1')
})

test('a second restart fails the run before any traffic', () => {
  const outcome = play([
    [0, '', false],
    [1000, 'run-1', true],
    [2000, '', false],
    [3000, 'run-2', true],
    [20_000, 'run-2', true]
  ])
  assert.match(outcome.failed, /more than one new run appeared after the change: run-1, run-2/)
})

test('stability restarts whenever the application stops answering ready', () => {
  const outcome = play([
    [1000, 'run-1', true],
    [4000, 'run-1', false],
    [5000, 'run-1', true],
    [9000, 'run-1', true]
  ])
  assert.equal(outcome.done, undefined, '4 s since it was ready again is not 5 s')
  assert.equal(outcome.state.stableSince, 5000)
  assert.equal(
    play([
      [1000, 'run-1', true],
      [4000, 'run-1', false],
      [5000, 'run-1', true],
      [10_000, 'run-1', true]
    ]).done,
    'run-1'
  )
})

test('the previous run and an unreachable journal never count as the new run', () => {
  const outcome = play([
    [0, 'run-0', true],
    [10_000, 'run-0', true],
    [20_000, '', false]
  ])
  assert.deepEqual(outcome.state.newRuns, [])
  assert.equal(outcome.done, undefined)
})

test('the command fails with exit 3 against an application that restarts twice', async () => {
  const runs = ['run-0', 'run-1', 'run-1', 'run-2']
  let calls = 0
  const server = createServer((request, response) => {
    if (request.url === '/journal') {
      const runId = runs[Math.min(calls++, runs.length - 1)]
      response.writeHead(200, {'Content-Type': 'application/json'}).end(JSON.stringify({runId}))
    } else {
      response.writeHead(200).end('ok')
    }
  })
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve))
  const base = `http://127.0.0.1:${server.address().port}`
  const script = join(dirname(fileURLToPath(import.meta.url)), 'await-restart.mjs')
  const child = spawn(process.execPath, [
    script,
    '--journal-url',
    `${base}/journal`,
    '--ready-url',
    `${base}/ready`,
    '--before',
    'run-0',
    '--stable-seconds',
    '30',
    '--timeout-seconds',
    '20',
    '--poll-millis',
    '50'
  ])
  let stderr = ''
  child.stderr.on('data', (chunk) => (stderr += chunk))
  const code = await new Promise((resolve) => child.on('close', resolve))
  server.close()
  assert.equal(code, 3)
  assert.match(stderr, /more than one new run/)
})
