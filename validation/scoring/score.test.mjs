// Self-check of the worksheet and the scorer: node --test validation/scoring/
import assert from 'node:assert/strict'
import {execFileSync, spawnSync} from 'node:child_process'
import {existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync} from 'node:fs'
import {tmpdir} from 'node:os'
import {dirname, join} from 'node:path'
import {test} from 'node:test'
import {fileURLToPath} from 'node:url'
import {
  atLeast,
  below,
  factKey,
  normalizeSentence,
  parseCsv,
  sampleHidden,
  toCsv,
  upperBound95,
  worstLatencyMillis
} from './lib.mjs'
import {investigations, judge, readCsvById, recall, score, timeToFirstObservation} from './score.mjs'
import {checkRegistration, harnessHash} from './harness.mjs'
import {buildWorksheet, checkRuns, loadRuns} from './worksheet.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const protocol = JSON.parse(readFileSync(join(here, '..', 'protocol.json'), 'utf8'))

const obs = (id, kind, subject, extra = {}) => ({
  id,
  kind,
  subject,
  status: 'OBSERVED',
  sentence: `${subject} did something.`,
  ...extra
})

test('rows differing only by counts and ids are one fact; different exceptions are two', () => {
  const a = obs('a', 'framework-warnings-by-route', 'GET /r', {
    sentence:
      'logged ERROR in 1 of 32 requests: "HTTP Request to /r failed, error id: 5f1e8a2c-1b2a-4c3d-8e9f-0a1b2c3d4e5f-1"'
  })
  const b = obs('b', 'framework-warnings-by-route', 'GET /r', {
    sentence:
      'logged ERROR in 2 of 32 requests: "HTTP Request to /r failed, error id: 9a8b7c6d-1b2a-4c3d-8e9f-0a1b2c3d4e5f-7"'
  })
  assert.equal(factKey('app', '', a), factKey('app', '', b))
  const npe = obs('c', 'exception-hotspots', 'GET /r', {
    sentence: '`GET /r` recorded `NullPointerException` in 8 of 32'
  })
  const auth = obs('d', 'exception-hotspots', 'GET /r', {
    sentence: '`GET /r` recorded `UnauthorizedException` in 8 of 32'
  })
  assert.notEqual(factKey('app', '', npe), factKey('app', '', auth))
  assert.notEqual(factKey('app', 'payment', npe), factKey('app', 'stock', npe))
  assert.equal(normalizeSentence('id 0123abcd99 took 1,234.5 ms'), 'id <id> took # ms')
})

test('the worst stated latency is read from a route-time-breakdown sentence', () => {
  assert.equal(worstLatencyMillis('`GET /a`: warm median 12 ms over 31 requests'), 12)
  assert.equal(worstLatencyMillis('`GET /a`: warm median 1,250 ms over 3 requests'), 1250)
  assert.equal(
    worstLatencyMillis('`GET /a`: warm median 3 ms over 1 request. Median CPU 9 ms. First request 2 s (cold).'),
    2000
  )
  assert.equal(worstLatencyMillis('no median'), -1)
})

test('gates compare exact fractions, never rounded shares', () => {
  assert.equal(below(62, 207, 30), true, '29.95 % is under 30 % although it displays as 30.0 %')
  assert.equal(atLeast(1, 2, 50), true)
  assert.equal(atLeast(49, 99, 50), false)
  assert.equal(atLeast(0, 0, 50), false)
  assert.equal(upperBound95(0, 10), 30.8)
  assert.equal(upperBound95(0, 70), 5.1)
  assert.equal(upperBound95(10, 10), 100)
})

test('the hidden sample is seeded, stratified, and keeps every mandatory stratum', () => {
  const hidden = []
  for (let i = 0; i < 30; i++) {
    hidden.push({
      key: `route-${i}`,
      kind: 'route-time-breakdown',
      sentence: `\`GET /r${i}\`: warm median ${i % 10} ms over 9 requests${i === 17 ? '. First request 900 ms (cold)' : ''}`,
      unlistedReason: 'Its warm median is under 20 ms, and authorization takes under 20 % of its time'
    })
  }
  hidden.push({
    key: 'ex-1',
    kind: 'exception-hotspots',
    sentence: 'x',
    unlistedReason: 'Every scheduled run or message that recorded it completed, so the exception was caught'
  })
  hidden.push({
    key: 'ex-2',
    kind: 'exception-hotspots',
    sentence: 'y',
    unlistedReason: 'It was recorded only behind 4xx responses'
  })
  hidden.push({
    key: 'gc-1',
    kind: 'gc-inflated-latency',
    sentence: 'z',
    unlistedReason: 'Garbage collection and heap rows are reached from the Memory panel'
  })
  hidden.push({
    key: 'few-1',
    kind: 'route-time-breakdown',
    sentence: 'w',
    unlistedReason: 'It has fewer than 5 warm requests'
  })

  const first = sampleHidden(hidden, {seed: 's/app', size: 10})
  const again = sampleHidden([...hidden].reverse(), {seed: 's/app', size: 10})
  assert.deepEqual(
    first.map((r) => r.key),
    again.map((r) => r.key)
  )
  assert.equal(first.length, 10)
  assert.equal(first.find((r) => r.stratum === 'slowest hidden route').key, 'route-17')
  assert.ok(first.some((r) => r.key === 'ex-1' && r.stratum.startsWith('exception caught')))
  for (const key of ['ex-2', 'gc-1', 'few-1'])
    assert.ok(
      first.some((r) => r.key === key),
      `${key} is sampled`
    )
  const other = sampleHidden(hidden, {seed: 'another/app', size: 10})
  assert.notDeepEqual(first.map((r) => r.key).sort(), other.map((r) => r.key).sort())
  assert.equal(sampleHidden(hidden.slice(0, 4), {seed: 's', size: 10}).length, 4)
})

test('CSV quoting survives a round trip', () => {
  const rows = [{id: 'a', note: 'says "hi", then\nleaves'}]
  assert.deepEqual(parseCsv(toCsv(rows, ['id', 'note'])), rows)
})

const SAME_BUILD = {bootuiCommit: 'c0ffee', engineSha256: 'e1e1', harnessSha256: 'h1h1'}

function evidenceTree() {
  const root = mkdtempSync(join(tmpdir(), 'bootui-validation-'))
  const write = (run, meta, services) => {
    mkdirSync(join(root, run), {recursive: true})
    writeFileSync(join(root, run, 'run.json'), JSON.stringify({...SAME_BUILD, ...meta}))
    for (const [service, report] of Object.entries(services)) {
      mkdirSync(join(root, run, service), {recursive: true})
      writeFileSync(join(root, run, service, 'runtime-insights.json'), JSON.stringify(report))
    }
  }
  write(
    'tuned-app',
    {app: 'tuned-app', role: 'tuned'},
    {
      '': {
        observations: [
          obs('t1', 'repeated-selects', 'GET /a', {listed: true}),
          obs('t2', 'repeated-selects', 'GET /b', {listed: true, status: 'PARTIAL'}),
          obs('t3', 'route-time-breakdown', 'GET /c', {
            listed: false,
            unlistedReason: 'Its warm median is under 20 ms',
            sentence: 'warm median 3 ms'
          }),
          obs('t4', 'route-time-breakdown', 'GET /d', {listed: true, status: 'INSUFFICIENT'}),
          obs('t5', 'framework-warnings-by-route', 'GET /e', {
            listed: false,
            unlistedReason: 'A reason nobody registered'
          })
        ],
        checks: [
          {kind: 'event-loop-blocking', status: 'NOT_APPLICABLE', reason: 'Servlet stack'},
          {kind: 'connections-per-request', status: 'EVALUATED', reason: null}
        ]
      }
    }
  )
  write(
    'holdout-app',
    {app: 'holdout-app', role: 'holdout', services: ['one', 'two']},
    {
      one: {
        observations: [obs('h1', 'repeated-selects', 'GET /x'), obs('h2', 'exception-hotspots', 'GET /x')],
        checks: []
      },
      two: {observations: [obs('h3', 'repeated-selects', 'GET /x')], checks: []}
    }
  )
  write(
    'tuned-app-agent',
    {app: 'tuned-app', role: 'tuned', agent: true},
    {
      '': {
        observations: [
          obs('a1', 'work-after-response', 'POST /a', {listed: true}),
          obs('a2', 'repeated-selects', 'GET /a', {listed: true})
        ],
        checks: [{kind: 'changed-code-not-executed', status: 'NOT_APPLICABLE', reason: 'no previous run'}]
      }
    }
  )
  write(
    'tuned-app.attempt-1',
    {app: 'tuned-app', role: 'tuned', supersededBecause: 'the broker did not start'},
    {
      '': {observations: [obs('x1', 'repeated-selects', 'GET /zzz')], checks: []}
    }
  )
  write('smoke', {app: 'tuned-app', role: 'tuned', comparison: true}, {'': {observations: [], checks: []}})
  return root
}

test('the worksheet: PARTIAL rows are facts, INSUFFICIENT rows honesty, a kind inventory, and agent kinds only', () => {
  const root = evidenceTree()
  const loaded = loadRuns(root)
  assert.deepEqual(loaded.superseded, [{label: 'tuned-app.attempt-1', reason: 'the broker did not start'}])
  const sheet = buildWorksheet(loaded, protocol, 'h1h1')
  assert.deepEqual(sheet.problems, [])
  const by = (section, app) => sheet.rows.filter((r) => r.section === section && r.app === app)
  assert.deepEqual(
    by('fact', 'tuned-app')
      .map((r) => r.subject)
      .sort(),
    ['GET /a', 'GET /b']
  )
  assert.deepEqual(
    by('hidden', 'tuned-app')
      .map((r) => r.subject)
      .sort(),
    ['GET /c', 'GET /e']
  )
  assert.deepEqual(
    by('honesty', 'tuned-app')
      .map((r) => r.kind)
      .sort(),
    ['event-loop-blocking', 'route-time-breakdown']
  )
  assert.equal(by('fact', 'holdout-app').length, 3, 'one fact per service')
  assert.ok(sheet.notes.some((n) => n.includes('holdout-app') && n.includes('before M4-19')))
  assert.ok(sheet.notes.some((n) => n.includes('A reason nobody registered')))
  assert.deepEqual(
    by('fact', 'tuned-app+agent').map((r) => r.kind),
    ['work-after-response']
  )
  assert.deepEqual(
    by('honesty', 'tuned-app+agent').map((r) => r.kind),
    ['changed-code-not-executed']
  )
  const inventory = Object.fromEntries(sheet.inventory.map((k) => [k.kind, k]))
  assert.deepEqual(inventory['connections-per-request'].checks, {'tuned-app': 'EVALUATED'})
  assert.equal(inventory['route-time-breakdown'].hidden, 1)
  assert.equal(inventory['repeated-selects'].listed, 4)
  assert.equal(sheet.harnessSha256, 'h1h1')
  assert.deepEqual(
    new Set(sheet.rows.map((r) => r.evidence)),
    new Set(['tuned-app', 'holdout-app/one', 'holdout-app/two', 'tuned-app-agent'])
  )
  assert.ok(sheet.rows.every((r) => /^[a-z+-]+\/(fact|honesty|hidden)\/[0-9a-f]{8}$/.test(r.id)))

  const problems = checkRuns(
    loaded.runs,
    loaded.superseded,
    {
      ...protocol,
      tunedApps: ['tuned-app', 'missing-app'],
      holdoutApps: ['holdout-app'],
      agentRuns: {'tuned-app': ['work-after-response'], 'holdout-app': ['work-after-response']}
    },
    'h1h1'
  )
  assert.deepEqual(problems.sort(), [
    'holdout-app+agent: 0 agent-attached runs, expected 1',
    'missing-app: 0 measured runs without the agent, expected 1'
  ])
  const smoke = checkRuns(
    [
      {label: 'x', app: 'tuned-app', role: 'holdout', iterationsOverride: 2, ...SAME_BUILD},
      {
        label: 'z',
        app: 'other',
        role: 'tuned',
        comparison: true,
        ...SAME_BUILD,
        engineSha256: 'e2e2',
        harnessSha256: 'old'
      }
    ],
    [{label: 'y.attempt-1', reason: null}],
    {...protocol, tunedApps: ['tuned-app'], holdoutApps: [], agentRuns: {}},
    'h1h1'
  )
  assert.deepEqual(smoke.sort(), [
    'every run must share one engineSha256, found e1e1, e2e2',
    'x is a smoke run (2 iterations)',
    'x: role holdout, registered as tuned',
    'y.attempt-1 was superseded without a recorded reason',
    'z ran with harness old, not the current one'
  ])
})

test('unlisted by design is read from the evidence and must agree with the registration', () => {
  const run = (observations) => ({
    runs: [
      {
        label: 'a',
        app: 'a',
        role: 'tuned',
        ...SAME_BUILD,
        services: [{service: '', evidence: 'a', report: {observations, checks: []}}]
      }
    ]
  })
  const memory = 'Garbage collection and heap rows are reached from the Memory panel rather than listed by default.'
  const ok = buildWorksheet(
    run([obs('g1', 'gc-inflated-latency', 'GET /a', {listed: false, unlistedReason: memory})]),
    protocol
  )
  assert.deepEqual(ok.problems, [])
  assert.equal(ok.inventory.find((k) => k.kind === 'gc-inflated-latency').unlistedByDesign, true)
  assert.equal(ok.inventory.find((k) => k.kind === 'heap-growth-after-gc').unlistedByDesign, true, 'registered, silent')
  const listed = buildWorksheet(run([obs('g2', 'gc-inflated-latency', 'GET /a', {listed: true})]), protocol)
  assert.match(listed.problems[0], /registered as unlisted by design, but 1 of its rows are listed/)
  assert.equal(listed.inventory.find((k) => k.kind === 'gc-inflated-latency').unlistedByDesign, false)
  const unregistered = buildWorksheet(
    run([obs('o1', 'orm-auto-flush', 'GET /a', {listed: false, unlistedReason: memory})]),
    protocol
  )
  assert.match(unregistered.problems[0], /orm-auto-flush is hidden as a whole kind/)
  assert.equal(protocol.unlistedByDesign.includes('orm-auto-flush'), false, "D29's kinds are listed since M4-18e")
})

test('a lost slowest-route stratum is an error of the worksheet', () => {
  const hidden = []
  for (let i = 0; i < 12; i++) {
    hidden.push(
      obs(`r${i}`, 'route-time-breakdown', `GET /r${i}`, {
        listed: false,
        unlistedReason: 'Its warm median is under 20 ms',
        sentence: 'reworded'
      })
    )
  }
  const sheet = buildWorksheet(
    {
      runs: [
        {
          label: 'a',
          app: 'a',
          role: 'tuned',
          ...SAME_BUILD,
          services: [{service: '', evidence: 'a', report: {observations: hidden}}]
        }
      ]
    },
    protocol
  )
  assert.match(sheet.problems[0], /slowest route is unknown/)
})

const reviewerMap = (entries) => new Map(Object.entries(entries).map(([id, judgment]) => [id, {id, judgment}]))

function scenario() {
  const rows = []
  const add = (app, role, kind, n, section = 'fact') => {
    for (let i = 0; i < n; i++)
      rows.push({id: `${app}/${section}/${kind}-${i}`, app, role, kind, section, subject: `S${i}`})
  }
  add('t1', 'tuned', 'repeated-selects', 2)
  add('t2', 'tuned', 'repeated-selects', 2)
  add('t1', 'tuned', 'route-time-breakdown', 4)
  add('h1', 'holdout', 'route-time-breakdown', 2)
  add('h1', 'holdout', 'exception-hotspots', 1)
  add('h1', 'holdout', 'gc-inflated-latency', 1, 'hidden')
  add('t1+agent', 'agent', 'work-after-response', 1)
  add('t1', 'tuned', 'event-loop-blocking', 1, 'honesty')
  add('h1', 'holdout', 'errors-behind-2xx', 1, 'honesty')
  const r1 = {}
  const r2 = {}
  for (const r of rows) {
    r1[r.id] = r.section === 'honesty' ? 'Honest' : 'Actionable'
    r2[r.id] = r1[r.id]
  }
  for (let i = 0; i < 4; i++) r2[`t1/fact/route-time-breakdown-${i}`] = 'Noise'
  r2['h1/fact/route-time-breakdown-0'] = 'Misleading'
  r1['h1/hidden/gc-inflated-latency-0'] = 'Informative'
  const inventory = [
    {
      kind: 'connections-per-request',
      checks: {t1: 'EVALUATED', h1: 'EVALUATED'},
      listed: 0,
      hidden: 0,
      unlistedByDesign: false
    },
    {kind: 'gc-inflated-latency', checks: {h1: 'EVALUATED'}, listed: 0, hidden: 3, unlistedByDesign: true},
    {
      kind: 'proxy-bypass',
      checks: {t1: 'NOT_APPLICABLE', h1: 'UNAVAILABLE'},
      listed: 0,
      hidden: 0,
      unlistedByDesign: false
    }
  ]
  return {worksheet: {rows}, r1, r2, inventory}
}

test('scores, adjudication rules, gates, and per-kind outcomes', () => {
  const {worksheet, r1, r2, inventory} = scenario()
  const pending = judge(worksheet, {r1: reviewerMap(r1), r2: reviewerMap(r2)}, new Map())
  assert.equal(pending.problems.filter((p) => p.includes('adjudication')).length, 6)

  const adjudications = new Map([
    ...[0, 1, 2, 3].map((i) => [
      `t1/fact/route-time-breakdown-${i}`,
      {judgment: 'Informative', reason: 'true and worth knowing'}
    ]),
    ['h1/fact/route-time-breakdown-0', {judgment: 'Misleading', reason: 'unattributed time named application code'}],
    ['h1/hidden/gc-inflated-latency-0', {judgment: 'Noise', reason: 'young pauses only'}],
    ['t2/fact/repeated-selects-0', {judgment: 'Noise', reason: 'trying to overrule two reviewers'}]
  ])
  const judged = judge(worksheet, {r1: reviewerMap(r1), r2: reviewerMap(r2)}, adjudications)
  assert.deepEqual(judged.problems, [])
  assert.equal(judged.warnings.length, 1, 'an adjudication of an agreed row is ignored')
  const s = score(judged, protocol, inventory)

  // Adjudicating a disagreement upward never makes a row useful to both reviewers.
  assert.equal(s.groups.tuned.facts, 8)
  assert.equal(s.groups.tuned.useful, 4)
  assert.equal(s.groups.tuned.usefulPercent, 50)
  assert.equal(s.groups.tuned.adjudicatedUsefulPercent, 100)
  assert.equal(s.groups.holdout.facts, 3)
  assert.equal(s.groups.holdout.useful, 2)
  assert.equal(s.groups.holdout.misleading, 1)
  assert.equal(s.groups.pooled.facts, 11)
  assert.equal(s.groups.pooled.usefulPercent, 54.5)
  assert.equal(s.groups.agent.facts, 1, 'agent runs are scored apart')
  assert.equal(s.groups.tuned.meetsPostM3, true)
  assert.equal(s.groups.holdout.meetsTarget, false)
  assert.equal(s.escalation.holdoutGap, -16.7)
  assert.equal(s.escalation.triggered, false)

  const kind = (k) => s.perKind.find((x) => x.kind === k)
  assert.equal(kind('repeated-selects').status, 'PASS')
  assert.equal(kind('route-time-breakdown').status, 'FAIL', 'one confirmed misleading fact fails the kind')
  assert.equal(kind('exception-hotspots').status, 'UNDER_SAMPLED')
  assert.equal(kind('exception-hotspots').outcome, 'hidden, not externally validated: too few facts')
  assert.equal(kind('work-after-response').status, 'UNDER_SAMPLED')
  assert.equal(kind('event-loop-blocking').outcome, 'stays listed, marked as not externally validated')
  assert.equal(kind('gc-inflated-latency').status, 'NOT_LISTED')
  assert.equal(kind('connections-per-request').status, 'SILENT', 'a kind with only EVALUATED checks is still gated')
  assert.equal(kind('event-loop-blocking').status, 'SILENT')
  assert.equal(kind('proxy-bypass').status, 'NOT_EXERCISED', 'a check that never ran is not silent')
  // A hidden row one reviewer judged Actionable is hidden value, whatever the adjudication says.
  assert.equal(s.hiddenValue.length, 1)
  assert.equal(kind('gc-inflated-latency').filterHidesValue, true, 'both reviewers found the hidden row useful')
  assert.equal(kind('repeated-selects').filterHidesValue, false)
  assert.equal(s.honesty.honest, 2)
})

test('two reviewers who agree are never overruled, Misleading included, and misleading honesty rows count', () => {
  const {worksheet, r1, r2, inventory} = scenario()
  r1['t2/fact/repeated-selects-1'] = r2['t2/fact/repeated-selects-1'] = 'Misleading'
  r1['h1/honesty/errors-behind-2xx-0'] = r2['h1/honesty/errors-behind-2xx-0'] = 'Misleading'
  const adjudications = new Map([
    ...[0, 1, 2, 3].map((i) => [`t1/fact/route-time-breakdown-${i}`, {judgment: 'Informative', reason: 'r'}]),
    ['h1/fact/route-time-breakdown-0', {judgment: 'Noise', reason: 'overturned: the time is attributed'}],
    ['h1/hidden/gc-inflated-latency-0', {judgment: 'Actionable', reason: 'r'}],
    ['t2/fact/repeated-selects-1', {judgment: 'Noise', reason: 'trying to overrule'}]
  ])
  const judged = judge(worksheet, {r1: reviewerMap(r1), r2: reviewerMap(r2)}, adjudications)
  assert.deepEqual(judged.problems, [])
  assert.equal(judged.rows.find((r) => r.id === 't2/fact/repeated-selects-1').final, 'Misleading')
  assert.ok(judged.warnings.some((w) => w.includes('t2/fact/repeated-selects-1')))
  const s = score(judged, protocol, inventory)
  const kind = (k) => s.perKind.find((x) => x.kind === k)
  assert.equal(kind('repeated-selects').status, 'FAIL')
  assert.equal(kind('route-time-breakdown').misleading, 0, 'one reviewer’s Misleading, overturned with a reason')
  assert.equal(kind('errors-behind-2xx').status, 'FAIL', 'a misleading honesty row fails its kind')
  assert.equal(s.groups.holdout.misleading, 1)
  assert.equal(s.hiddenValue.length, 1)
})

test('the escalation folds every kind that does not pass, and an empty holdout triggers it', () => {
  const {worksheet, r1, r2, inventory} = scenario()
  for (const id of Object.keys(r2)) if (id.startsWith('h1/fact/')) r2[id] = r1[id] = 'Noise'
  const adjudications = new Map([
    ...[0, 1, 2, 3].map((i) => [`t1/fact/route-time-breakdown-${i}`, {judgment: 'Noise', reason: 'fast route'}]),
    ['h1/hidden/gc-inflated-latency-0', {judgment: 'Noise', reason: 'young pauses'}]
  ])
  const s = score(judge(worksheet, {r1: reviewerMap(r1), r2: reviewerMap(r2)}, adjudications), protocol, inventory)
  assert.equal(s.groups.holdout.usefulPercent, 0)
  assert.equal(s.escalation.holdoutGapTooLarge, true)
  assert.equal(s.escalation.triggered, true)
  const kind = (k) => s.perKind.find((x) => x.kind === k)
  assert.equal(kind('repeated-selects').outcome, 'stays listed by default')
  assert.equal(kind('exception-hotspots').outcome, 'folds (escalation)')
  assert.equal(kind('connections-per-request').outcome, 'folds (escalation)')
  assert.equal(kind('gc-inflated-latency').outcome, 'not listed by default; judged through the hidden sample')

  const noHoldout = {rows: worksheet.rows.filter((r) => r.role !== 'holdout')}
  const t = score(judge(noHoldout, {r1: reviewerMap(r1), r2: reviewerMap(r2)}, adjudications), protocol, inventory)
  assert.equal(t.escalation.holdoutEmpty, true)
  assert.equal(t.escalation.triggered, true)
})

test('duplicate ids in a judgment file are reported', () => {
  const read = readCsvById('id,judgment\na,Noise\na,Actionable\n', 'reviewer r1')
  assert.deepEqual(read.problems, ['reviewer r1 lists a twice'])
})

test('recall, counterexamples, regressions, and investigations', () => {
  const known = {
    applications: {
      app: {
        misses: [
          {id: 'A-1', firstRun: 'found'},
          {id: 'A-2', firstRun: 'found'},
          {id: 'A-3', exercised: false},
          {id: 'A-4'}
        ],
        counterexamples: [
          {id: 'A-C1', subjects: ['GET /c1']},
          {id: 'A-C2', subjects: ['GET /c2']}
        ]
      }
    }
  }
  const outcomes = (extra = {}) =>
    new Map(
      Object.entries({
        'A-1': {outcome: 'found-default', rows: 'app/fact/2'},
        'A-2': {outcome: 'found-hidden', rows: 'route-time-breakdown:0a1b2c3d4e'},
        'A-3': {outcome: 'not-exercised'},
        'A-4': {outcome: 'missed'},
        'A-C1': {outcome: 'respected'},
        'A-C2': {outcome: 'violated', rows: 'app/fact/1'},
        ...extra
      }).map(([id, v]) => [id, {id, ...v}])
    )
  const rows = [
    {id: 'app/fact/1', app: 'app', section: 'fact', subject: 'GET /c2', final: 'Misleading'},
    {id: 'app/fact/2', app: 'app', section: 'fact', subject: 'GET /a', final: 'Actionable'}
  ]
  const r = recall(known, outcomes(), rows)
  assert.deepEqual(r.problems, [])
  assert.equal(r.byApp[0].recallDefaultPercent, 33.3)
  assert.equal(r.byApp[0].recallAnyPercent, 66.7)
  assert.deepEqual(r.byApp[0].violated, ['A-C2'])
  assert.deepEqual(r.regressions, ['A-2 (found-hidden)'])
  const respectedWrongly = recall(known, outcomes({'A-C2': {outcome: 'respected'}}), rows)
  assert.ok(respectedWrongly.problems.some((p) => p.includes('A-C2 is marked respected')))
  const unbacked = recall(known, outcomes({'A-1': {outcome: 'found-default'}, 'A-2': {outcome: 'found-hidden'}}), rows)
  assert.equal(unbacked.problems.length, 2, 'a found item names the rows that state it')
  assert.equal(recall(known, new Map()).problems.length, 6)
  const bad = recall(known, outcomes({'A-4': {outcome: 'not-exercised'}, 'Z-9': {outcome: 'missed'}}), [
    {id: 'app/fact/1', final: 'Noise'}
  ])
  // not-exercised without registration, an unknown id, an unbacked violation, and a found row that is not a fact
  assert.equal(bad.problems.length, 4)

  const runs = []
  for (let q = 1; q <= 10; q++) {
    runs.push({question: String(q), arm: '2.0', result: 'correct', calls: '5', help_calls: '1'})
    runs.push({question: String(q), arm: '1.x', result: q < 9 ? 'correct' : 'wrong', calls: '6', help_calls: '2'})
  }
  assert.equal(investigations(runs, protocol).meetsTarget, true)
  runs[0].result = 'partial'
  assert.equal(investigations(runs, protocol).meetsTarget, false)
  assert.equal(investigations(runs.slice(1), protocol).problems.length, 1)
  assert.equal(investigations([...runs, runs[3]], protocol).problems.length, 2)
})

test('the scorer reproduces the first run: 16 of 116 rows useful to both reviewers, 19 misleading for either', () => {
  const fixture = join(here, 'fixtures', 'first-run')
  const worksheet = JSON.parse(readFileSync(join(fixture, 'worksheet.json'), 'utf8'))
  const reviewers = Object.fromEntries(
    ['r1', 'r2'].map((name) => [name, readCsvById(readFileSync(join(fixture, `${name}.csv`), 'utf8'), name).map])
  )
  const judged = judge(worksheet, reviewers, new Map())
  const s = score(judged, protocol, [])
  assert.equal(s.groups.tuned.facts, 116)
  assert.equal(s.groups.tuned.useful, 16)
  assert.equal(s.groups.tuned.misleadingEither, 19)
  assert.equal(judged.rows.filter((r) => r.judgments.r1 === 'Misleading' && r.judgments.r2 === 'Misleading').length, 6)
  assert.equal(judged.rows.filter((r) => r.judgments.r1 === r.judgments.r2).length, 77)
  const petclinic = s.byApp.find((a) => a.app === 'petclinic')
  assert.equal(petclinic.facts, 32)
  assert.equal(petclinic.misleadingEither, 5)
  // Without adjudication, the 6 rows both reviewers judged Misleading are confirmed; the rest wait for the maintainer.
  assert.equal(s.groups.tuned.misleadingFacts, 6)
})

test('the command line lists rows to adjudicate without scores, and refuses a final score with anything missing', () => {
  const fixture = join(here, 'fixtures', 'first-run')
  const out = mkdtempSync(join(tmpdir(), 'bootui-first-run-'))
  const worksheet = JSON.parse(readFileSync(join(fixture, 'worksheet.json'), 'utf8'))
  writeFileSync(join(out, 'worksheet.json'), JSON.stringify({...worksheet, harnessSha256: harnessHash()}))
  const args = [
    join(here, 'score.mjs'),
    '--worksheet',
    join(out, 'worksheet.json'),
    '--reviewer',
    `r1=${join(fixture, 'r1.csv')}`,
    '--reviewer',
    `r2=${join(fixture, 'r2.csv')}`,
    '--out',
    out,
    '--registration-ref',
    'none'
  ]
  const listing = execFileSync(process.execPath, [...args, '--to-adjudicate'], {encoding: 'utf8'})
  assert.match(listing, /^39 rows to adjudicate/)
  assert.equal(parseCsv(readFileSync(join(out, 'to-adjudicate.csv'), 'utf8')).length, 39)
  assert.equal(existsSync(join(out, 'score.md')), false, 'no score before adjudication')
  const final = spawnSync(process.execPath, args, {encoding: 'utf8'})
  assert.equal(final.status, 3)
  assert.match(final.stderr, /needs the maintainer's adjudication/)
  assert.match(final.stderr, /recall is part of the protocol/)
})

test('with no fact anywhere, nothing passes, the escalation triggers, and every kind is silent or not listed', () => {
  const rows = [
    {id: 'a/honesty/1', app: 'a', role: 'tuned', kind: 'event-loop-blocking', section: 'honesty', subject: '(check)'}
  ]
  const judged = judge(
    {rows},
    {r1: reviewerMap({'a/honesty/1': 'Honest'}), r2: reviewerMap({'a/honesty/1': 'Honest'})},
    new Map()
  )
  const inventory = [{kind: 'gc-inflated-latency', checks: {}, listed: 0, hidden: 0, unlistedByDesign: true}]
  const s = score(judged, protocol, inventory)
  assert.equal(s.groups.pooled.facts, 0)
  assert.equal(s.groups.pooled.usefulPercent, null)
  assert.equal(s.groups.tuned.meetsTarget, false)
  assert.equal(s.escalation.pooledBelow, true)
  assert.equal(s.escalation.holdoutEmpty, true)
  assert.equal(s.escalation.triggered, true)
  assert.deepEqual(
    s.perKind.map((k) => [k.kind, k.status, k.outcome]),
    [
      ['event-loop-blocking', 'SILENT', 'folds (escalation)'],
      ['gc-inflated-latency', 'NOT_LISTED', 'not listed by default; judged through the hidden sample']
    ]
  )
})

test('time to first observation takes one measurement per application on the rerun commit', () => {
  const rows = [
    {app: 'petclinic', bootuiCommit: 'old', seconds: 900},
    {app: 'petclinic', bootuiCommit: 'c0ffee', seconds: 400},
    {app: 'petclinic', bootuiCommit: 'c0ffee', seconds: 20, reason: 'the first build hit a network error'},
    {app: 'timeless', bootuiCommit: 'c0ffee', seconds: 200},
    {app: 'timeless', bootuiCommit: 'c0ffee', seconds: 100}
  ]
  const t = timeToFirstObservation(rows, protocol, {bootuiCommit: 'c0ffee'})
  assert.deepEqual(
    t.rows.map((r) => [r.app, r.seconds]),
    [
      ['petclinic', 20],
      ['timeless', 100]
    ]
  )
  assert.deepEqual(
    t.superseded.map((r) => [r.app, r.seconds, r.supersededBecause]),
    [
      ['petclinic', 400, 'the first build hit a network error'],
      ['timeless', 200, null]
    ]
  )
  assert.ok(t.problems.includes('timeless was measured again without a reason'))
  assert.equal(t.problems.filter((p) => p.startsWith('no time to first observation')).length, 5)
})

test('a kind whose rows are all hidden, or whose only listed rows are honesty rows, is not silent', () => {
  const rows = [
    {id: 'a/hidden/1', app: 'a', role: 'tuned', kind: 'safe-method-dml', section: 'hidden', subject: 'GET /x'},
    {id: 'a/fact/1', app: 'a', role: 'tuned', kind: 'repeated-selects', section: 'fact', subject: 'GET /y'},
    {id: 'h/fact/1', app: 'h', role: 'holdout', kind: 'repeated-selects', section: 'fact', subject: 'GET /z'}
  ]
  const j = {'a/hidden/1': 'Noise', 'a/fact/1': 'Actionable', 'h/fact/1': 'Actionable'}
  const judged = judge({rows}, {r1: reviewerMap(j), r2: reviewerMap(j)}, new Map())
  const inventory = [
    {kind: 'safe-method-dml', checks: {a: 'EVALUATED'}, listed: 0, hidden: 4, unlistedByDesign: false},
    {kind: 'proxy-bypass', checks: {a: 'EVALUATED'}, listed: 0, hidden: 0, unlistedByDesign: false},
    {kind: 'route-time-breakdown', checks: {a: 'EVALUATED'}, listed: 2, hidden: 5, unlistedByDesign: false}
  ]
  const s = score(judged, protocol, inventory)
  assert.equal(s.escalation.triggered, false)
  const kinds = Object.fromEntries(s.perKind.map((k) => [k.kind, k]))
  assert.equal(kinds['safe-method-dml'].status, 'ALL_HIDDEN')
  assert.equal(kinds['safe-method-dml'].outcome, 'hidden, not externally validated: no row of it was listed by default')
  assert.equal(kinds['route-time-breakdown'].status, 'LISTED_NO_FACTS', 'only INSUFFICIENT rows listed')
  assert.equal(kinds['route-time-breakdown'].outcome, 'hidden, not externally validated: only honesty rows were listed')
  assert.equal(kinds['proxy-bypass'].status, 'SILENT')
  assert.equal(kinds['proxy-bypass'].outcome, 'stays listed, marked as not externally validated')
})

test('recall: found rows belong to the item’s application, and unmatched counterexample subjects warn', () => {
  const known = {
    applications: {
      app: {misses: [{id: 'A-1'}], counterexamples: [{id: 'A-C1', subjects: ['com.example.Gone']}]}
    }
  }
  const rows = [{id: 'other/fact/1', app: 'other', section: 'fact', subject: 'GET /a', final: 'Actionable'}]
  const judgments = new Map([
    ['A-1', {id: 'A-1', outcome: 'found-default', rows: 'other/fact/1'}],
    ['A-C1', {id: 'A-C1', outcome: 'respected'}]
  ])
  const r = recall(known, judgments, rows, {app: ['com.example.Present']})
  assert.match(r.problems[0], /must be app's worksheet facts/)
  assert.match(r.warnings[0], /A-C1: none of its subjects appears/)
})

test('investigations must run on the rerun commit', () => {
  const rows = []
  for (let q = 1; q <= 10; q++) {
    rows.push({question: String(q), arm: '2.0', result: 'correct', calls: '5', bootui_commit: 'c0ffee'})
    rows.push({
      question: String(q),
      arm: '1.x',
      result: 'correct',
      calls: '6',
      bootui_commit: q === 3 ? 'old' : 'c0ffee'
    })
  }
  const r = investigations(rows, protocol, 'c0ffee')
  assert.match(r.problems[0], /1 investigations did not run on the rerun's BootUI commit/)
  assert.equal(r.meetsTarget, false)
})

test('three facts or more on a single application are under-sampled, and so hidden', () => {
  const rows = [0, 1, 2, 3].map((i) => ({
    id: `a/fact/${i}`,
    app: 'a',
    role: 'tuned',
    kind: 'repeated-selects',
    section: 'fact',
    subject: `S${i}`
  }))
  const all = Object.fromEntries(rows.map((r) => [r.id, 'Actionable']))
  const s = score(judge({rows}, {r1: reviewerMap(all), r2: reviewerMap(all)}, new Map()), protocol, [])
  const kind = s.perKind.find((k) => k.kind === 'repeated-selects')
  assert.equal(kind.usefulPercent, 100)
  assert.equal(kind.status, 'UNDER_SAMPLED')
  assert.equal(kind.outcome, 'folds (escalation)', 'the holdouts have no fact, so the escalation triggers')
  const agentApp = [
    ...rows,
    {id: 'a+agent/fact/9', app: 'a+agent', role: 'agent', kind: 'repeated-selects', section: 'fact', subject: 'S9'}
  ]
  const both = Object.fromEntries(agentApp.map((r) => [r.id, 'Actionable']))
  const t = score(judge({rows: agentApp}, {r1: reviewerMap(both), r2: reviewerMap(both)}, new Map()), protocol, [])
  assert.equal(t.perKind.find((k) => k.kind === 'repeated-selects').apps, 1, 'an agent run is the same application')
})

test('the registration tag must be annotated and the one origin publishes; offline is not final', () => {
  const git = (...args) => execFileSync('git', args, {cwd: here, encoding: 'utf8'}).trim()
  const name = `m4-20-selftest-${process.pid}`
  git(
    '-c',
    'user.name=selftest',
    '-c',
    'user.email=selftest@example.invalid',
    'tag',
    '-a',
    name,
    '-m',
    'self-test',
    'HEAD'
  )
  try {
    const tag = git('rev-parse', `refs/tags/${name}`)
    const clean = (r) => r.problems.filter((p) => !/differs from|does not register/.test(p))
    assert.deepEqual(clean(checkRegistration(name, [], {lookupRemote: () => tag})), [])
    assert.equal(checkRegistration(name, [], {lookupRemote: () => tag}).final, true)
    assert.match(
      clean(checkRegistration(name, [], {lookupRemote: () => 'f'.repeat(40)}))[0],
      /not the one origin publishes/
    )
    assert.match(clean(checkRegistration(name, [], {lookupRemote: () => null}))[0], /origin has no tag/)
    const unreachable = () => {
      throw new Error('unreachable')
    }
    assert.match(clean(checkRegistration(name, [], {lookupRemote: unreachable}))[0], /origin is unreachable/)
    const offline = checkRegistration(name, [], {lookupRemote: unreachable, offline: true})
    assert.deepEqual(clean(offline), [])
    assert.equal(offline.final, false)
  } finally {
    git('tag', '-d', name)
  }
  assert.match(checkRegistration('m4-20-no-such-tag').problems[0], /does not exist/)
  assert.equal(checkRegistration('none').final, false)
})
