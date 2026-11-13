<script setup>
import {computed, inject, onMounted, ref, watch} from 'vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import FlashBanner from './components/FlashBanner.vue'
import UnavailableState from './components/UnavailableState.vue'
import {useServerPagedList} from '../utils/useServerPagedList.js'
import {panelProps, useDataState, usePanelState} from '../utils/panelState.js'
import {buildMappingProbeLink, composeProbePath, isHttpProbeWritable} from '../utils/httpProbePrefill.js'
import ServerListFooter from './components/ServerListFooter.vue'

const props = defineProps(panelProps)
const filter = ref('')
const panels = inject('panels', ref(null))
const {manifestAvailable, manifestUnavailableReason} = usePanelState(props)

const {
  data,
  error,
  hasLoaded,
  items: visibleMappings,
  load,
  loadMore,
  loading,
  loadingMore,
  matchedCount,
  pageSize,
  scheduleReload,
  shownCount,
  totalCount
} = useServerPagedList(
  'api/mappings/flat',
  'mappings',
  () => {
    return {q: filter.value.trim()}
  },
  {errorContext: 'Could not load mappings'}
)

const filterActive = computed(() => filter.value.trim().length > 0)
const panelState = useDataState({
  loading,
  loaded: hasLoaded,
  error,
  hasData: computed(() => data.value !== null),
  available: manifestAvailable,
  total: totalCount,
  matched: matchedCount,
  filterActive
})
const staleMessage = {
  text: 'Could not refresh mappings. Showing the last successful results.',
  type: 'warning'
}

const methodClass = (m) =>
  ({
    GET: 'bg-success',
    POST: 'bg-primary',
    PUT: 'bg-warning text-dark',
    DELETE: 'bg-danger',
    PATCH: 'bg-info text-dark',
    ANY: 'bg-secondary'
  })[m] || 'bg-secondary'

const httpProbeWritable = computed(() => isHttpProbeWritable(panels.value))
const basePath = computed(() => data.value?.basePath ?? '')

function mappingProbeLink(mapping) {
  return buildMappingProbeLink(mapping, basePath.value)
}

function composedMappingPath(mapping) {
  return composeProbePath(basePath.value, mapping?.pattern)
}

function clearFilter() {
  filter.value = ''
}

function refreshMappings() {
  if (manifestAvailable.value) return load()
}

function scheduleMappingsReload() {
  if (manifestAvailable.value) scheduleReload()
}

onMounted(refreshMappings)
watch(filter, scheduleMappingsReload)
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-signpost-2"
      title="HTTP mappings"
      :error="manifestAvailable && panelState.retryableError.value ? error : null"
      :loading="loading"
      :refreshable="manifestAvailable"
      @refresh="refreshMappings"
    />
    <UnavailableState
      v-if="!manifestAvailable"
      icon="bi-signpost-2"
      :message="manifestUnavailableReason"
      variant="info"
    />

    <template v-else>
      <input v-model="filter" aria-label="Filter HTTP mappings" class="form-control mb-3" placeholder="Filter…" />

      <PanelSkeleton v-if="panelState.initialLoading.value" label="Loading HTTP mappings…" />

      <template v-else-if="panelState.hasSuccessfulData.value">
        <FlashBanner v-if="panelState.stale.value" :dismissible="false" :message="staleMessage" with-icon />
        <p v-else-if="loading" class="small text-muted" role="status">
          Updating mappings… Showing the last successful results.
        </p>

        <UnavailableState
          v-if="panelState.empty.value"
          icon="bi-signpost-2"
          message="No HTTP mappings were reported by the application."
        />
        <UnavailableState v-else-if="panelState.filteredEmpty.value" icon="bi-search">
          <span
            >No mappings match <strong>{{ filter.trim() }}</strong
            >.</span
          >
          <button class="btn btn-sm btn-outline-secondary ms-2" type="button" @click="clearFilter">Clear filter</button>
        </UnavailableState>

        <div v-else class="table-responsive">
          <table class="table table-sm table-hover align-middle">
            <thead>
              <tr>
                <th style="width: 90px">Method</th>
                <th>Pattern</th>
                <th>Handler</th>
                <th v-if="httpProbeWritable" class="text-end" style="width: 170px">
                  <span class="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(r, i) in visibleMappings" :key="`${r.method}:${r.pattern}:${r.handler}:${i}`">
                <td>
                  <span :class="methodClass(r.method)" class="badge">{{ r.method }}</span>
                </td>
                <td>
                  <code>{{ r.pattern }}</code>
                </td>
                <td>
                  <code class="small">{{ r.handler }}</code>
                </td>
                <td v-if="httpProbeWritable" class="text-end">
                  <router-link
                    v-if="mappingProbeLink(r)"
                    :aria-label="`Open ${r.method || 'GET'} ${composedMappingPath(r)} in HTTP Probe`"
                    :to="mappingProbeLink(r)"
                    class="btn btn-outline-secondary btn-sm rounded-pill text-nowrap mappings-probe-link"
                  >
                    <i aria-hidden="true" class="bi bi-send me-1"></i>Open in HTTP Probe
                  </router-link>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <ServerListFooter
          v-if="!loading && !panelState.empty.value && !panelState.filteredEmpty.value"
          :loading="loadingMore"
          :matched="matchedCount"
          :page-size="pageSize"
          :shown="shownCount"
          :total="totalCount"
          item-label="mappings"
          @load-more="loadMore"
        />
      </template>
    </template>
  </div>
</template>
