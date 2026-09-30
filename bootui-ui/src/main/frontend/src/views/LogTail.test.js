import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import LogTail from './LogTail.vue'

class EventSourceStub {
  static instances = []

  constructor(url) {
    this.url = url
    this.listeners = new Map()
    EventSourceStub.instances.push(this)
  }

  addEventListener(type, listener) {
    this.listeners.set(type, listener)
  }

  close() {}
}

describe('Log Tail', () => {
  afterEach(() => {
    EventSourceStub.instances = []
    vi.unstubAllGlobals()
  })

  it('uses the shared panel header and exposes streamed lines as a live log', async () => {
    vi.stubGlobal('EventSource', EventSourceStub)

    const wrapper = mount(LogTail)
    await flushPromises()

    expect(wrapper.get('.panel-header__title').text()).toBe('Log Tail')
    expect(EventSourceStub.instances).toHaveLength(1)

    const status = wrapper.get('[role="status"][aria-label="Log stream status"]')
    EventSourceStub.instances[0].onopen()
    await flushPromises()
    expect(status.text()).toBe('Connected')

    const log = wrapper.get('[role="log"]')
    expect(log.attributes('aria-live')).toBe('polite')
    expect(log.attributes('aria-relevant')).toBe('additions')

    EventSourceStub.instances[0].listeners.get('log')({
      data: JSON.stringify({
        timestamp: '2026-08-21T10:00:00Z',
        level: 'INFO',
        logger: 'com.example.Application',
        message: 'Started'
      })
    })
    await flushPromises()

    expect(log.text()).toContain('com.example.Application')
    expect(log.text()).toContain('Started')
  })

  it('marks omitted and missing messages instead of rendering an empty line', async () => {
    vi.stubGlobal('EventSource', EventSourceStub)
    const wrapper = mount(LogTail)
    await flushPromises()
    const emit = (line) =>
      EventSourceStub.instances[0].listeners.get('log')({
        data: JSON.stringify({timestamp: 1_700_000_000_000, thread: 'main', ...line})
      })

    emit({level: 'WARN', logger: 'com.example.Db', message: null, messageOmitted: true})
    emit({level: 'INFO', logger: 'com.example.Empty', message: null, messageOmitted: false})
    emit({level: 'ERROR', logger: 'com.example.Api', message: 'login password=******', messageOmitted: false})
    await flushPromises()

    const rows = wrapper.findAll('[role="log"] .d-block')
    expect(rows).toHaveLength(3)
    expect(rows[0].get('.log-placeholder').text()).toBe('message omitted by policy')
    expect(rows[1].get('.log-placeholder').text()).toBe('no message')
    expect(rows[2].find('.log-placeholder').exists()).toBe(false)
    expect(rows[2].text()).toContain('login password=******')
    expect(wrapper.get('[role="note"]').text()).toContain(
      'Log messages are omitted because bootui.expose-values is METADATA_ONLY.'
    )
  })

  it('filters lines without a message by level and logger', async () => {
    vi.stubGlobal('EventSource', EventSourceStub)
    const wrapper = mount(LogTail)
    await flushPromises()
    const emit = (line) =>
      EventSourceStub.instances[0].listeners.get('log')({
        data: JSON.stringify({
          timestamp: 1_700_000_000_000,
          thread: 'main',
          message: null,
          messageOmitted: true,
          ...line
        })
      })
    emit({level: 'INFO', logger: 'com.example.Quiet'})
    emit({level: 'ERROR', logger: 'com.example.Loud'})
    await flushPromises()

    await wrapper.get('#log-tail-level-filter').setValue('WARN+')
    let rows = wrapper.findAll('[role="log"] .d-block')
    expect(rows).toHaveLength(1)
    expect(rows[0].text()).toContain('com.example.Loud')

    await wrapper.get('#log-tail-level-filter').setValue('ALL')
    await wrapper.get('#log-tail-text-filter').setValue('com.example.quiet')
    rows = wrapper.findAll('[role="log"] .d-block')
    expect(rows).toHaveLength(1)
    expect(rows[0].text()).toContain('com.example.Quiet')

    await wrapper.get('#log-tail-text-filter').setValue('password')
    expect(wrapper.findAll('[role="log"] .d-block')).toHaveLength(0)
    expect(wrapper.find('[role="note"]').exists()).toBe(false)
  })
})
