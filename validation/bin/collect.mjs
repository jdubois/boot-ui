// Saves what the reviewers judge, once an application's traffic has finished: the full Runtime Insights report (every
// observation, with `listed` and `unlistedReason` when the build has M4-19), every observation's evidence, the agent
// view as an agent first sees it (the default list) and with `query=all`, the run comparison in both forms, the
// journal status, the panel list, and the Java agent status. Nothing here triggers a scan or a mutation.
//
//   node validation/bin/collect.mjs --base-url http://localhost:18181 --out <dir> [--api-path /bootui/api]

import {mkdirSync, writeFileSync} from 'node:fs'
import {join} from 'node:path'
import {parseArgs} from 'node:util'

const {values} = parseArgs({
  options: {
    'base-url': {type: 'string'},
    'api-path': {type: 'string', default: '/bootui/api'},
    out: {type: 'string'},
    'max-limit': {type: 'string', default: '1000'}
  }
})
if (!values['base-url'] || !values.out) {
  console.error('usage: collect.mjs --base-url <url> --out <dir> [--api-path /bootui/api]')
  process.exit(64)
}
const base = values['base-url'].replace(/\/+$/, '')
if (new URL(base).port === '8080') throw new Error('Port 8080 is reserved for the developer’s own application')
const api = base + values['api-path']
const out = values.out
mkdirSync(join(out, 'insights'), {recursive: true})

async function call(method, path, body) {
  const init = {method, headers: {Accept: 'application/json'}, signal: AbortSignal.timeout(120_000)}
  if (body !== undefined) {
    init.body = JSON.stringify(body)
    init.headers['Content-Type'] = 'application/json'
  }
  const response = await fetch(api + path, init)
  const text = await response.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    // Kept as text below.
  }
  return {status: response.status, json, text}
}

function save(name, result) {
  writeFileSync(join(out, name), result.json ? JSON.stringify(result.json, null, 2) + '\n' : result.text)
  return result
}

const manifest = {baseUrl: base, collectedAt: new Date().toISOString(), files: {}}

const report = save('runtime-insights.json', await call('GET', '/runtime-insights'))
if (report.status !== 200 || !report.json) throw new Error(`GET /runtime-insights answered ${report.status}`)
const observations = report.json.observations || []
for (const observation of observations) {
  const detail = await call('GET', `/runtime-insights/insights/${encodeURIComponent(observation.id)}`)
  save(join('insights', `${observation.id.replace(/[^A-Za-z0-9._-]/g, '_')}.json`), detail)
}

const agentDefault = save('agent-default.json', await call('POST', '/cli/tools/get_runtime_insights', {}))
let limit = Math.max(1, Math.min(Number(values['max-limit']), observations.length || 1))
let agentAll = await call('POST', '/cli/tools/get_runtime_insights', {query: 'all', limit})
// The limit is capped by bootui.mcp.max-results; a smaller one still returns `omitted`, which the manifest records.
while (agentAll.status === 400 && limit > 25) {
  limit = Math.floor(limit / 2)
  agentAll = await call('POST', '/cli/tools/get_runtime_insights', {query: 'all', limit})
}
save('agent-all.json', agentAll)
save('comparison.json', await call('GET', '/runtime-insights/comparison'))
save('agent-comparison.json', await call('POST', '/cli/tools/get_runtime_run_comparison', {}))
save('journal-status.json', await call('GET', '/activity/journal'))
save('panels.json', await call('GET', '/panels'))
save('java-agent.json', await call('GET', '/java-agent'))

const listed = observations.filter((o) => o.listed !== false)
manifest.observations = observations.length
manifest.listed = listed.length
manifest.listedFlagPresent = observations.some((o) => Object.hasOwn(o, 'listed'))
manifest.byStatus = observations.reduce((acc, o) => ({...acc, [o.status]: (acc[o.status] || 0) + 1}), {})
manifest.checksNotEvaluated = (report.json.checks || []).filter((c) => c.status !== 'EVALUATED').length
manifest.agentDefault = {status: agentDefault.status, observations: agentDefault.json?.observations?.length ?? null}
manifest.agentAll = {
  status: agentAll.status,
  limit,
  observations: agentAll.json?.observations?.length ?? null,
  omitted: agentAll.json?.omitted ?? null
}
writeFileSync(join(out, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n')
console.log(
  `collected ${observations.length} observations (${listed.length} listed by default) into ${out}; ` +
    `agent default ${manifest.agentDefault.observations}, agent all ${manifest.agentAll.observations}` +
    (manifest.listedFlagPresent ? '' : ' (no listed flag: a build before M4-19, every row counts as listed)')
)
