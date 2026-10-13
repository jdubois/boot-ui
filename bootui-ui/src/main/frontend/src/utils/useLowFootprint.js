import {readonly, ref} from 'vue'
import {getJson} from '../api.js'

// Shared, module-level state for BootUI's low-footprint mode so the toggle and every panel banner
// agree. The mode lives in the backend; `refresh()` is a plain read and never changes anything.

const status = ref(null)
let loading = null

export function useLowFootprint() {
  async function refresh() {
    if (loading) return loading
    loading = getJson('api/live-memory/footprint')
      .then((next) => {
        status.value = next
      })
      .catch(() => {
        // Keep the last known state; the toggle surfaces its own failures.
      })
      .finally(() => {
        loading = null
      })
    return loading
  }

  async function setEnabled(enabled) {
    const next = await getJson('api/live-memory/footprint', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({enabled})
    })
    status.value = next
    return next
  }

  return {status: readonly(status), refresh, setEnabled}
}

export function resetLowFootprintForTests() {
  status.value = null
  loading = null
}
