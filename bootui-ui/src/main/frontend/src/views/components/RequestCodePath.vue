<script setup>
import {computed, inject, ref, watch} from 'vue'

import {getJson} from '../../api.js'
import {formatMillis, shortName} from '../../utils/format.js'

// The request's code path (docs/PLAN-v2.md §5.14, M5-4b): its application methods with the most self time, from the
// BootUI agent's code-paths sensor, while the run keeps its tree. Read only when the drawer opens a request and the Code
// Paths panel can be opened; it renders nothing otherwise.
const props = defineProps({
  requestId: {type: String, default: null}
})

const panels = inject('panels', ref(null))
const tree = ref(null)

const usable = computed(() => {
  const list = panels.value?.panels
  if (!list) return false
  const panel = list.find((candidate) => candidate.id === 'code-paths')
  return !!panel && panel.available !== false && panel.enabled !== false
})

let token = 0

watch(
  [() => props.requestId, usable],
  async ([requestId, canRead]) => {
    const current = ++token
    tree.value = null
    if (!requestId || !canRead) return
    try {
      const loaded = await getJson(`api/code-paths/requests/${encodeURIComponent(requestId)}`)
      if (current === token) tree.value = loaded
    } catch {
      // The section is a shortcut: a failed read leaves the drawer as it was.
    }
  },
  {immediate: true}
)

function methodLabel(key) {
  if (!key) return '—'
  const hash = key.indexOf('#')
  if (hash < 0) return key
  const paren = key.indexOf('(', hash)
  return `${shortName(key.slice(0, hash))}.${key.slice(hash + 1, paren < 0 ? key.length : paren)}`
}
</script>

<template>
  <section
    v-if="tree?.available && tree.found"
    class="mb-3 request-code-path"
    aria-labelledby="request-code-path-heading"
  >
    <h3 id="request-code-path-heading" class="h6">Code path</h3>
    <p v-if="tree.assemblyOnly" class="small text-muted mb-1">
      Its handler ran on an event loop, returned a reactive or asynchronous result, or BootUI could not tell where its
      work ran: these times are its assembly, not the work.
    </p>
    <ul v-if="tree.topMethods.length" class="list-unstyled small mb-1">
      <li v-for="method in tree.topMethods" :key="method.method">
        <code :title="method.method">{{ methodLabel(method.method) }}</code>
        <span class="text-muted"> · {{ formatMillis(method.selfMillis) }} ms self · {{ method.share }} %</span>
      </li>
    </ul>
    <p v-else class="small text-muted mb-1">No application method took measurable time.</p>
    <router-link v-if="tree.route" :to="{path: '/code-paths', query: {route: tree.route}}" class="small">
      Open {{ tree.route }} in Code Paths
    </router-link>
  </section>
</template>
