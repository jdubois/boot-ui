import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {ref} from 'vue'

const {confirm} = vi.hoisted(() => ({confirm: vi.fn()}))
vi.mock('../utils/useConfirm.js', () => ({useConfirm: () => ({confirm})}))

import Config from './Config.vue'

const EMPTY_CONFIG = {
  properties: [],
  page: {matched: 0, total: 0},
  sources: [],
  activeProfiles: [],
  propertySuggestions: []
}

function deferred() {
  let resolve
  const promise = new Promise((resolver) => {
    resolve = resolver
  })
  return {promise, resolve}
}

function configWith(properties) {
  return {
    ...EMPTY_CONFIG,
    properties,
    overrideCount: properties.filter((property) => property.override).length,
    page: {matched: properties.length, total: properties.length}
  }
}

async function mountConfig(provide, fetchMock) {
  vi.stubGlobal(
    'fetch',
    fetchMock || vi.fn(() => Promise.resolve(new Response(JSON.stringify(EMPTY_CONFIG), {status: 200})))
  )

  const wrapper = mount(Config, provide ? {global: {provide}} : undefined)
  await flushPromises()
  return wrapper
}

describe('Config', () => {
  beforeEach(() => {
    confirm.mockReset()
    confirm.mockResolvedValue(true)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('describes Spring Boot configuration keys and placeholder by default', async () => {
    const wrapper = await mountConfig()

    expect(wrapper.text()).toContain('known Spring Boot')
    expect(wrapper.text()).not.toContain('known Quarkus')

    await wrapper.find('button.btn-success').trigger('click')
    await flushPromises()
    expect(wrapper.find('input[list="bootPropertySuggestions"]').attributes('placeholder')).toBe(
      'spring.application.name'
    )
  })

  it('describes Quarkus configuration keys and placeholder when the platform is quarkus', async () => {
    const wrapper = await mountConfig({panels: ref({platform: 'quarkus'})})

    expect(wrapper.text()).toContain('known Quarkus')
    expect(wrapper.text()).not.toContain('known Spring Boot')

    await wrapper.find('button.btn-success').trigger('click')
    await flushPromises()
    expect(wrapper.find('input[list="bootPropertySuggestions"]').attributes('placeholder')).toBe(
      'quarkus.application.name'
    )
  })

  it('keeps a new override intact when file-write confirmation is cancelled', async () => {
    confirm.mockResolvedValueOnce(false)
    const fetchMock = vi.fn(() => Promise.resolve(new Response(JSON.stringify(EMPTY_CONFIG), {status: 200})))
    const wrapper = await mountConfig(undefined, fetchMock)
    await wrapper.get('button.btn-success').trigger('click')
    const inputs = wrapper.findAll('tr.table-warning input')
    await inputs[0].setValue('sample.timeout')
    await inputs[1].setValue('30s')

    await wrapper.get('tr.table-warning button.btn-success').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith({
      title: 'Create configuration override?',
      message:
        'Write this property override to .bootui/application-bootui.properties. The running app may require a restart before the new value takes full effect.',
      resource: 'sample.timeout',
      confirmLabel: 'Create override'
    })
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
    expect(inputs[0].element.value).toBe('sample.timeout')
    expect(inputs[1].element.value).toBe('30s')
  })

  it('waits for one confirmation when Enter updates an override', async () => {
    const confirmation = deferred()
    confirm.mockReturnValueOnce(confirmation.promise)
    const property = {
      name: 'sample.greeting',
      value: 'hello',
      defaultValue: null,
      source: 'applicationConfig',
      override: false,
      masked: false,
      description: null
    }
    const config = configWith([property])
    const fetchMock = vi.fn((url, init) => {
      if (init?.method === 'POST') {
        return Promise.resolve(new Response(JSON.stringify({message: 'Restart may be required.'}), {status: 200}))
      }
      return Promise.resolve(new Response(JSON.stringify(config), {status: 200}))
    })
    const wrapper = await mountConfig(undefined, fetchMock)
    await wrapper.get('button.btn-primary').trigger('click')
    const input = wrapper.get('input.font-monospace')
    await input.setValue('bonjour')

    await input.trigger('keyup', {key: 'Enter'})
    await input.trigger('keyup', {key: 'Enter'})
    await flushPromises()

    expect(confirm).toHaveBeenCalledOnce()
    expect(confirm).toHaveBeenCalledWith(
      expect.objectContaining({
        title: 'Update configuration override?',
        resource: 'sample.greeting',
        confirmLabel: 'Update override'
      })
    )
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
    expect(input.element.value).toBe('bonjour')

    confirmation.resolve(true)
    await flushPromises()

    const posts = fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')
    expect(posts).toHaveLength(1)
    expect(JSON.parse(posts[0][1].body)).toEqual({name: 'sample.greeting', value: 'bonjour'})
  })

  it('does not delete an override when confirmation is cancelled', async () => {
    confirm.mockResolvedValueOnce(false)
    const config = configWith([
      {
        name: 'sample.greeting',
        value: 'hello',
        defaultValue: null,
        source: 'bootuiOverrides',
        override: true,
        masked: false,
        description: null
      }
    ])
    const fetchMock = vi.fn(() => Promise.resolve(new Response(JSON.stringify(config), {status: 200})))
    const wrapper = await mountConfig(undefined, fetchMock)

    await wrapper.get('button[title="Remove override"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalledWith(
      expect.objectContaining({title: 'Remove override?', resource: 'sample.greeting'})
    )
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'DELETE')).toBe(false)
  })

  it('suggests the canonical property name when the override name is typed as an environment variable', async () => {
    const config = {
      ...EMPTY_CONFIG,
      propertySuggestions: [
        {name: 'bootui.mcp.enabled', type: 'java.lang.Boolean', description: 'Whether the MCP server is enabled.'},
        {name: 'bootui.mcp.max-results', type: 'java.lang.Integer', description: null},
        {name: 'spring.application.name', type: 'java.lang.String', description: null}
      ]
    }
    const fetchMock = vi.fn(() => Promise.resolve(new Response(JSON.stringify(config), {status: 200})))
    const wrapper = await mountConfig(undefined, fetchMock)
    await wrapper.get('button.btn-success').trigger('click')
    const nameInput = wrapper.get('input[list="bootPropertySuggestions"]')

    await nameInput.setValue('BOOTUI_MCP')

    expect(wrapper.findAll('#bootPropertySuggestions option').map((option) => option.attributes('value'))).toEqual([
      'bootui.mcp.enabled',
      'bootui.mcp.max-results'
    ])
  })

  it('keeps narrowing override suggestions by the literal name that was typed', async () => {
    const config = {
      ...EMPTY_CONFIG,
      propertySuggestions: [
        {name: 'bootui.mcp.max-results', type: 'java.lang.Integer', description: null},
        {name: 'spring.application.name', type: 'java.lang.String', description: null}
      ]
    }
    const fetchMock = vi.fn(() => Promise.resolve(new Response(JSON.stringify(config), {status: 200})))
    const wrapper = await mountConfig(undefined, fetchMock)
    await wrapper.get('button.btn-success').trigger('click')
    const nameInput = wrapper.get('input[list="bootPropertySuggestions"]')

    await nameInput.setValue('bootui.mcp.max-results')

    expect(wrapper.findAll('#bootPropertySuggestions option').map((option) => option.attributes('value'))).toEqual([
      'bootui.mcp.max-results'
    ])
  })
})
