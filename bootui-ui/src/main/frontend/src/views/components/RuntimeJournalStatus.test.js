import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import RuntimeJournalStatus from './RuntimeJournalStatus.vue'
import {confirmState, settleConfirm} from '../../utils/useConfirm.js'

function status(overrides = {}) {
  return {
    enabled: true,
    runId: '1a2b3c4d',
    retainedEvents: 1234,
    retainedBytes: 2 * 1024 * 1024,
    maxEvents: 50000,
    maxBytes: 32 * 1024 * 1024,
    reservedEvents: 12,
    reservedCapacity: 5000,
    evictedEvents: 0,
    bindingBound: null,
    oldestRetainedAt: Date.UTC(2026, 9, 1, 9, 30, 0),
    queueDepth: 0,
    queueCapacity: 10000,
    recorded: {http: 300, sql: 900},
    dropped: {},
    droppedEvents: 0,
    ...overrides
  }
}

function respond(body, ok = true, statusCode = 200) {
  return Promise.resolve({ok, status: statusCode, json: () => Promise.resolve(body)})
}

describe('RuntimeJournalStatus', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'fetch',
      vi.fn((url) => (url === 'api/activity/journal' ? respond(status()) : respond({})))
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    settleConfirm(false)
  })

  it('loads the status once it is opened and states what the journal holds', async () => {
    const wrapper = mount(RuntimeJournalStatus)
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/activity/journal', expect.anything())
    const text = wrapper.text()
    expect(text).toContain('Runtime journal')
    expect(text).toContain('run 1a2b3c4d')
    expect(text).toContain(`${(1234).toLocaleString()} of ${(50000).toLocaleString()} events`)
    expect(text).toContain('http 300')
    expect(text).toContain('sql 900')
    expect(text).toContain('None: every event was recorded.')
    expect(wrapper.find('i.bi-journal-text').attributes('aria-hidden')).toBe('true')
  })

  it('names the drops per source and the bound that evicts', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        respond(status({droppedEvents: 42, dropped: {sql: 40, cache: 2}, evictedEvents: 7, bindingBound: 'BYTES'}))
      )
    )
    const wrapper = mount(RuntimeJournalStatus)
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('42, because the queue was full')
    expect(text).toContain('sql 40')
    expect(text).toContain('cache 2')
    expect(text).toContain('oldest events leave as the memory bound is reached')
  })

  it('says when the journal is disabled and offers nothing to clear', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => respond(status({enabled: false, runId: null})))
    )
    const wrapper = mount(RuntimeJournalStatus)
    await flushPromises()

    expect(wrapper.text()).toContain('The runtime journal is disabled')
    expect(wrapper.text()).not.toContain('Clear recording')
  })

  it('clears the recording only after confirmation, then reloads the status', async () => {
    const wrapper = mount(RuntimeJournalStatus)
    await flushPromises()
    fetch.mockImplementation((url) =>
      url === 'api/activity/journal/clear'
        ? respond({status: 'cleared', message: 'Cleared 1234 recorded events and the aggregates of this run.'})
        : respond(status({retainedEvents: 0}))
    )

    const clear = wrapper.findAll('button').find((button) => button.text().includes('Clear recording'))
    await clear.trigger('click')
    expect(confirmState.open).toBe(true)
    expect(confirmState.options.title).toBe('Clear recording?')
    settleConfirm(true)
    await flushPromises()

    const [, init] = fetch.mock.calls.find(([url]) => url === 'api/activity/journal/clear')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body)).toEqual({confirm: true})
    expect(wrapper.emitted('flash')[0]).toEqual([
      'Cleared 1234 recorded events and the aggregates of this run.',
      'success'
    ])
    expect(wrapper.text()).toContain(`0 of ${(50000).toLocaleString()} events`)
  })

  it('does not clear when the confirmation is cancelled or the panel is read-only', async () => {
    const wrapper = mount(RuntimeJournalStatus, {props: {readOnly: true, readOnlyReason: 'Live Activity is read-only'}})
    await flushPromises()

    const clear = wrapper.findAll('button').find((button) => button.text().includes('Clear recording'))
    expect(clear.attributes('disabled')).toBeDefined()
    expect(clear.attributes('title')).toBe('Live Activity is read-only')

    const writable = mount(RuntimeJournalStatus)
    await flushPromises()
    await writable
      .findAll('button')
      .find((button) => button.text().includes('Clear recording'))
      .trigger('click')
    settleConfirm(false)
    await flushPromises()
    expect(fetch.mock.calls.some(([url]) => url === 'api/activity/journal/clear')).toBe(false)
  })

  it('reports a status it cannot load', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => respond({}, false, 503))
    )
    const wrapper = mount(RuntimeJournalStatus)
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('Could not load the runtime journal status')
  })
})
