import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import CodeInventory from './CodeInventory.vue'

const RouterLinkStub = {
  props: ['to'],
  template: '<a class="router-link-stub" :data-to="JSON.stringify(to)"><slot /></a>'
}

const summary = {
  available: true,
  unavailableReason: null,
  run: {
    generation: 3,
    application: 'orders',
    mode: 'dev',
    claimedAtEpochMillis: 1_700_000_000_000,
    readyAtEpochMillis: 1_700_000_005_000,
    packages: ['shop']
  },
  scan: {status: 'COMPLETE', reason: null, roots: 1, classes: 12, reused: 11, skipped: 0, durationMillis: 40},
  methods: {
    packages: 2,
    classes: 12,
    methods: 60,
    tracked: 55,
    executed: 31,
    neverExecuted: 24,
    notTracked: 5,
    generated: 0
  },
  changes: {
    previousRun: true,
    note: null,
    changed: 1,
    added: 1,
    removed: 0,
    executed: 1,
    notExecuted: 1,
    partial: false,
    scanStatus: 'COMPLETE'
  },
  dependencies: {declared: 40, loaded: 30, loadedEarlier: 2, notLoaded: 8, undeclared: 3, declaredReason: null},
  limitations: ['Methods called before BootUI claimed the agent are not seen.']
}

function method(name, status, change, extra = {}) {
  return {
    key: `shop.OrderService#${name}()V`,
    packageName: 'shop',
    className: 'shop.OrderService',
    name,
    descriptor: '()V',
    status,
    notTrackedReason: null,
    change,
    firstRequestId: null,
    firstRoute: null,
    firstHitEpochMillis: null,
    ...extra
  }
}

const page = (items) => ({
  total: items.length,
  matched: items.length,
  offset: 0,
  limit: 500,
  returned: items.length,
  hasMore: false
})

const changes = {
  available: true,
  unavailableReason: null,
  counts: summary.changes,
  changes: [
    method('refund', 'NEVER_EXECUTED', 'ADDED'),
    method('pay', 'EXECUTED', 'CHANGED', {firstRequestId: '00000000000000ab', firstRoute: 'POST /orders/{id}/pay'})
  ],
  page: page([1, 2])
}

const packagesReport = {
  available: true,
  unavailableReason: null,
  packages: [{name: 'shop', classes: 2, methods: 10, executed: 6, neverExecuted: 3, notTracked: 1}],
  classes: [],
  methods: [],
  page: page([1])
}

const packageMethods = {
  available: true,
  unavailableReason: null,
  packages: packagesReport.packages,
  classes: [
    {
      packageName: 'shop',
      className: 'shop.OrderService',
      methods: 2,
      executed: 1,
      neverExecuted: 1,
      notTracked: 0,
      changed: 2
    }
  ],
  methods: [method('neverCalled', 'NEVER_EXECUTED', null), method('total', 'EXECUTED', null)],
  page: page([1, 2])
}

const dependencies = {
  available: true,
  unavailableReason: null,
  counts: summary.dependencies,
  dependencies: [
    {
      jar: null,
      groupId: 'org.apache.commons',
      artifactId: 'commons-csv',
      version: '1.12.0',
      declared: true,
      status: 'NOT_LOADED',
      classesLoaded: 0,
      classesLoadedTotal: 0,
      loadedAt: null,
      firstLoadEpochMillis: null,
      firstRoute: null,
      firstRequestId: null
    },
    {
      jar: 'jackson-databind-2.19.0.jar',
      groupId: 'com.fasterxml.jackson.core',
      artifactId: 'jackson-databind',
      version: '2.19.0',
      declared: true,
      status: 'LOADED',
      classesLoaded: 120,
      classesLoadedTotal: 140,
      loadedAt: 'AFTER_STARTUP',
      firstLoadEpochMillis: 1_700_000_009_000,
      firstRoute: 'GET /orders',
      firstRequestId: '00000000000000cd'
    }
  ],
  page: page([1, 2])
}

function jsonResponse(body) {
  return {ok: true, status: 200, json: () => Promise.resolve(body)}
}

function routeFetch(responses) {
  return vi.fn((url) => {
    const path = String(url)
    const key = Object.keys(responses)
      .sort((a, b) => b.length - a.length)
      .find((prefix) => path.includes(prefix))
    return Promise.resolve(jsonResponse(responses[key]))
  })
}

function mountPanel(responses, props = {}) {
  const fetch = routeFetch(responses)
  vi.stubGlobal('fetch', fetch)
  const wrapper = mount(CodeInventory, {props, global: {stubs: {RouterLink: RouterLinkStub}}})
  return {wrapper, fetch}
}

describe('Code Inventory panel', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('leads with the executed methods and the changes since the previous run, not executed first', async () => {
    ;({wrapper} = mountPanel({
      'api/code-inventory/changes': changes,
      'api/code-inventory': summary
    }))
    await flushPromises()

    expect(wrapper.get('#code-inventory-headline').text()).toBe('31 of 55 application methods executed')
    const tabs = wrapper.findAll('[role="tab"]')
    expect(tabs.map((tab) => tab.text())).toEqual([
      'Changed since the previous run',
      'Application code',
      'Dependencies'
    ])
    expect(tabs[0].attributes('aria-selected')).toBe('true')
    expect(tabs[1].attributes('tabindex')).toBe('-1')
    const rows = wrapper.findAll('#code-inventory-panel-changes tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('OrderService.refund()V')
    expect(rows[0].text()).toContain('Added')
    expect(rows[0].text()).toContain('Not executed')
    expect(rows[1].text()).toContain('Executed')
    const [probe, link] = rows[1].findAll('.router-link-stub')
    expect(JSON.parse(probe.attributes('data-to'))).toEqual({
      path: '/code-paths',
      query: {probe: 'shop.OrderService#pay()V'}
    })
    expect(probe.text()).toBe('Probe in Code Paths')
    expect(JSON.parse(link.attributes('data-to'))).toEqual({path: '/activity', query: {request: '00000000000000ab'}})
    expect(link.text()).toBe('POST /orders/{id}/pay')
  })

  it('opens Application code first without a previous run, and filters to never executed', async () => {
    const withoutPrevious = {...summary, changes: {...summary.changes, previousRun: false, note: 'No previous run.'}}
    let fetch
    ;({wrapper, fetch} = mountPanel({
      'api/code-inventory/methods?package=shop': packageMethods,
      'api/code-inventory/methods': packagesReport,
      'api/code-inventory': withoutPrevious
    }))
    await flushPromises()

    const tabs = wrapper.findAll('[role="tab"]')
    expect(tabs.map((tab) => tab.text())).toEqual([
      'Application code',
      'Dependencies',
      'Changed since the previous run'
    ])
    expect(wrapper.get('#code-inventory-panel-code').text()).toContain('shop')

    await wrapper.get('.code-inventory-package').trigger('click')
    await flushPromises()
    expect(wrapper.get('.code-inventory-classes').text()).toContain('OrderService')
    expect(wrapper.get('.code-inventory-classes').text()).toContain('neverCalled()V')

    await wrapper.get('#code-inventory-never-executed').setValue(true)
    await flushPromises()
    expect(fetch.mock.calls.map(([url]) => String(url))).toEqual(
      expect.arrayContaining([
        expect.stringContaining('api/code-inventory/methods?limit=1&status=never-executed'),
        expect.stringContaining('api/code-inventory/methods?package=shop&limit=500&status=never-executed')
      ])
    )
  })

  it('says the scan is still running rather than that there is no previous run, and reads again', async () => {
    vi.useFakeTimers()
    try {
      const counts = {
        ...summary.changes,
        changed: 0,
        added: 0,
        removed: null,
        executed: 0,
        notExecuted: 0,
        note: 'The scan of the application’s class files is still running: changes since the previous run are compared once it ends.',
        scanStatus: 'RUNNING'
      }
      const running = {
        ...summary,
        scan: {...summary.scan, status: 'RUNNING', durationMillis: null},
        methods: null,
        changes: counts
      }
      let fetch
      ;({wrapper, fetch} = mountPanel({
        'api/code-inventory/changes': {...changes, counts, changes: []},
        'api/code-inventory': running
      }))
      await flushPromises()

      const tabs = wrapper.findAll('[role="tab"]')
      expect(tabs[0].text()).toBe('Changed since the previous run')
      const state = wrapper.get('#code-inventory-panel-changes .code-inventory-scan-state')
      expect(state.text()).toContain('still running')
      expect(wrapper.get('#code-inventory-panel-changes').text()).not.toContain('No previous run')
      expect(wrapper.text()).toContain('Scanning the application’s class files')
      const calls = fetch.mock.calls.length

      await vi.advanceTimersByTimeAsync(1500)
      await flushPromises()
      expect(fetch.mock.calls.length).toBeGreaterThan(calls)
    } finally {
      vi.useRealTimers()
    }
  })

  it('says the scan failed rather than that nothing changed', async () => {
    const counts = {
      ...summary.changes,
      changed: 0,
      added: 0,
      removed: null,
      executed: 0,
      notExecuted: 0,
      note: 'The scan of the application’s class files failed, so no change since the previous run can be compared. The scan failed: IllegalStateException.',
      scanStatus: 'FAILED'
    }
    ;({wrapper} = mountPanel({
      'api/code-inventory/changes': {...changes, counts, changes: []},
      'api/code-inventory': {...summary, scan: {...summary.scan, status: 'FAILED'}, changes: counts}
    }))
    await flushPromises()

    const panel = wrapper.get('#code-inventory-panel-changes')
    expect(panel.get('.code-inventory-scan-state').text()).toContain('IllegalStateException')
    expect(panel.text()).not.toContain('No application method changed')
  })

  it('lists declared dependencies not loaded in this run without calling them unused', async () => {
    ;({wrapper} = mountPanel({
      'api/code-inventory/dependencies': dependencies,
      'api/code-inventory/changes': changes,
      'api/code-inventory': summary
    }))
    await flushPromises()

    const tab = wrapper.findAll('[role="tab"]')[2]
    await tab.trigger('click')
    await flushPromises()

    const panel = wrapper.get('#code-inventory-panel-dependencies')
    expect(panel.text()).toContain('8 not loaded in this run')
    expect(panel.text()).toContain('not proof it is unused')
    const rows = panel.findAll('tbody tr')
    expect(rows[0].text()).toContain('org.apache.commons:commons-csv:1.12.0')
    expect(rows[0].text()).toContain('Not loaded in this run')
    expect(rows[1].text()).toContain('Loaded in this run')
    expect(rows[1].text()).toContain('After startup')
    expect(panel.text()).not.toMatch(/\bunused jar/i)
  })

  it('moves between tabs with the arrow keys', async () => {
    ;({wrapper} = mountPanel({
      'api/code-inventory/dependencies': dependencies,
      'api/code-inventory/methods': packagesReport,
      'api/code-inventory/changes': changes,
      'api/code-inventory': summary
    }))
    await flushPromises()

    await wrapper.findAll('[role="tab"]')[0].trigger('keydown', {key: 'ArrowRight'})
    await flushPromises()
    expect(wrapper.findAll('[role="tab"]')[1].attributes('aria-selected')).toBe('true')
    expect(wrapper.find('#code-inventory-panel-code').exists()).toBe(true)
    await wrapper.findAll('[role="tab"]')[1].trigger('keydown', {key: 'End'})
    await flushPromises()
    expect(wrapper.find('#code-inventory-panel-dependencies').exists()).toBe(true)
  })

  it('says why without the agent and links to the Java Agent panel', async () => {
    ;({wrapper} = mountPanel({
      'api/code-inventory': {
        available: false,
        unavailableReason:
          "Requires the BootUI agent's inventory sensor: This JVM runs without the BootUI agent. Start the application with -javaagent:bootui-agent.jar (see the Java Agent panel).",
        run: null,
        scan: null,
        methods: null,
        changes: null,
        dependencies: null,
        limitations: []
      }
    }))
    await flushPromises()

    expect(wrapper.text()).toContain("Requires the BootUI agent's inventory sensor")
    expect(JSON.parse(wrapper.get('.code-inventory-agent-link').attributes('data-to'))).toBe('/java-agent')
    expect(wrapper.find('[role="tab"]').exists()).toBe(false)
  })

  it('does not fetch when the manifest says the panel is unavailable', async () => {
    let fetch
    ;({wrapper, fetch} = mountPanel(
      {'api/code-inventory': summary},
      {panel: {id: 'code-inventory', available: false, unavailableReason: 'Requires the BootUI agent.'}}
    ))
    await flushPromises()

    expect(fetch).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Requires the BootUI agent.')
    expect(wrapper.find('.code-inventory-agent-link').exists()).toBe(true)
  })
})
