import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'

import DatabaseConnectionPools from './DatabaseConnectionPools.vue'
import PanelHeader from './components/PanelHeader.vue'

function jsonResponse(body, ok = true, status = 200) {
  return {ok, status, json: () => Promise.resolve(body)}
}

describe('Database connection pools panel', () => {
  let wrapper

  beforeEach(() => {
    vi.useFakeTimers()
    Object.defineProperty(document, 'visibilityState', {configurable: true, value: 'visible'})
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('shows an unavailable tip without calling the pool endpoint', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(DatabaseConnectionPools, {
      props: {
        panel: {
          id: 'database-connection-pools',
          enabled: true,
          available: false,
          unavailableReason: 'No supported JDBC connection pool is available'
        }
      }
    })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(10_000)

    expect(fetchMock).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('Database connection pool metrics are unavailable')
    expect(wrapper.text()).toContain('No supported JDBC connection pool is available')
    expect(wrapper.findComponent(PanelHeader).props('refreshable')).toBe(false)
  })

  it('loads the generic database connection pools endpoint when available', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({total: 0, pools: []}))
    vi.stubGlobal('fetch', fetchMock)

    wrapper = mount(DatabaseConnectionPools, {
      props: {
        panel: {
          id: 'database-connection-pools',
          enabled: true,
          available: true,
          unavailableReason: null
        }
      }
    })
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('api/database-connection-pools/pools', expect.anything())
    expect(wrapper.text()).toContain('No database connection pool beans were detected')
    expect(wrapper.findComponent(PanelHeader).props('refreshable')).toBe(true)
  })
})
