#!/usr/bin/env node
// Turns the collected evidence of a rerun into the reviewers' worksheet, under the registered protocol:
//   - fact: a default-visible OBSERVED or PARTIAL row, one per distinct fact (lib.mjs factKey);
//   - honesty: a default-visible INSUFFICIENT or NOT_APPLICABLE row, or a check that did not fully run;
//   - hidden: the seeded, stratified sample of rows left out of the default list.
// It also records, per kind, where its check ran and how many rows it listed and hid, so a kind that produced nothing
// is still gated, and the SHA-256 of the registered files, which the scorer checks.
// Agent-attached runs contribute only the kinds that need the agent; the rest is judged on the run without it.
//
//   node validation/scoring/worksheet.mjs --evidence <root> --out <dir> [--allow-incomplete]
//
// <root> holds one directory per run with a run.json (written by bin/rerun.sh) and, per service (or at the top), the
// collector's files. Superseded attempts (<label>.attempt-N) are reported, not judged. Without --allow-incomplete, the
// worksheet refuses a smoke run, a missing or unexpected run, or a role that is not the registered one.

import {createHash} from 'node:crypto'
import {existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync} from 'node:fs'
import {dirname, join, relative} from 'node:path'
import {fileURLToPath} from 'node:url'
import {parseArgs} from 'node:util'
import {NOT_EVALUATED, factKey, isListed, sampleHidden, shortId, toCsv} from './lib.mjs'

const here = dirname(fileURLToPath(import.meta.url))
export const validationHome = join(here, '..')

export function registeredHashes(protocol, home = validationHome) {
  return Object.fromEntries(
    protocol.registeredFiles.map((file) => [
      file,
      createHash('sha256')
        .update(readFileSync(join(home, file)))
        .digest('hex')
    ])
  )
}

export function loadRuns(root) {
  const runs = []
  const superseded = []
  for (const entry of readdirSync(root, {withFileTypes: true})) {
    if (!entry.isDirectory() || !existsSync(join(root, entry.name, 'run.json'))) continue
    const dir = join(root, entry.name)
    const run = JSON.parse(readFileSync(join(dir, 'run.json'), 'utf8'))
    if (/\.attempt-\d+$/.test(entry.name)) {
      superseded.push({label: entry.name, reason: run.supersededBecause || null})
      continue
    }
    const services = (run.services?.length ? run.services : ['']).map((service) => {
      const serviceDir = join(dir, service)
      const report = JSON.parse(readFileSync(join(serviceDir, 'runtime-insights.json'), 'utf8'))
      return {service, dir: serviceDir, report}
    })
    runs.push({label: entry.name, dir, ...run, services})
  }
  runs.sort((a, b) => a.label.localeCompare(b.label))
  return {runs, superseded}
}

/** Problems with the set of runs: the registered applications and agent runs, each once, with its registered role. */
export function checkRuns(runs, superseded, protocol) {
  const problems = []
  const measured = runs.filter((r) => !r.comparison)
  const expected = new Map([
    ...protocol.tunedApps.map((app) => [app, 'tuned']),
    ...protocol.holdoutApps.map((app) => [app, 'holdout'])
  ])
  for (const run of measured) {
    if (run.iterationsOverride) problems.push(`${run.label} is a smoke run (${run.iterationsOverride} iterations)`)
    if (!expected.has(run.app)) problems.push(`${run.label}: ${run.app} is not a registered application`)
    else if (run.role !== expected.get(run.app)) {
      problems.push(`${run.label}: role ${run.role}, registered as ${expected.get(run.app)}`)
    }
    if (run.agent && !protocol.agentRuns[run.app])
      problems.push(`${run.label}: no agent run is registered for ${run.app}`)
  }
  for (const app of expected.keys()) {
    const count = measured.filter((r) => r.app === app && !r.agent).length
    if (count !== 1) problems.push(`${app}: ${count} measured runs without the agent, expected 1`)
  }
  for (const app of Object.keys(protocol.agentRuns)) {
    const count = measured.filter((r) => r.app === app && r.agent).length
    if (count !== 1) problems.push(`${app}+agent: ${count} agent-attached runs, expected 1`)
  }
  for (const attempt of superseded) {
    if (!attempt.reason) problems.push(`${attempt.label} was superseded without a recorded reason`)
  }
  return problems
}

export function buildWorksheet({runs, superseded = []}, protocol, root = '.', hashes = {}) {
  const rows = []
  const notes = superseded.map((a) => `${a.label}: superseded attempt, because ${a.reason || 'no reason recorded'}`)
  const inventory = {}
  const kindEntry = (kind) =>
    (inventory[kind] ||= {
      kind,
      checks: {},
      listed: 0,
      hidden: 0,
      unlistedByDesign: protocol.unlistedByDesign.includes(kind)
    })
  const unknownReasons = new Set()
  for (const run of runs) {
    if (run.comparison) {
      notes.push(`${run.label}: a no-change comparison run, reported in the report's text, not judged row by row`)
      continue
    }
    if (run.iterationsOverride)
      notes.push(`${run.label}: NOT A MEASURED RUN, its traffic was cut to ${run.iterationsOverride} iterations`)
    const app = run.agent ? `${run.app}+agent` : run.app
    const role = run.agent ? 'agent' : run.role
    const agentKinds = protocol.agentRuns[run.app] || protocol.agentKinds
    const keep = (kind) => !run.agent || agentKinds.includes(kind)
    const facts = new Map()
    const hidden = new Map()
    let listedFlag = false
    let leftToRunWithoutAgent = 0
    for (const {service, dir, report} of run.services) {
      for (const check of report.checks || []) {
        if (!keep(check.kind)) continue
        const entry = kindEntry(check.kind)
        entry.checks[`${app}${service ? `/${service}` : ''}`] = check.status
        if (!NOT_EVALUATED.includes(check.status)) continue
        rows.push({
          section: 'honesty',
          source: 'check',
          key: `${app}\u241f${service}\u241fcheck\u241f${check.kind}`,
          app,
          role,
          service,
          kind: check.kind,
          subject: '(check)',
          status: check.status,
          sentence: check.reason || '',
          observationIds: [],
          evidence: relative(root, dir)
        })
      }
      for (const observation of report.observations || []) {
        if (Object.hasOwn(observation, 'listed')) listedFlag = true
        if (!keep(observation.kind)) {
          leftToRunWithoutAgent++
          continue
        }
        const key = factKey(app, service, observation)
        const target = isListed(observation) ? facts : hidden
        const existing = target.get(key)
        if (existing) {
          existing.observationIds.push(observation.id)
          continue
        }
        if (!isListed(observation)) {
          const reason = observation.unlistedReason || ''
          if (!protocol.knownUnlistedReasons.some((known) => reason.startsWith(known))) unknownReasons.add(reason)
        }
        target.set(key, {
          key,
          app,
          role,
          service,
          kind: observation.kind,
          subject: observation.subject,
          status: observation.status,
          sentence: observation.sentence,
          unlistedReason: observation.unlistedReason || '',
          observationIds: [observation.id],
          evidence: relative(root, dir)
        })
      }
    }
    for (const fact of facts.values()) {
      kindEntry(fact.kind).listed++
      const isFact = fact.status === 'OBSERVED' || fact.status === 'PARTIAL'
      rows.push({...fact, section: isFact ? 'fact' : 'honesty', source: 'observation'})
    }
    for (const row of hidden.values()) kindEntry(row.kind).hidden++
    if (run.agent) {
      notes.push(`${app}: ${leftToRunWithoutAgent} rows of kinds that do not need the agent are judged on ${run.app}`)
    }
    if (!listedFlag && run.services.some((s) => (s.report.observations || []).length)) {
      notes.push(`${app}: no row carries \`listed\` (a build before M4-19), so every row is default-visible`)
    }
    if (hidden.size) {
      const sample = sampleHidden([...hidden.values()], {seed: `${protocol.seed}/${app}`, size: protocol.hiddenSample})
      for (const row of sample) rows.push({...row, section: 'hidden', source: 'observation'})
      notes.push(`${app}: ${hidden.size} distinct hidden rows, ${sample.length} sampled`)
    }
  }
  for (const reason of unknownReasons) {
    notes.push(`unregistered unlistedReason, stratified by its text: "${reason}"`)
  }
  for (const row of rows) {
    row.id = `${row.app}/${row.section}/${shortId(row.key)}`
    row.mergedRows = row.observationIds.length
  }
  const ids = new Set()
  for (const row of rows) {
    if (ids.has(row.id)) throw new Error(`two rows share the id ${row.id}; the evidence holds a duplicate run`)
    ids.add(row.id)
  }
  rows.sort((a, b) => a.id.localeCompare(b.id))
  return {
    protocol: protocol.name,
    seed: protocol.seed,
    registeredFiles: hashes,
    generatedAt: new Date().toISOString(),
    notes,
    inventory: Object.values(inventory).sort((a, b) => a.kind.localeCompare(b.kind)),
    rows
  }
}

function main() {
  const {values} = parseArgs({
    options: {
      evidence: {type: 'string'},
      out: {type: 'string'},
      'allow-incomplete': {type: 'boolean', default: false}
    }
  })
  if (!values.evidence || !values.out) {
    console.error('usage: worksheet.mjs --evidence <root> --out <dir> [--allow-incomplete]')
    process.exit(64)
  }
  const protocol = JSON.parse(readFileSync(join(validationHome, 'protocol.json'), 'utf8'))
  const loaded = loadRuns(values.evidence)
  const problems = checkRuns(loaded.runs, loaded.superseded, protocol)
  if (problems.length) {
    for (const problem of problems) console.error(`${values['allow-incomplete'] ? 'warning' : 'error'}: ${problem}`)
    if (!values['allow-incomplete']) process.exit(3)
  }
  const worksheet = buildWorksheet(loaded, protocol, values.evidence, registeredHashes(protocol))
  if (problems.length) worksheet.notes.unshift(`INCOMPLETE: ${problems.length} problems with the run set`)
  mkdirSync(values.out, {recursive: true})
  writeFileSync(join(values.out, 'worksheet.json'), JSON.stringify(worksheet, null, 2) + '\n')
  const template = worksheet.rows.map((r) => ({
    ...r,
    observation_ids: r.observationIds.join(' '),
    judgment: '',
    note: ''
  }))
  const columns = [
    'id',
    'section',
    'app',
    'service',
    'kind',
    'subject',
    'status',
    'sentence',
    'unlistedReason',
    'stratum'
  ]
  writeFileSync(
    join(values.out, 'judgments-template.csv'),
    toCsv(template, [...columns, 'mergedRows', 'observation_ids', 'evidence', 'judgment', 'note'])
  )
  const counts = worksheet.rows.reduce((acc, r) => ({...acc, [r.section]: (acc[r.section] || 0) + 1}), {})
  console.log(`worksheet: ${JSON.stringify(counts)}`)
  for (const note of worksheet.notes) console.log(`  ${note}`)
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main()
