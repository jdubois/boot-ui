import {flushPromises, mount} from '@vue/test-utils'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {ref} from 'vue'

import HttpProbe from './HttpProbe.vue'

const {apiFetch, confirm} = vi.hoisted(() => ({apiFetch: vi.fn(), confirm: vi.fn()}))

vi.mock('../api.js', async (importOriginal) => ({...(await importOriginal()), apiFetch}))
vi.mock('../utils/useConfirm.js', () => ({useConfirm: () => ({confirm})}))

function deferred() {
  let resolve
  const promise = new Promise((resolver) => {
    resolve = resolver
  })
  return {promise, resolve}
}

function ok(payload) {
  return {ok: true, status: 200, json: async () => payload}
}

function rejected(status, payload) {
  return {
    ok: false,
    status,
    json: async () => {
      if (payload === undefined) throw new SyntaxError('Unexpected end of JSON input')
      return payload
    }
  }
}

function response(overrides = {}) {
  return {
    status: 200,
    statusText: 'OK',
    headers: {},
    body: 'response',
    durationMs: 12,
    error: null,
    truncated: false,
    ...overrides
  }
}

describe('HttpProbe', () => {
  beforeEach(() => {
    apiFetch.mockReset()
    confirm.mockReset()
    confirm.mockResolvedValue(true)
  })

  it('rechecks policy after the unsafe-probe confirmation', async () => {
    const confirmation = deferred()
    confirm.mockReturnValueOnce(confirmation.promise)
    const wrapper = mount(HttpProbe, {props: {panel: {readOnly: false}}})
    await wrapper.get('select').setValue('POST')
    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()
    expect(confirm).toHaveBeenCalledOnce()
    await wrapper.setProps({panel: {readOnly: true}})
    confirmation.resolve(true)
    await flushPromises()
    expect(apiFetch).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('preserves canonical denial text and status rather than treating it as a probe result', async () => {
    apiFetch.mockResolvedValue(
      rejected(403, {
        error: 'BootUI panel access denied',
        panel: 'http-probe',
        reason: 'HTTP probes are read-only.'
      })
    )
    const wrapper = mount(HttpProbe)
    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('HTTP probes are read-only.')
    expect(wrapper.text()).toContain('HTTP 403')
    wrapper.unmount()
  })

  it('describes the Spring Boot app in the subtitle by default', () => {
    const wrapper = mount(HttpProbe)
    expect(wrapper.text()).toContain('running Spring Boot app')
  })

  it('describes the Quarkus app when the platform is quarkus', () => {
    const wrapper = mount(HttpProbe, {
      global: {provide: {panels: ref({platform: 'quarkus'})}}
    })
    expect(wrapper.text()).toContain('running Quarkus app')
    expect(wrapper.text()).not.toContain('Spring Boot')
  })

  it('warns when the response body was truncated', async () => {
    apiFetch.mockResolvedValue(ok(response({body: 'partial response', truncated: true})))
    const wrapper = mount(HttpProbe)

    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Response body was truncated at the configured byte limit.')
    expect(wrapper.text()).toContain('partial response')
    expect(confirm).not.toHaveBeenCalled()
  })

  it('links to the request profile when the probe response carries X-BootUI-Request-Id', async () => {
    const RouterLinkStub = {
      props: ['to'],
      template: '<a class="router-link-stub" :data-to="JSON.stringify(to)"><slot /></a>'
    }
    apiFetch.mockResolvedValue(ok(response({headers: {'X-BootUI-Request-Id': '1bfc811d88dbab7d'}})))
    const wrapper = mount(HttpProbe, {global: {stubs: {RouterLink: RouterLinkStub}}})

    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()

    const link = wrapper.get('.http-probe-profile-link')
    expect(link.text()).toContain('Open Request Profile')
    expect(JSON.parse(link.attributes('data-to'))).toEqual({
      path: '/activity',
      query: {request: '1bfc811d88dbab7d'}
    })
  })

  it.each(['GET', 'HEAD'])('sends safe %s probes without confirmation', async (method) => {
    apiFetch.mockResolvedValue(ok(response()))
    const wrapper = mount(HttpProbe)
    await wrapper.get('select').setValue(method)

    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()

    expect(confirm).not.toHaveBeenCalled()
    expect(apiFetch).toHaveBeenCalledOnce()
    expect(JSON.parse(apiFetch.mock.calls[0][1].body)).toMatchObject({method})
  })

  it('waits for confirmation before sending an unsafe probe', async () => {
    const confirmation = deferred()
    confirm.mockReturnValueOnce(confirmation.promise)
    apiFetch.mockResolvedValue(ok(response()))
    const wrapper = mount(HttpProbe)
    await wrapper.get('select').setValue('POST')
    await wrapper.get('input').setValue('/api/orders')

    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith({
      title: 'Send POST request?',
      message: 'This request may change state in your running app. Review the method and path before continuing.',
      resource: 'POST /api/orders',
      confirmLabel: 'Send POST',
      danger: true
    })
    expect(apiFetch).not.toHaveBeenCalled()

    confirmation.resolve(true)
    await flushPromises()

    expect(apiFetch).toHaveBeenCalledOnce()
    expect(JSON.parse(apiFetch.mock.calls[0][1].body)).toMatchObject({
      method: 'POST',
      path: '/api/orders'
    })
  })

  it.each(['POST', 'PUT', 'PATCH', 'DELETE'])('cancels unsafe %s probes without sending a request', async (method) => {
    confirm.mockResolvedValueOnce(false)
    const wrapper = mount(HttpProbe)
    await wrapper.get('select').setValue(method)

    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledOnce()
    expect(apiFetch).not.toHaveBeenCalled()
  })

  it('routes Enter through one confirmation without bypassing or duplicating the request', async () => {
    const confirmation = deferred()
    confirm.mockReturnValueOnce(confirmation.promise)
    const wrapper = mount(HttpProbe)
    await wrapper.get('select').setValue('PATCH')
    const pathInput = wrapper.get('input')
    await pathInput.setValue('/api/orders/1')

    await pathInput.trigger('keyup', {key: 'Enter'})
    await pathInput.trigger('keyup', {key: 'Enter'})
    await flushPromises()

    expect(confirm).toHaveBeenCalledOnce()
    expect(apiFetch).not.toHaveBeenCalled()

    confirmation.resolve(false)
    await flushPromises()

    expect(apiFetch).not.toHaveBeenCalled()
  })

  it('surfaces the canonical rejection message when the probe input exceeds a limit', async () => {
    apiFetch.mockResolvedValue(rejected(400, {error: 'HTTP Probe request body exceeds the maximum of 65536 bytes'}))
    const wrapper = mount(HttpProbe)

    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('HTTP Probe request body exceeds the maximum of 65536 bytes')
    expect(wrapper.text()).toContain('0 Rejected')
  })

  it('falls back to a status-based message when a rejection carries no JSON body', async () => {
    apiFetch.mockResolvedValue(rejected(413))
    const wrapper = mount(HttpProbe)

    await wrapper.get('button.btn-primary').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Probe request rejected (HTTP 413)')
  })
})
