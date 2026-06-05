import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Flyway from './Flyway.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function flywayReport() {
  return {
    total: 1,
    databases: [
      {
        name: 'dataSource',
        currentVersion: '1',
        applied: 1,
        pending: 0,
        migrateEnabled: true,
        cleanEnabled: false,
        migrations: [
          {
            version: '1',
            description: 'init schema',
            script: 'V1__init.sql',
            type: 'SQL',
            state: 'Success',
            installedOn: '2026-01-01',
            executionTime: 12
          }
        ]
      }
    ]
  }
}

describe('Flyway', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('shows a shared unavailable reason when Flyway is not on the classpath', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(null, false, 404)))

    wrapper = mount(Flyway)
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.classes()).toContain('alert-info')
    expect(alert.text()).toContain('Flyway is not on the classpath')
    expect(alert.find('code').text()).toBe('flyway-core')
  })

  it('reports when Flyway is present but no beans are detected', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({total: 0, databases: []})))

    wrapper = mount(Flyway)
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.classes()).toContain('alert-secondary')
    expect(alert.text()).toContain('no Flyway beans were detected')
  })

  it('renders migrations when Flyway beans are present', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(flywayReport())))

    wrapper = mount(Flyway)
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/flyway/migrations', expect.anything())
    expect(wrapper.text()).not.toContain('not on the classpath')
    expect(wrapper.text()).toContain('migration(s) across')
    expect(wrapper.text()).toContain('V1__init.sql')
  })
})
