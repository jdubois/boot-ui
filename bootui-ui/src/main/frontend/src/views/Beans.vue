<script setup>
import {defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {apiFetch} from '../api.js'
import {isAbortError} from '../utils/loadError.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import {useServerPagedList} from '../utils/useServerPagedList.js'
import ServerListFooter from './components/ServerListFooter.vue'

// Keep the complete graph workspace in its own route-level async chunk.
const BeansGraphMode = defineAsyncComponent(() => import('./BeansGraphMode.vue'))

// ── List mode ──────────────────────────────────────────────────────────────────
const filter = ref('')
const classification = ref('')

const {
  error,
  hasLoaded,
  items: visibleBeans,
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
  'api/beans',
  'beans',
  () => {
    return {
      q: filter.value.trim(),
      classification: classification.value
    }
  },
  {errorContext: 'Could not load beans'}
)

watch([filter, classification], scheduleReload)

const graphMode = ref(true)
const listActivated = ref(false)
const graphFocusRequest = ref(null)
const bootUiClassificationAvailable = ref(false)
let graphFocusRequestId = 0
let classificationProbe = null

onMounted(loadClassificationAvailability)
onBeforeUnmount(() => classificationProbe?.abort())

async function loadClassificationAvailability() {
  const ac = new AbortController()
  classificationProbe = ac
  try {
    const response = await apiFetch('api/beans?classification=BOOTUI&offset=0&limit=1', {signal: ac.signal})
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const report = await response.json()
    if (classificationProbe === ac) {
      bootUiClassificationAvailable.value = (report.page?.matched ?? report.beans?.length ?? 0) > 0
    }
  } catch (error) {
    if (!isAbortError(error)) {
      console.warn('Could not determine whether BootUI beans are available', error)
      bootUiClassificationAvailable.value = true
    }
  } finally {
    if (classificationProbe === ac) classificationProbe = null
  }
}

function activateGraphMode() {
  graphMode.value = true
}

function deactivateGraphMode() {
  graphMode.value = false
  if (!listActivated.value) {
    listActivated.value = true
    load()
  }
}

function showBeanGraph(bean) {
  graphFocusRequest.value = {
    id: ++graphFocusRequestId,
    name: bean.name,
    classification: bean.classification || ''
  }
  graphMode.value = true
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-diagram-3"
      title="Beans"
      :subtitle="graphMode ? 'Focused dependency neighbourhood' : `${totalCount} beans · ${matchedCount} matched`"
      :error="graphMode ? null : error"
    >
      <template #actions>
        <div class="beans-view-switcher" role="group" aria-label="Beans view">
          <button
            type="button"
            class="beans-view-switcher__option"
            title="Dependency graph"
            aria-label="Dependency graph"
            :aria-pressed="graphMode"
            @click="activateGraphMode"
          >
            <i class="bi bi-diagram-3" aria-hidden="true"></i>
            <span>Graph</span>
          </button>
          <button
            type="button"
            class="beans-view-switcher__option"
            title="List view"
            aria-label="List view"
            :aria-pressed="!graphMode"
            @click="deactivateGraphMode"
          >
            <i class="bi bi-list-ul" aria-hidden="true"></i>
            <span>List</span>
          </button>
        </div>
      </template>
    </PanelHeader>

    <!-- ── LIST MODE ── -->
    <template v-if="!graphMode">
      <div class="row g-2 mb-3">
        <div class="col-md-8">
          <input
            v-model="filter"
            aria-label="Filter beans"
            class="form-control"
            placeholder="Filter by name or type…"
          />
        </div>
        <div class="col-md-4">
          <select v-model="classification" aria-label="Filter beans by classification" class="form-select">
            <option value="">All classifications</option>
            <option value="APPLICATION">Application</option>
            <option value="FRAMEWORK">Framework</option>
            <option v-if="bootUiClassificationAvailable" value="BOOTUI">BootUI</option>
            <option value="PLATFORM">Platform</option>
            <option value="OTHER">Other</option>
          </select>
        </div>
      </div>

      <PanelSkeleton v-if="loading && !hasLoaded" :rows="8" />

      <div v-else class="table-responsive">
        <table class="table table-sm table-hover beans-table">
          <colgroup>
            <col class="beans-table-name" />
            <col class="beans-table-type" />
            <col class="beans-table-scope" />
            <col class="beans-table-classification" />
            <col class="beans-table-dependencies" />
          </colgroup>
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>Scope</th>
              <th>Class.</th>
              <th>Dependencies</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="b in visibleBeans" :key="b.name">
              <td>
                <button
                  class="btn btn-outline-secondary btn-sm bean-graph-link"
                  type="button"
                  :title="`Show dependency graph for ${b.name}`"
                  :aria-label="`Show dependency graph for ${b.name}`"
                  @click="showBeanGraph(b)"
                >
                  <code class="text-truncate d-block">{{ b.name }}</code>
                </button>
              </td>
              <td>
                <code :title="b.type" class="small text-truncate d-block">{{ b.type }}</code>
              </td>
              <td>{{ b.scope }}</td>
              <td>
                <span class="badge bg-light text-dark">{{ b.classification }}</span>
              </td>
              <td>
                <code :title="b.dependencies.join(', ')" class="small text-muted text-truncate d-block">{{
                  b.dependencies.join(', ')
                }}</code>
              </td>
            </tr>
            <tr v-if="!loading && matchedCount === 0">
              <td class="text-center text-muted py-4" colspan="5">No beans match your filters.</td>
            </tr>
          </tbody>
        </table>
      </div>
      <ServerListFooter
        v-if="!loading"
        :loading="loadingMore"
        :matched="matchedCount"
        :page-size="pageSize"
        :shown="shownCount"
        :total="totalCount"
        item-label="beans"
        @load-more="loadMore"
      />
    </template>

    <KeepAlive>
      <BeansGraphMode
        v-if="graphMode"
        :focus-request="graphFocusRequest"
        :boot-ui-classification-available="bootUiClassificationAvailable"
      />
    </KeepAlive>
  </div>
</template>

<style scoped>
.beans-view-switcher {
  align-items: center;
  background: var(--bootui-surface-alt);
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-md);
  box-shadow: inset 0 1px 2px rgba(15, 23, 42, 0.05);
  display: inline-flex;
  gap: 0.2rem;
  padding: 0.22rem;
}

.beans-view-switcher__option {
  align-items: center;
  background: transparent;
  border: 0;
  border-radius: calc(var(--bootui-radius-md) - 0.2rem);
  color: var(--bootui-text-muted);
  display: inline-flex;
  font-size: 0.82rem;
  font-weight: 700;
  gap: 0.4rem;
  justify-content: center;
  min-height: 2.25rem;
  min-width: 5.25rem;
  padding: 0.4rem 0.75rem;
  transition:
    background-color 150ms ease,
    color 150ms ease,
    box-shadow 150ms ease;
}

.beans-view-switcher__option:hover:not([aria-pressed='true']) {
  background: var(--bootui-nav-hover-bg);
  color: var(--bootui-nav-hover-color);
}

.beans-view-switcher__option[aria-pressed='true'] {
  background: var(--bootui-nav-active-bg);
  box-shadow: 0 0.35rem 0.8rem rgba(25, 135, 84, 0.2);
  color: var(--bootui-nav-active-color);
}

.beans-view-switcher__option:focus-visible {
  outline: 2px solid var(--bootui-blue);
  outline-offset: 2px;
}

.beans-table {
  table-layout: fixed;
}

.bean-graph-link {
  display: block;
  max-width: 100%;
  text-align: left;
}

.beans-table-name {
  width: 22%;
}

.beans-table-type {
  width: 34%;
}

.beans-table-scope {
  width: 10%;
}

.beans-table-classification {
  width: 14%;
}

.beans-table-dependencies {
  width: 20%;
}
@media (prefers-reduced-motion: reduce) {
  .beans-view-switcher__option {
    transition: none;
  }
}
</style>
