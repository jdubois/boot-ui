import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Constellation from './Constellation.vue'

function report(overrides = {}) {
  return {
    enabled: true,
    peers: [],
    ...overrides
  }
}

function peer(overrides = {}) {
  return {
    url: 'http://localhost:8081',
    reachable: true,
    applicationName: 'orders-service',
    platform: 'spring-boot',
    frameworkVersion: '4.1.0',
    javaVersion: '17',
    activeProfiles: ['dev'],
    errorMessage: null,
    ...overrides
  }
}

async function mountWith(data) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve(new Response(JSON.stringify(data), {status: 200})))
  )

  const wrapper = mount(Constellation)
  await flushPromises()
  return wrapper
}

describe('Constellation', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows setup guidance when the panel is not enabled', async () => {
    const wrapper = await mountWith(report({enabled: false}))

    expect(wrapper.text()).toContain('Constellation is not configured.')
    expect(wrapper.text()).toContain('bootui.constellation.peers')
  })

  it('shows an empty state when enabled but no peers are configured', async () => {
    const wrapper = await mountWith(report({enabled: true, peers: []}))

    expect(wrapper.text()).toContain('No peers configured.')
  })

  it('renders a reachable peer with its identity and framework details', async () => {
    const wrapper = await mountWith(report({peers: [peer()]}))

    expect(wrapper.text()).toContain('orders-service')
    expect(wrapper.text()).toContain('Reachable')
    expect(wrapper.text()).toContain('Spring Boot')
    expect(wrapper.text()).toContain('4.1.0')
    expect(wrapper.text()).toContain('dev')
    expect(wrapper.text()).toContain('1 / 1 peers reachable')
  })

  it('renders an unreachable peer with its error message', async () => {
    const wrapper = await mountWith(
      report({
        peers: [peer({reachable: false, applicationName: null, errorMessage: 'Connection refused'})]
      })
    )

    expect(wrapper.text()).toContain('Unreachable')
    expect(wrapper.text()).toContain('Connection refused')
    expect(wrapper.text()).toContain('0 / 1 peers reachable')
  })
})
