<script setup>
import {computed, ref} from 'vue'
import {useRoute} from 'vue-router'
import {getJson} from '../api.js'
import {formatMillis, formatNumber, shortName} from '../utils/format.js'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import UnavailableState from './components/UnavailableState.vue'

const props = defineProps(panelProps)
const {manifestAvailable, manifestUnavailableReason} = usePanelState(props)

/** The deepest level and most nodes one route read asks for: the whole tree the API serves at once. */
const TREE_DEPTH = 33
const TREE_LIMIT = 500

const route = useRoute()
const summary = ref(null)
const error = ref(null)
const lastFetched = ref(null)
const selectedRoute = ref(typeof route?.query?.route === 'string' ? route.query.route : null)
const tree = ref(null)
const treeError = ref(null)
const selectedMethod = ref(null)

const available = computed(() => summary.value?.available === true)
const routes = computed(() => summary.value?.routes ?? [])
const status = computed(() => summary.value?.status ?? null)

async function fetchSummary() {
  error.value = null
  try {
    summary.value = await getJson('api/code-paths')
    lastFetched.value = Date.now()
    if (summary.value?.available) {
      if (!selectedRoute.value || !routes.value.some((row) => row.route === selectedRoute.value)) {
        selectedRoute.value = routes.value.find((row) => row.warmRequests > 0)?.route ?? routes.value[0]?.route ?? null
      }
      if (selectedRoute.value) {
        await loadTree(selectedRoute.value)
      } else {
        tree.value = null
      }
    }
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load Code Paths')
  }
}

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchSummary, {
  enabled: manifestAvailable,
  defaultEnabled: false
})

async function loadTree(name) {
  treeError.value = null
  try {
    tree.value = await getJson(
      `api/code-paths/route?route=${encodeURIComponent(name)}&depth=${TREE_DEPTH}&limit=${TREE_LIMIT}`
    )
  } catch (e) {
    tree.value = null
    treeError.value = formatLoadError(e, `Unable to load the tree of ${name}`)
  }
}

async function selectRoute(name) {
  if (selectedRoute.value === name && tree.value) return
  selectedRoute.value = name
  selectedMethod.value = null
  tree.value = null
  await loadTree(name)
}

/** A method key's short label, {@code SimpleClass.method}. */
function methodLabel(key) {
  if (!key) return '—'
  const hash = key.indexOf('#')
  if (hash < 0) return key
  const paren = key.indexOf('(', hash)
  return `${shortName(key.slice(0, hash))}.${key.slice(hash + 1, paren < 0 ? key.length : paren)}`
}

function nodeLabel(node) {
  switch (node.kind) {
    case 'REQUEST':
      return 'Request'
    case 'ASYNC':
      return 'Executor work'
    case 'OTHER':
      return 'Other methods'
    default:
      return methodLabel(node.method)
  }
}

function phaseLabel(phase) {
  return {FILTERS: 'filters', HANDLER: 'handler', RESPONSE: 'response'}[phase] ?? null
}

const shareLabel = computed(() => (tree.value?.shareOf === 'request' ? 'Share of the request' : 'Share of the handler'))

/** The largest share among the tree's nodes below the request, which the bars are drawn against. */
const topShare = computed(() => {
  let top = 0
  for (const node of tree.value?.nodes ?? []) {
    if (node.kind !== 'REQUEST' && node.share != null) top = Math.max(top, node.share)
  }
  return top
})

const methodsByKey = computed(() => new Map((tree.value?.methods ?? []).map((method) => [method.method, method])))
const selectedMethodDetail = computed(() =>
  selectedMethod.value ? (methodsByKey.value.get(selectedMethod.value) ?? null) : null
)

function selectMethod(key) {
  selectedMethod.value = selectedMethod.value === key ? null : key
}

function callerLabel(caller) {
  if (caller === 'REQUEST') return 'The request itself'
  if (caller === 'ASYNC') return 'Executor work'
  return methodLabel(caller)
}

function moreNodes(report) {
  const page = report?.page
  return page && page.hasMore ? page.matched - page.offset - page.returned : 0
}
</script>

<template>
  <div class="code-paths-panel">
    <PanelHeader
      icon="bi-hourglass-split"
      title="Code Paths"
      subtitle="Which application methods each route spends its time in, from the BootUI agent's code-paths sensor."
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <UnavailableState v-if="!manifestAvailable" icon="bi-hourglass-split">
      {{ manifestUnavailableReason }}
      <div class="mt-2">
        <router-link to="/java-agent" class="code-paths-agent-link">Open the Java Agent panel</router-link>
        to attach the BootUI agent.
      </div>
    </UnavailableState>

    <PanelSkeleton v-else-if="initialLoading" />

    <template v-else-if="summary && !available">
      <UnavailableState icon="bi-hourglass-split" class="code-paths-unavailable">
        {{ summary.unavailableReason }}
        <div class="mt-2">
          <router-link to="/java-agent" class="code-paths-agent-link">Open the Java Agent panel</router-link>
          to attach the BootUI agent.
        </div>
      </UnavailableState>
    </template>

    <template v-else-if="summary">
      <section class="card mb-4" aria-labelledby="code-paths-headline">
        <div class="card-body p-4">
          <h3 id="code-paths-headline" class="h4 fw-bold mb-1">
            {{ formatNumber(routes.length) }} {{ routes.length === 1 ? 'route' : 'routes' }} with a call tree
          </h3>
          <p v-if="status" class="text-muted small mb-0 code-paths-status">
            Run {{ formatNumber(status.generation) }} · {{ formatNumber(status.settledTrees) }} request trees settled ·
            {{ formatNumber(status.routeNodes) }} of {{ formatNumber(status.routeNodeBudget) }} route-tree nodes
            <template v-if="status.unroutedTrees"> · {{ formatNumber(status.unroutedTrees) }} without a route</template>
          </p>
          <details v-if="summary.limitations?.length" class="mt-3 small code-paths-limitations">
            <summary>What these trees cannot see ({{ summary.limitations.length }})</summary>
            <ul class="mb-0 mt-2">
              <li v-for="limitation in summary.limitations" :key="limitation">{{ limitation }}</li>
            </ul>
          </details>
        </div>
      </section>

      <UnavailableState v-if="!routes.length" icon="bi-hourglass" class="code-paths-empty">
        No route has a call tree yet. Send requests to the application's routes; each tree settles about two seconds
        after its request.
      </UnavailableState>

      <template v-else>
        <section class="mb-4" aria-labelledby="code-paths-routes-heading">
          <h3 id="code-paths-routes-heading" class="h6 text-muted mb-2">Routes by warm median</h3>
          <div class="table-responsive">
            <table class="table table-sm align-middle code-paths-table code-paths-routes">
              <caption class="visually-hidden">
                Routes with a call tree, slowest warm median first
              </caption>
              <thead>
                <tr>
                  <th scope="col">Route</th>
                  <th scope="col" class="text-end">Warm requests</th>
                  <th scope="col" class="text-end">Median (ms)</th>
                  <th scope="col" class="text-end">p95 (ms)</th>
                  <th scope="col" class="text-end">First request (ms)</th>
                  <th scope="col">Top method by self time</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in routes" :key="row.route" :class="{'table-active': row.route === selectedRoute}">
                  <td>
                    <button
                      :aria-pressed="row.route === selectedRoute"
                      class="btn btn-link p-0 text-start code-paths-route"
                      type="button"
                      @click="selectRoute(row.route)"
                    >
                      <code class="bootui-break-anywhere">{{ row.route }}</code>
                    </button>
                    <span v-if="row.assemblyOnly" class="badge text-bg-secondary ms-2 code-paths-assembly">
                      Assembly only
                    </span>
                  </td>
                  <td class="text-end">{{ formatNumber(row.warmRequests) }}</td>
                  <td class="text-end">{{ row.warmRequests ? formatMillis(row.p50Millis) : '—' }}</td>
                  <td class="text-end">{{ row.warmRequests ? formatMillis(row.p95Millis) : '—' }}</td>
                  <td class="text-end">{{ formatMillis(row.firstRequestMillis) }}</td>
                  <td>
                    <template v-if="row.topMethods?.length">
                      <code>{{ methodLabel(row.topMethods[0].method) }}</code>
                      <span class="small text-muted ms-1">
                        {{ formatMillis(row.topMethods[0].selfMillis) }} ms · {{ row.topMethods[0].share }} %
                      </span>
                    </template>
                    <span v-else class="text-muted">—</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>

        <section v-if="selectedRoute" class="mb-4" aria-labelledby="code-paths-tree-heading">
          <h3 id="code-paths-tree-heading" class="h5 fw-semibold mb-2">
            <code class="bootui-break-anywhere">{{ selectedRoute }}</code>
          </h3>
          <div v-if="treeError" class="alert alert-danger" role="alert">{{ treeError }}</div>
          <p v-else-if="!tree" class="small text-muted">Loading…</p>
          <template v-else>
            <div v-if="tree.assemblyOnly" class="alert alert-secondary small code-paths-assembly-note" role="note">
              <strong>Assembly only.</strong> This route's handler ran on an event loop, returned a reactive or
              asynchronous result, or BootUI could not tell where its work ran, so its tree times the handler's
              assembly, not the work that ran later or elsewhere, and Runtime Insights does not split its handler by
              method.
            </div>
            <p class="small mb-2 code-paths-tree-summary">
              <strong>{{ formatNumber(tree.warmRequests) }}</strong> warm requests, mean
              <strong>{{ formatMillis(tree.ownMillis) }} ms</strong> of their own time<template
                v-if="tree.handlerMillis != null"
              >
                , <strong>{{ formatMillis(tree.handlerMillis) }} ms</strong> of it in the handler's application methods
              </template>
              <template v-if="tree.firstRequestMillis != null">
                · first recorded request {{ formatMillis(tree.firstRequestMillis) }} ms, kept apart</template
              >. Times are per warm request; medians are approximate (≈), from log2 buckets.
            </p>
            <p v-if="!tree.nodes.length" class="text-muted small">
              Only the route's first recorded request, kept apart, was recorded: send it again to build its warm tree.
            </p>
            <div v-else class="table-responsive">
              <table class="table table-sm align-middle code-paths-table code-paths-tree">
                <caption class="visually-hidden">
                  The call tree of
                  {{
                    selectedRoute
                  }}, each method under its caller
                </caption>
                <thead>
                  <tr>
                    <th scope="col">Method</th>
                    <th scope="col" class="text-end">Calls per request</th>
                    <th scope="col" class="text-end">Total (ms)</th>
                    <th scope="col" class="text-end">Self (ms)</th>
                    <th scope="col" class="text-end">
                      <span title="Approximate: interpolated within log2 buckets of per-request time"
                        >Median (≈ ms)</span
                      >
                    </th>
                    <th scope="col" class="code-paths-share-column">{{ shareLabel }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="node in tree.nodes"
                    :key="node.id"
                    :class="{
                      'code-paths-async': node.async,
                      'table-active': node.method && node.method === selectedMethod
                    }"
                  >
                    <td>
                      <div class="code-paths-indent" :style="{paddingInlineStart: `${node.depth * 1.1}rem`}">
                        <button
                          v-if="node.kind === 'METHOD'"
                          :aria-pressed="node.method === selectedMethod"
                          :title="node.method"
                          class="btn btn-link p-0 text-start code-paths-method"
                          type="button"
                          @click="selectMethod(node.method)"
                        >
                          <code>{{ nodeLabel(node) }}</code>
                        </button>
                        <span v-else :class="{'fw-semibold': node.kind === 'REQUEST'}">{{ nodeLabel(node) }}</span>
                        <span v-if="node.kind === 'ASYNC'" class="badge text-bg-info ms-2">Async, shown apart</span>
                        <span v-else-if="node.async" class="badge text-bg-info ms-2">Async</span>
                        <span v-if="phaseLabel(node.phase) && node.kind === 'METHOD'" class="small text-muted ms-2">
                          {{ phaseLabel(node.phase) }}
                        </span>
                      </div>
                    </td>
                    <td class="text-end">{{ node.kind === 'REQUEST' ? '—' : node.callsPerRequest }}</td>
                    <td class="text-end">{{ formatMillis(node.totalMillis) }}</td>
                    <td class="text-end">{{ formatMillis(node.selfMillis) }}</td>
                    <td class="text-end code-paths-median">
                      <template v-if="node.p50Millis != null">≈ {{ formatMillis(node.p50Millis) }}</template>
                      <span v-else class="text-muted">—</span>
                    </td>
                    <td>
                      <span v-if="node.share != null && node.kind !== 'REQUEST'" class="code-paths-share">
                        <span class="code-paths-share-track" aria-hidden="true">
                          <span
                            class="code-paths-share-bar"
                            :class="{'code-paths-share-bar-top': node.share === topShare}"
                            :style="{width: `${Math.min(100, node.share)}%`}"
                          ></span>
                        </span>
                        <span class="code-paths-share-value">{{ node.share }} %</span>
                      </span>
                      <span v-else class="text-muted">—</span>
                    </td>
                  </tr>
                </tbody>
              </table>
              <p v-if="moreNodes(tree)" class="small text-muted">
                {{ formatNumber(moreNodes(tree)) }} more nodes not shown.
              </p>
            </div>

            <section
              v-if="selectedMethodDetail"
              class="card mb-3 code-paths-method-detail"
              aria-labelledby="code-paths-method-heading"
            >
              <div class="card-body">
                <h4 id="code-paths-method-heading" class="h6 mb-1">
                  <code>{{ methodLabel(selectedMethodDetail.method) }}</code>
                </h4>
                <p class="small text-muted bootui-break-anywhere mb-2">{{ selectedMethodDetail.method }}</p>
                <div class="row g-3 small">
                  <div class="col-md-6">
                    <h5 class="h6 small text-muted">Called by, in this route</h5>
                    <ul class="mb-0 code-paths-callers">
                      <li v-for="caller in selectedMethodDetail.callers" :key="caller">
                        <code>{{ callerLabel(caller) }}</code>
                      </li>
                    </ul>
                  </div>
                  <div class="col-md-6">
                    <h5 class="h6 small text-muted">Routes that reach it</h5>
                    <ul class="mb-0 code-paths-reach">
                      <li v-for="name in selectedMethodDetail.routes" :key="name">
                        <button
                          v-if="name !== selectedRoute"
                          class="btn btn-link p-0 text-start"
                          type="button"
                          @click="selectRoute(name)"
                        >
                          <code>{{ name }}</code>
                        </button>
                        <code v-else>{{ name }}</code>
                      </li>
                    </ul>
                  </div>
                </div>
              </div>
            </section>

            <p v-if="tree.exemplarRequestIds?.length" class="small mb-0 code-paths-exemplars">
              Slowest and failed requests kept:
              <template v-for="(id, index) in tree.exemplarRequestIds" :key="id">
                <router-link :to="{path: '/activity', query: {request: id}}"
                  ><code>{{ id }}</code></router-link
                ><template v-if="Number(index) < tree.exemplarRequestIds.length - 1">, </template>
              </template>
            </p>
          </template>
        </section>
      </template>

      <section class="mb-4" aria-labelledby="code-paths-excluded-heading">
        <h3 id="code-paths-excluded-heading" class="h6 text-muted mb-2">Excluded methods</h3>
        <p v-if="!summary.excludedMethods?.length" class="small text-muted mb-0">No method was excluded in this run.</p>
        <ul v-else class="small mb-0 code-paths-excluded">
          <li v-for="method in summary.excludedMethods" :key="method.method">
            <code :title="method.method">{{ methodLabel(method.method) }}</code>
            <span class="text-muted"> — {{ method.reason }}</span>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>
.code-paths-table td,
.code-paths-table th {
  font-variant-numeric: tabular-nums;
}

.code-paths-route,
.code-paths-method {
  color: inherit;
  text-decoration: none;
}

.code-paths-route:hover code,
.code-paths-route:focus-visible code,
.code-paths-method:hover code,
.code-paths-method:focus-visible code {
  text-decoration: underline;
}

.code-paths-indent {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
}

.code-paths-async td {
  background: var(--bs-tertiary-bg);
}

.code-paths-share-column {
  width: 30%;
}

.code-paths-share {
  display: flex;
  align-items: center;
  gap: 0.6rem;
}

.code-paths-share-track {
  position: relative;
  flex: 1 1 auto;
  height: 0.6rem;
  background: var(--bs-secondary-bg);
  border-radius: var(--bootui-radius-xs);
  overflow: hidden;
}

.code-paths-share-bar {
  position: absolute;
  inset: 0 auto 0 0;
  min-width: 2px;
  background: var(--bootui-text-muted);
  border-radius: var(--bootui-radius-xs);
}

.code-paths-share-bar-top {
  background: var(--bootui-green-dark);
}

.code-paths-share-value {
  flex: 0 0 3.5rem;
  text-align: end;
}

.code-paths-limitations summary {
  cursor: pointer;
}
</style>
