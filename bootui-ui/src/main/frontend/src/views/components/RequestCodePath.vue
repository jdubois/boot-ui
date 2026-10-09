<script setup>
import {computed, inject, ref, watch} from 'vue'

import {getJson} from '../../api.js'
import {formatMillis, shortName} from '../../utils/format.js'
import {formatLoadError} from '../../utils/loadError.js'
import {panelDisabledReason} from '../../utils/panelNavigation.js'

// The request's code path (docs/PLAN-v2.md §5.14, M5-4b): its application methods with the most self time, from the
// BootUI agent's code-paths sensor, while the run keeps its tree. Read only when the drawer opens a request and the Code
// Paths panel can be opened.
const props = defineProps({
  requestId: {type: String, default: null},
  route: {type: String, default: null}
})

const panels = inject('panels', ref(null))
const tree = ref(null)
const error = ref(null)
const loading = ref(false)

const manifestLoaded = computed(() => Array.isArray(panels.value?.panels))
const codePathsPanel = computed(() => {
  const list = panels.value?.panels
  return list?.find((candidate) => candidate.id === 'code-paths') ?? null
})
const runtimeInsightsPanel = computed(
  () => panels.value?.panels?.find((candidate) => candidate.id === 'runtime-insights') ?? null
)
const usable = computed(
  () => !!codePathsPanel.value && codePathsPanel.value.available !== false && codePathsPanel.value.enabled !== false
)
const codePathsUnavailableReason = computed(() => {
  if (!manifestLoaded.value) return null
  if (!codePathsPanel.value) return 'Code Paths is not available in this runtime.'
  if (codePathsPanel.value.enabled === false) return panelDisabledReason(codePathsPanel.value)
  if (codePathsPanel.value.available === false) {
    return codePathsPanel.value.unavailableReason || 'Code Paths is unavailable in this runtime.'
  }
  return null
})
const runtimeInsightsUsable = computed(
  () =>
    !manifestLoaded.value ||
    (!!runtimeInsightsPanel.value &&
      runtimeInsightsPanel.value.available !== false &&
      runtimeInsightsPanel.value.enabled !== false)
)
const runtimeInsightsUnavailableReason = computed(() => {
  if (!manifestLoaded.value) return null
  if (!runtimeInsightsPanel.value) return 'Runtime Insights is not available in this runtime.'
  if (runtimeInsightsPanel.value.enabled === false) return panelDisabledReason(runtimeInsightsPanel.value)
  if (runtimeInsightsPanel.value.available === false) {
    return runtimeInsightsPanel.value.unavailableReason || 'Runtime Insights is unavailable in this runtime.'
  }
  return null
})
const codePathsRoute = computed(() => tree.value?.route || props.route)

let token = 0

watch(
  [() => props.requestId, usable],
  async ([requestId, canRead]) => {
    const current = ++token
    tree.value = null
    error.value = null
    loading.value = false
    if (!requestId || !canRead) return
    loading.value = true
    try {
      const loaded = await getJson(`api/code-paths/requests/${encodeURIComponent(requestId)}`)
      if (current === token) tree.value = loaded
    } catch (e) {
      if (current === token) error.value = formatLoadError(e, 'Unable to load this request’s Code Paths tree')
    } finally {
      if (current === token) loading.value = false
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
  <section class="mb-3 request-code-path" aria-labelledby="performance-deep-dives-heading">
    <h3 id="performance-deep-dives-heading" class="h6">Performance deep dives</h3>
    <ul class="list-unstyled small mb-0">
      <li>
        <router-link
          v-if="runtimeInsightsUsable"
          :to="{path: '/runtime-insights', query: {tab: 'profile'}}"
          class="btn btn-outline-secondary btn-sm"
        >
          Open the JFR profile in Runtime Insights
        </router-link>
        <span v-else>JFR profile unavailable: {{ runtimeInsightsUnavailableReason }}</span>
        <p v-if="runtimeInsightsUsable" class="text-muted mb-1">
          Opens the profiler tab; recording starts only if you choose Profile resources there.
        </p>
      </li>
      <li>
        <p v-if="codePathsUnavailableReason" class="text-muted mb-1">
          Code Paths unavailable: {{ codePathsUnavailableReason }}
        </p>
        <p v-else-if="!requestId" class="text-muted mb-1">No request ID is available for this activity entry.</p>
        <p v-else-if="loading" class="text-muted mb-1" role="status">Loading this request’s code path…</p>
        <p v-else-if="error" class="alert alert-warning small py-2 mb-1" role="alert">{{ error }}</p>
        <p v-else-if="tree && !tree.available" class="text-muted mb-1">
          Code Paths unavailable: {{ tree.unavailableReason || 'the code-paths sensor is not recording.' }}
        </p>
        <p v-else-if="tree && !tree.found" class="text-muted mb-1">
          No retained code-path tree was found for this request. Trees are bounded and kept for this run, so this
          request may not have been captured or may no longer be retained.
        </p>
        <template v-else-if="tree?.found">
          <p v-if="tree.assemblyOnly" class="text-muted mb-1">
            Its handler ran on an event loop, returned a reactive or asynchronous result, or BootUI could not tell where
            its work ran: these times are its assembly, not the work.
          </p>
          <ul v-if="tree.topMethods.length" class="list-unstyled mb-1">
            <li v-for="method in tree.topMethods" :key="method.method">
              <code :title="method.method">{{ methodLabel(method.method) }}</code>
              <span class="text-muted"> · {{ formatMillis(method.selfMillis) }} ms self · {{ method.share }} %</span>
            </li>
          </ul>
          <p v-else class="text-muted mb-1">No application method took measurable time.</p>
        </template>
        <span v-else-if="!codePathsUnavailableReason && requestId" class="text-muted">
          Request code-path data is not available yet.
        </span>
        <template v-if="codePathsRoute && !codePathsUnavailableReason">
          <router-link
            :to="{path: '/code-paths', query: {route: codePathsRoute}}"
            class="btn btn-outline-secondary btn-sm"
          >
            Open {{ codePathsRoute }} in Code Paths
          </router-link>
          <p class="text-muted mb-1">This opens the route-level tree, not an exact replay of this request.</p>
        </template>
        <p v-else-if="tree?.found && !codePathsRoute" class="text-muted mb-1">
          This request has no known route for the route-level Code Paths view.
        </p>
      </li>
    </ul>
  </section>
</template>
