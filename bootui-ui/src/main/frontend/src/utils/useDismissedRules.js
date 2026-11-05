import {apiError, apiFetch} from '../api.js'
import {ref} from 'vue'

/**
 * Composable for dismissing/restoring advisor rules.
 *
 * Dismissed rule IDs are persisted on the server under the `dismissedRules` node
 * of `.bootui/boot-ui.yml`.
 * The server applies them when building each advisor report, so dismissing or
 * restoring a rule simply POSTs/DELETEs and then reloads the panel report (passed
 * in as `reload`) to pick up the server-applied `dismissed` flags and recomputed
 * severity counts.
 * Callers must surface rejected mutations through their panel error display.
 */
export function useDismissedRules(reload) {
  const dismissLoading = ref(false)

  async function mutate(ruleId, method) {
    if (dismissLoading.value) return
    dismissLoading.value = true
    try {
      const res = await apiFetch(`api/dismissed-rules/${encodeURIComponent(ruleId)}`, {method})
      if (!res.ok) throw await apiError(res)
      if (typeof reload === 'function') {
        await reload()
      }
    } finally {
      dismissLoading.value = false
    }
  }

  return {
    dismissLoading,
    dismiss: (ruleId) => mutate(ruleId, 'POST'),
    restore: (ruleId) => mutate(ruleId, 'DELETE')
  }
}
