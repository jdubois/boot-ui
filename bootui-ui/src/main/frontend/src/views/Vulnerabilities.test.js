import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Vulnerabilities from './Vulnerabilities.vue'

function vulnerability(id, severity, dismissed = false) {
  return {
    id,
    summary: `${id} summary`,
    details: null,
    severity,
    score: null,
    aliases: [],
    references: [],
    fixedVersions: [],
    dismissed
  }
}

function dependency(packageName, version, vulnerabilities, highestSeverity) {
  return {
    groupId: packageName.split(':')[0],
    artifactId: packageName.split(':')[1],
    version,
    packageName,
    source: 'test',
    vulnerabilityCount: vulnerabilities.filter((v) => !v.dismissed).length,
    highestSeverity,
    vulnerabilities
  }
}

function report(dependencies, vulnerable = dependencies.filter((d) => d.vulnerabilityCount > 0).length) {
  return {
    scanningEnabled: true,
    total: dependencies.length,
    vulnerable,
    severityCounts: [
      {severity: 'CRITICAL', count: 0},
      {severity: 'HIGH', count: 0},
      {severity: 'MEDIUM', count: 0},
      {severity: 'LOW', count: 0}
    ],
    scan: {
      scanner: 'OSV.dev',
      status: 'SCANNED',
      message: 'Scan completed against OSV.dev.',
      scannedAt: 1_700_000_000_000,
      packagesScanned: dependencies.length,
      vulnerabilitiesFound: dependencies.reduce((sum, d) => sum + d.vulnerabilityCount, 0)
    },
    dependencies
  }
}

/**
 * Stubs global fetch so GET api/vulnerabilities serves successive entries of `reports` (the last
 * entry repeats once exhausted, so a reload after the fixture list runs out still resolves), while
 * POST/DELETE api/dismissed-rules/* is acknowledged without mutating server state -- exactly like
 * useDismissedRules.test.js pins the composable in isolation. The panel itself is responsible for
 * re-fetching after a dismiss/restore, so each test supplies the *next* report reflecting the
 * expected server-side effect.
 */
async function mountWithReports(reports) {
  let callIndex = 0
  const fetchMock = vi.fn((input, init) => {
    const url = typeof input === 'string' ? input : input.toString()
    const method = (init?.method || 'GET').toUpperCase()
    if (url.startsWith('api/dismissed-rules/')) {
      return Promise.resolve(new Response(JSON.stringify({dismissed: []}), {status: 200}))
    }
    if (url === 'api/vulnerabilities' && method === 'GET') {
      const nextReport = reports[Math.min(callIndex, reports.length - 1)]
      callIndex++
      return Promise.resolve(new Response(JSON.stringify(nextReport), {status: 200}))
    }
    return Promise.resolve(new Response('{}', {status: 200}))
  })
  vi.stubGlobal('fetch', fetchMock)

  const wrapper = mount(Vulnerabilities)
  await flushPromises()
  return {wrapper, fetchMock}
}

describe('Vulnerabilities', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows "None found" only when a dependency has no vulnerabilities at all', async () => {
    const clean = dependency('org.example:clean', '1.0.0', [], 'NONE')
    const {wrapper} = await mountWithReports([report([clean])])

    expect(wrapper.text()).toContain('None found')
  })

  it('keeps a dismissed-only dependency out of "None found" so it can still be restored', async () => {
    // Regression guard: vulnerabilityCount excludes dismissed findings (server-side), so the
    // template must key "None found" off vulnerabilities.length, not vulnerabilityCount.
    const dismissedOnly = dependency(
      'org.example:legacy',
      '1.0.0',
      [vulnerability('GHSA-dismissed', 'HIGH', true)],
      'NONE'
    )
    const {wrapper} = await mountWithReports([report([dismissedOnly])])

    expect(wrapper.text()).not.toContain('None found')
    expect(wrapper.text()).toContain('Dismissed')
    expect(wrapper.text()).toContain('Restore')
  })

  it('sinks a dismissed vulnerability below active ones regardless of severity', async () => {
    const mixed = dependency(
      'org.example:mixed',
      '1.0.0',
      [vulnerability('GHSA-dismissed-critical', 'CRITICAL', true), vulnerability('GHSA-active-low', 'LOW', false)],
      'LOW'
    )
    const {wrapper} = await mountWithReports([report([mixed])])

    const ids = wrapper.findAll('.vulnerability-list a, .vulnerability-list span.fw-semibold').map((n) => n.text())
    expect(ids.indexOf('GHSA-active-low')).toBeLessThan(ids.indexOf('GHSA-dismissed-critical'))

    const buttons = wrapper.findAll('button').filter((b) => /Dismiss|Restore/.test(b.text()))
    expect(buttons.map((b) => b.text().trim())).toEqual(['Dismiss', 'Restore'])
  })

  it('dismissing a vulnerability POSTs the vulnerabilityId::packageName composite key and reloads', async () => {
    const beforeDismiss = dependency(
      'org.example:sample',
      '1.0.0',
      [vulnerability('GHSA-active-0001', 'CRITICAL', false)],
      'CRITICAL'
    )
    const afterDismiss = dependency(
      'org.example:sample',
      '1.0.0',
      [vulnerability('GHSA-active-0001', 'CRITICAL', true)],
      'NONE'
    )
    const {wrapper, fetchMock} = await mountWithReports([report([beforeDismiss]), report([afterDismiss], 0)])

    await wrapper.find('button.btn-outline-secondary').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      'api/dismissed-rules/GHSA-active-0001%3A%3Aorg.example%3Asample',
      expect.objectContaining({method: 'POST'})
    )
    expect(wrapper.findAll('button.btn-outline-secondary').map((b) => b.text().trim())).toEqual(['Restore'])
  })

  it('restoring a vulnerability DELETEs the same composite key and reloads', async () => {
    const dismissed = dependency(
      'org.example:sample',
      '1.0.0',
      [vulnerability('GHSA-active-0001', 'CRITICAL', true)],
      'NONE'
    )
    const restored = dependency(
      'org.example:sample',
      '1.0.0',
      [vulnerability('GHSA-active-0001', 'CRITICAL', false)],
      'CRITICAL'
    )
    const {wrapper, fetchMock} = await mountWithReports([report([dismissed], 0), report([restored])])

    await wrapper.find('button.btn-outline-secondary').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      'api/dismissed-rules/GHSA-active-0001%3A%3Aorg.example%3Asample',
      expect.objectContaining({method: 'DELETE'})
    )
    expect(wrapper.text()).toContain('Dismiss')
  })
})
