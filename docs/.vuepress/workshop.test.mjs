import assert from 'node:assert/strict'
import {spawnSync} from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import test from 'node:test'
import {createDocsSidebar} from './sidebar.js'
import {toDocLink} from './doc-links.js'

const docsRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const workshopRoot = path.join(docsRoot, 'workshop')
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

test('setup uses the requested workshop directory and introduction has no v2 comparison section', () => {
  for (const file of ['workshop/01-setup-and-tooling.md', '../workshop/PLAN.md']) {
    const content = fs.readFileSync(path.join(docsRoot, file), 'utf8')
    assert.match(
      content,
      /git clone --branch v2\.0\.0 --depth 1 https:\/\/github\.com\/jdubois\/boot-ui\.git workshop\ncd workshop/
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

test('reference answer is applicable without editing the sample fixture', () => {
  const result = spawnSync('git', ['apply', '--check', 'workshop/answers/eager-orders.patch'], {
    cwd: path.resolve(docsRoot, '..'),
    encoding: 'utf8'
  })
  assert.ifError(result.error)
  assert.equal(result.status, 0, result.stderr)
})
