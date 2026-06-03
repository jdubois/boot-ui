import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import GraalVm from './GraalVm.vue'

function finding(id, name, severity, occurrenceCount = 1) {
  return {
    id,
    name,
    category: 'Reflection',
    severity,
    description: `${name} description.`,
    status: 'REVIEW',
    occurrenceCount,
    sampleOccurrences: occurrenceCount > 0 ? [`${id} detail`] : [],
    recommendation: `${name} recommendation.`
  }
}

function graalvmReport(findings, {includeDependencies = true, dependencies = []} = {}) {
  return {
    localOnly: true,
    disclaimer: 'GraalVM disclaimer.',
    basePackages: ['com.example'],
    includeDependencies,
    classesAnalyzed: 12,
    checksRun: 5,
    findingsFound: findings.length,
    severityCounts: [
      {severity: 'HIGH', count: severityCount(findings, 'HIGH')},
      {severity: 'MEDIUM', count: severityCount(findings, 'MEDIUM')},
      {severity: 'LOW', count: severityCount(findings, 'LOW')},
      {severity: 'INFO', count: severityCount(findings, 'INFO')}
    ],
    scan: {
      analyzer: 'BootUI GraalVM readiness',
      status: 'SCANNED',
      message: 'Readiness checks completed.',
      scannedAt: 1_700_000_000_000,
      checksRun: 5,
      classesAnalyzed: 12,
      findingsFound: findings.length
    },
    findings,
    dependenciesAnalyzed: dependencies.length,
    dependenciesWithoutMetadata: dependencies.filter((dep) => !dep.shipsMetadata).length,
    dependencies,
    metadata: {reflectionEntries: 2, serializationEntries: 1, resourceEntries: 3}
  }
}

function severityCount(findings, severity) {
  return findings.filter((item) => item.severity === severity).length
}

async function mountWithReport(report) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve(new Response(JSON.stringify(report), {status: 200})))
  )

  const wrapper = mount(GraalVm)
  await flushPromises()
  return wrapper
}

describe('GraalVm', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows readiness concerns sorted by severity with a metadata download link', async () => {
    const wrapper = await mountWithReport(
      graalvmReport([
        finding('GRAAL-REFLECT-001', 'Medium severity concern', 'MEDIUM', 3),
        finding('GRAAL-RES-001', 'Low severity concern', 'LOW', 1)
      ])
    )

    expect(wrapper.text()).toContain('Scan complete')
    expect(wrapper.text()).toContain('2 concerns, sorted by importance')
    expect(wrapper.text()).toContain('What happened:')
    expect(wrapper.text()).toContain('3 occurrences found for this check.')
    expect(wrapper.findAll('.list-group-item h3').map((title) => title.text())).toEqual([
      'Medium severity concern',
      'Low severity concern'
    ])
    const download = wrapper.find('a[href="api/graalvm/metadata"]')
    expect(download.exists()).toBe(true)
    expect(download.attributes('download')).toBe('reachability-metadata.json')
  })

  it('shows an empty state when no readiness concerns are found', async () => {
    const wrapper = await mountWithReport(graalvmReport([]))

    expect(wrapper.text()).toContain('No native-image readiness concerns found')
  })

  it('lists surveyed dependencies and their metadata signal', async () => {
    const wrapper = await mountWithReport(
      graalvmReport([], {
        dependencies: [
          {name: 'org.example:with-metadata', shipsMetadata: true, note: ''},
          {name: 'org.example:no-metadata', shipsMetadata: false, note: 'No native-image metadata'}
        ]
      })
    )

    expect(wrapper.text()).toContain('1 of 2 dependencies ship metadata')
    expect(wrapper.text()).toContain('org.example:with-metadata')
    expect(wrapper.text()).toContain('org.example:no-metadata')
  })

  it('hides the dependency section when dependencies were not surveyed', async () => {
    const wrapper = await mountWithReport(graalvmReport([], {includeDependencies: false}))

    expect(wrapper.text()).not.toContain('Dependency reachability metadata')
  })
})
