import {apiFetch} from '../api.js'
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
 */
export function useDismissedRules(reload) {
  const dismissLoading = ref(false)

  async function mutate(ruleId, method) {
    dismissLoading.value = true
    try {
      const res = await apiFetch(`api/dismissed-rules/${encodeURIComponent(ruleId)}`, {method})
      if (res.ok && typeof reload === 'function') {
        await reload()
      }
    } catch {
      // Non-fatal: dismissed rules are a UI convenience; ignore errors
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
