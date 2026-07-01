import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import {ref} from 'vue'

import SecurityLogs from './SecurityLogs.vue'

function event(overrides = {}) {
  return {
    timestamp: 1_700_000_000_000,
    type: 'AUTHENTICATION_SUCCESS',
    principal: 'alice',
    data: [],
    ...overrides
  }
}

function report(overrides = {}) {
  return {
    events: [event()],
    typeSummaries: [{type: 'AUTHENTICATION_SUCCESS', count: 1}],
    page: {returned: 1, matched: 1, total: 1, offset: 0, limit: 100, hasMore: false},
    maxLogs: 500,
    auditEventsPresent: true,
    ...overrides
  }
}

async function mountWith(securityLogs, {platform} = {}) {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve(new Response(JSON.stringify(securityLogs), {status: 200})))
  )

  const global = platform ? {provide: {panels: ref({platform})}} : {}
  const wrapper = mount(SecurityLogs, {global})
  await flushPromises()
  return wrapper
}

describe('SecurityLogs', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('describes Spring Boot audit events and renders rows by default', async () => {
    const wrapper = await mountWith(report())

    expect(wrapper.text()).toContain('Read-only Spring Boot audit events from the application')
    expect(wrapper.text()).toContain('AuditEventRepository')
    expect(wrapper.text()).toContain('up to')
    expect(wrapper.text()).toContain('alice')
    expect(wrapper.text()).not.toContain('Read-only Quarkus security events')
  })

  it('describes Quarkus CDI security events when the platform is quarkus', async () => {
    const wrapper = await mountWith(report(), {platform: 'quarkus'})

    expect(wrapper.text()).toContain(
      'Read-only Quarkus security events captured from CDI authentication/authorization events.'
    )
    expect(wrapper.text()).toContain('On Quarkus this panel captures CDI security events')
    expect(wrapper.text()).not.toContain('Read-only Spring Boot audit events from the application')
  })

  it('shows an empty state when no events match', async () => {
    const wrapper = await mountWith(report({events: [], typeSummaries: []}), {platform: 'quarkus'})

    expect(wrapper.text()).toContain('No audit events match the current filters.')
  })
})
