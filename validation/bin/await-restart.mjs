#!/usr/bin/env node
// Waits, after a code change in dev mode, for exactly one restart of the application: a new journal run whose id has
// stayed the same, while the application answers ready, for the registered number of seconds (protocol.json
// `changeRestart.stableSeconds`). Fails when a second new run appears, so traffic never meets a restart and
// changed-code-not-executed never compares with an intermediate run.
//
//   node validation/bin/await-restart.mjs --journal-url <url> --ready-url <url> --before <runId>
//                                         --stable-seconds <n> --timeout-seconds <n>
//
// Prints the new run id and exits 0; exits 3 when more than one new run appears, 4 on timeout.

import {parseArgs} from 'node:util'
import {fileURLToPath} from 'node:url'

/** The waiting state: the run ids seen since the change, and since when the latest has been stable and ready. */
export function initial(before) {
  return {before, newRuns: [], stableSince: null}
}

/**
 * One poll: `sample` is {runId, ready} (runId empty while the application restarts), `now` in milliseconds. Returns the
 * next state and, when the wait is over, its outcome: {done: runId} or {failed: reason}.
 */
export function observe(state, sample, now, stableMillis) {
  const next = {...state, newRuns: [...state.newRuns]}
  const runId = sample.runId || ''
  if (runId && runId !== state.before && !next.newRuns.includes(runId)) {
    next.newRuns.push(runId)
    next.stableSince = null
  }
  if (next.newRuns.length > 1) {
    return {state: next, failed: `more than one new run appeared after the change: ${next.newRuns.join(', ')}`}
  }
  const latest = next.newRuns[0]
  if (latest && runId === latest && sample.ready) {
    next.stableSince ??= now
    if (now - next.stableSince >= stableMillis) return {state: next, done: latest}
  } else {
    next.stableSince = null
  }
  return {state: next}
}

async function sample(journalUrl, readyUrl) {
  let runId = ''
  let ready = false
  try {
    const journal = await fetch(journalUrl, {signal: AbortSignal.timeout(10_000)})
    runId = (await journal.json()).runId || ''
  } catch {
    // Restarting.
  }
  try {
    ready = (await fetch(readyUrl, {signal: AbortSignal.timeout(10_000)})).ok
  } catch {
    // Not ready.
  }
  return {runId, ready}
}

async function main() {
  const {values} = parseArgs({
    options: {
      'journal-url': {type: 'string'},
      'ready-url': {type: 'string'},
      before: {type: 'string', default: ''},
      'stable-seconds': {type: 'string'},
      'timeout-seconds': {type: 'string'},
      'poll-millis': {type: 'string', default: '1000'}
    }
  })
  const stableMillis = 1000 * Number(values['stable-seconds'])
  const deadline = Date.now() + 1000 * Number(values['timeout-seconds'])
  if (!values['journal-url'] || !values['ready-url'] || !(stableMillis > 0) || !(deadline > Date.now())) {
    console.error(
      'usage: await-restart.mjs --journal-url <url> --ready-url <url> --before <runId> --stable-seconds <n> --timeout-seconds <n>'
    )
    process.exit(64)
  }
  let state = initial(values.before)
  while (Date.now() < deadline) {
    const outcome = observe(state, await sample(values['journal-url'], values['ready-url']), Date.now(), stableMillis)
    state = outcome.state
    if (outcome.failed) {
      console.error(outcome.failed)
      process.exit(3)
    }
    if (outcome.done) {
      console.log(outcome.done)
      return
    }
    await new Promise((resolve) => setTimeout(resolve, Number(values['poll-millis'])))
  }
  console.error(
    state.newRuns.length
      ? `run ${state.newRuns[0]} did not stay stable and ready for ${stableMillis / 1000} s`
      : 'no new run appeared after the change'
  )
  process.exit(4)
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) await main()
