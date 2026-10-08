import {flushPromises, mount} from '@vue/test-utils'
import {ref} from 'vue'
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

  it('opens on the setup and what the agent adds while it is not attached, without the attached-only sections', async () => {
    wrapper = mountPanel({...baseReport, warnings: ['A stale agent jar is on the class path']})
    await flushPromises()

    const sections = wrapper.findAll('section').map((section) => section.attributes('aria-labelledby'))
    expect(sections).toEqual([
      'java-agent-state-title',
      'java-agent-setup-title',
      'java-agent-about-title',
      'java-agent-warnings-title'
    ])
    expect(wrapper.get('#java-agent-setup-title').text()).toBe('Attach the agent')
    const steps = wrapper.get('[data-testid="java-agent-setup-steps"]').findAll('li')
    expect(steps).toHaveLength(3)
    expect(steps[0].text()).toContain('Download the agent')
    expect(steps[2].text()).toContain('Armed')
    expect(steps[2].text()).toContain('the status above says why')

    const about = wrapper.get('[aria-labelledby="java-agent-about-title"]')
    expect(about.text()).toContain('A Java agent is a JAR the JVM loads at start-up')
    expect(about.text()).toContain('does nothing on its own')
    for (const feature of [
      'Code Inventory',
      'Code Paths',
      'Side Effects',
      'Caught exceptions',
      'Thread pools',
      'Vulnerabilities',
      'Runtime Insights'
    ]) {
      expect(about.findAll('dt').map((term) => term.text())).toContain(feature)
    }
    expect(about.text()).toContain('claims it automatically when this application starts')
    expect(about.text()).toContain('The other panels work without it')
    expect(about.text()).not.toContain('Every other panel')
    expect(about.text()).toContain('overhead budget of 10%')

    const text = wrapper.text()
    for (const absent of ['Versions & runtime facts', 'No active claim', 'Runtime switches', 'No sensor installed']) {
      expect(text).not.toContain(absent)
    }
    expect(wrapper.findAll('[role="tablist"]')).toHaveLength(1)
  })

  it('skips the download step once the jar is found', async () => {
    const snippets = baseReport.setup.snippets.filter((snippet) => snippet.id !== 'maven-download')
    wrapper = mountPanel({...baseReport, setup: {...baseReport.setup, jarFound: true, snippets}})
    await flushPromises()

    const steps = wrapper.get('[data-testid="java-agent-setup-steps"]').findAll('li')
    expect(steps).toHaveLength(2)
    expect(steps[0].text()).toContain('-javaagent')
  })

  it.each(['DORMANT', 'UNAVAILABLE', 'DISABLED'])(
    'keeps the diagnosis first and the setup snippets last when the state is %s',
    async (state) => {
      wrapper = mountPanel({...baseReport, state, reason: 'Some reason.'})
      await flushPromises()

      const sections = wrapper.findAll('section').map((section) => section.attributes('aria-labelledby'))
      expect(sections[0]).toBe('java-agent-state-title')
      expect(sections.at(-1)).toBe('java-agent-setup-title')
      expect(sections).toContain('java-agent-sensors-title')
      expect(sections).not.toContain('java-agent-about-title')
      expect(wrapper.get('#java-agent-setup-title').text()).toBe('Setup snippets')
      expect(wrapper.find('[data-testid="java-agent-setup-steps"]').exists()).toBe(false)
      expect(wrapper.text()).toContain('No sensor installed: the agent installs the sensors this application asks for')
    }
  )

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
    expect(text).toContain('Install and release time (summed)')
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

  it('shows the inventory sensor with its record hooks and explained counters', async () => {
    wrapper = mountPanel({
      ...baseReport,
      state: 'ARMED',
      sensors: [
        {
          id: 'inventory',
          state: 'installed',
          instrumentedTypes: 12,
          failures: [],
          durationMillis: 230,
          selfTestPassed: true,
          selfTestError: null,
          selfTestSteps: {probe: 'ok'},
          hooks: [
            {
              id: 'method entry',
              kind: 'record',
              type: '(claimed packages)',
              present: true,
              transformed: true,
              selfTest: 'passed',
              fired: 87
            },
            {
              id: 'class load',
              kind: 'record',
              type: '(every class)',
              present: true,
              transformed: true,
              selfTest: 'not-exercised',
              fired: 4210
            }
          ],
          failedTypes: 0,
          skippedTypes: 0,
          executors: null,
          inventory: {
            methodsTracked: 340,
            executedThisRun: 87,
            methodOverflow: 0,
            transformFailures: 1,
            codeSources: 93,
            ringDropped: 5,
            ringLost: 0,
            internOverflow: 0,
            disabledReason: null
          }
        }
      ]
    })
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('records first calls')
    expect(text).toContain('counts loaded classes')
    expect(text).toContain('Methods tracked 340')
    expect(text).toContain('Executed this run 87')
    expect(text).toContain('Transform failures 1')
    expect(text).toContain('Code sources 93')
    expect(text).toContain('Records dropped 5')
    expect(text).not.toContain('Pending')
    expect(text).not.toContain('is disabled for this claim')
    expect(wrapper.find('[aria-labelledby="java-agent-hooks-inventory"]').exists()).toBe(true)
  })

  it('shows the code-paths sensor with its record hook and explained counters', async () => {
    wrapper = mountPanel({
      ...baseReport,
      state: 'ARMED',
      sensors: [
        {
          id: 'code-paths',
          state: 'installed',
          active: true,
          instrumentedTypes: 14,
          failures: [],
          durationMillis: 230,
          selfTestPassed: true,
          selfTestError: null,
          selfTestSteps: {probe: 'ok'},
          hooks: [
            {
              id: 'bean methods',
              kind: 'record',
              type: '(bean classes)',
              present: true,
              transformed: true,
              selfTest: 'passed',
              fired: 52
            }
          ],
          failedTypes: 0,
          skippedTypes: 0,
          executors: null,
          inventory: null,
          codePaths: {
            fragmentsFlushed: 52,
            fragmentsDropped: 0,
            queueDropped: 1,
            callsDropped: 7,
            queueBytes: 2048,
            excludedMethods: 2,
            errors: 0,
            disabledReason: null
          }
        }
      ]
    })
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('times bean methods per request')
    expect(text).toContain('Fragments recorded 52')
    expect(text).toContain('Queue full 1')
    expect(text).toContain('Calls in no node 7')
    expect(text).toContain('Methods excluded 2')
    expect(text).not.toContain('Methods tracked')
    expect(text).not.toContain('is disabled for this claim')
    expect(wrapper.find('[aria-labelledby="java-agent-counters-code-paths"]').exists()).toBe(true)
  })

  it('says when the code-paths sensor switched itself off', async () => {
    wrapper = mountPanel({
      ...baseReport,
      state: 'ARMED',
      sensors: [
        {
          id: 'code-paths',
          state: 'installed',
          instrumentedTypes: 3,
          failures: [],
          selfTestPassed: true,
          selfTestSteps: {},
          hooks: [
            {
              id: 'bean methods',
              kind: 'record',
              type: '(bean classes)',
              present: true,
              transformed: true,
              selfTest: 'passed',
              fired: 0
            }
          ],
          executors: null,
          inventory: null,
          codePaths: {
            fragmentsFlushed: 0,
            fragmentsDropped: 0,
            queueDropped: 0,
            callsDropped: 0,
            queueBytes: 0,
            excludedMethods: 0,
            errors: 100,
            disabledReason: 'switched off after 100 internal errors'
          }
        }
      ]
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Recording is disabled for this claim: switched off after 100 internal errors')
    expect(wrapper.text()).toContain('Internal errors 100')
  })

  it('says when the inventory sensor stopped recording', async () => {
    wrapper = mountPanel({
      ...baseReport,
      state: 'ARMED',
      sensors: [
        {
          id: 'inventory',
          state: 'self-test-failed',
          instrumentedTypes: 0,
          failures: [],
          durationMillis: null,
          selfTestPassed: false,
          selfTestError: 'self-test failed: the probe never ran',
          selfTestSteps: {},
          hooks: [
            {
              id: 'method entry',
              kind: 'record',
              type: '(claimed packages)',
              present: true,
              transformed: false,
              selfTest: 'failed',
              fired: 0
            }
          ],
          failedTypes: 0,
          skippedTypes: 0,
          executors: null,
          inventory: {
            methodsTracked: 0,
            executedThisRun: 0,
            methodOverflow: 0,
            transformFailures: 0,
            codeSources: 0,
            ringDropped: 0,
            ringLost: 0,
            internOverflow: 0,
            disabledReason: 'self-test failed: the probe never ran'
          }
        }
      ]
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Recording is disabled for this claim: self-test failed: the probe never ran')
  })

  it('says the opt-in switches need an armed claim, and lists them with their reasons once armed', async () => {
    wrapper = mountPanel({...baseReport, state: 'DORMANT'})
    await flushPromises()
    expect(wrapper.get('[data-testid="java-agent-toggles-unavailable"]').text()).toContain(
      'once the BootUI agent is attached and this application holds its claim'
    )
    wrapper.unmount()

    const toggles = [
      {
        id: 'threads',
        configured: false,
        enabled: false,
        overridden: false,
        state: 'off',
        optInReason: 'Off by default: it retransforms java.lang.Thread.',
        available: true,
        unavailableReason: null
      },
      {
        id: 'environment',
        configured: false,
        enabled: true,
        overridden: true,
        state: 'installed',
        optInReason: 'Off by default: it advises System.getProperty.',
        available: true,
        unavailableReason: null
      }
    ]
    const armed = {...baseReport, state: 'ARMED', toggles}
    const switched = {...armed, toggles: [toggles[0], {...toggles[1], enabled: false, overridden: false, state: 'off'}]}
    const fetchMock = vi.fn((url) =>
      Promise.resolve(
        String(url).includes('sensors/environment')
          ? new Response(JSON.stringify(switched), {status: 200, headers: {'content-type': 'application/json'}})
          : jsonResponse(armed)
      )
    )
    vi.stubGlobal('fetch', fetchMock)
    const panels = ref({panels: [{id: 'java-agent', enabled: true, available: true, readOnly: false}]})
    wrapper = mount(JavaAgent, {global: {provide: {panels}}})
    await flushPromises()

    expect(wrapper.text()).toContain('Runtime switches')
    expect(wrapper.get('[data-testid="agent-sensor-toggle-threads"]').text()).toContain('retransforms java.lang.Thread')
    const environment = wrapper.get('[data-testid="agent-sensor-toggle-environment"]')
    expect(environment.get('input').element.checked).toBe(true)
    expect(environment.text()).toContain('Overridden')

    await environment.get('input').setValue(false)
    await flushPromises()

    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('api/java-agent/sensors/environment'))).toBe(true)
    const after = wrapper.get('[data-testid="agent-sensor-toggle-environment"]')
    expect(after.get('input').element.checked).toBe(false)
    expect(after.text()).not.toContain('Overridden')
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
