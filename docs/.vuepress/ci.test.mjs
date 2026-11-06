import assert from 'node:assert/strict'
import test from 'node:test'
import {createDocsSidebar} from './sidebar.js'

test('CI quick start and references are deliberate sidebar entries, not additional docs', () => {
  const sidebar = createDocsSidebar()
  const started = sidebar.find((group) => group.text === 'Get started')
  const reference = sidebar.find((group) => group.text === 'Reference')
  assert.ok(started.children.some((item) => item.link === '/ci'))
  assert.ok(reference.children.some((item) => item.link === '/ci/framework-tests'))
  assert.ok(reference.children.some((item) => item.link === '/ci/reference'))
  const links = sidebar.flatMap((group) => group.children?.map((item) => item.link) ?? [group.link])
  for (const link of ['/ci', '/ci/framework-tests', '/ci/reference']) {
    assert.equal(links.filter((candidate) => candidate === link).length, 1)
  }
})
