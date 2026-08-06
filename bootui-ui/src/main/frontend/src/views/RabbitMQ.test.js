import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {useRoute} from 'vue-router'

import RabbitMq from './RabbitMQ.vue'
import PanelHeader from './components/PanelHeader.vue'

vi.mock('../utils/useConfirm.js', () => ({
  useConfirm: () => ({confirm: () => Promise.resolve(true)})
}))

vi.mock('vue-router', () => ({useRoute: vi.fn(() => ({query: {}}))}))

const emptyReport = {
  available: true,
  unavailableReason: null,
  capturing: true,
  captureCorrelationIdEnabled: false,
  maxEntries: 200,
  totalCaptured: 0,
  total: 0,
  messages: []
}

function reportWithMessages() {
  return {
    ...emptyReport,
    captureCorrelationIdEnabled: true,
    totalCaptured: 2,
    total: 2,
    messages: [
      {
        id: 2,
        timestamp: 1700000000000,
        direction: 'CONSUME',
        exchange: 'orders',
        routingKey: 'order.created',
        queue: 'fulfillment',
        correlationId: 'a1b2c3d4e5f6a7b8',
        durationMillis: 12,
        success: true,
        errorMessage: null
      },
      {
        id: 1,
        timestamp: 1700000000000,
        direction: 'PUBLISH',
        exchange: 'shipping',
        routingKey: 'shipment.failed',
        queue: null,
        correlationId: null,
        durationMillis: null,
        success: false,
        errorMessage: 'Message processing failed'
      }
    ]
  }
}

function jsonResponse(body) {
  return {ok: true, status: 200, json: () => Promise.resolve(body)}
}

describe('RabbitMQ panel', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('does not call the API when the manifest reports RabbitMQ unavailable', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(RabbitMq, {
      props: {
        panel: {
          id: 'rabbitmq',
          enabled: true,
          available: false,
          unavailableReason: 'RabbitMQ integration is not present'
        }
      }
    })
    await flushPromises()

    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('RabbitMQ capture is unavailable')
    expect(wrapper.text()).toContain('RabbitMQ integration is not present')
    expect(wrapper.findComponent(PanelHeader).props('refreshable')).toBe(false)
  })

  it('loads and filters retained metadata without rendering payloads or raw headers', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(reportWithMessages())))
    wrapper = mount(RabbitMq)
    await flushPromises()

    expect(wrapper.text()).toContain('orders')
    expect(wrapper.text()).toContain('order.created')
    expect(wrapper.text()).toContain('fulfillment')
    expect(wrapper.text()).toContain('a1b2c3d4e5f6a7b8')
    expect(wrapper.text()).not.toContain('payload')
    expect(wrapper.text()).not.toContain('headers')
    expect(wrapper.get('input.rabbit-filter-input').attributes('aria-label')).toBe('Filter RabbitMQ activity')
    expect(wrapper.get('select.rabbit-direction-select').attributes('aria-label')).toBe(
      'Filter RabbitMQ activity by direction'
    )
    expect(wrapper.findAll('th').every((header) => header.attributes('scope') === 'col')).toBe(true)

    await wrapper.get('input.rabbit-filter-input').setValue('shipping')
    expect(wrapper.text()).toContain('shipment.failed')
    expect(wrapper.text()).not.toContain('fulfillment')

    await wrapper.get('select.rabbit-direction-select').setValue('CONSUME')
    expect(wrapper.text()).toContain('No captured RabbitMQ activity matches your filter')
  })

  it('explains the privacy default when correlation ID capture is disabled', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(emptyReport)))
    wrapper = mount(RabbitMq)
    await flushPromises()

    expect(wrapper.text()).toContain('Correlation ID hashes are not being captured')
    expect(wrapper.text()).toContain('bootui.rabbitmq.capture-correlation-id=true')
  })

  it('shows coherent empty and capture-disabled states', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({...emptyReport, capturing: false})))
    wrapper = mount(RabbitMq)
    await flushPromises()

    expect(wrapper.text()).toContain('RabbitMQ capture is currently disabled')
    expect(wrapper.text()).toContain('bootui.rabbitmq.enabled=false')
    expect(wrapper.text()).toContain('No RabbitMQ activity captured yet')
  })

  it('prefills the filter from a Live Activity deep link', async () => {
    vi.mocked(useRoute).mockReturnValueOnce({query: {q: 'shipping'}})
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(reportWithMessages())))
    wrapper = mount(RabbitMq)
    await flushPromises()

    expect(wrapper.get('input.rabbit-filter-input').element.value).toBe('shipping')
    expect(wrapper.text()).toContain('shipment.failed')
    expect(wrapper.text()).not.toContain('fulfillment')
  })

  it('clears captured activity when confirmed', async () => {
    let cleared = false
    const fetchMock = vi.fn((url, init) => {
      if (url === 'api/rabbitmq' && init?.method === 'DELETE') {
        cleared = true
        return Promise.resolve({ok: true, status: 204})
      }
      return Promise.resolve(jsonResponse(cleared ? emptyReport : reportWithMessages()))
    })
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(RabbitMq)
    await flushPromises()

    await wrapper.get('button.btn-outline-danger').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('api/rabbitmq', {method: 'DELETE'})
    expect(wrapper.text()).toContain('Cleared captured RabbitMQ activity')
  })

  it('blocks clear locally in read-only mode', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(reportWithMessages()))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(RabbitMq, {
      props: {
        panel: {
          id: 'rabbitmq',
          enabled: true,
          available: true,
          readOnly: true,
          readOnlyReason: 'RabbitMQ actions are read-only'
        }
      }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Clearing captured RabbitMQ activity is read-only')
    expect(wrapper.get('button.btn-outline-danger').attributes('disabled')).toBeDefined()
    expect(fetchMock).not.toHaveBeenCalledWith('api/rabbitmq', {method: 'DELETE'})
  })
})
