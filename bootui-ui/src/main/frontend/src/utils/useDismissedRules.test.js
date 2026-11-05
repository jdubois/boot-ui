import {describe, it, expect, vi, beforeEach} from 'vitest'
import {mount} from '@vue/test-utils'

const apiFetch = vi.fn()
vi.mock('../api.js', async (importOriginal) => ({
  ...(await importOriginal()),
  apiFetch: (...args) => apiFetch(...args)
}))

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
  it('retains the canonical policy reason and status without reloading', async () => {
    apiFetch.mockResolvedValue(
      new Response(
        JSON.stringify({
          error: 'BootUI panel access denied',
          panel: 'dismissed-rules',
          reason: 'BootUI is read-only.'
        }),
        {status: 403, headers: {'Content-Type': 'application/json'}}
      )
    )
    const reload = vi.fn()
    await expect(harness(reload).dismiss('ARCH-1')).rejects.toThrow('BootUI is read-only. (HTTP 403)')
    expect(reload).not.toHaveBeenCalled()
  })

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

    await expect(api.dismiss('SPRING-CONFIG-001')).rejects.toMatchObject({status: 500})

    expect(reload).not.toHaveBeenCalled()
    expect(api.dismissLoading.value).toBe(false)
  })

  it('propagates network errors and clears the loading flag', async () => {
    apiFetch.mockRejectedValue(new Error('offline'))
    const reload = vi.fn()
    const api = harness(reload)

    await expect(api.dismiss('SPRING-CONFIG-001')).rejects.toThrow('offline')

    expect(reload).not.toHaveBeenCalled()
    expect(api.dismissLoading.value).toBe(false)
  })

  it('propagates refresh failures and clears the loading flag', async () => {
    apiFetch.mockResolvedValue({ok: true})
    const api = harness(vi.fn().mockRejectedValue(new Error('Refresh failed')))

    await expect(api.restore('PT-A05-040')).rejects.toThrow('Refresh failed')
    expect(api.dismissLoading.value).toBe(false)
  })

  it('ignores concurrent mutations until persistence and refresh settle', async () => {
    let finishReload
    apiFetch.mockResolvedValue({ok: true})
    const api = harness(() => new Promise((resolve) => (finishReload = resolve)))

    const first = api.dismiss('PT-A05-040')
    await Promise.resolve()
    expect(api.dismissLoading.value).toBe(true)
    await api.restore('PT-A05-040')
    expect(apiFetch).toHaveBeenCalledTimes(1)
    finishReload()
    await first
    expect(api.dismissLoading.value).toBe(false)
  })
})
