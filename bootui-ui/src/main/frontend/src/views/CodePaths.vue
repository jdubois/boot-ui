<script setup>
import {computed, nextTick, ref, watch} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {getJson} from '../api.js'
import {methodLabel, nodeKeys, splitRoute} from '../utils/codePaths.js'
import {formatMillis, formatNumber} from '../utils/format.js'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import CodePathsTree from './components/CodePathsTree.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import PanelTabs from './components/PanelTabs.vue'
import UnavailableState from './components/UnavailableState.vue'
import MethodProbes from './components/MethodProbes.vue'

const props = defineProps(panelProps)
const {manifestAvailable, manifestUnavailableReason, readOnly, readOnlyReason} = usePanelState(props)

/** The deepest level and most nodes one route read asks for: the whole tree the API serves at once. */
const TREE_DEPTH = 33
const TREE_LIMIT = 500

const route = useRoute()
const router = useRouter()
const summary = ref(null)
const error = ref(null)
const lastFetched = ref(null)
// The route whose tree is open under its row, as Runtime Insights opens an observation: none until one is asked for.
const selectedRoute = ref(queryString('route'))
const tree = ref(null)
const treeError = ref(null)
// The selected tree node (see nodeKeys) and its method, whose detail opens under its row.
const selectedKey = ref(null)
const selectedMethod = ref(null)
// A method to select once its route's tree arrives: the ?method= deep link, or a method another panel asked to probe.
let pendingMethod = queryString('method') ?? queryString('probe')
// A method another panel asked to probe, as Code Inventory's Probe links do.
const requestedProbe = ref(queryString('probe'))
const probeActionTarget = ref(null)
const probesSection = ref(null)
const routeList = ref(null)
let firstLoad = true

const SORTS = [
  {id: 'median', label: 'Slowest warm median', caption: 'slowest warm median first'},
  {id: 'p95', label: 'Slowest p95', caption: 'slowest p95 first'},
  {id: 'requests', label: 'Most warm requests', caption: 'most warm requests first'},
  {id: 'first', label: 'Slowest first request', caption: 'slowest first request first'},
  {id: 'route', label: 'Route, A to Z', caption: 'by path'}
]
const search = ref('')
const sortBy = ref('median')

const TABS = [
  {id: 'routes', label: 'Routes', icon: 'bi-signpost-split'},
  {id: 'beans', label: 'Beans at runtime', icon: 'bi-diagram-3'}
]
const activeTab = ref(route?.query?.tab === 'beans' ? 'beans' : 'routes')
const beans = ref(null)
const beansError = ref(null)
const beansLoading = ref(false)
const notCalledOnly = ref(false)

const available = computed(() => summary.value?.available === true)
// Disabling HTTP Exchanges hides Code Paths' route-keyed trees; attaching the agent would not help.
const httpExchangesDisabled = computed(
  () => summary.value?.unavailableReason?.startsWith('The HTTP Exchanges panel is disabled') === true
)
const routes = computed(() => summary.value?.routes ?? [])
const status = computed(() => summary.value?.status ?? null)

function queryString(name) {
  const value = route?.query?.[name]
  return typeof value === 'string' && value ? value : null
}

// The open route and method live in the URL, so a reload or a shared link reopens them. The URL is replaced in place,
// not through router.replace: panels are keyed on the route's full path, so a router navigation would remount this one
// and lose its search, order, open branches, and focus. Vue Router's own history state is kept in step.
function syncQuery() {
  if (!router?.resolve || typeof window === 'undefined') return
  const query = {...(route?.query ?? {})}
  delete query.route
  delete query.method
  if (selectedRoute.value) query.route = selectedRoute.value
  if (selectedRoute.value && selectedMethod.value) query.method = selectedMethod.value
  const target = router.resolve({path: route?.path ?? '/code-paths', query})
  const state = window.history.state
  window.history.replaceState(state ? {...state, current: target.fullPath} : state, '', target.href)
}

async function fetchSummary() {
  error.value = null
  try {
    summary.value = await getJson('api/code-paths')
    lastFetched.value = Date.now()
    if (summary.value?.available) {
      if (firstLoad && !selectedRoute.value && requestedProbe.value) {
        selectedRoute.value =
          routes.value.find((row) => row.topMethods?.some((method) => method.method === requestedProbe.value))?.route ??
          null
      }
      if (selectedRoute.value && !routes.value.some((row) => row.route === selectedRoute.value)) {
        // The route left the recording, as after a Clear recording: its row and tree are gone.
        closeRoute()
      }
      if (selectedRoute.value) {
        await loadTree(selectedRoute.value)
      }
      if (activeTab.value === 'beans') {
        await loadBeans()
      }
      if (firstLoad) {
        firstLoad = false
        revealArrival()
      }
    }
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load Code Paths')
  }
}

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchSummary, {
  enabled: manifestAvailable
})

// Only the newest tree read may land: an older route's answer arriving last must not fill the newer route's drawer.
let treeRequest = 0

async function loadTree(name) {
  const request = ++treeRequest
  treeError.value = null
  try {
    const result = await getJson(
      `api/code-paths/route?route=${encodeURIComponent(name)}&depth=${TREE_DEPTH}&limit=${TREE_LIMIT}`
    )
    if (request !== treeRequest) return
    tree.value = result
    applySelection(result)
  } catch (e) {
    if (request !== treeRequest) return
    tree.value = null
    treeError.value = formatLoadError(e, `Unable to load the tree of ${name}`)
  }
}

/** Keeps the selected node across a refresh while the tree still has it, and selects a method asked for by link. */
function applySelection(result) {
  const keys = nodeKeys(result?.nodes)
  const methodNodes = (result?.nodes ?? []).filter((node) => node.kind === 'METHOD')
  if (pendingMethod) {
    const wanted = methodNodes.find((node) => node.method === pendingMethod)
    pendingMethod = null
    if (wanted) {
      selectedKey.value = keys.get(wanted.id)
      selectedMethod.value = wanted.method
      return
    }
  }
  if (selectedKey.value && !methodNodes.some((node) => keys.get(node.id) === selectedKey.value)) {
    selectedKey.value = null
    selectedMethod.value = null
  }
}

function closeRoute() {
  treeRequest++
  selectedRoute.value = null
  tree.value = null
  treeError.value = null
  selectedKey.value = null
  selectedMethod.value = null
  probeActionTarget.value = null
}

async function openRoute(name) {
  closeRoute()
  selectedRoute.value = name
  syncQuery()
  await loadTree(name)
}

function toggleRoute(name) {
  if (selectedRoute.value === name) {
    closeRoute()
    syncQuery()
    return
  }
  return openRoute(name)
}

/** Follows a route that reaches the selected method: opens it and brings its row to the reader. */
async function followRoute(name) {
  search.value = ''
  const opened = openRoute(name)
  await nextTick()
  const row = document.getElementById(routeRowId(name))
  row?.scrollIntoView?.({block: 'start'})
  /** @type {HTMLElement | null | undefined} */
  const toggle = row?.querySelector('.code-paths-route')
  toggle?.focus({preventScroll: true})
  await opened
}

function selectNode(selection) {
  selectedKey.value = selection?.key ?? null
  selectedMethod.value = selection?.method ?? null
  syncQuery()
}

/** On arrival from a link, shows what it asked for: the open route, else the probe card for a method in no route. */
async function revealArrival() {
  await nextTick()
  if (selectedRoute.value) {
    document.getElementById(routeRowId(selectedRoute.value))?.scrollIntoView?.({block: 'start'})
  } else if (requestedProbe.value) {
    probesSection.value?.scrollIntoView?.({block: 'start'})
  }
}

watch(
  () => route?.query?.route,
  (value) => {
    if (typeof value === 'string' && value && value !== selectedRoute.value && available.value) openRoute(value)
  }
)

/** The method Probe this method offers: the selected tree method, else the one another panel asked for. */
const probeTarget = computed(() => selectedMethod.value ?? requestedProbe.value)

function rankRoutes(rows, sort) {
  const timed = (read) => (a, b) => (b.warmRequests ? 1 : 0) - (a.warmRequests ? 1 : 0) || read(b) - read(a)
  const list = [...rows]
  switch (sort) {
    case 'p95':
      return list.sort(timed((row) => row.p95Millis))
    case 'requests':
      return list.sort((a, b) => b.warmRequests - a.warmRequests)
    case 'first':
      return list.sort((a, b) => (b.firstRequestMillis ?? -1) - (a.firstRequestMillis ?? -1))
    case 'route':
      return list.sort(
        (a, b) => splitRoute(a.route).path.localeCompare(splitRoute(b.route).path) || a.route.localeCompare(b.route)
      )
    default:
      // The API already ranks routes by warm median.
      return list
  }
}

const rankedRoutes = computed(() => rankRoutes(routes.value, sortBy.value))

// While a route is open, a refresh keeps the rows where they were, new routes last, so the open drawer never moves
// under the reader; closing it, or choosing a sort, ranks them again.
const frozenOrder = ref(null)
watch([selectedRoute, sortBy, available], () => {
  frozenOrder.value = selectedRoute.value && available.value ? rankedRoutes.value.map((row) => row.route) : null
})

const orderedRoutes = computed(() => {
  const ranked = rankedRoutes.value
  if (!frozenOrder.value) return ranked
  const position = new Map(frozenOrder.value.map((name, index) => [name, index]))
  const rank = (row, index) => position.get(row.route) ?? frozenOrder.value.length + index
  return ranked
    .map((row, index) => ({row, at: rank(row, index)}))
    .sort((a, b) => a.at - b.at)
    .map((entry) => entry.row)
})

function searchText(row) {
  const methods = (row.topMethods ?? []).map((method) => `${method.method} ${methodLabel(method.method)}`)
  return `${row.route} ${methods.join(' ')}`.toLowerCase()
}

const visibleRoutes = computed(() => {
  const terms = search.value.trim().toLowerCase().split(/\s+/).filter(Boolean)
  if (!terms.length) return orderedRoutes.value
  // The open route stays listed, so a search never hides the drawer being read.
  return orderedRoutes.value.filter(
    (row) => row.route === selectedRoute.value || terms.every((term) => searchText(row).includes(term))
  )
})

const routesHeading = computed(() => {
  const count = search.value.trim()
    ? `${formatNumber(visibleRoutes.value.length)} of ${formatNumber(routes.value.length)} routes`
    : `${formatNumber(routes.value.length)} ${routes.value.length === 1 ? 'route' : 'routes'}`
  return `${count}, ${sortCaption.value}. Open one to see where its time goes.`
})

const sortCaption = computed(() => SORTS.find((option) => option.id === sortBy.value)?.caption ?? '')

/** The slowest warm median, which each route's bar is drawn against. */
const slowestMedian = computed(() =>
  Math.max(0, ...routes.value.filter((row) => row.warmRequests).map((row) => row.p50Millis))
)

function medianWidth(row) {
  if (!row.warmRequests || !slowestMedian.value) return 0
  return Math.max(2, Math.round((row.p50Millis / slowestMedian.value) * 100))
}

function routeRowId(name) {
  return `code-paths-route-${encodeURIComponent(name).replace(/[^A-Za-z0-9_-]/g, '_')}`
}

/** Up and Down move between the routes' toggles, as in a list; Home and End go to its ends. */
function onRouteKeydown(event) {
  const current = /** @type {HTMLElement} */ (event.target)
  if (!current.classList?.contains('code-paths-route')) return
  /** @type {HTMLElement[]} */
  const toggles = [...(routeList.value?.querySelectorAll('.code-paths-route') ?? [])]
  const index = toggles.indexOf(current)
  const next = {
    ArrowDown: toggles[index + 1],
    ArrowUp: toggles[index - 1],
    Home: toggles[0],
    End: toggles[toggles.length - 1]
  }[event.key]
  if (!(event.key in {ArrowDown: 1, ArrowUp: 1, Home: 1, End: 1})) return
  event.preventDefault()
  next?.focus()
}

const hasCalls = computed(() => (tree.value?.nodes ?? []).some((node) => node.calls?.length))

async function loadBeans() {
  beansError.value = null
  beansLoading.value = true
  try {
    beans.value = await getJson('api/code-paths/beans')
  } catch (e) {
    beans.value = null
    beansError.value = formatLoadError(e, 'Unable to load Beans at runtime')
  } finally {
    beansLoading.value = false
  }
}

function selectTab(id) {
  if (activeTab.value === id) return
  activeTab.value = id
  if (id === 'beans' && !beans.value) {
    loadBeans()
  }
}

const beanEdges = computed(() => {
  const edges = beans.value?.edges ?? []
  return notCalledOnly.value ? edges.filter((edge) => edge.declared && !edge.observed && edge.observable) : edges
})
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
        <div v-if="!httpExchangesDisabled" class="mt-2">
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

      <PanelTabs
        class="mb-3"
        :tabs="TABS"
        :selected="activeTab"
        id-prefix="code-paths"
        label="Code Paths views"
        @select="selectTab"
      />

      <section
        v-if="activeTab === 'routes'"
        id="code-paths-panel-routes"
        aria-labelledby="code-paths-tab-routes"
        role="tabpanel"
        tabindex="0"
      >
        <UnavailableState v-if="!routes.length" icon="bi-hourglass" class="code-paths-empty">
          No route has a call tree yet. Send requests to the application's routes; each tree settles about two seconds
          after its request.
        </UnavailableState>

        <section v-else class="mb-4" aria-labelledby="code-paths-routes-heading">
          <div class="code-paths-route-tools">
            <div class="code-paths-search">
              <i class="bi bi-search code-paths-search-icon" aria-hidden="true"></i>
              <input
                v-model="search"
                aria-controls="code-paths-route-list"
                aria-label="Filter routes"
                class="form-control code-paths-route-search"
                placeholder="Filter by path, HTTP method, or Java method"
                type="search"
              />
            </div>
            <label class="visually-hidden" for="code-paths-route-sort">Sort routes</label>
            <select id="code-paths-route-sort" v-model="sortBy" class="form-select code-paths-route-sort">
              <option v-for="option in SORTS" :key="option.id" :value="option.id">{{ option.label }}</option>
            </select>
          </div>
          <h3 id="code-paths-routes-heading" class="small text-muted fw-normal mb-2" aria-live="polite">
            {{ routesHeading }}
          </h3>

          <p v-if="!visibleRoutes.length" class="small text-muted code-paths-route-none">
            No route matches “{{ search.trim() }}”.
            <button class="btn btn-link btn-sm p-0 align-baseline" type="button" @click="search = ''">
              Clear the filter
            </button>
          </p>
          <ul
            v-else
            id="code-paths-route-list"
            ref="routeList"
            class="list-unstyled mb-0 code-paths-routes"
            aria-labelledby="code-paths-routes-heading"
            @keydown="onRouteKeydown"
          >
            <li
              v-for="row in visibleRoutes"
              :id="routeRowId(row.route)"
              :key="row.route"
              class="code-paths-route-row"
              :class="{open: row.route === selectedRoute}"
            >
              <button
                :aria-controls="`${routeRowId(row.route)}-detail`"
                :aria-expanded="row.route === selectedRoute ? 'true' : 'false'"
                class="code-paths-route bootui-keyboard-target"
                type="button"
                @click="toggleRoute(row.route)"
              >
                <span class="code-paths-route-main">
                  <code class="code-paths-route-name"
                    ><span class="code-paths-verb">{{ splitRoute(row.route).verb }}</span>
                    {{ splitRoute(row.route).path }}</code
                  >
                  <span class="code-paths-route-meta">
                    <span v-if="row.topMethods?.length" class="code-paths-route-top" :title="row.topMethods[0].method">
                      <code>{{ methodLabel(row.topMethods[0].method) }}</code>
                      {{ formatMillis(row.topMethods[0].selfMillis) }} ms · {{ row.topMethods[0].share }} %
                    </span>
                    <span v-else class="code-paths-route-top">No method took measurable time</span>
                    <span class="code-paths-route-counts">
                      <template v-if="row.warmRequests">
                        {{ formatNumber(row.warmRequests) }} warm · p95 {{ formatMillis(row.p95Millis) }} ms
                      </template>
                      <template v-else>No warm request yet</template>
                      <template v-if="row.firstRequestMillis != null">
                        · first request {{ formatMillis(row.firstRequestMillis) }} ms</template
                      >
                    </span>
                    <span v-if="row.assemblyOnly" class="badge text-bg-secondary code-paths-assembly">
                      Assembly only
                    </span>
                  </span>
                </span>
                <span class="code-paths-route-time">
                  <span class="code-paths-route-median">
                    <template v-if="row.warmRequests">{{ formatMillis(row.p50Millis) }} ms</template>
                    <template v-else>—</template>
                    <span class="visually-hidden"> warm median</span>
                  </span>
                  <span class="code-paths-route-bar" aria-hidden="true">
                    <span
                      :class="{'code-paths-route-bar-top': row.warmRequests && row.p50Millis === slowestMedian}"
                      :style="{width: `${medianWidth(row)}%`}"
                    ></span>
                  </span>
                </span>
                <i class="bi bi-chevron-down code-paths-route-chevron" aria-hidden="true"></i>
              </button>

              <section
                v-if="row.route === selectedRoute"
                :id="`${routeRowId(row.route)}-detail`"
                class="code-paths-route-detail"
                aria-labelledby="code-paths-tree-heading"
              >
                <h4 id="code-paths-tree-heading" class="visually-hidden">{{ row.route }}</h4>
                <div v-if="treeError" class="alert alert-danger mb-0" role="alert">{{ treeError }}</div>
                <p v-else-if="!tree" class="small text-muted mb-0" role="status">Loading the call tree…</p>
                <template v-else>
                  <div
                    v-if="tree.assemblyOnly"
                    class="alert alert-secondary small code-paths-assembly-note"
                    role="note"
                  >
                    <strong>Assembly only.</strong> This route's handler ran on an event loop, returned a reactive or
                    asynchronous result, or BootUI could not tell where its work ran, so its tree times the handler's
                    assembly, not the work that ran later or elsewhere, and Runtime Insights does not split its handler
                    by method.
                  </div>
                  <p class="small mb-3 code-paths-tree-summary">
                    <strong>{{ formatNumber(tree.warmRequests) }}</strong> warm requests, mean
                    <strong>{{ formatMillis(tree.ownMillis) }} ms</strong> of their own time<template
                      v-if="tree.handlerMillis != null"
                      >, <strong>{{ formatMillis(tree.handlerMillis) }} ms</strong> of it in the handler's application
                      methods</template
                    >
                    <template v-if="tree.firstRequestMillis != null">
                      · first recorded request {{ formatMillis(tree.firstRequestMillis) }} ms, kept apart</template
                    >.
                    <span class="text-muted">
                      Times are per warm request; medians are approximate (≈), from log2 buckets.
                      <template v-if="hasCalls">
                        SQL, REST client, cache, and AI rows under a method were issued while it was the innermost
                        instrumented method open on their thread, and their time is part of its self time. A statement
                        Hibernate flushes at commit runs after the @Transactional method returned, so it shows under the
                        method that called it.
                      </template>
                    </span>
                  </p>
                  <p v-if="!tree.nodes.length" class="text-muted small mb-0">
                    Only the route's first recorded request, kept apart, was recorded: send it again to build its warm
                    tree.
                  </p>
                  <CodePathsTree
                    v-else
                    :tree="tree"
                    :selected="selectedKey"
                    @select="selectNode"
                    @select-route="followRoute"
                    @action-target="probeActionTarget = $event"
                  />
                  <p v-if="tree.exemplarRequestIds?.length" class="small mt-3 mb-0 code-paths-exemplars">
                    Slowest and failed requests kept:
                    <template v-for="(id, position) in tree.exemplarRequestIds" :key="id">
                      <router-link :to="{path: '/activity', query: {request: id}}"
                        ><code>{{ id }}</code></router-link
                      ><template v-if="Number(position) < tree.exemplarRequestIds.length - 1">, </template>
                    </template>
                  </p>
                </template>
              </section>
            </li>
          </ul>
        </section>

        <div ref="probesSection" class="code-paths-probes-anchor">
          <MethodProbes
            :method="probeTarget"
            :action-target="selectedMethod ? probeActionTarget : null"
            :read-only="readOnly"
            :read-only-reason="readOnlyReason"
          />
        </div>

        <section class="mb-4" aria-labelledby="code-paths-excluded-heading">
          <h3 id="code-paths-excluded-heading" class="h6 text-muted mb-2">Excluded methods</h3>
          <p v-if="!summary.excludedMethods?.length" class="small text-muted mb-0">
            No method was excluded in this run.
          </p>
          <ul v-else class="small mb-0 code-paths-excluded">
            <li v-for="method in summary.excludedMethods" :key="method.method">
              <code :title="method.method">{{ methodLabel(method.method) }}</code>
              <span class="text-muted"> — {{ method.reason }}</span>
            </li>
          </ul>
        </section>
      </section>

      <section
        v-else
        id="code-paths-panel-beans"
        aria-labelledby="code-paths-tab-beans"
        class="code-paths-beans"
        role="tabpanel"
        tabindex="0"
      >
        <div v-if="beansError" class="alert alert-danger" role="alert">{{ beansError }}</div>
        <p v-else-if="!beans || beansLoading" class="small text-muted">Loading…</p>
        <UnavailableState v-else-if="!beans.available" icon="bi-diagram-3" class="code-paths-beans-unavailable">
          {{ beans.unavailableReason }}
        </UnavailableState>
        <template v-else>
          <p class="small mb-2 code-paths-beans-summary">
            <strong>{{ formatNumber(beans.observedEdges) }}</strong>
            {{ beans.observedEdges === 1 ? 'call pair' : 'call pairs' }} between beans observed in this run's route
            trees, beside <strong>{{ formatNumber(beans.declaredEdges) }}</strong> declared
            {{ beans.declaredEdges === 1 ? 'dependency' : 'dependencies' }}, of which
            <strong>{{ formatNumber(beans.notCalled) }}</strong> not called in this run.
          </p>
          <div v-if="beans.beansAvailable" class="form-check form-switch mb-2">
            <input
              id="code-paths-not-called"
              v-model="notCalledOnly"
              class="form-check-input"
              role="switch"
              type="checkbox"
            />
            <label class="form-check-label small" for="code-paths-not-called">
              Only declared dependencies not called in this run
            </label>
          </div>
          <p v-if="!beanEdges.length" class="small text-muted code-paths-beans-empty">
            {{
              notCalledOnly
                ? 'Every declared dependency Code Paths would observe was called in this run.'
                : 'No bean-to-bean call or declared dependency to show yet.'
            }}
          </p>
          <div v-else class="table-responsive">
            <table class="table table-sm align-middle code-paths-table code-paths-beans-table">
              <caption class="visually-hidden">
                Calls between beans observed in this run, beside the dependencies the beans declare
              </caption>
              <thead>
                <tr>
                  <th scope="col">Bean</th>
                  <th scope="col">Calls or depends on</th>
                  <th scope="col">Declared</th>
                  <th scope="col" class="text-end">Calls in this run</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="edge in beanEdges" :key="`${edge.from}>${edge.to}`">
                  <td>
                    <code :title="edge.fromType ?? undefined">{{ edge.from }}</code>
                  </td>
                  <td>
                    <code :title="edge.toType ?? undefined">{{ edge.to }}</code>
                  </td>
                  <td>{{ edge.declared ? 'Yes' : 'No' }}</td>
                  <td class="text-end">
                    <template v-if="edge.observed">{{ formatNumber(edge.calls) }}</template>
                    <span v-else-if="edge.observable" class="text-body-secondary code-paths-not-called"
                      >Not called in this run</span
                    >
                    <span
                      v-else
                      class="text-muted code-paths-not-observable"
                      :title="edge.unobservableReason ?? 'A call between these beans would not be observed'"
                      >Not observable</span
                    >
                  </td>
                </tr>
              </tbody>
            </table>
            <p v-if="beans.omitted" class="small text-muted">{{ formatNumber(beans.omitted) }} more edges not shown.</p>
          </div>
          <details v-if="beans.limitations?.length" class="mt-2 small code-paths-limitations">
            <summary>What these edges cannot show ({{ beans.limitations.length }})</summary>
            <ul class="mb-0 mt-2">
              <li v-for="limitation in beans.limitations" :key="limitation">{{ limitation }}</li>
            </ul>
          </details>
        </template>
      </section>
    </template>
  </div>
</template>

<style scoped>
.code-paths-table td,
.code-paths-table th {
  font-variant-numeric: tabular-nums;
}

.code-paths-limitations summary {
  cursor: pointer;
}

.code-paths-route-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 0.5rem;
}

.code-paths-search {
  position: relative;
  flex: 1 1 18rem;
}

.code-paths-search-icon {
  position: absolute;
  inset-block: 0;
  inset-inline-start: 0.85rem;
  display: flex;
  align-items: center;
  color: var(--bootui-text-muted);
  pointer-events: none;
}

.code-paths-route-search {
  padding-inline-start: 2.3rem;
}

.code-paths-route-sort {
  flex: 0 1 15rem;
  width: auto;
}

.code-paths-routes {
  background: var(--bootui-surface-solid);
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-lg);
  box-shadow: var(--bootui-shadow-sm);
  overflow: hidden;
}

.code-paths-route-row {
  scroll-margin-top: 7rem;
}

.code-paths-route-row + .code-paths-route-row {
  border-top: 1px solid var(--bootui-border);
}

.code-paths-route {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 8.5rem auto;
  align-items: center;
  gap: 0.35rem 1.25rem;
  width: 100%;
  padding: 0.75rem 1rem;
  border: 0;
  background: transparent;
  color: var(--bootui-text);
  text-align: start;
  transition: background-color 150ms ease;
}

.code-paths-route:focus-visible {
  outline-offset: -2px;
}

.code-paths-route:hover,
.code-paths-route-row.open .code-paths-route {
  background: var(--bootui-nav-hover-bg);
}

.code-paths-route-main {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  min-width: 0;
}

.code-paths-route-name {
  overflow-wrap: anywhere;
  font-size: 0.85rem;
  font-weight: 600;
}

.code-paths-verb {
  font-weight: 800;
  letter-spacing: 0.02em;
}

.code-paths-route-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.15rem 0.9rem;
  color: var(--bootui-text-muted);
  font-size: 0.875rem;
  font-variant-numeric: tabular-nums;
}

.code-paths-route-top {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.code-paths-route-time {
  display: grid;
  gap: 0.35rem;
  justify-items: end;
}

.code-paths-route-median {
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.code-paths-route-bar {
  display: block;
  width: 100%;
  height: 0.3rem;
  border-radius: var(--bootui-radius-pill);
  background: var(--bootui-border-alt);
  overflow: hidden;
}

.code-paths-route-bar > span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--bootui-text-muted);
}

.code-paths-route-bar > .code-paths-route-bar-top {
  background: var(--bootui-green-dark);
}

.code-paths-route-chevron {
  color: var(--bootui-text-muted);
  transition: transform 150ms ease;
}

.code-paths-route-row.open .code-paths-route-chevron {
  transform: rotate(180deg);
}

.code-paths-route-detail {
  padding: 1rem 1.25rem 1.25rem;
  border-top: 1px solid var(--bootui-border);
}

.code-paths-probes-anchor {
  scroll-margin-top: 7rem;
}

@media (max-width: 575.98px) {
  .code-paths-route {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .code-paths-route-time {
    grid-column: 1 / -1;
    grid-row: 2;
    grid-template-columns: auto minmax(0, 1fr);
    align-items: center;
    justify-items: stretch;
    gap: 0.75rem;
  }

  .code-paths-route-chevron {
    grid-column: 2;
    grid-row: 1;
  }

  .code-paths-route-detail {
    padding: 0.85rem 0.75rem 1rem;
  }
}

@media (prefers-reduced-motion: reduce) {
  .code-paths-route,
  .code-paths-route-chevron {
    transition: none;
  }
}
</style>
