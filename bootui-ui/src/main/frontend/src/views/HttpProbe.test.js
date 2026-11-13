import {flushPromises, mount} from '@vue/test-utils'
import {beforeEach, describe, expect, it, vi} from 'vitest'
import {reactive, ref} from 'vue'

import HttpProbe from './HttpProbe.vue'

const {apiFetch, getJson, confirm, routeState} = vi.hoisted(() => ({
  apiFetch: vi.fn(),
  getJson: vi.fn(),
  confirm: vi.fn(),
  routeState: {query: {}}
}))

vi.mock('../api.js', async (importOriginal) => ({...(await importOriginal()), apiFetch, getJson}))
vi.mock('../utils/useConfirm.js', () => ({useConfirm: () => ({confirm})}))
vi.mock('vue-router', () => ({useRoute: () => routeState}))

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
    getJson.mockReset()
    confirm.mockReset()
    confirm.mockResolvedValue(true)
    routeState.query = reactive({})
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

  it('loads declared routes on picker focus, composes basePath, prefills headers/body, and highlights path placeholders without auto-sending', async () => {
    getJson.mockResolvedValueOnce({
      total: 2,
      basePath: '/host',
      mappings: [
        {
          method: 'POST',
          pattern: '/api/orders/{id:[0-9]+}',
          handler: 'com.example.OrderController#update',
          produces: 'application/json',
          consumes: 'application/json'
        },
        {
          method: 'ANY',
          pattern: '/api/health',
          handler: 'com.example.HealthController#check',
          produces: 'text/plain',
          consumes: null
        }
      ],
      page: {total: 2, matched: 2, offset: 0, limit: 50, returned: 2, hasMore: false}
    })

    const wrapper = mount(HttpProbe, {
      global: {
        provide: {
          panels: ref({
            platform: 'spring-boot',
            panels: [{id: 'mappings', enabled: true, available: true}]
          })
        }
      }
    })

    // No network call on render.
    expect(getJson).not.toHaveBeenCalled()
    expect(apiFetch).not.toHaveBeenCalled()

    const picker = wrapper.get('#http-probe-route-picker')
    await picker.trigger('focus')
    await flushPromises()

    expect(getJson).toHaveBeenCalledWith('api/mappings/flat?limit=50')
    const options = wrapper.findAll('.http-probe-route-option')
    expect(options).toHaveLength(2)
    expect(options[0].text()).toContain('/host/api/orders/{id:[0-9]+}')
    expect(options[0].text()).toContain('OrderController#update')

    await options[0].trigger('click')
    await flushPromises()

    expect(wrapper.get('#http-probe-method').element.value).toBe('POST')
    expect(wrapper.get('#http-probe-path').element.value).toBe('/host/api/orders/{id:[0-9]+}')
    expect(wrapper.get('#http-probe-accept').element.value).toBe('application/json')
    expect(wrapper.get('#http-probe-content-type').element.value).toBe('application/json')
    expect(wrapper.get('#http-probe-body').element.value).toBe('{}')
    expect(wrapper.get('.http-probe-placeholder-hint').text()).toContain('{id:[0-9]+}')
    expect(apiFetch).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('prefills passively from route query parameters without auto-sending', async () => {
    routeState.query = reactive({
      method: 'POST',
      path: '/api/orders?page=VALUE',
      accept: 'application/json',
      contentType: 'application/json',
      source: 'http-exchanges',
      omittedQuery: '1'
    })

    const wrapper = mount(HttpProbe)
    await flushPromises()

    expect(wrapper.get('#http-probe-method').element.value).toBe('POST')
    expect(wrapper.get('#http-probe-path').element.value).toBe('/api/orders?page=VALUE')
    expect(wrapper.get('#http-probe-accept').element.value).toBe('application/json')
    expect(wrapper.get('#http-probe-content-type').element.value).toBe('application/json')
    const hintText = wrapper.get('.http-probe-placeholder-hint').text()
    expect(hintText).toContain('VALUE')
    expect(hintText).toContain('1 masked or malformed query parameter was omitted')
    expect(hintText).toContain('BootUI never records request bodies in HTTP Exchanges')
    expect(apiFetch).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('selects a route with ArrowDown and Enter without triggering a probe on Enter keyup', async () => {
    getJson.mockResolvedValueOnce({
      total: 1,
      basePath: '/host',
      mappings: [
        {
          method: 'GET',
          pattern: '/api/orders/{id}',
          handler: 'com.example.OrderController#get',
          produces: 'application/json',
          consumes: null
        }
      ],
      page: {total: 1, matched: 1, offset: 0, limit: 50, returned: 1, hasMore: false}
    })

    const wrapper = mount(HttpProbe, {
      attachTo: document.body,
      global: {
        provide: {
          panels: ref({
            platform: 'spring-boot',
            panels: [{id: 'mappings', enabled: true, available: true}]
          })
        }
      }
    })

    const picker = wrapper.get('#http-probe-route-picker')
    await picker.trigger('focus')
    await flushPromises()
    expect(picker.attributes('aria-expanded')).toBe('true')

    await picker.trigger('keydown', {key: 'ArrowDown'})
    expect(picker.attributes('aria-activedescendant')).toBe('http-probe-route-option-0')

    await picker.trigger('keydown', {key: 'Enter'})
    await flushPromises()
    // Simulate the physical Enter key release landing on the newly focused Path input.
    const pathInput = wrapper.get('#http-probe-path')
    await pathInput.trigger('keyup', {key: 'Enter'})
    await flushPromises()

    expect(pathInput.element.value).toBe('/host/api/orders/{id}')
    expect(picker.element.value).toBe('/host/api/orders/{id}')
    expect(apiFetch).not.toHaveBeenCalled()
    expect(confirm).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('recovers cleanly when blurred while a route lookup is still in flight', async () => {
    const firstLookup = deferred()
    getJson.mockReturnValueOnce(firstLookup.promise).mockResolvedValueOnce({
      total: 1,
      basePath: '',
      mappings: [{method: 'GET', pattern: '/api/hello', handler: 'Sample#hello', produces: null, consumes: null}],
      page: {total: 1, matched: 1, offset: 0, limit: 50, returned: 1, hasMore: false}
    })

    const wrapper = mount(HttpProbe, {
      global: {
        provide: {
          panels: ref({
            platform: 'spring-boot',
            panels: [{id: 'mappings', enabled: true, available: true}]
          })
        }
      }
    })

    const picker = wrapper.get('#http-probe-route-picker')
    await picker.trigger('focus')
    expect(getJson).toHaveBeenCalledTimes(1)

    // Blur while first request is still pending.
    await picker.trigger('blur')
    firstLookup.resolve({
      total: 0,
      basePath: '',
      mappings: [],
      page: {total: 0, matched: 0, offset: 0, limit: 50, returned: 0, hasMore: false}
    })
    await flushPromises()

    // Focusing again must issue a fresh request rather than getting stuck on "Loading declared routes…".
    await picker.trigger('focus')
    await flushPromises()

    expect(getJson).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('.http-probe-route-option')).toHaveLength(1)
    wrapper.unmount()
  })
})
