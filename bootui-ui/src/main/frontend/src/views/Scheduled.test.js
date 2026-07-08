import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

const routeQuery = {}

vi.mock('vue-router', () => ({useRoute: () => ({query: routeQuery})}))

import Scheduled from './Scheduled.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function report(overrides = {}) {
  return {
    schedulingPresent: true,
    total: 2,
    tasks: [
      {runnable: 'com.example.jobs.NightlyJob.run', triggerType: 'CRON', expression: '0 0 * * * *'},
      {runnable: 'com.example.jobs.CleanupJob.run', triggerType: 'FIXED_RATE', expression: '60000'}
    ],
    ...overrides
  }
}

describe('Scheduled', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
    routeQuery.q = undefined
  })

  it('renders the registered scheduled tasks', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonResponse(report())))
    )

    wrapper = mount(Scheduled)
    await flushPromises()

    expect(wrapper.text()).toContain('com.example.jobs.NightlyJob.run')
    expect(wrapper.text()).toContain('com.example.jobs.CleanupJob.run')
  })

  it('prefills the filter box from the ?q= deep-link query parameter', async () => {
    routeQuery.q = 'NightlyJob'
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(jsonResponse(report())))
    )

    wrapper = mount(Scheduled)
    await flushPromises()

    expect(wrapper.get('input.form-control').element.value).toBe('NightlyJob')
    expect(wrapper.text()).toContain('com.example.jobs.NightlyJob.run')
    expect(wrapper.text()).not.toContain('com.example.jobs.CleanupJob.run')
  })
})
