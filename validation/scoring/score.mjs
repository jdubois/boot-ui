#!/usr/bin/env node
// Scores a rerun under the registered protocol and writes the tables the validation report needs.
//
//   1. Before adjudication, list what the maintainer must settle, without any score:
//      node validation/scoring/score.mjs --worksheet worksheet.json --reviewer r1=r1.csv --reviewer r2=r2.csv \
//        --out <dir> --to-adjudicate
//   2. The final score, which refuses to run while anything is missing:
//      node validation/scoring/score.mjs --worksheet worksheet.json --evidence <root> --reviewer r1=r1.csv \
//        --reviewer r2=r2.csv --adjudication adjudication.csv --recall-judgments recall.csv \
//        [--investigations investigations.csv] [--ttfo ttfo.jsonl] --out <dir>
//
// The harness must match the registration tag (protocol.json `registration.ref`, an annotated tag on the commit that
// registered the protocol), and every run's BootUI commit must descend from it; `--registration-ref none` skips that
// check for tests, and the output says it is not a final score. Time to first observation and the investigations are
// required; `--partial` scores without them and stamps the output as partial. The recall
// list is always the registered validation/recall/known-misses.json, and the worksheet is rebuilt from the evidence
// and must match the one the reviewers judged.
//
// Reviewer files: CSV with `id,judgment,note` (the worksheet template works as is). Facts and hidden rows take
// Actionable, Informative, Noise, or Misleading; honesty rows take Honest, Hides, or Misleading.
// Adjudication file: CSV with `id,judgment,reason` for every row the reviewers judged differently, and every row one of
// them judged Misleading. Two reviewers who agree are never overruled, Misleading included.
// Recall file: CSV with `id,outcome,rows,note`; `rows` lists the worksheet ids that support the outcome.

import {createHash} from 'node:crypto'
import {mkdirSync, readFileSync, writeFileSync} from 'node:fs'
import {dirname, join} from 'node:path'
import {fileURLToPath} from 'node:url'
import {parseArgs} from 'node:util'
import {HONESTY, JUDGMENTS, USEFUL, atLeast, below, parseCsv, percent, toCsv, upperBound95} from './lib.mjs'
import {checkRegistration, harnessHash} from './harness.mjs'
import {validationHome, worksheetFor} from './worksheet.mjs'

const baseApp = (app) => String(app ?? '').replace(/\+agent$/, '')
const ROLES = ['tuned', 'holdout', 'agent']

export function readCsvById(text, label) {
  const map = new Map()
  const problems = []
  for (const row of parseCsv(text)) {
    if (!row.id) continue
    if (map.has(row.id)) problems.push(`${label} lists ${row.id} twice`)
    map.set(row.id, row)
  }
  return {map, problems}
}

export function judge(worksheet, reviewers, adjudications) {
  const problems = []
  const warnings = []
  const names = Object.keys(reviewers)
  if (names.length !== 2) problems.push(`exactly two reviewers are required, got ${names.length}`)
  const known = new Set(worksheet.rows.map((r) => r.id))
  for (const [name, judgments] of Object.entries(reviewers)) {
    for (const id of judgments.keys()) if (!known.has(id)) problems.push(`${name} judged unknown row ${id}`)
  }
  for (const id of adjudications.keys()) if (!known.has(id)) problems.push(`adjudication of unknown row ${id}`)
  const rows = worksheet.rows.map((row) => {
    if (!ROLES.includes(row.role)) problems.push(`${row.id} has role ${row.role}, not one of ${ROLES.join(', ')}`)
    const allowed = row.section === 'honesty' ? HONESTY : JUDGMENTS
    const [j1, j2] = names.map((name) => reviewers[name].get(row.id)?.judgment || null)
    for (const [i, j] of [j1, j2].entries()) {
      if (!j) problems.push(`${names[i]} has no judgment for ${row.id}`)
      else if (!allowed.includes(j)) problems.push(`${names[i]} judged ${row.id} "${j}", expected ${allowed.join('|')}`)
    }
    const agree = j1 !== null && j1 === j2
    const needsAdjudication = !agree || j1 === null || (j1 === 'Misleading') !== (j2 === 'Misleading')
    const adjudication = adjudications.get(row.id)
    let final = null
    if (agree) {
      if (adjudication && adjudication.judgment !== j1) {
        warnings.push(`adjudication of ${row.id} ignored: both reviewers judged it ${j1}, and they are not overruled`)
      }
      final = j1
    } else if (!adjudication?.judgment) {
      problems.push(`${row.id} needs the maintainer's adjudication (${j1} / ${j2})`)
    } else if (!allowed.includes(adjudication.judgment)) {
      problems.push(`adjudication of ${row.id} is not one of ${allowed.join('|')}`)
    } else if (!adjudication.reason) {
      problems.push(`adjudication of ${row.id} gives no reason`)
    } else {
      final = adjudication.judgment
    }
    return {
      ...row,
      judgments: Object.fromEntries(names.map((name, i) => [name, [j1, j2][i]])),
      bothUseful: USEFUL.includes(j1) && USEFUL.includes(j2),
      actionableEither: j1 === 'Actionable' || j2 === 'Actionable',
      misleadingEither: j1 === 'Misleading' || j2 === 'Misleading',
      needsAdjudication: needsAdjudication && !agree,
      final,
      adjudicationReason: agree ? null : adjudication?.reason || null
    }
  })
  return {rows, problems, warnings, reviewers: names}
}

function tally(facts, honesty = []) {
  const useful = facts.filter((r) => r.bothUseful).length
  return {
    facts: facts.length,
    useful,
    usefulPercent: percent(useful, facts.length),
    misleadingFacts: facts.filter((r) => r.final === 'Misleading').length,
    // A listed row or check whose status or reason is false is misleading too: nothing misleading stays listed.
    misleadingHonesty: honesty.filter((r) => r.final === 'Misleading').length,
    misleadingEither: facts.filter((r) => r.misleadingEither).length,
    // For the record only, never for a gate: the useful share after the maintainer settled disagreements.
    adjudicatedUsefulPercent: percent(facts.filter((r) => USEFUL.includes(r.final)).length, facts.length)
  }
}

const misleadingOf = (t) => t.misleadingFacts + t.misleadingHonesty

export function score(judged, protocol, inventory = []) {
  const g = protocol.gates
  const facts = judged.rows.filter((r) => r.section === 'fact')
  const honestyRows = judged.rows.filter((r) => r.section === 'honesty')
  const of = (rows, roles) => rows.filter((r) => roles.includes(r.role))
  const groups = {
    tuned: tally(of(facts, ['tuned']), of(honestyRows, ['tuned'])),
    holdout: tally(of(facts, ['holdout']), of(honestyRows, ['holdout'])),
    pooled: tally(of(facts, ['tuned', 'holdout']), of(honestyRows, ['tuned', 'holdout'])),
    agent: tally(of(facts, ['agent']), of(honestyRows, ['agent']))
  }
  for (const name of ['tuned', 'holdout', 'pooled']) {
    const group = groups[name]
    group.misleading = misleadingOf(group)
    group.meetsTarget = atLeast(group.useful, group.facts, g.target.usefulPercent) && group.misleading === 0
    group.meetsPostM3 = atLeast(group.useful, group.facts, g.postM3.usefulPercent)
  }
  groups.agent.misleading = misleadingOf(groups.agent)
  const {tuned, holdout, pooled} = groups
  // tuned − holdout > 20 points, compared exactly: uT/fT − uH/fH > 0.20.
  const gapTooLarge =
    tuned.facts > 0 &&
    holdout.facts > 0 &&
    100 * (tuned.useful * holdout.facts - holdout.useful * tuned.facts) >
      g.escalation.holdoutGapAbovePoints * tuned.facts * holdout.facts
  const escalation = {
    pooledBelow: pooled.facts === 0 || below(pooled.useful, pooled.facts, g.escalation.pooledBelowPercent),
    holdoutGap:
      tuned.facts && holdout.facts
        ? Math.round(10 * ((100 * tuned.useful) / tuned.facts - (100 * holdout.useful) / holdout.facts)) / 10
        : null,
    holdoutGapTooLarge: gapTooLarge,
    // Filters that hide everything on the holdouts cannot escape the comparison.
    holdoutEmpty: holdout.facts === 0
  }
  escalation.triggered = escalation.pooledBelow || escalation.holdoutGapTooLarge || escalation.holdoutEmpty

  const apps = [...new Set(judged.rows.map((r) => r.app))].sort()
  const byApp = apps.map((app) => {
    const t = tally(
      facts.filter((r) => r.app === app),
      honestyRows.filter((r) => r.app === app)
    )
    return {app, role: judged.rows.find((r) => r.app === app)?.role, ...t, misleading: misleadingOf(t)}
  })

  const hidden = judged.rows.filter((r) => r.section === 'hidden')
  const kinds = [...new Set([...inventory.map((k) => k.kind), ...judged.rows.map((r) => r.kind)])].sort()
  const perKind = kinds.map((kind) => {
    const kindFacts = facts.filter((r) => r.kind === kind)
    const t = tally(
      kindFacts,
      honestyRows.filter((r) => r.kind === kind)
    )
    const misleading = misleadingOf(t)
    const kindApps = new Set(kindFacts.map((r) => baseApp(r.app)))
    const kindHidden = hidden.filter((r) => r.kind === kind)
    const hiddenUseful = kindHidden.filter((r) => r.bothUseful).length
    const entry = inventory.find((k) => k.kind === kind)
    const byDesign = entry?.unlistedByDesign ?? protocol.unlistedByDesign.includes(kind)
    const ran = !entry || Object.values(entry.checks).some((status) => status === 'EVALUATED' || status === 'PARTIAL')
    let status
    if (kindFacts.length === 0) {
      // Silent means the kind produced no row at all; a kind whose every row was filtered out of the default list
      // cannot escape its gate by being hidden.
      const anyRow = (entry ? entry.listed + entry.hidden : 0) + kindHidden.length > 0
      if (byDesign) status = 'NOT_LISTED'
      else if (misleading > 0) status = 'FAIL'
      else if (anyRow) status = 'ALL_HIDDEN'
      else status = ran ? 'SILENT' : 'NOT_EXERCISED'
    } else if (misleading > g.perKind.misleading) status = 'FAIL'
    else if (kindFacts.length < g.perKind.minFacts || kindApps.size < g.perKind.minApps) status = 'UNDER_SAMPLED'
    else status = atLeast(t.useful, kindFacts.length, g.perKind.usefulPercent) ? 'PASS' : 'FAIL'
    const outcomes = {
      PASS: 'stays listed by default',
      FAIL: 'folds into its panel or stays hidden',
      UNDER_SAMPLED: 'hidden, not externally validated: too few facts',
      SILENT: 'stays listed, marked as not externally validated',
      NOT_EXERCISED: 'stays listed, marked as not externally validated: its check never ran',
      ALL_HIDDEN: 'hidden, not externally validated: no row of it was listed by default',
      NOT_LISTED: 'not listed by default; judged through the hidden sample'
    }
    // Under escalation, every kind that does not pass its gate folds, the silent, not-exercised, and under-sampled
    // ones included (D35, with the maintainer's ruling of 2026-10-05).
    const folds = escalation.triggered && status !== 'PASS' && status !== 'NOT_LISTED'
    return {
      kind,
      ...t,
      misleading,
      apps: kindApps.size,
      tunedUseful: `${kindFacts.filter((r) => r.role === 'tuned' && r.bothUseful).length}/${kindFacts.filter((r) => r.role === 'tuned').length}`,
      holdoutUseful: `${kindFacts.filter((r) => r.role === 'holdout' && r.bothUseful).length}/${kindFacts.filter((r) => r.role === 'holdout').length}`,
      listedRows: entry?.listed ?? null,
      hiddenRows: entry?.hidden ?? null,
      hiddenSampled: kindHidden.length,
      hiddenUseful,
      hiddenActionable: kindHidden.filter((r) => r.actionableEither).length,
      // The default list hides value when sampled hidden rows of the kind are useful at least as often as its listed ones.
      filterHidesValue:
        hiddenUseful > 0 && (kindFacts.length === 0 || hiddenUseful * kindFacts.length >= t.useful * kindHidden.length),
      status,
      outcome: folds ? 'folds (escalation)' : outcomes[status]
    }
  })

  const hiddenByApp = apps
    .map((app) => {
      const rows = hidden.filter((r) => r.app === app)
      return {
        app,
        sampled: rows.length,
        actionableEither: rows.filter((r) => r.actionableEither).length,
        usefulToBoth: rows.filter((r) => r.bothUseful).length,
        misleading: rows.filter((r) => r.final === 'Misleading').length
      }
    })
    .filter((a) => a.sampled > 0)
  // Hidden value: either reviewer judged a hidden row Actionable. The adjudication is recorded, not decisive.
  const hiddenValue = hidden.filter((r) => r.actionableEither)
  const hiddenSummary = {
    sampled: hidden.length,
    actionableEither: hiddenValue.length,
    usefulToBoth: hidden.filter((r) => r.bothUseful).length,
    upperBoundPercent: upperBound95(hiddenValue.length, hidden.length)
  }

  const honesty = {
    rows: honestyRows.length,
    honest: honestyRows.filter((r) => r.final === 'Honest').length,
    hides: honestyRows.filter((r) => r.final === 'Hides'),
    misleading: honestyRows.filter((r) => r.final === 'Misleading')
  }
  honesty.honestPercent = percent(honesty.honest, honesty.rows)

  return {groups, escalation, byApp, perKind, hiddenByApp, hiddenValue, hiddenSummary, honesty}
}

const RECALL_OUTCOMES = ['found-default', 'found-hidden', 'honest-gap', 'missed', 'not-exercised']

export function recall(knownMisses, judgments, judgedRows = [], evidenceSubjects = null) {
  const out = []
  const problems = []
  const warnings = []
  const regressions = []
  const byId = new Map(judgedRows.map((r) => [r.id, r]))
  const known = new Set()
  for (const [app, entry] of Object.entries(knownMisses.applications)) {
    const misses = entry.misses.map((m) => ({...m, ...judgments.get(m.id)}))
    const counter = (entry.counterexamples || []).map((c) => ({...c, ...judgments.get(c.id)}))
    for (const m of misses) {
      known.add(m.id)
      if (!RECALL_OUTCOMES.includes(m.outcome))
        problems.push(`recall item ${m.id} has no outcome (${RECALL_OUTCOMES.join('|')})`)
      if (m.outcome === 'not-exercised' && m.exercised !== false) {
        problems.push(`recall item ${m.id} is registered as exercised by the traffic, so it cannot be not-exercised`)
      }
      if (m.firstRun === 'found' && ['found-hidden', 'missed', 'honest-gap'].includes(m.outcome)) {
        regressions.push(`${m.id} (${m.outcome})`)
      }
      const rows = (m.rows || '').split(/\s+/).filter(Boolean)
      const ownFact = (id) => byId.get(id)?.section === 'fact' && baseApp(byId.get(id).app) === app
      if (m.outcome === 'found-default' && !(rows.length && rows.every(ownFact))) {
        problems.push(`recall item ${m.id} is found in the default list, so its rows must be ${app}'s worksheet facts`)
      }
      if (m.outcome === 'found-hidden' && !rows.length) {
        problems.push(`recall item ${m.id} is found in a hidden row: name it (a worksheet id or an observation id)`)
      }
    }
    for (const c of counter) {
      known.add(c.id)
      if (!['respected', 'violated'].includes(c.outcome))
        problems.push(`counterexample ${c.id} has no outcome (respected|violated)`)
      const named = (c.subjects || []).filter((subject) => !subject.startsWith('('))
      if (c.outcome === 'respected' && evidenceSubjects && named.length) {
        const seen = new Set(evidenceSubjects[app] || [])
        if (!named.some((subject) => seen.has(subject))) {
          warnings.push(
            `counterexample ${c.id}: none of its subjects appears in ${app}'s evidence, so "respected" is unchecked`
          )
        }
      }
      if (c.outcome === 'respected') {
        const contradicting = judgedRows.filter(
          (r) =>
            baseApp(r.app) === app &&
            r.section === 'fact' &&
            r.final === 'Misleading' &&
            (c.subjects || []).includes(r.subject)
        )
        if (contradicting.length) {
          problems.push(
            `counterexample ${c.id} is marked respected, but ${contradicting.map((r) => r.id).join(', ')} on its subject is misleading`
          )
        }
      }
      if (c.outcome === 'violated') {
        const rows = (c.rows || '').split(/\s+/).filter(Boolean)
        if (!rows.length || !rows.every((id) => byId.get(id)?.final === 'Misleading')) {
          problems.push(`counterexample ${c.id} is violated, so its rows must be facts adjudicated Misleading`)
        }
      }
    }
    const exercised = misses.filter((m) => m.outcome !== 'not-exercised')
    const foundDefault = misses.filter((m) => m.outcome === 'found-default').length
    const foundHidden = misses.filter((m) => m.outcome === 'found-hidden').length
    out.push({
      app,
      known: misses.length,
      exercised: exercised.length,
      foundDefault,
      foundHidden,
      honestGap: misses.filter((m) => m.outcome === 'honest-gap').length,
      missed: misses.filter((m) => m.outcome === 'missed').length,
      recallDefaultPercent: percent(foundDefault, exercised.length),
      recallAnyPercent: percent(foundDefault + foundHidden, exercised.length),
      counterexamples: counter.length,
      violated: counter.filter((c) => c.outcome === 'violated').map((c) => c.id)
    })
  }
  for (const id of judgments.keys()) if (!known.has(id)) problems.push(`recall judgment for unknown item ${id}`)
  return {byApp: out, regressions, problems, warnings}
}

export function investigations(rows, protocol, commit = null) {
  const problems = []
  if (commit) {
    const others = rows.filter((r) => r.bootui_commit !== commit)
    if (others.length)
      problems.push(`${others.length} investigations did not run on the rerun's BootUI commit ${commit.slice(0, 9)}`)
  }
  const arms = {}
  const seen = new Set()
  for (const r of rows) {
    const key = `${r.arm}/${r.question}`
    if (seen.has(key)) problems.push(`investigation ${key} is listed twice`)
    seen.add(key)
    if (!['correct', 'partial', 'wrong'].includes(r.result))
      problems.push(`investigation ${key} has result "${r.result}"`)
    const arm = (arms[r.arm] ||= {correct: 0, partial: 0, wrong: 0, calls: 0, helpCalls: 0, questions: 0})
    arm.questions++
    arm[r.result] = (arm[r.result] || 0) + 1
    arm.calls += Number(r.calls || 0)
    arm.helpCalls += Number(r.help_calls || 0)
  }
  for (const name of ['2.0', '1.x']) {
    if (arms[name]?.questions !== protocol.investigations.count) {
      problems.push(
        `the ${name} arm has ${arms[name]?.questions ?? 0} questions, expected ${protocol.investigations.count}`
      )
    }
  }
  const v2 = arms['2.0']
  const v1 = arms['1.x']
  return {
    arms,
    problems,
    meetsTarget: Boolean(!problems.length && v2.correct === protocol.investigations.count && v2.calls < v1.calls)
  }
}

/**
 * One measurement per registered application on the rerun's BootUI commit. A repeated measurement must say why
 * (`reason`, written by ttfo.sh --reason); the last one counts, and the earlier ones are listed.
 */
export function timeToFirstObservation(rows, protocol, worksheet = {}) {
  const problems = []
  const commit = worksheet.bootuiCommit
  const byApp = new Map()
  for (const row of rows) {
    if (commit && row.bootuiCommit !== commit) continue
    if (!byApp.has(row.app)) byApp.set(row.app, [])
    byApp.get(row.app).push(row)
  }
  const superseded = []
  for (const [app, measured] of byApp) {
    measured.slice(1).forEach((row, i) => {
      if (!row.reason) problems.push(`${app} was measured again without a reason`)
      superseded.push({...measured[i], supersededBecause: row.reason || null})
    })
  }
  for (const app of [...protocol.tunedApps, ...protocol.holdoutApps]) {
    if (!byApp.has(app)) problems.push(`no time to first observation for ${app}`)
  }
  return {rows: [...byApp.values()].map((measured) => measured.at(-1)), superseded, problems}
}

const cell = (v) => (v === null || v === undefined ? '—' : String(v).replace(/\|/g, '\\|').replace(/\n/g, ' '))
const table = (header, rows) =>
  [
    `| ${header.join(' | ')} |`,
    `| ${header.map(() => '---').join(' | ')} |`,
    ...rows.map((r) => `| ${r.map(cell).join(' | ')} |`)
  ].join('\n')
const pct = (v) => (v === null ? '—' : `${v} %`)
const yes = (b) => (b ? 'Yes' : 'No')

export function markdown(result) {
  const {score: s, judged, recall: rc, investigations: inv, ttfo} = result
  const out = []
  if (result.registration.ref !== result.expectedRegistration || !result.registration.sha) {
    out.push(`**Not checked against the registration tag (${result.registration.ref}): not a final score.**`, '')
  } else {
    out.push(
      `Registered protocol: annotated tag \`${result.registration.ref}\` (\`${result.registration.tag}\`) on ` +
        `commit \`${result.registration.sha}\`.`,
      ''
    )
  }
  if (result.missing?.length) out.push(`**Partial score: missing ${result.missing.join(' and ')}.**`, '')
  if (result.reviewers) {
    out.push(`Reviewers: ${result.reviewers.map((r) => `${r.id} on ${r.model}`).join(', ')}.`, '')
  }
  if (result.holdoutExposure?.length) {
    out.push(
      '## Holdout exposure',
      '',
      'What the maintainer saw of each holdout before the rerun. It annotates the holdout scores and never removes a',
      'holdout fact from them or from the gap.',
      '',
      ...result.holdoutExposure.map((e) => `- ${e.app} (${e.date}): ${e.seen}`),
      ''
    )
  }
  if (result.notes?.length) out.push('## Worksheet notes', '', ...result.notes.map((n) => `- ${n}`), '')
  out.push('## Scores', '')
  out.push(
    table(
      [
        'Group',
        'Facts',
        'Useful to both',
        'Misleading (adjudicated, facts and honesty rows)',
        'Misleading (either reviewer)',
        '≥ 70 %, none misleading',
        '≥ 50 %'
      ],
      ['tuned', 'holdout', 'pooled'].map((name) => {
        const g = s.groups[name]
        return [
          name,
          g.facts,
          `${g.useful} (${pct(g.usefulPercent)})`,
          g.misleading,
          g.misleadingEither,
          yes(g.meetsTarget),
          yes(g.meetsPostM3)
        ]
      })
    ),
    ''
  )
  out.push(
    `Escalation (§2.3): pooled under 30 %: ${yes(s.escalation.pooledBelow)}; tuned minus holdout: ` +
      `${s.escalation.holdoutGap ?? '—'} points (above 20: ${yes(s.escalation.holdoutGapTooLarge)}); ` +
      `no holdout fact: ${yes(s.escalation.holdoutEmpty)}. ` +
      `**${s.escalation.triggered ? 'Triggered: every kind that does not pass its gate folds.' : 'Not triggered.'}**`,
    ''
  )
  out.push(
    `Agent-attached runs (their registered kinds only): ${s.groups.agent.facts} facts, ${s.groups.agent.useful} useful ` +
      `to both, ${s.groups.agent.misleading} misleading.`,
    ''
  )
  out.push('### Per application', '')
  out.push(
    table(
      ['Application', 'Role', 'Facts', 'Useful to both', 'Misleading'],
      s.byApp.map((a) => [a.app, a.role, a.facts, `${a.useful} (${pct(a.usefulPercent)})`, a.misleading])
    ),
    ''
  )
  out.push('### Per kind', '')
  out.push(
    table(
      [
        'Kind',
        'Facts',
        'Applications',
        'Useful to both',
        'Tuned',
        'Holdout',
        'Misleading',
        'Listed / hidden rows',
        'Hidden sampled, useful',
        'Gate',
        'Outcome'
      ],
      s.perKind.map((k) => [
        `\`${k.kind}\``,
        k.facts,
        k.apps,
        `${k.useful} (${pct(k.usefulPercent)})`,
        k.tunedUseful,
        k.holdoutUseful,
        k.misleading,
        `${k.listedRows ?? '—'} / ${k.hiddenRows ?? '—'}`,
        `${k.hiddenSampled}, ${k.hiddenUseful}${k.filterHidesValue ? ' (hides value)' : ''}`,
        k.status,
        k.outcome
      ])
    ),
    ''
  )
  out.push('### Honesty', '')
  out.push(
    `${s.honesty.rows} rows (insufficient or not applicable rows, and checks that did not fully run): ` +
      `${s.honesty.honest} honest (${pct(s.honesty.honestPercent)}), ${s.honesty.hides.length} hid something real, ` +
      `${s.honesty.misleading.length} misleading.`,
    ''
  )
  const notHonest = [...s.honesty.hides, ...s.honesty.misleading]
  if (notHonest.length) {
    out.push(
      table(
        ['Row', 'Kind', 'Subject', 'Status', 'Judgment', 'Reason'],
        notHonest.map((r) => [r.id, r.kind, r.subject, r.status, r.final, r.adjudicationReason])
      ),
      ''
    )
  }
  out.push('### Hidden-row sample', '')
  out.push(
    table(
      ['Application', 'Sampled', 'Actionable for either reviewer', 'Useful to both', 'Misleading'],
      s.hiddenByApp.map((a) => [a.app, a.sampled, a.actionableEither, a.usefulToBoth, a.misleading])
    ),
    '',
    `${s.hiddenSummary.actionableEither} of ${s.hiddenSummary.sampled} sampled hidden rows were judged actionable by a ` +
      `reviewer; among sampled rows, at 95 % confidence, the actionable share is at most ` +
      `${s.hiddenSummary.upperBoundPercent} % (the sample is stratified, so this does not bound all hidden rows).`,
    ''
  )
  if (s.hiddenValue.length) {
    out.push(
      table(
        ['Row', 'Kind', 'Subject', 'Stratum', 'Reason left out', ...judged.reviewers, 'Adjudicated'],
        s.hiddenValue.map((r) => [
          r.id,
          r.kind,
          r.subject,
          r.stratum,
          r.unlistedReason,
          ...judged.reviewers.map((n) => r.judgments[n]),
          r.final
        ])
      ),
      ''
    )
  }
  const misleading = judged.rows.filter((r) => r.misleadingEither)
  out.push('### Rows judged misleading by either reviewer', '')
  out.push(
    misleading.length
      ? table(
          ['Row', 'Section', 'Kind', 'Subject', ...judged.reviewers, 'Final', 'Reason'],
          misleading.map((r) => [
            r.id,
            r.section,
            r.kind,
            r.subject,
            ...judged.reviewers.map((n) => r.judgments[n]),
            r.final,
            r.adjudicationReason
          ])
        )
      : 'None.',
    ''
  )
  if (rc) {
    out.push('### Recall', '')
    out.push(
      table(
        [
          'Application',
          'Known',
          'Exercised',
          'Found (default list)',
          'Found (hidden only)',
          'Honest gap',
          'Missed',
          'Recall (default)',
          'Counterexamples violated'
        ],
        rc.byApp.map((a) => [
          a.app,
          a.known,
          a.exercised,
          a.foundDefault,
          a.foundHidden,
          a.honestGap,
          a.missed,
          pct(a.recallDefaultPercent),
          a.violated.join(', ') || 'none'
        ])
      ),
      '',
      rc.regressions.length
        ? `Found by the first run, not in the rerun's default list (regressions): ${rc.regressions.join(', ')}.`
        : "Every item the first run found is in the rerun's default list.",
      ''
    )
    if (rc.warnings?.length) out.push(...rc.warnings.map((w) => `- Warning: ${w}`), '')
  }
  if (inv) {
    out.push('### Agent investigations', '')
    out.push(
      table(
        ['Arm', 'Questions', 'Correct', 'Partial', 'Wrong', 'Tool calls', 'Of which --help'],
        Object.entries(inv.arms).map(([arm, a]) => [
          arm,
          a.questions,
          a.correct,
          a.partial,
          a.wrong,
          a.calls,
          a.helpCalls
        ])
      ),
      '',
      `Target (all ten correct with 2.0, fewer calls than 1.x): ${yes(inv.meetsTarget)}.`,
      ''
    )
  }
  if (ttfo) {
    out.push('### Time to first observation', '')
    out.push(
      table(
        ['Application', 'Stack', 'Minutes', 'First row', 'First OBSERVED row, minutes', '≤ 5 minutes'],
        ttfo.rows.map((t) => [
          t.app,
          t.stack,
          (t.seconds / 60).toFixed(1),
          `${t.firstKind} ${t.firstStatus}`,
          t.firstObservedSeconds === null ? '—' : (t.firstObservedSeconds / 60).toFixed(1),
          yes(t.seconds !== null && t.seconds <= 300)
        ])
      ),
      '',
      ...ttfo.superseded.map((t) => `- Superseded measurement of ${t.app} (${t.seconds} s): ${t.supersededBecause}`),
      ''
    )
  }
  return out.join('\n')
}

const sha256 = (path) => createHash('sha256').update(readFileSync(path)).digest('hex')

function main() {
  const {values} = parseArgs({
    options: {
      worksheet: {type: 'string'},
      reviewer: {type: 'string', multiple: true, default: []},
      adjudication: {type: 'string'},
      evidence: {type: 'string'},
      'registration-ref': {type: 'string'},
      'recall-judgments': {type: 'string'},
      investigations: {type: 'string'},
      ttfo: {type: 'string'},
      out: {type: 'string'},
      'to-adjudicate': {type: 'boolean', default: false},
      partial: {type: 'boolean', default: false}
    }
  })
  if (!values.worksheet || !values.out || values.reviewer.length === 0) {
    console.error(
      'usage: score.mjs --worksheet <file> --reviewer name=<csv> --reviewer name=<csv> [--adjudication <csv>] --out <dir>'
    )
    process.exit(64)
  }
  const protocol = JSON.parse(readFileSync(join(validationHome, 'protocol.json'), 'utf8'))
  const worksheet = JSON.parse(readFileSync(values.worksheet, 'utf8'))
  const problems = []
  const current = harnessHash()
  if (worksheet.harnessSha256 !== current) {
    problems.push('the harness changed since the worksheet was generated: the protocol is not the registered one')
  }
  const registration = checkRegistration(values['registration-ref'] || protocol.registration.ref)
  problems.push(...registration.problems)
  if ((worksheet.notes || []).some((n) => n.startsWith('INCOMPLETE'))) {
    problems.push('the worksheet was built from an incomplete run set (--allow-incomplete)')
  }
  const panel = protocol.reviewers.panel.map((r) => r.id)
  const given = values.reviewer.map((spec) => spec.split('=')[0])
  if (given.join() !== panel.join())
    problems.push(`the reviewers must be the registered ${panel.join(' and ')}, in that order`)
  const inputs = {}
  const reviewers = {}
  for (const spec of values.reviewer) {
    const [name, path] = spec.includes('=') ? spec.split(/=(.*)/s) : [spec, spec]
    const read = readCsvById(readFileSync(path, 'utf8'), `reviewer ${name}`)
    reviewers[name] = read.map
    problems.push(...read.problems)
    inputs[`reviewer ${name}`] = sha256(path)
  }
  let adjudications = new Map()
  if (values.adjudication) {
    const read = readCsvById(readFileSync(values.adjudication, 'utf8'), 'the adjudication')
    adjudications = read.map
    problems.push(...read.problems)
    inputs.adjudication = sha256(values.adjudication)
  }
  const judged = judge(worksheet, reviewers, adjudications)
  mkdirSync(values.out, {recursive: true})

  if (values['to-adjudicate']) {
    // No score here: the maintainer adjudicates without knowing which ruling moves which gate.
    const todo = judged.rows.filter((r) => r.needsAdjudication && Object.values(r.judgments).every(Boolean))
    writeFileSync(
      join(values.out, 'to-adjudicate.csv'),
      toCsv(
        todo.map((r) => ({
          ...r,
          [judged.reviewers[0]]: r.judgments[judged.reviewers[0]],
          [judged.reviewers[1]]: r.judgments[judged.reviewers[1]],
          judgment: '',
          reason: ''
        })),
        ['id', 'section', 'app', 'kind', 'subject', 'status', 'sentence', ...judged.reviewers, 'judgment', 'reason']
      )
    )
    const other = [...problems, ...judged.problems].filter((p) => !p.includes("needs the maintainer's adjudication"))
    for (const problem of other) console.error(`error: ${problem}`)
    console.log(`${todo.length} rows to adjudicate, in ${join(values.out, 'to-adjudicate.csv')}`)
    process.exit(other.length ? 3 : 0)
  }

  problems.push(...judged.problems)
  if (values.evidence) {
    // The worksheet the reviewers judged must be the one the evidence gives under the registered rules.
    const rebuilt = worksheetFor(values.evidence, protocol, registration.ref)
    problems.push(...rebuilt.problems)
    const shape = (w) => JSON.stringify({rows: w.rows, inventory: w.inventory})
    if (shape(rebuilt.worksheet) !== shape(worksheet))
      problems.push('the worksheet does not match the one the evidence gives')
    inputs.evidence = values.evidence
  } else {
    problems.push('pass --evidence, so the worksheet can be checked against the evidence')
  }
  const result = {
    protocol: protocol.name,
    registration: {ref: registration.ref, tag: registration.tag, sha: registration.sha},
    expectedRegistration: protocol.registration.ref,
    harnessSha256: current,
    reviewers: protocol.reviewers.panel,
    holdoutExposure: protocol.holdoutExposure,
    missing: [],
    inputs,
    notes: worksheet.notes || [],
    judged,
    score: score(judged, protocol, worksheet.inventory)
  }
  if (values['recall-judgments']) {
    const read = readCsvById(readFileSync(values['recall-judgments'], 'utf8'), 'the recall judgments')
    problems.push(...read.problems)
    const known = JSON.parse(readFileSync(join(validationHome, 'recall', 'known-misses.json'), 'utf8'))
    result.recall = recall(known, read.map, judged.rows, worksheet.subjects || null)
    problems.push(...result.recall.problems)
    for (const warning of result.recall.warnings) console.warn(`warning: ${warning}`)
  } else {
    problems.push('recall is part of the protocol: pass --recall-judgments')
  }
  if (values.investigations) {
    result.investigations = investigations(
      parseCsv(readFileSync(values.investigations, 'utf8')),
      protocol,
      worksheet.bootuiCommit
    )
    problems.push(...result.investigations.problems)
  } else {
    result.missing.push('the agent investigations')
  }
  if (values.ttfo) {
    const rows = readFileSync(values.ttfo, 'utf8')
      .split('\n')
      .filter(Boolean)
      .map((line) => JSON.parse(line))
    result.ttfo = timeToFirstObservation(rows, protocol, worksheet)
    problems.push(...result.ttfo.problems)
  } else {
    result.missing.push('the time to first observation')
  }
  if (result.missing.length && !values.partial) {
    problems.push(`missing ${result.missing.join(' and ')}: pass them, or --partial for a score marked partial`)
  }
  for (const warning of judged.warnings) console.warn(`warning: ${warning}`)
  if (problems.length) {
    for (const problem of problems.slice(0, 50)) console.error(`error: ${problem}`)
    if (problems.length > 50) console.error(`... and ${problems.length - 50} more`)
    process.exit(3)
  }
  writeFileSync(join(values.out, 'score.json'), JSON.stringify(result, null, 2) + '\n')
  writeFileSync(join(values.out, 'score.md'), markdown(result) + '\n')
  const s = result.score
  console.log(
    `pooled ${pct(s.groups.pooled.usefulPercent)} (${s.groups.pooled.useful}/${s.groups.pooled.facts}), tuned ` +
      `${pct(s.groups.tuned.usefulPercent)}, holdout ${pct(s.groups.holdout.usefulPercent)}, misleading ` +
      `${s.groups.pooled.misleading}; escalation ${s.escalation.triggered ? 'TRIGGERED' : 'not triggered'}; ` +
      `tables in ${join(values.out, 'score.md')}`
  )
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) main()
