import assert from 'node:assert/strict'
import {spawnSync} from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import test from 'node:test'
import {createDocsSidebar} from './sidebar.js'
import {toDocLink} from './doc-links.js'
import {
  createWorkshopFixtures,
  selectScreenshotServer
} from '../../bootui-spring-sample-app/e2e/scripts/workshop-screenshots.mjs'

const docsRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const workshopRoot = path.join(docsRoot, 'workshop')
const workshopVersion = JSON.parse(fs.readFileSync(path.join(docsRoot, '../package.json'), 'utf8')).version
const pages = [
  'README.md',
  '00-runtime-understanding.md',
  '01-setup-and-tooling.md',
  '02-configuration-and-wiring.md',
  '03-runtime-journal.md',
  '04-runtime-insights.md',
  '05-java-instrumentation.md',
  '06-assessment-and-impact.md',
  '07-agent-change-loop.md',
  '08-going-further.md',
  'participant-worksheet.md',
  'appendix-a-prompts.md',
  'appendix-b-troubleshooting.md',
  'appendix-c-frameworks.md',
  'appendix-d-extensions.md'
]

test('workshop sidebar follows Get started and includes every page exactly once in teaching order', () => {
  const sidebar = createDocsSidebar()
  const index = sidebar.findIndex((group) => group.text === 'Workshop')
  assert.equal(sidebar[index - 1].text, 'Get started')
  assert.equal(sidebar[index + 1].text, 'Features')
  const expected = pages.map((page) => toDocLink(`workshop/${page}`))
  assert.deepEqual(
    sidebar[index].children.map((item) => item.link),
    expected
  )
  const links = sidebar.flatMap((group) => group.children?.map((item) => item.link) ?? [group.link])
  for (const link of expected) {
    assert.equal(links.filter((item) => item === link).length, 1, link)
  }
  assert.deepEqual(
    fs
      .readdirSync(workshopRoot)
      .filter((file) => file.endsWith('.md'))
      .sort(),
    [...pages].sort()
  )
})

test('navbar, homepage and repository entry lead to participant material, not the preserved plan', () => {
  const config = fs.readFileSync(path.join(docsRoot, '.vuepress/config.js'), 'utf8')
  assert.match(
    config,
    /\{text: 'Features', link: toDocLink\('features\/README\.md'\)\},\s*\{text: 'Workshop', link: toDocLink\('workshop\/README\.md'\)\}/
  )
  assert.match(fs.readFileSync(path.join(docsRoot, 'README.md'), 'utf8'), /\[Workshop\]\(workshop\/README\.md\)/)
  assert.match(
    fs.readFileSync(path.join(docsRoot, '../README.md'), 'utf8'),
    /\[Three-hour BootUI workshop\]\(docs\/workshop\/README\.md\)/
  )
  assert.ok(fs.existsSync(path.join(docsRoot, '../workshop/PLAN.md')))
  assert.ok(!fs.existsSync(path.join(docsRoot, '../workshop/README.md')))
})

test('participant Markdown links resolve and numbered chapters have a next step', () => {
  for (const page of pages) {
    const content = fs.readFileSync(path.join(workshopRoot, page), 'utf8')
    assert.match(content, /^# /, page)
    for (const [, href] of content.matchAll(/\[[^\]]*\]\(([^)]+)\)/g)) {
      if (/^(?:[a-z][a-z0-9+.-]*:|#|\/)/i.test(href)) continue
      const pathname = href.split(/[?#]/)[0]
      assert.ok(fs.existsSync(path.resolve(workshopRoot, pathname)), `${page}: ${href}`)
    }
    if (/^0[0-7]-/.test(page)) assert.match(content, /\*\*Next:\*\*/, page)
  }
})

test('workshop agenda is contiguous and totals exactly 180 minutes including its break', () => {
  const content = fs.readFileSync(path.join(workshopRoot, 'README.md'), 'utf8')
  const rows = [...content.matchAll(/^\| (\d\d):(\d\d)-(\d\d):(\d\d) \| (\d+) \|/gm)]
  assert.equal(rows.length, 10)
  let end = 0
  for (const [, startHour, startMinute, endHour, endMinute, duration] of rows) {
    const start = Number(startHour) * 60 + Number(startMinute)
    const next = Number(endHour) * 60 + Number(endMinute)
    assert.equal(start, end)
    assert.equal(next - start, Number(duration))
    end = next
  }
  assert.equal(end, 180)
  assert.match(content, /\| 01:30-01:40 \| 10 \| Break \|/)
})

test('every participant page has an illustrated workshop checkpoint with descriptive alt text', () => {
  for (const page of pages) {
    const content = fs.readFileSync(path.join(workshopRoot, page), 'utf8')
    const screenshots = [...content.matchAll(/!\[([^\]]+)\]\((\.\.\/images\/workshop-[^)]+\.webp)\)/g)]
    assert.ok(screenshots.length > 0, `${page}: missing workshop screenshot`)
    assert.match(content, /Illustrative reference/, `${page}: label reference imagery, not live proof`)
    for (const [, alt, href] of screenshots) {
      assert.ok(alt.length >= 40, `${page}: describe the evidence in ${href}`)
      const image = fs.readFileSync(path.resolve(workshopRoot, href))
      assert.equal(image.toString('ascii', 0, 4), 'RIFF', `${page}: ${href}`)
      assert.equal(image.toString('ascii', 8, 12), 'WEBP', `${page}: ${href}`)
    }
  }
})

test('workshop reference fixtures use the selected release and published Maven coordinates', () => {
  const fixtures = createWorkshopFixtures({
    nowMillis: 0,
    bootUiVersion: '7.8.9',
    springBootVersion: '4.1.1',
    panelManifest: {panels: []},
    cliManifest: {},
    pagedReport: () => assert.fail('Unexpected pagination')
  })
  const agent = fixtures.resolveEndpoint('agent-baseline', 'java-agent', new URL('http://localhost'))
  assert.equal(agent.agentVersion, '7.8.9')
  assert.equal(agent.bootUiVersion, '7.8.9')
  assert.equal(agent.claim.mode, 'dev')
  assert.match(agent.jarPath, /\/com\/julien-dubois\/bootui\/bootui-agent\/7\.8\.9\/bootui-agent-7\.8\.9\.jar$/)
  assert.match(
    agent.setup.snippets.find((snippet) => snippet.id === 'maven-download').text,
    /-Dartifact=com\.julien-dubois\.bootui:bootui-agent:7\.8\.9/
  )
  assert.match(
    agent.setup.snippets.find((snippet) => snippet.id === 'curl-download').text,
    /maven2\/com\/julien-dubois\/bootui\/bootui-agent\/7\.8\.9\//
  )
})

test('pinned frontend captures cannot silently reuse another server', () => {
  assert.throws(() => selectScreenshotServer({workshop: true}), /require BOOTUI_SCREENSHOT_FRONTEND_DIR/)
  for (const pinned of [
    {workshop: true, frontendOverride: '/released/frontend'},
    {frontendOverride: '/released/frontend'}
  ]) {
    assert.equal(selectScreenshotServer(pinned), 'start')
    assert.throws(() => selectScreenshotServer({...pinned, serverReady: true}), /start their own frontend/)
    assert.throws(
      () => selectScreenshotServer({...pinned, baseUrlOverride: 'http://localhost:5173'}),
      /start their own frontend/
    )
  }
  assert.equal(selectScreenshotServer({}), 'start')
  assert.equal(selectScreenshotServer({serverReady: true}), 'reuse')
  assert.equal(selectScreenshotServer({baseUrlOverride: 'http://localhost:5173'}), 'external')
})

test('workshop reference evidence uses real call sites and complete route-tree fields', () => {
  const fixtures = createWorkshopFixtures({
    nowMillis: 0,
    bootUiVersion: workshopVersion,
    springBootVersion: '4.1.1',
    panelManifest: {panels: []},
    cliManifest: {tools: []},
    pagedReport: () => assert.fail('Unexpected pagination')
  })
  const resolve = (endpoint) => fixtures.resolveEndpoint('agent-baseline', endpoint, new URL('http://localhost'))
  const tree = resolve('code-paths/route')
  assert.equal(tree.found, true)
  assert.equal(tree.assemblyOnly, false)
  assert.equal(tree.ownMillis, 18)
  assert.equal(tree.handlerMillis, 17.6)
  assert.ok(tree.exemplarRequestIds.length > 0)
  assert.deepEqual(
    Object.keys(tree).sort(),
    [
      'available',
      'unavailableReason',
      'route',
      'found',
      'assemblyOnly',
      'warmRequests',
      'firstRequestMillis',
      'firstRequestId',
      'ownMillis',
      'handlerMillis',
      'shareOf',
      'depth',
      'nodes',
      'methods',
      'exemplarRequestIds',
      'page',
      'limitations'
    ].sort()
  )
  assert.ok(resolve('runtime-insights/impact').observed.every((route) => route.check === null))
  const detail = resolve('runtime-insights/insights/obs-repeated-selects-eager-orders')
  assert.deepEqual(detail.columns, ['Request', 'Executions', 'Time (ms)', 'Call site', 'Phase', 'In transaction'])
  assert.equal(
    detail.rows.reduce((total, row) => total + Number(row.cells[1]), 0),
    96
  )
  assert.equal(Math.round(detail.rows.reduce((total, row) => total + Number(row.cells[2]), 0) * 10), 744)
  const evidence = JSON.stringify([
    detail,
    resolve('activity/request/req-eager-01'),
    resolve('activity/request/req-eager-01/journal')
  ])
  const callSites = [...evidence.matchAll(/((?:[\w$]+\.)+[\w$]+)\.(\w+)\((\w+)\.java:(\d+)\)/g)]
  assert.ok(callSites.length > 0)
  for (const [, className, method, file, line] of callSites) {
    const sourcePath = path.join(
      docsRoot,
      '../bootui-spring-sample-app/src/main/java',
      `${className.replaceAll('.', '/')}.java`
    )
    const source = fs.readFileSync(sourcePath, 'utf8').split('\n')
    assert.ok(source[Number(line) - 1]?.trim(), `${file}:${line}`)
    if (file === 'EagerDemoOrderService' && method === 'listWithSecondarySelects') {
      assert.match(source[Number(line) - 1], /findWithSecondarySelects/)
    }
  }
})

test('release captures read panel and tool manifests from the selected checkout', () => {
  const capture = fs.readFileSync(
    path.join(docsRoot, '../bootui-spring-sample-app/e2e/scripts/capture-docs-screenshots.mjs'),
    'utf8'
  )
  assert.match(capture, /const panelManifest[\s\S]*?metadataRoot,\s*'bootui-conformance/)
  assert.match(capture, /const cliManifestPath = path\.join\(metadataRoot, 'bootui-cli'/)
})

test('documentation CI covers screenshot-only changes and main-branch workshop checks', () => {
  const v2Workflow = fs.readFileSync(path.join(docsRoot, '../.github/workflows/docs-v2.yml'), 'utf8')
  assert.match(v2Workflow, /- 'bootui-spring-sample-app\/e2e\/scripts\/\*\*'/)
  for (const workflow of ['docs-v2.yml', 'pages.yml']) {
    assert.match(
      fs.readFileSync(path.join(docsRoot, '../.github/workflows', workflow), 'utf8'),
      /run: node --test docs\/\.vuepress\/workshop\.test\.mjs/
    )
  }
})

test('the order investigation measures N+1 before revealing the response-preserving fix', () => {
  const solution = /join[- ]fetch|fetch[- ]join|(?:find|list)WithCustomerJoin/i
  for (const page of pages.filter((page) => /^0[0-5]-/.test(page))) {
    const content = fs.readFileSync(path.join(workshopRoot, page), 'utf8')
    assert.doesNotMatch(content, solution, page)
  }
  const journal = fs.readFileSync(path.join(workshopRoot, '03-runtime-journal.md'), 'utf8')
  assert.match(journal, /1 \+ 16 = 17 SELECTs/)
  const assessment = fs.readFileSync(path.join(workshopRoot, '06-assessment-and-impact.md'), 'utf8')
  assert.match(assessment, /Write your own diagnosis[\s\S]*## Assess the existing evidence/)
  assert.match(assessment, /## Review the proposed action[\s\S]*join-fetch/)
  assert.doesNotMatch(assessment.split('## Review the proposed action')[0], solution)
  const worksheet = fs.readFileSync(path.join(workshopRoot, 'participant-worksheet.md'), 'utf8')
  assert.match(worksheet, /## Your investigation/)
  assert.match(worksheet, /What proves the live application ran the fix\?/)
})

test('setup uses the requested workshop directory and introduction has no v2 comparison section', () => {
  for (const file of ['workshop/01-setup-and-tooling.md', '../workshop/PLAN.md']) {
    const content = fs.readFileSync(path.join(docsRoot, file), 'utf8')
    assert.ok(
      content.includes(
        `git clone --branch v${workshopVersion} --depth 1 https://github.com/jdubois/boot-ui.git workshop\ncd workshop`
      ),
      `${file}: workshop clone must match the project release`
    )
    assert.doesNotMatch(content, /bootui-v2-workshop/)
  }
  const introduction = fs.readFileSync(path.join(workshopRoot, '00-runtime-understanding.md'), 'utf8')
  assert.doesNotMatch(introduction, /^## What v2 adds$/m)
})

test('participant Bash examples parse without executing workshop actions', () => {
  for (const page of pages) {
    const content = fs.readFileSync(path.join(workshopRoot, page), 'utf8')
    for (const [index, [, code]] of [...content.matchAll(/```bash\n([\s\S]*?)```/g)].entries()) {
      const result = spawnSync('bash', ['-n'], {input: code, encoding: 'utf8'})
      assert.ifError(result.error)
      assert.equal(result.status, 0, `${page}, Bash example ${index + 1}: ${result.stderr}`)
    }
  }
})

test('every participant sample launch explicitly binds the host application to loopback', () => {
  let launches = 0
  for (const page of pages) {
    const content = fs.readFileSync(path.join(workshopRoot, page), 'utf8')
    for (const [, code] of content.matchAll(/```(?:bash|powershell)\n([\s\S]*?)```/g)) {
      if (/spring-boot:run|bootui-spring[\w-]*\/run-local(?:-agent)?\.sh/.test(code)) {
        assert.match(code, /--server\.address=127\.0\.0\.1/, page)
        launches++
      }
      if (/quarkus:dev|bootui-quarkus-sample-app\/run-local(?:-agent)?\.sh/.test(code)) {
        assert.match(code, /-Dquarkus\.http\.host=127\.0\.0\.1/, page)
        launches++
      }
    }
  }
  assert.equal(launches, 10)
})

test('Windows workload requests fail fast and multi-module build lists remain single arguments', () => {
  for (const page of pages) {
    const content = fs.readFileSync(path.join(workshopRoot, page), 'utf8')
    for (const [, code] of content.matchAll(/```powershell\n([\s\S]*?)```/g)) {
      for (const line of code.split('\n')) {
        if (/Invoke-RestMethod "\$env:BOOTUI_URL/.test(line)) {
          assert.match(line, /-ErrorAction Stop/, `${page}: ${line}`)
        }
        if (/Resolve-Path /.test(line)) assert.match(line, /-ErrorAction Stop/, `${page}: ${line}`)
        if (/-pl .*?,bootui-agent/.test(line)) {
          assert.match(line, /-pl "[^"]+,bootui-agent"/, `${page}: ${line}`)
        }
      }
    }
  }
})

test('agent approval follows the red test and cannot release the live reload trigger', () => {
  const assessment = fs.readFileSync(path.join(workshopRoot, '06-assessment-and-impact.md'), 'utf8')
  const prompts = fs.readFileSync(path.join(workshopRoot, 'appendix-a-prompts.md'), 'utf8')
  const capstone = fs.readFileSync(path.join(workshopRoot, '07-agent-change-loop.md'), 'utf8')
  for (const content of [assessment, prompts]) {
    assert.match(content, /already-installed WorkshopEagerOrdersTest/)
    assert.match(content, /Do not update the DevTools trigger file/)
    assert.doesNotMatch(content, /install\/run/)
  }
  assert.match(assessment, /prepared, unsent/)
  assert.match(capstone, /after confirming the expected red result/)
  assert.match(capstone, /compile &&\n\s+touch/)
})

test('instrumentation optional labs and lost historical evidence are explicit', () => {
  const instrumentation = fs.readFileSync(path.join(workshopRoot, '05-java-instrumentation.md'), 'utf8')
  const assessment = fs.readFileSync(path.join(workshopRoot, '06-assessment-and-impact.md'), 'utf8')
  const frameworks = fs.readFileSync(path.join(workshopRoot, 'appendix-c-frameworks.md'), 'utf8')
  assert.match(instrumentation, /^## Optional: observe a bounded process side effect$/m)
  assert.match(instrumentation, /^## Optional: probe one method$/m)
  assert.match(instrumentation, /loses cached advisor reports/)
  assert.match(assessment, /historical worksheet note/)
  assert.match(frameworks, /PostgreSQL Dev Services and requires a\nrunning Docker/)
})

test('comparison and transaction summary limits do not invalidate separate live evidence', () => {
  const capstone = fs.readFileSync(path.join(workshopRoot, '07-agent-change-loop.md'), 'utf8')
  const insights = fs.readFileSync(path.join(workshopRoot, '04-runtime-insights.md'), 'utf8')
  assert.match(capstone, /PARTIAL[\s\S]*More traffic cannot restore those missing events/)
  assert.match(capstone, /separate sources of evidence, not a successful automated run comparison/)
  assert.match(insights, /bootui tx list[\s\S]*PARTICIPATING[\s\S]*summary limitation/)
})

test('reference answer is applicable without editing the sample fixture', () => {
  const result = spawnSync('git', ['apply', '--check', 'workshop/answers/eager-orders.patch'], {
    cwd: path.resolve(docsRoot, '..'),
    encoding: 'utf8'
  })
  assert.ifError(result.error)
  assert.equal(result.status, 0, result.stderr)
})
