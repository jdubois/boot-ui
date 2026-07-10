import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Vulnerabilities from './Vulnerabilities.vue'

function vulnerability(id, severity, dismissed = false, overrides = {}) {
  return {
    id,
    summary: `${id} summary`,
    details: null,
    severity,
    score: null,
    aliases: [],
    references: [],
    fixedVersions: [],
    fixAvailable: false,
    epssScore: null,
    epssPercentile: null,
    dismissed,
    ...overrides
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

  it('renders the numeric CVSS base score next to the severity badge', async () => {
    const scored = dependency(
      'org.example:scored',
      '1.0.0',
      [vulnerability('GHSA-scored-0001', 'HIGH', false, {score: 8.1})],
      'HIGH'
    )
    const {wrapper} = await mountWithReports([report([scored])])

    expect(wrapper.text()).toContain('HIGH · 8.1')
  })

  it('omits the CVSS score suffix when the advisory carries no score', async () => {
    const unscored = dependency(
      'org.example:unscored',
      '1.0.0',
      [vulnerability('GHSA-unscored-0001', 'HIGH', false, {score: null})],
      'HIGH'
    )
    const {wrapper} = await mountWithReports([report([unscored])])

    expect(wrapper.text()).not.toContain('·')
  })

  it('renders an EPSS badge with likelihood-of-exploitation when epssScore is present', async () => {
    const withEpss = dependency(
      'org.example:epss',
      '1.0.0',
      [
        vulnerability('CVE-2021-44228', 'CRITICAL', false, {
          score: 10.0,
          aliases: ['CVE-2021-44228'],
          epssScore: 0.023,
          epssPercentile: 0.92
        })
      ],
      'CRITICAL'
    )
    const {wrapper} = await mountWithReports([report([withEpss])])

    expect(wrapper.text()).toContain('2.3% EPSS')
    const badge = wrapper.findAll('.badge').find((b) => b.text().includes('EPSS'))
    expect(badge.attributes('title')).toContain('92nd percentile')
  })

  it('omits the EPSS badge entirely when epssScore is null', async () => {
    const noEpss = dependency(
      'org.example:no-epss',
      '1.0.0',
      [vulnerability('GHSA-no-epss-0001', 'HIGH', false, {epssScore: null})],
      'HIGH'
    )
    const {wrapper} = await mountWithReports([report([noEpss])])

    expect(wrapper.text()).not.toContain('EPSS')
  })

  it('does not claim that no fix exists when OSV reports no fixed event', async () => {
    const noFix = dependency(
      'org.example:no-fix',
      '1.0.0',
      [vulnerability('GHSA-no-fix-0001', 'HIGH', false, {fixedVersions: [], fixAvailable: false})],
      'HIGH'
    )
    const {wrapper} = await mountWithReports([report([noFix])])

    expect(wrapper.text()).toContain('No fixed version reported by OSV')
  })

  it('shows the upgrade target when a newer fixed version is available', async () => {
    const fixable = dependency(
      'org.example:fixable',
      '1.0.0',
      [vulnerability('GHSA-fixable-0001', 'HIGH', false, {fixedVersions: ['1.2.0'], fixAvailable: true})],
      'HIGH'
    )
    const {wrapper} = await mountWithReports([report([fixable])])

    expect(wrapper.text()).toContain('fixed in 1.2.0')
    expect(wrapper.text()).not.toContain('No fixed version reported by OSV')
  })

  it('shows "already on a fixed version" when fixedVersions is non-empty but fixAvailable is false', async () => {
    const alreadyFixed = dependency(
      'org.example:already-fixed',
      '2.0.0',
      [vulnerability('GHSA-already-fixed-0001', 'HIGH', false, {fixedVersions: ['1.5.0'], fixAvailable: false})],
      'HIGH'
    )
    const {wrapper} = await mountWithReports([report([alreadyFixed])])

    expect(wrapper.text()).toContain('already on a fixed version')
    expect(wrapper.text()).not.toContain('No fixed version reported by OSV')
  })

  it('links a CVE alias to NVD and a GHSA alias to GitHub Advisories', async () => {
    const aliased = dependency(
      'org.example:aliased',
      '1.0.0',
      [
        vulnerability('GHSA-aliased-0001', 'CRITICAL', false, {
          aliases: ['CVE-2021-44228', 'GHSA-aliased-0001']
        })
      ],
      'CRITICAL'
    )
    const {wrapper} = await mountWithReports([report([aliased])])

    const cveLink = wrapper.findAll('a').find((a) => a.text() === 'CVE-2021-44228')
    const ghsaLink = wrapper.findAll('a').find((a) => a.text() === 'GHSA-aliased-0001')
    expect(cveLink.attributes('href')).toBe('https://nvd.nist.gov/vuln/detail/CVE-2021-44228')
    expect(ghsaLink.attributes('href')).toBe('https://github.com/advisories/GHSA-aliased-0001')
  })
})
