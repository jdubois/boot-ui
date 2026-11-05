import {flushPromises, shallowMount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import Traces from './Traces.vue'
import Exceptions from './Exceptions.vue'
import Email from './Email.vue'
import Jms from './Jms.vue'
import Kafka from './Kafka.vue'
import RabbitMQ from './RabbitMQ.vue'
import Config from './Config.vue'
import DevTools from './DevTools.vue'
import DevServices from './DevServices.vue'
import LiveActivity from './LiveActivity.vue'
import SqlTrace from './SqlTrace.vue'
import RestClientTrace from './RestClientTrace.vue'
import Transactions from './Transactions.vue'
import WebSockets from './WebSockets.vue'
import Threads from './Threads.vue'
import HttpSessions from './HttpSessions.vue'
import GraalVm from './GraalVm.vue'
import Crac from './Crac.vue'
import RuntimeJournalStatus from './components/RuntimeJournalStatus.vue'
import MethodProbes from './components/MethodProbes.vue'

const {confirm} = vi.hoisted(() => ({confirm: vi.fn()}))
vi.mock('../utils/useConfirm.js', () => ({useConfirm: () => ({confirm})}))
vi.mock('vue-router', async (importOriginal) => ({
  ...(await importOriginal()),
  useRoute: () => ({query: {}}),
  useRouter: () => ({replace: vi.fn(), push: vi.fn()})
}))

const captureOptions = {confirm: {title: 'Clear'}, url: 'api/not-called', init: {method: 'POST'}}
const cases = [
  ['Traces', Traces, 'clearAll'],
  ['Exceptions', Exceptions, 'clearAll'],
  ['Email', Email, 'clearAll'],
  ['JMS', Jms, 'clearAll'],
  ['Kafka', Kafka, 'clearAll'],
  ['RabbitMQ', RabbitMQ, 'clearAll'],
  ['Config save', Config, 'postOverride', ['example', 'value', 'create']],
  ['Config remove', Config, 'removeOverride', ['example']],
  ['DevTools', DevTools, 'restart'],
  ['DevServices', DevServices, 'restart', [{id: 'service', name: 'Service'}]],
  ['Live Activity', LiveActivity, 'useExistingDatasource'],
  ['SQL Trace', SqlTrace, 'applyAction', ['clear', captureOptions]],
  ['REST Client', RestClientTrace, 'applyAction', ['clear', captureOptions]],
  ['Transactions', Transactions, 'applyAction', ['clear', captureOptions]],
  ['WebSockets', WebSockets, 'applyAction', ['clear', captureOptions]],
  ['Threads', Threads, 'downloadDump'],
  ['HTTP Sessions clear', HttpSessions, 'clearSession', [{id: 'session', sessionKey: 'key'}]],
  ['HTTP Sessions destroy', HttpSessions, 'destroySession', [{id: 'session', sessionKey: 'key'}]],
  ['GraalVM metadata', GraalVm, 'installMetadata'],
  ['GraalVM Dockerfile', GraalVm, 'installDockerfile'],
  ['GraalVM combined', GraalVm, 'installBoth'],
  ['CRaC Dockerfile', Crac, 'installDockerfile'],
  ['CRaC entrypoint', Crac, 'installEntrypoint'],
  ['CRaC combined', Crac, 'installBoth'],
  ['Runtime journal', RuntimeJournalStatus, 'clearRecording', [], true],
  ['Method probes', MethodProbes, 'start', [], true]
]

describe('Action policy changes during confirmation', () => {
  let wrapper
  beforeEach(() => {
    confirm.mockReset()
    document.cookie = 'XSRF-TOKEN=policy-test; path=/'
    vi.stubGlobal(
      'fetch',
      vi.fn(() => new Promise(() => {}))
    )
  })
  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it.each(cases)(
    '%s rechecks policy before issuing its request',
    async (_title, component, handler, args = [], direct = false) => {
      let approve
      confirm.mockImplementation(
        () =>
          new Promise((resolve) => {
            approve = resolve
          })
      )
      const policy = {readOnly: false, available: true, enabled: true}
      wrapper = shallowMount(component, {
        props: direct ? {readOnly: false} : {panel: policy},
        global: {stubs: {RouterLink: true}}
      })
      const state = wrapper.vm.$.setupState
      if (handler === 'useExistingDatasource') {
        state.journalStatus = {enabled: true, store: 'IN_MEMORY'}
      }
      const action = state[handler](...args)
      await flushPromises()
      expect(confirm).toHaveBeenCalled()
      expect(approve).toBeTypeOf('function')
      await wrapper.setProps(direct ? {readOnly: true} : {panel: {...policy, readOnly: true}})
      approve(true)
      await action
      expect(fetch.mock.calls.filter(([, init]) => ['POST', 'DELETE', 'PUT', 'PATCH'].includes(init?.method))).toEqual(
        []
      )
    }
  )

  it.each(cases.filter(([title]) => /^(Traces|Exceptions|Email|JMS|Kafka|RabbitMQ|GraalVM|CRaC)/.test(title)))(
    '%s preserves canonical denial text and status',
    async (_title, component, handler, args = []) => {
      const reason = 'BootUI is read-only via bootui.read-only=true'
      confirm.mockResolvedValue(true)
      fetch.mockImplementation((_url, init) =>
        init?.method
          ? Promise.resolve(
              new Response(JSON.stringify({error: 'BootUI panel access denied', panel: 'test', reason}), {
                status: 403,
                headers: {'Content-Type': 'application/json'}
              })
            )
          : new Promise(() => {})
      )
      wrapper = shallowMount(component, {
        props: {panel: {readOnly: false}},
        global: {stubs: {RouterLink: true}}
      })
      const state = wrapper.vm.$.setupState
      await state[handler](...args)
      const messages = [
        state.banner?.text,
        state.installResult?.message,
        state.dockerInstallResult?.message,
        state.entrypointInstallResult?.message,
        state.bothInstallResult?.message
      ]
        .filter(Boolean)
        .join(' ')
      expect(messages).toContain(reason)
      expect(messages).toContain('HTTP 403')
    }
  )

  it('guards GraalVM cancellation under read-only and reports a late cancellation denial', async () => {
    wrapper = shallowMount(GraalVm, {props: {panel: {readOnly: true}}})
    await wrapper.vm.$.setupState.cancelScan()
    expect(fetch.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
    await wrapper.setProps({panel: {readOnly: false}})
    fetch.mockResolvedValue(
      new Response(
        JSON.stringify({
          error: 'BootUI panel access denied',
          panel: 'graalvm',
          reason: 'Readiness is read-only.'
        }),
        {status: 403, headers: {'Content-Type': 'application/json'}}
      )
    )
    await wrapper.vm.$.setupState.cancelScan()
    expect(wrapper.vm.$.setupState.actionMessage).toContain('Readiness is read-only.')
    expect(wrapper.vm.$.setupState.actionMessage).toContain('HTTP 403')
  })

  it('retains exception status and cached groups when a status action is denied', async () => {
    wrapper = shallowMount(Exceptions, {
      props: {panel: {readOnly: false}},
      global: {stubs: {RouterLink: true}}
    })
    const state = wrapper.vm.$.setupState
    const group = {id: 'group', status: 'OPEN', exceptionClassName: 'example.Failure'}
    state.report = {groups: [group]}
    fetch.mockResolvedValue(
      new Response(
        JSON.stringify({
          error: 'BootUI panel access denied',
          panel: 'exceptions',
          reason: 'Exceptions are read-only.'
        }),
        {status: 403, headers: {'Content-Type': 'application/json'}}
      )
    )
    await state.changeStatus(group, 'RESOLVED')
    expect(state.report.groups[0].status).toBe('OPEN')
    expect(state.banner.text).toContain('Exceptions are read-only.')
    expect(state.banner.text).toContain('HTTP 403')
  })
})
