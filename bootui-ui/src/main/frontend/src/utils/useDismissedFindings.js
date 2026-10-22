import {ApiError, apiFetch, getJson} from '../api.js'
import {ref} from 'vue'

const ENDPOINT = 'api/dismissed-findings'

/**
 * Composable for dismissing and restoring single advisor findings, next to {@link useDismissedRules} which handles
 * whole rules.
 *
 * Dismissed findings are persisted on the server under the `dismissedFindings` node of `.bootui/boot-ui.yml`, and
 * the server applies them to each advisor report, so a mutation simply sends the finding's rule id and canonical
 * identity and then reloads the panel report (passed in as `reload`). The note is user text: it is only ever sent to
 * and read back from this endpoint, never shown in a report.
 * Callers must surface rejected mutations through their panel error display.
 */
export function useDismissedFindings(reload) {
  const findingLoading = ref(false)

  async function send(method, body) {
    const res = await apiFetch(ENDPOINT, {
      method,
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(body)
    })
    if (!res.ok) throw await refusal(res)
    return res
  }

  async function mutate(method, body) {
    if (findingLoading.value) return false
    findingLoading.value = true
    try {
      await send(method, body)
      if (typeof reload === 'function') {
        await reload()
      }
      return true
    } finally {
      findingLoading.value = false
    }
  }

  return {
    findingLoading,
    /** Dismisses one finding, or replaces the note of one already dismissed. */
    dismissFinding: (ruleId, identity, note) => {
      const body = {ruleId, identity}
      if (note) body.note = note
      return mutate('POST', body)
    },
    restoreFinding: (ruleId, identity) => mutate('DELETE', {ruleId, identity}),
    /** The dismissed findings with their notes. A read, so it never touches the report. */
    list: () => getJson(ENDPOINT)
  }
}

async function refusal(res) {
  let body = null
  try {
    body = res.headers?.get('content-type')?.toLowerCase().includes('json') ? await res.json() : null
  } catch {
    body = null
  }
  const error = new ApiError(res.status, body)
  if (typeof body?.error === 'string' && body.error) error.message = body.error
  return error
}
