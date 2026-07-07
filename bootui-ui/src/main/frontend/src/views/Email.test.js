import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Email from './Email.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function unavailableReport() {
  return {
    available: false,
    unavailableReason: 'No JavaMailSender bean is present',
    devTrapEnabled: false,
    maxEntries: 100,
    total: 0,
    messages: []
  }
}

function emptyReport() {
  return {
    available: true,
    unavailableReason: null,
    devTrapEnabled: false,
    maxEntries: 100,
    total: 0,
    messages: []
  }
}

function reportWithMessages(overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    devTrapEnabled: false,
    maxEntries: 100,
    total: 1,
    messages: [
      {
        id: 'msg-1',
        timestamp: 1700000000000,
        from: 'noreply@example.com',
        to: ['customer@example.com'],
        cc: [],
        bcc: [],
        subject: 'Order shipped',
        textBody: 'Your order is on the way',
        htmlBody: '<p>Your order is on the way</p>',
        attachments: [{filename: 'invoice.pdf', contentType: 'application/pdf', sizeBytes: 2048}],
        sent: true
      }
    ],
    ...overrides
  }
}

describe('Email panel', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('shows an unavailable notice when no JavaMailSender bean is present', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(unavailableReport())))
    wrapper = mount(Email)
    await flushPromises()

    expect(wrapper.text()).toContain('Email capture is unavailable')
    expect(wrapper.text()).toContain('No JavaMailSender bean is present')
  })

  it('shows an empty state when no emails have been captured yet', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(emptyReport())))
    wrapper = mount(Email)
    await flushPromises()

    expect(wrapper.text()).toContain('No outgoing emails captured yet')
  })

  it('lists captured emails and opens a detail drawer with the HTML preview', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(reportWithMessages())))
    wrapper = mount(Email)
    await flushPromises()

    expect(wrapper.text()).toContain('noreply@example.com')
    expect(wrapper.text()).toContain('customer@example.com')
    expect(wrapper.text()).toContain('Order shipped')
    expect(wrapper.text()).toContain('sent')

    const viewButton = wrapper.findAll('button').find((b) => b.text() === 'View')
    expect(viewButton).toBeTruthy()
    await viewButton.trigger('click')
    await flushPromises()

    const frame = wrapper.find('iframe.email-html-frame')
    expect(frame.exists()).toBe(true)
    expect(frame.attributes('srcdoc')).toContain('Your order is on the way')
    expect(frame.attributes('sandbox')).toBe('')
    expect(wrapper.text()).toContain('invoice.pdf')
  })

  it('filters messages by sender, recipient, or subject', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(reportWithMessages())))
    wrapper = mount(Email)
    await flushPromises()

    const input = wrapper.find('input.form-control')
    await input.setValue('no-such-match')
    await flushPromises()

    expect(wrapper.text()).toContain('No captured emails match your filter')
  })

  it('shows a dev-trap banner when dev-trap mode is enabled', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(reportWithMessages({devTrapEnabled: true}))))
    wrapper = mount(Email)
    await flushPromises()

    expect(wrapper.text()).toContain('Dev-trap mode is enabled')
    expect(wrapper.text()).toContain('bootui.email.dev-trap=true')
  })

  it('offers a per-message .eml download link', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(reportWithMessages())))
    wrapper = mount(Email)
    await flushPromises()

    const downloadLink = wrapper.find('a[download]')
    expect(downloadLink.exists()).toBe(true)
    expect(downloadLink.attributes('href')).toBe('api/email/msg-1/eml')
  })
})
