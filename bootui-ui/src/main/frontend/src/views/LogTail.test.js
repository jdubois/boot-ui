import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import LogTail from './LogTail.vue'

const route = vi.hoisted(() => ({query: {}}))
const push = vi.hoisted(() => vi.fn())

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({push})
}))

class FakeEventSource {
  constructor() {
    this.listeners = {}
    FakeEventSource.instances.push(this)
  }

  addEventListener(name, cb) {
    this.listeners[name] = cb
  }

  close() {
    this.closed = true
  }

  open() {
    if (this.onopen) this.onopen()
  }

  emitLog(line) {
    this.listeners.log?.({data: JSON.stringify(line)})
  }
}
FakeEventSource.instances = []

function line(overrides = {}) {
  return {
    timestamp: Date.now(),
    level: 'INFO',
    logger: 'com.example.Foo',
    message: 'hello world',
    thread: 'main',
    traceId: null,
    spanId: null,
    ...overrides
  }
}

describe('Log Tail', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    FakeEventSource.instances = []
    route.query = {}
    push.mockReset()
  })

  it('renders a per-line trace link and focuses the trace on click', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const wrapper = mount(LogTail)
    const source = FakeEventSource.instances[0]
    source.open()
    source.emitLog(line({message: 'traced', traceId: '4bf92f3577b34da6a3ce929d0e0e4736'}))
    await flushPromises()

    const traceLink = wrapper.find('.log-trace-link')
    expect(traceLink.exists()).toBe(true)
    await traceLink.trigger('click')
    expect(push).toHaveBeenCalledWith({
      name: 'log-tail',
      query: {trace: '4bf92f3577b34da6a3ce929d0e0e4736'}
    })
  })

  it('filters log lines to the active trace id from the route', async () => {
    route.query = {trace: 'trace-a'}
    vi.stubGlobal('EventSource', FakeEventSource)
    const wrapper = mount(LogTail)
    const source = FakeEventSource.instances[0]
    source.open()
    source.emitLog(line({message: 'matching', traceId: 'trace-a'}))
    source.emitLog(line({message: 'other-trace', traceId: 'trace-b'}))
    source.emitLog(line({message: 'no-trace', traceId: null}))
    await flushPromises()

    expect(wrapper.find('.correlation-banner').exists()).toBe(true)
    expect(wrapper.text()).toContain('matching')
    expect(wrapper.text()).not.toContain('other-trace')
    expect(wrapper.text()).not.toContain('no-trace')
  })
})
