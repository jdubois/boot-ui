import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import JavaAgent from './JavaAgent.vue'

const baseReport = {
  state: 'NOT_ATTACHED',
  reason: null,
  agentVersion: null,
  bootUiVersion: '2.0.0-SNAPSHOT',
  protocol: null,
  expectedProtocol: 1,
  jdk: 'OpenJDK 26',
  loadMode: null,
  jarPath: null,
  startupMicros: null,
  claim: null,
  heldBy: null,
  sensors: [],
  retransformation: null,
  counters: null,
  messages: [],
  warnings: [],
  setup: {
    jarPath: '/Users/dev/.m2/repository/com/julien-dubois/bootui/bootui-agent/2.0.0/bootui-agent-2.0.0.jar',
    jarFound: false,
    buildTool: 'MAVEN',
    snippets: [
      {
        id: 'maven-download',
        label: 'Download the agent',
        language: 'shell',
        text: './mvnw dependency:get -Dartifact=com.julien-dubois.bootui:bootui-agent:2.0.0'
      },
      {
        id: 'surefire',
        label: 'Surefire and Failsafe',
        language: 'xml',
        text: '<argLine>@{argLine} -javaagent:/Users/dev/.m2/bootui-agent.jar</argLine>'
      }
    ]
  }
}

function jsonResponse(body) {
  return {ok: true, status: 200, json: () => Promise.resolve(body)}
}

function mountPanel(report = baseReport, props = {}) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(report)))
  return mount(JavaAgent, {props})
}

describe('Java Agent panel', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('renders a not-attached state with setup tabs and copies the active snippet', async () => {
    const writeText = vi.fn().mockResolvedValue()
    vi.stubGlobal('navigator', {clipboard: {writeText}})
    wrapper = mountPanel()
    await flushPromises()

    expect(wrapper.text()).toContain('Not attached')
    expect(wrapper.text()).toContain('This JVM runs without the BootUI agent')
    expect(wrapper.text()).toContain('No sensor installed: the agent installs the sensors this application asks for')
    expect(wrapper.text()).toContain('MAVEN')
    expect(wrapper.get('#java-agent-tab-maven-download').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('#java-agent-tab-surefire').attributes('aria-selected')).toBe('false')
    expect(wrapper.get('#java-agent-panel-maven-download').attributes('style') ?? '').not.toContain('display: none')
    expect(wrapper.get('#java-agent-panel-surefire').attributes('style') ?? '').toContain('display: none')

    await wrapper.get('#java-agent-tab-surefire').trigger('click')
    expect(wrapper.get('#java-agent-tab-surefire').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('#java-agent-panel-surefire').attributes('style') ?? '').not.toContain('display: none')

    const copyButton = wrapper.findAll('button').find((button) => button.text().includes('Copy'))
    await copyButton.trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith(baseReport.setup.snippets[1].text)
    expect(wrapper.text()).toContain('Copied!')
    expect(fetch).toHaveBeenCalledWith('api/java-agent', {})
  })

  it('shows an armed claim with packages and runtime counters', async () => {
    wrapper = mountPanel({
      ...baseReport,
      state: 'ARMED',
      reason: 'Claimed by this Spring application.',
      agentVersion: '2.0.0',
      protocol: 1,
      loadMode: 'javaagent',
      jarPath: '/agents/bootui-agent.jar',
      startupMicros: 1534,
      claim: {
        generation: 7,
        owner: 'orders@abcd',
        application: 'orders',
        mode: 'dev',
        armedAt: 1_700_000_000_000,
        packages: ['com.example.orders', 'com.example.shared'],
        armed: true,
        abandoned: false
      },
      retransformation: {
        state: 'installed',
        transformed: 12,
        retransformed: 3,
        failed: 0,
        skipped: 1,
        durationMillis: 42,
        running: false
      },
      counters: {claims: 4, takeovers: 1, holds: 0, staleTokens: 0, errors: 0}
    })
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('Armed')
    expect(text).toContain('Claimed by this Spring application.')
    expect(text).toContain('2.0.0')
    expect(text).toContain('1 / 1')
    expect(text).toContain('/agents/bootui-agent.jar')
    expect(text).toContain('1.53 ms')
    expect(text).toContain('orders@abcd')
    expect(text).toContain('com.example.orders')
    expect(text).toContain('Class transformation')
    expect(text).toContain('Retransformation time (summed)')
    expect(text).toContain('aggregate work rather than a wall-clock interval')
    expect(text).toContain('Counters')
  })

  it('labels a held state with the owning application', async () => {
    wrapper = mountPanel({...baseReport, state: 'HELD', heldBy: 'inventory@efgh'})
    await flushPromises()

    expect(wrapper.text()).toContain('Held by inventory@efgh')
    expect(wrapper.text()).toContain('Another application currently holds the BootUI agent claim.')
  })

  it('renders warnings as warning content and agent messages oldest first', async () => {
    wrapper = mountPanel({
      ...baseReport,
      warnings: ['Agent version 1.9.0 differs from BootUI 2.0.0'],
      messages: ['Agent installed', 'Claim released']
    })
    await flushPromises()

    const warning = wrapper.get('.alert-warning')
    expect(warning.text()).toContain('Agent version 1.9.0 differs from BootUI 2.0.0')
    expect(wrapper.text().indexOf('Agent installed')).toBeLessThan(wrapper.text().indexOf('Claim released'))
  })

  it('shows the executors sensor with its self-test, hooks, and explained counters', async () => {
    wrapper = mountPanel({
      ...baseReport,
      state: 'ARMED',
      sensors: [
        {
          id: 'executors',
          state: 'installed',
          active: true,
          instrumentedTypes: 9,
          failures: [],
          durationMillis: 87,
          installMillis: 80,
          selfTestMillis: 7,
          retransformMillis: 64,
          transformedTypes: 2,
          retransformedTypes: 9,
          selfTestPassed: true,
          selfTestError: null,
          selfTestSteps: {'thread-pool': 'passed'},
          hooks: [
            {
              id: 'ThreadPoolExecutor.addWorker',
              kind: 'key',
              type: 'java.util.concurrent.ThreadPoolExecutor',
              present: true,
              transformed: true,
              selfTest: 'passed',
              fired: 12
            },
            {
              id: 'DelayScheduler',
              kind: 'key',
              type: 'java.util.concurrent.DelayScheduler$ScheduledForkJoinTask',
              present: false,
              transformed: false,
              selfTest: 'unsupported',
              fired: 0
            }
          ],
          failedTypes: 0,
          skippedTypes: 0,
          executors: {
            pending: 1,
            neverApplied: 3,
            ambiguous: 0,
            stale: 0,
            refused: 0,
            virtualSkipped: 0,
            periodicSkipped: 2,
            skippedTasks: 5,
            skippedThreads: 0,
            failures: 1,
            disabledReason: null,
            asyncApplies: true
          }
        }
      ]
    })
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('installed in 87.0 ms (install 80.0 ms, self-test 7.00 ms)')
    expect(text).toContain('active')
    expect(text).not.toContain('inactive')
    expect(text).toContain('9 in 64.0 ms')
    expect(text).toContain('passed')
    expect(text).toContain('ThreadPoolExecutor.addWorker')
    expect(text).toContain('receives tasks')
    expect(text).toContain('unsupported on this JDK')
    expect(text).toContain('Never applied 3')
    expect(text).toContain('pools whose workers started before the claim')
    expect(text).toContain('Periodic tasks skipped 2')
    expect(text).not.toContain('No sensor installed')
    expect(wrapper.find('[aria-labelledby="java-agent-hooks-executors"]').exists()).toBe(true)
  })

  it('shows the threads sensor with its own explained counters', async () => {
    wrapper = mountPanel({
      ...baseReport,
      state: 'ARMED',
      sensors: [
        {
          id: 'threads',
          state: 'installed',
          active: false,
          instrumentedTypes: 3,
          failures: [],
          durationMillis: 41,
          selfTestPassed: true,
          selfTestError: null,
          selfTestSteps: {},
          hooks: [
            {
              id: 'Thread.start',
              kind: 'key',
              type: 'java.lang.Thread',
              present: true,
              transformed: true,
              selfTest: 'passed',
              fired: 4
            }
          ],
          failedTypes: 0,
          skippedTypes: 0,
          executors: {
            pending: 0,
            neverApplied: 0,
            ambiguous: 0,
            stale: 0,
            refused: 0,
            virtualSkipped: 0,
            periodicSkipped: 0,
            skippedTasks: 0,
            skippedThreads: 0,
            failures: 0,
            disabledReason: null,
            asyncApplies: false,
            libraryThreadsSkipped: 6,
            poolWorkersSkipped: 2
          }
        }
      ]
    })
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('Thread.start')
    expect(text).toContain('starts threads')
    expect(text).toContain('inactive')
    expect(text).toContain('Library threads skipped 6')
    expect(text).toContain('Pool workers skipped 2')
    expect(text).not.toContain('Periodic tasks skipped')
    expect(wrapper.find('[aria-labelledby="java-agent-hooks-threads"]').exists()).toBe(true)
  })

  it('does not call the API when manifest availability says the panel is unavailable', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(JavaAgent, {
      props: {panel: {id: 'java-agent', enabled: true, available: false, unavailableReason: 'agent disabled'}}
    })
    await flushPromises()

    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('agent disabled')
  })
})
