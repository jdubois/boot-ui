import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'

import Liquibase from './Liquibase.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

function liquibaseReport() {
  return {
    total: 1,
    databases: [
      {
        name: 'dataSource',
        applied: 1,
        pending: 0,
        updateEnabled: true,
        changeSets: [
          {
            id: 'create-users',
            author: 'dev',
            changeLog: 'db/changelog.xml',
            execType: 'EXECUTED',
            orderExecuted: 1,
            dateExecuted: '2026-01-01',
            description: 'create users table'
          }
        ]
      }
    ]
  }
}

describe('Liquibase', () => {
  let wrapper

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.unstubAllGlobals()
  })

  it('shows a shared unavailable reason when Liquibase is not on the classpath', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(null, false, 404)))

    wrapper = mount(Liquibase)
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.classes()).toContain('alert-info')
    expect(alert.text()).toContain('Liquibase is not on the classpath')
    expect(alert.find('code').text()).toBe('liquibase-core')
  })

  it('reports when Liquibase is present but no beans are detected', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({total: 0, databases: []})))

    wrapper = mount(Liquibase)
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.classes()).toContain('alert-secondary')
    expect(alert.text()).toContain('no Liquibase beans were detected')
  })

  it('renders change sets when Liquibase beans are present', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(liquibaseReport())))

    wrapper = mount(Liquibase)
    await flushPromises()

    expect(fetch).toHaveBeenCalledWith('api/liquibase/changesets', expect.anything())
    expect(wrapper.text()).not.toContain('not on the classpath')
    expect(wrapper.text()).toContain('change set(s) across')
    expect(wrapper.text()).toContain('create-users')
  })
})
