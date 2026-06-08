import {apiFetch} from '../api.js'
import {ref} from 'vue'

/**
 * Composable for managing dismissed advisor rule IDs.
 *
 * Dismissed rules are persisted on the server in `.bootui/dismissed-rules.yaml`
 * and are loaded once per panel mount. Dismissing or restoring a rule makes an
 * API call and updates the local reactive set immediately.
 */
export function useDismissedRules() {
  const dismissedIds = ref(new Set())
  const dismissLoading = ref(false)

  async function loadDismissed() {
    try {
      const res = await apiFetch('api/dismissed-rules')
      if (!res.ok) return
      const data = await res.json()
      dismissedIds.value = new Set(data.dismissed ?? [])
    } catch {
      // Non-fatal: dismissed rules are a UI convenience; ignore load errors
    }
  }

  function isDismissed(ruleId) {
    return dismissedIds.value.has(ruleId)
  }

  async function dismiss(ruleId) {
    dismissLoading.value = true
    try {
      const res = await apiFetch(`api/dismissed-rules/${encodeURIComponent(ruleId)}`, {method: 'POST'})
      if (res.ok) {
        const data = await res.json()
        dismissedIds.value = new Set(data.dismissed ?? [])
      }
    } catch {
      // Non-fatal
    } finally {
      dismissLoading.value = false
    }
  }

  async function restore(ruleId) {
    dismissLoading.value = true
    try {
      const res = await apiFetch(`api/dismissed-rules/${encodeURIComponent(ruleId)}`, {method: 'DELETE'})
      if (res.ok) {
        const data = await res.json()
        dismissedIds.value = new Set(data.dismissed ?? [])
      }
    } catch {
      // Non-fatal
    } finally {
      dismissLoading.value = false
    }
  }

  return {dismissedIds, dismissLoading, loadDismissed, isDismissed, dismiss, restore}
}
