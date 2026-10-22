import {beforeEach, describe, expect, it, vi} from 'vitest'
import {mount} from '@vue/test-utils'

const apiFetch = vi.fn()
const getJson = vi.fn()
vi.mock('../api.js', async (importOriginal) => ({
  ...(await importOriginal()),
  apiFetch: (...args) => apiFetch(...args),
  getJson: (...args) => getJson(...args)
}))

import {useDismissedFindings} from './useDismissedFindings'

function harness(reload) {
  let api
  mount({
    setup() {
      api = useDismissedFindings(reload)
      return () => null
    }
  })
  return api
}

const json = (body, status = 200) =>
  new Response(JSON.stringify(body), {status, headers: {'content-type': 'application/json'}})

describe('useDismissedFindings', () => {
  beforeEach(() => {
    apiFetch.mockReset()
    getJson.mockReset()
  })

  it('POSTs the rule, identity and note as JSON and reloads the report', async () => {
    apiFetch.mockResolvedValue(json({dismissed: [], ignored: 0}))
    const reload = vi.fn().mockResolvedValue()
    const api = harness(reload)

    await expect(api.dismissFinding('HIB-ID-006', 'v1:unit=app|entity=A|member=id', 'Intentional')).resolves.toBe(true)

    expect(apiFetch).toHaveBeenCalledWith('api/dismissed-findings', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({ruleId: 'HIB-ID-006', identity: 'v1:unit=app|entity=A|member=id', note: 'Intentional'})
    })
    expect(reload).toHaveBeenCalledOnce()
    expect(api.findingLoading.value).toBe(false)
  })

  it('omits an empty note and DELETEs with the rule and identity only to restore', async () => {
    apiFetch.mockResolvedValue(json({dismissed: [], ignored: 0}))
    const api = harness(vi.fn().mockResolvedValue())

    await api.dismissFinding('HIB-ID-006', 'v1:a=b', '')
    await api.restoreFinding('HIB-ID-006', 'v1:a=b')

    expect(JSON.parse(apiFetch.mock.calls[0][1].body)).toEqual({ruleId: 'HIB-ID-006', identity: 'v1:a=b'})
    expect(apiFetch.mock.calls[1][1].method).toBe('DELETE')
    expect(JSON.parse(apiFetch.mock.calls[1][1].body)).toEqual({ruleId: 'HIB-ID-006', identity: 'v1:a=b'})
  })

  it('rejects with the server message and never reloads when the mutation is refused', async () => {
    apiFetch.mockResolvedValue(json({error: 'BootUI is read-only'}, 403))
    const reload = vi.fn()
    const api = harness(reload)

    await expect(api.dismissFinding('HIB-ID-006', 'v1:a=b')).rejects.toMatchObject({
      status: 403,
      message: 'BootUI is read-only'
    })
    expect(reload).not.toHaveBeenCalled()
    expect(api.findingLoading.value).toBe(false)
  })

  it('ignores a second mutation while one is running', async () => {
    let finish
    apiFetch.mockReturnValue(new Promise((resolve) => (finish = resolve)))
    const api = harness(vi.fn().mockResolvedValue())

    const first = api.dismissFinding('HIB-ID-006', 'v1:a=b')
    await expect(api.dismissFinding('HIB-ID-006', 'v1:c=d')).resolves.toBe(false)
    finish(json({dismissed: [], ignored: 0}))
    await first

    expect(apiFetch).toHaveBeenCalledOnce()
  })

  it('lists the dismissed findings with a read that mutates nothing', async () => {
    getJson.mockResolvedValue({dismissed: [{ruleId: 'A-1', identity: 'v1:a=b', note: 'n'}], ignored: 0})
    const api = harness()

    await expect(api.list()).resolves.toEqual({dismissed: [{ruleId: 'A-1', identity: 'v1:a=b', note: 'n'}], ignored: 0})
    expect(getJson).toHaveBeenCalledWith('api/dismissed-findings')
    expect(apiFetch).not.toHaveBeenCalled()
  })
})
