import {describe, it, expect, vi, beforeEach} from 'vitest'
import {mount} from '@vue/test-utils'

const apiFetch = vi.fn()
vi.mock('../api.js', () => ({apiFetch: (...args) => apiFetch(...args)}))

import {useDismissedRules} from './useDismissedRules'

function harness(reload) {
  let api
  mount({
    setup() {
      api = useDismissedRules(reload)
      return () => null
    }
  })
  return api
}

describe('useDismissedRules', () => {
  beforeEach(() => {
    apiFetch.mockReset()
  })

  it('POSTs to dismiss the rule and reloads the panel on success', async () => {
    apiFetch.mockResolvedValue({ok: true})
    const reload = vi.fn().mockResolvedValue()
    const api = harness(reload)

    await api.dismiss('SPRING-CONFIG-001')

    expect(apiFetch).toHaveBeenCalledWith('api/dismissed-rules/SPRING-CONFIG-001', {method: 'POST'})
    expect(reload).toHaveBeenCalledTimes(1)
    expect(api.dismissLoading.value).toBe(false)
  })

  it('DELETEs to restore the rule and reloads the panel on success', async () => {
    apiFetch.mockResolvedValue({ok: true})
    const reload = vi.fn().mockResolvedValue()
    const api = harness(reload)

    await api.restore('SEC-CONFIG-001')

    expect(apiFetch).toHaveBeenCalledWith('api/dismissed-rules/SEC-CONFIG-001', {method: 'DELETE'})
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('encodes rule IDs that contain URL-sensitive characters', async () => {
    apiFetch.mockResolvedValue({ok: true})
    const api = harness(vi.fn().mockResolvedValue())

    await api.dismiss('A/B C')

    expect(apiFetch).toHaveBeenCalledWith('api/dismissed-rules/A%2FB%20C', {method: 'POST'})
  })

  it('does not reload when the request fails', async () => {
    apiFetch.mockResolvedValue({ok: false, status: 500})
    const reload = vi.fn()
    const api = harness(reload)

    await api.dismiss('SPRING-CONFIG-001')

    expect(reload).not.toHaveBeenCalled()
    expect(api.dismissLoading.value).toBe(false)
  })

  it('swallows network errors and clears the loading flag', async () => {
    apiFetch.mockRejectedValue(new Error('offline'))
    const reload = vi.fn()
    const api = harness(reload)

    await api.dismiss('SPRING-CONFIG-001')

    expect(reload).not.toHaveBeenCalled()
    expect(api.dismissLoading.value).toBe(false)
  })
})
