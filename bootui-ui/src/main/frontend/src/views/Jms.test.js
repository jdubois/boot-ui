import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {useRoute} from 'vue-router'

import Jms from './Jms.vue'
import PanelHeader from './components/PanelHeader.vue'

vi.mock('../utils/useConfirm.js', () => ({
  useConfirm: () => ({confirm: () => Promise.resolve(true)})
}))

vi.mock('vue-router', () => ({useRoute: vi.fn(() => ({query: {}}))}))

const emptyReport = {
  available: true,
  unavailableReason: null,
  capturing: true,
  captureMessageIdEnabled: false,
  maxEntries: 200,
  totalCaptured: 0,
  total: 0,
  messages: []
}

function reportWithMessages() {
  return {
    ...emptyReport,
    captureMessageIdEnabled: true,
    totalCaptured: 2,
    total: 2,
    messages: [
      {
        id: 2,
        timestamp: 1700000000000,
        direction: 'CONSUME',
        destination: 'orders',
        messageId: 'a1b2c3d4e5f6a7b8',
        durationMillis: 12,
        success: true,
        failureType: null,
        subscriptionName: 'fulfillment',
        listenerId: 'orderListenerFactory'
      },
      {
        id: 1,
        timestamp: 1700000000000,
        direction: 'PRODUCE',
        destination: 'shipping',
        messageId: null,
        durationMillis: 3,
        success: false,
        failureType: 'jakarta.jms.JMSException',
        subscriptionName: null,
        listenerId: null
      }
    ]
  }
}

function jsonResponse(body) {
  return {ok: true, status: 200, json: () => Promise.resolve(body)}
}

describe('JMS panel', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('does not call the API when the manifest reports JMS unavailable', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(Jms, {
      props: {
        panel: {
          id: 'jms',
          enabled: true,
          available: false,
          unavailableReason: 'No JmsTemplate bean is available'
        }
      }
    })
    await flushPromises()

    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('JMS capture is unavailable')
    expect(wrapper.text()).toContain('No JmsTemplate bean is available')
    expect(wrapper.findComponent(PanelHeader).props('refreshable')).toBe(false)
  })

  it('loads and filters retained metadata without rendering payloads or raw headers', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(reportWithMessages())))
    wrapper = mount(Jms)
    await flushPromises()

    expect(wrapper.text()).toContain('orders')
    expect(wrapper.text()).toContain('a1b2c3d4e5f6a7b8')
    expect(wrapper.text()).toContain('fulfillment')
    expect(wrapper.text()).toContain('orderListenerFactory')
    expect(wrapper.text()).not.toContain('payload')
    expect(wrapper.text()).not.toContain('headers')

    await wrapper.get('input.jms-filter-input').setValue('shipping')
    expect(wrapper.text()).toContain('shipping')
    expect(wrapper.text()).not.toContain('fulfillment')

    const errorBadge = wrapper.findAll('.badge').find((badge) => badge.text() === 'error')
    expect(errorBadge.attributes('title')).toBe('jakarta.jms.JMSException')
  })

  it('filters by direction and explains when nothing matches', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(reportWithMessages())))
    wrapper = mount(Jms)
    await flushPromises()

    await wrapper.get('select.jms-direction-select').setValue('CONSUME')
    expect(wrapper.text()).toContain('orders')
    expect(wrapper.text()).not.toContain('shipping')

    await wrapper.get('input.jms-filter-input').setValue('missing')
    expect(wrapper.text()).toContain('No captured JMS activity matches your filter')
  })

  it('explains the privacy default when message ID capture is disabled', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(emptyReport)))
    wrapper = mount(Jms)
    await flushPromises()

    expect(wrapper.text()).toContain('Message ID hashes are not being captured')
    expect(wrapper.text()).toContain('bootui.jms.capture-message-id=true')
  })

  it('clears captured activity when confirmed', async () => {
    let cleared = false
    const fetchMock = vi.fn((url, init) => {
      if (url === 'api/jms' && init?.method === 'DELETE') {
        cleared = true
        return Promise.resolve({ok: true, status: 204})
      }
      return Promise.resolve(jsonResponse(cleared ? emptyReport : reportWithMessages()))
    })
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(Jms)
    await flushPromises()

    await wrapper.get('button.btn-outline-danger').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('api/jms', {method: 'DELETE'})
    expect(wrapper.text()).toContain('Cleared captured JMS activity')
  })

  it('blocks clear locally in read-only mode', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(reportWithMessages()))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(Jms, {
      props: {
        panel: {
          id: 'jms',
          enabled: true,
          available: true,
          readOnly: true,
          readOnlyReason: 'JMS actions are read-only'
        }
      }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('Clearing captured JMS activity is read-only')
    expect(wrapper.get('button.btn-outline-danger').attributes('disabled')).toBeDefined()
    expect(fetchMock).not.toHaveBeenCalledWith('api/jms', {method: 'DELETE'})
  })

  it('prefills the filter from a Live Activity deep link', async () => {
    vi.mocked(useRoute).mockReturnValueOnce({query: {q: 'shipping'}})
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(reportWithMessages())))
    wrapper = mount(Jms)
    await flushPromises()

    expect(wrapper.get('input.jms-filter-input').element.value).toBe('shipping')
    expect(wrapper.text()).toContain('shipping')
    expect(wrapper.text()).not.toContain('fulfillment')
  })
})
