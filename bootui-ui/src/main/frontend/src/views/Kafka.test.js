import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {useRoute} from 'vue-router'

import Kafka from './Kafka.vue'

vi.mock('../utils/useConfirm.js', () => ({
  useConfirm: () => ({confirm: () => Promise.resolve(true)})
}))

vi.mock('vue-router', () => ({useRoute: vi.fn(() => ({query: {}}))}))

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function unavailableReport() {
  return {
    available: false,
    unavailableReason: 'No KafkaTemplate bean is present',
    capturing: false,
    captureKeyEnabled: false,
    maxEntries: 200,
    totalCaptured: 0,
    total: 0,
    messages: []
  }
}

function emptyReport() {
  return {
    available: true,
    unavailableReason: null,
    capturing: true,
    captureKeyEnabled: true,
    maxEntries: 200,
    totalCaptured: 0,
    total: 0,
    messages: []
  }
}

function reportWithMessages(overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    capturing: true,
    captureKeyEnabled: true,
    maxEntries: 200,
    totalCaptured: 2,
    total: 2,
    messages: [
      {
        id: 2,
        timestamp: 1700000000000,
        direction: 'CONSUME',
        topic: 'orders',
        partition: 0,
        offset: 41,
        key: 'a1b2c3d4e5f6a7b8',
        durationMillis: 12,
        success: true,
        errorMessage: null,
        groupId: 'orders-group',
        listenerId: 'orderListener'
      },
      {
        id: 1,
        timestamp: 1700000000000,
        direction: 'PRODUCE',
        topic: 'shipments',
        partition: null,
        offset: null,
        key: null,
        durationMillis: null,
        success: false,
        errorMessage: 'Broker not available',
        groupId: null,
        listenerId: null
      }
    ],
    ...overrides
  }
}

describe('Kafka panel', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('shows an unavailable notice when no KafkaTemplate bean is present', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(unavailableReport())))
    wrapper = mount(Kafka)
    await flushPromises()

    expect(wrapper.text()).toContain('Kafka capture is unavailable')
    expect(wrapper.text()).toContain('No KafkaTemplate bean is present')
  })

  it('shows an empty state when no Kafka activity has been captured yet', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(emptyReport())))
    wrapper = mount(Kafka)
    await flushPromises()

    expect(wrapper.text()).toContain('No Kafka activity captured yet')
  })

  it('lists captured Kafka activity with topic, direction, and status', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(reportWithMessages())))
    wrapper = mount(Kafka)
    await flushPromises()

    expect(wrapper.text()).toContain('orders')
    expect(wrapper.text()).toContain('orders-group')
    expect(wrapper.text()).toContain('orderListener')
    expect(wrapper.text()).toContain('shipments')
    expect(wrapper.text()).toContain('ok')
    expect(wrapper.text()).toContain('error')
    expect(wrapper.text()).toContain('2 retained')
    expect(wrapper.text()).toContain('2 captured since startup')

    const errorBadge = wrapper.findAll('.badge').find((b) => b.text() === 'error')
    expect(errorBadge.attributes('title')).toBe('Broker not available')
  })

  it('filters messages by topic, key, group, or listener', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(reportWithMessages())))
    wrapper = mount(Kafka)
    await flushPromises()

    const input = wrapper.find('input.kafka-filter-input')
    await input.setValue('orders-group')
    await flushPromises()

    expect(wrapper.text()).toContain('orders')
    expect(wrapper.text()).not.toContain('shipments')
  })

  it('shows no results message when the filter matches nothing', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(reportWithMessages())))
    wrapper = mount(Kafka)
    await flushPromises()

    const input = wrapper.find('input.kafka-filter-input')
    await input.setValue('no-such-match')
    await flushPromises()

    expect(wrapper.text()).toContain('No captured Kafka activity matches your filter')
  })

  it('filters messages by direction', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(reportWithMessages())))
    wrapper = mount(Kafka)
    await flushPromises()

    const select = wrapper.find('select.kafka-direction-select')
    await select.setValue('PRODUCE')
    await flushPromises()

    expect(wrapper.text()).toContain('shipments')
    expect(wrapper.text()).not.toContain('orders-group')
  })

  it('shows a capture-disabled banner when capturing is paused', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(reportWithMessages({capturing: false}))))
    wrapper = mount(Kafka)
    await flushPromises()

    expect(wrapper.text()).toContain('Kafka capture is currently disabled')
    expect(wrapper.text()).toContain('bootui.kafka.enabled=false')
  })

  it('shows a key-hash-disabled banner when key capture is off', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(reportWithMessages({captureKeyEnabled: false}))))
    wrapper = mount(Kafka)
    await flushPromises()

    expect(wrapper.text()).toContain('Key hashes are not being captured')
    expect(wrapper.text()).toContain('bootui.kafka.capture-key=true')
  })

  it('clears captured Kafka activity when confirmed', async () => {
    let cleared = false
    const fetchMock = vi.fn((url, init) => {
      if (url === 'api/kafka' && init?.method === 'DELETE') {
        cleared = true
        return Promise.resolve({ok: true, status: 204})
      }
      return Promise.resolve(jsonResponse(cleared ? emptyReport() : reportWithMessages()))
    })
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(Kafka)
    await flushPromises()

    await wrapper.get('button.btn-outline-danger').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('api/kafka', {method: 'DELETE'})
    expect(wrapper.text()).toContain('Cleared captured Kafka activity')
    expect(wrapper.text()).toContain('No Kafka activity captured yet')
  })

  it('prefills the filter from a ?q= deep link (e.g. from Live Activity)', async () => {
    vi.mocked(useRoute).mockReturnValueOnce({query: {q: 'shipments'}})
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(reportWithMessages())))
    wrapper = mount(Kafka)
    await flushPromises()

    expect(wrapper.find('input.kafka-filter-input').element.value).toBe('shipments')
    expect(wrapper.text()).toContain('shipments')
    expect(wrapper.text()).not.toContain('orders-group')
  })
})
