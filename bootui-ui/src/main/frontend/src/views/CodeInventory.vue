<script setup>
import {computed, onBeforeUnmount, ref, watch} from 'vue'
import {getJson} from '../api.js'
import {formatNumber} from '../utils/format.js'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import UnavailableState from './components/UnavailableState.vue'

const props = defineProps(panelProps)
const {manifestAvailable, manifestUnavailableReason} = usePanelState(props)

/** How many rows one tab loads at once; the API pages, and the panel says when more exist. */
const PAGE = 500

/** How long the panel waits before reading again while the class-file scan runs. */
const SCAN_POLL_MILLIS = 1500

const SCAN_IN_PROGRESS = ['PENDING', 'RUNNING']

const STATUS = {
  EXECUTED: {label: 'Executed', badge: 'text-bg-success'},
  NEVER_EXECUTED: {label: 'Not executed', badge: 'text-bg-warning'},
  NOT_TRACKED: {label: 'Not tracked', badge: 'text-bg-secondary'},
  GENERATED: {label: 'Generated', badge: 'text-bg-info'}
}

const DEPENDENCY_STATUS = {
  LOADED: {label: 'Loaded in this run', badge: 'text-bg-success'},
  LOADED_EARLIER: {label: 'Loaded before this run', badge: 'text-bg-info'},
  NOT_LOADED: {label: 'Not loaded in this run', badge: 'text-bg-secondary'}
}

const summary = ref(null)
const error = ref(null)
const lastFetched = ref(null)
const activeTab = ref(null)

const changes = ref(null)
const changesError = ref(null)
const code = ref(null)
const codeError = ref(null)
const neverExecutedOnly = ref(false)
const selectedPackage = ref(null)
const packageMethods = ref(null)
const packageError = ref(null)
const dependencies = ref(null)
const dependenciesError = ref(null)

const available = computed(() => summary.value?.available === true)
const previousRun = computed(() => summary.value?.changes?.previousRun === true)

const tabs = computed(() => {
  const changed = {id: 'changes', label: 'Changed since the previous run', icon: 'bi-pencil-square'}
  const application = {id: 'code', label: 'Application code', icon: 'bi-box-seam'}
  const deps = {id: 'dependencies', label: 'Dependencies', icon: 'bi-boxes'}
  return previousRun.value ? [changed, application, deps] : [application, deps, changed]
})

const methods = computed(() => summary.value?.methods ?? null)
const headline = computed(() => {
  const counts = methods.value
  if (!counts) return null
  return {executed: counts.executed, tracked: counts.tracked}
})

/** The scan of the comparison shown in the Changed tab, while it has not compared anything yet or failed. */
const changesScan = computed(() => {
  const counts = changes.value?.counts
  if (!counts) return null
  if (counts.scanStatus === 'FAILED') return 'failed'
  if (SCAN_IN_PROGRESS.includes(counts.scanStatus) && !changes.value.changes?.length) return 'running'
  return null
})

const scanLabel = computed(() => {
  const scan = summary.value?.scan
  if (!scan) return null
  switch (scan.status) {
    case 'PENDING':
    case 'RUNNING':
      return 'Scanning the application’s class files…'
    case 'PARTIAL':
      return `Scan partial: ${formatNumber(scan.classes)} classes`
    case 'FAILED':
      return 'Scan failed'
    default:
      return `${formatNumber(scan.classes)} classes scanned in ${formatNumber(scan.durationMillis ?? 0)} ms`
  }
})

let scanPoll = null

function stopScanPoll() {
  if (scanPoll) {
    clearTimeout(scanPoll)
    scanPoll = null
  }
}

async function fetchSummary() {
  error.value = null
  stopScanPoll()
  try {
    summary.value = await getJson('api/code-inventory')
    lastFetched.value = Date.now()
    if (summary.value?.available) {
      if (!activeTab.value || !tabs.value.some((tab) => tab.id === activeTab.value)) {
        activeTab.value = tabs.value[0].id
      }
      await loadTab(activeTab.value)
      // While the scan runs, the counts and changes are not final: read again until it ends.
      if (SCAN_IN_PROGRESS.includes(summary.value?.scan?.status) && !autoRefresh.value) {
        scanPoll = setTimeout(() => {
          scanPoll = null
          load()
        }, SCAN_POLL_MILLIS)
      }
    }
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load Code Inventory')
  }
}

onBeforeUnmount(stopScanPoll)

const {autoRefresh, loading, initialLoading, load} = useAutoRefresh(fetchSummary, {
  enabled: manifestAvailable,
  defaultEnabled: false
})

async function loadTab(id) {
  if (id === 'changes') {
    changesError.value = null
    try {
      changes.value = await getJson(`api/code-inventory/changes?limit=${PAGE}`)
    } catch (e) {
      changesError.value = formatLoadError(e, 'Unable to load the changed methods')
    }
  } else if (id === 'code') {
    await loadCode()
  } else if (id === 'dependencies') {
    dependenciesError.value = null
    try {
      dependencies.value = await getJson(`api/code-inventory/dependencies?limit=${PAGE}`)
    } catch (e) {
      dependenciesError.value = formatLoadError(e, 'Unable to load the dependencies')
    }
  }
}

function statusQuery() {
  return neverExecutedOnly.value ? '&status=never-executed' : ''
}

async function loadCode() {
  codeError.value = null
  try {
    code.value = await getJson(`api/code-inventory/methods?limit=1${statusQuery()}`)
    if (selectedPackage.value) {
      await loadPackage(selectedPackage.value)
    }
  } catch (e) {
    codeError.value = formatLoadError(e, 'Unable to load the application code')
  }
}

async function loadPackage(name) {
  packageError.value = null
  try {
    packageMethods.value = await getJson(
      `api/code-inventory/methods?package=${encodeURIComponent(name)}&limit=${PAGE}${statusQuery()}`
    )
  } catch (e) {
    packageError.value = formatLoadError(e, `Unable to load ${name}`)
  }
}

async function togglePackage(name) {
  if (selectedPackage.value === name) {
    selectedPackage.value = null
    packageMethods.value = null
    return
  }
  selectedPackage.value = name
  packageMethods.value = null
  await loadPackage(name)
}

watch(neverExecutedOnly, () => {
  loadCode()
})

function selectTab(id) {
  if (activeTab.value === id) return
  activeTab.value = id
  loadTab(id)
}

function onTabKeydown(event, index) {
  if (!['ArrowRight', 'ArrowLeft', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const count = tabs.value.length
  let next = index
  if (event.key === 'ArrowRight') next = (index + 1) % count
  if (event.key === 'ArrowLeft') next = (index - 1 + count) % count
  if (event.key === 'Home') next = 0
  if (event.key === 'End') next = count - 1
  selectTab(tabs.value[next].id)
  event.currentTarget.closest('[role="tablist"]')?.querySelectorAll('[role="tab"]')[next]?.focus()
}

/** The methods of the selected package, grouped by class, in the classes' order. */
const packageClasses = computed(() => {
  const report = packageMethods.value
  if (!report) return []
  const byClass = new Map()
  for (const method of report.methods ?? []) {
    if (!byClass.has(method.className)) byClass.set(method.className, [])
    byClass.get(method.className).push(method)
  }
  return (report.classes ?? [])
    .filter((row) => row.packageName === selectedPackage.value)
    .map((row) => ({...row, methods: byClass.get(row.className) ?? []}))
})

function simpleName(className) {
  const dot = className.lastIndexOf('.')
  return dot < 0 ? className : className.slice(dot + 1)
}

function methodLabel(method) {
  const name =
    method.name === '<init>' ? 'constructor' : method.name === '<clinit>' ? 'static initializer' : method.name
  return `${name}${method.descriptor}`
}

/** Whether Code Paths can probe the method: a method, not a constructor or a static initializer (M5-8). */
function probeable(method) {
  return method.name !== '<init>' && method.name !== '<clinit>'
}

function statusOf(method) {
  return STATUS[method.status] ?? STATUS.NOT_TRACKED
}

function dependencyStatus(row) {
  return DEPENDENCY_STATUS[row.status] ?? DEPENDENCY_STATUS.NOT_LOADED
}

function coordinates(row) {
  if (!row.groupId && !row.artifactId) return null
  return `${row.groupId ?? '?'}:${row.artifactId ?? '?'}${row.version ? `:${row.version}` : ''}`
}

function loadedWhen(row) {
  if (row.loadedAt === 'STARTUP') return 'At startup'
  if (row.loadedAt === 'AFTER_STARTUP') return 'After startup'
  return '—'
}

function formatTimestamp(epochMillis) {
  if (!epochMillis) return '—'
  return new Date(epochMillis).toLocaleString()
}

function moreRows(report) {
  const page = report?.page
  return page && page.hasMore ? page.matched - page.returned : 0
}
</script>

<template>
  <div class="code-inventory-panel">
    <PanelHeader
      icon="bi-list-check"
      title="Code Inventory"
      subtitle="Which application methods this run executed, what changed since the previous run, and which dependencies loaded classes."
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <UnavailableState v-if="!manifestAvailable" icon="bi-list-check">
      {{ manifestUnavailableReason }}
      <div class="mt-2">
        <router-link to="/java-agent" class="code-inventory-agent-link">Open the Java Agent panel</router-link>
        to attach the BootUI agent.
      </div>
    </UnavailableState>

    <PanelSkeleton v-else-if="initialLoading" />

    <template v-else-if="summary && !available">
      <UnavailableState icon="bi-list-check" class="code-inventory-unavailable">
        {{ summary.unavailableReason }}
        <div class="mt-2">
          <router-link to="/java-agent" class="code-inventory-agent-link">Open the Java Agent panel</router-link>
          to attach the BootUI agent.
        </div>
      </UnavailableState>
    </template>

    <template v-else-if="summary">
      <section class="card mb-4" aria-labelledby="code-inventory-headline">
        <div class="card-body p-4">
          <h3 id="code-inventory-headline" class="h4 fw-bold mb-1">
            <template v-if="headline">
              {{ formatNumber(headline.executed) }} of {{ formatNumber(headline.tracked) }} application methods executed
            </template>
            <template v-else>Waiting for the scan of the application’s class files</template>
          </h3>
          <p class="text-muted small mb-2">
            Run {{ formatNumber(summary.run?.generation) }}
            <template v-if="summary.run?.application">
              of <code>{{ summary.run.application }}</code>
            </template>
            <template v-if="summary.run?.claimedAtEpochMillis">
              · claimed {{ formatTimestamp(summary.run.claimedAtEpochMillis) }}
            </template>
            <template v-if="scanLabel"> · {{ scanLabel }}</template>
          </p>
          <ul v-if="methods" class="list-inline small mb-0 code-inventory-counts">
            <li class="list-inline-item">
              <strong>{{ formatNumber(methods.neverExecuted) }}</strong> never executed
            </li>
            <li class="list-inline-item">
              <strong>{{ formatNumber(methods.notTracked) }}</strong> not tracked
            </li>
            <li v-if="methods.generated" class="list-inline-item">
              <strong>{{ formatNumber(methods.generated) }}</strong> generated
            </li>
            <li class="list-inline-item">
              <strong>{{ formatNumber(methods.classes) }}</strong> classes in
              <strong>{{ formatNumber(methods.packages) }}</strong> packages
            </li>
          </ul>
          <details v-if="summary.limitations?.length" class="mt-3 small code-inventory-limitations">
            <summary>What these counts cannot see ({{ summary.limitations.length }})</summary>
            <ul class="mb-0 mt-2">
              <li v-for="limitation in summary.limitations" :key="limitation">{{ limitation }}</li>
            </ul>
          </details>
        </div>
      </section>

      <ul class="nav nav-tabs mb-3" role="tablist" aria-label="Code Inventory views">
        <li v-for="(tab, index) in tabs" :key="tab.id" class="nav-item" role="presentation">
          <button
            :id="`code-inventory-tab-${tab.id}`"
            :aria-controls="`code-inventory-panel-${tab.id}`"
            :aria-selected="activeTab === tab.id"
            :class="{active: activeTab === tab.id}"
            :tabindex="activeTab === tab.id ? 0 : -1"
            class="nav-link"
            role="tab"
            type="button"
            @click="selectTab(tab.id)"
            @keydown="onTabKeydown($event, index)"
          >
            <i :class="['bi', tab.icon, 'me-1']" aria-hidden="true"></i>{{ tab.label }}
          </button>
        </li>
      </ul>

      <section
        v-if="activeTab === 'changes'"
        id="code-inventory-panel-changes"
        aria-labelledby="code-inventory-tab-changes"
        role="tabpanel"
        tabindex="0"
      >
        <div v-if="changesError" class="alert alert-danger" role="alert">{{ changesError }}</div>
        <template v-else-if="changes">
          <UnavailableState
            v-if="changesScan === 'running'"
            icon="bi-hourglass-split"
            class="code-inventory-scan-state"
          >
            {{
              changes.counts.note ||
              'The scan of the application’s class files is still running: changes are compared once it ends.'
            }}
          </UnavailableState>
          <UnavailableState
            v-else-if="changesScan === 'failed'"
            icon="bi-exclamation-triangle"
            class="code-inventory-scan-state"
          >
            {{ changes.counts.note || 'The scan of the application’s class files failed: no change can be compared.' }}
          </UnavailableState>
          <UnavailableState v-else-if="!changes.counts?.previousRun" icon="bi-clock-history">
            {{ changes.counts?.note || 'No previous run of this application was kept to compare with.' }}
          </UnavailableState>
          <template v-else>
            <p class="small mb-2">
              <strong>{{ formatNumber(changes.counts.changed) }}</strong> changed,
              <strong>{{ formatNumber(changes.counts.added) }}</strong> added,
              <strong>{{ changes.counts.removed == null ? 'unknown' : formatNumber(changes.counts.removed) }}</strong>
              removed since the previous run;
              <strong>{{ formatNumber(changes.counts.executed) }}</strong> executed and
              <strong>{{ formatNumber(changes.counts.notExecuted) }}</strong> not executed in this run.
            </p>
            <p v-if="changes.counts.note" class="small text-muted">{{ changes.counts.note }}</p>
            <p v-if="changes.counts.partial" class="small text-muted">
              Partial: some classes were not covered by both runs’ scans, so they are not compared.
            </p>
            <p v-if="!changes.changes.length" class="text-muted small">
              No application method changed since the previous run.
            </p>
            <div v-else class="table-responsive">
              <table class="table table-sm align-middle code-inventory-table">
                <caption class="visually-hidden">
                  Methods changed or added since the previous run
                </caption>
                <thead>
                  <tr>
                    <th scope="col">Method</th>
                    <th scope="col">Change</th>
                    <th scope="col">This run</th>
                    <th scope="col">First request</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="method in changes.changes" :key="method.key">
                    <td>
                      <code class="bootui-break-anywhere"
                        >{{ simpleName(method.className) }}.{{ methodLabel(method) }}</code
                      >
                      <div class="small text-muted bootui-break-anywhere">{{ method.packageName }}</div>
                    </td>
                    <td>{{ method.change === 'ADDED' ? 'Added' : 'Changed' }}</td>
                    <td>
                      <span :class="['badge', statusOf(method).badge]">{{ statusOf(method).label }}</span>
                      <div v-if="method.notTrackedReason" class="small text-muted">{{ method.notTrackedReason }}</div>
                      <router-link
                        v-if="probeable(method)"
                        :to="{path: '/code-paths', query: {probe: method.key}}"
                        class="small d-inline-block code-inventory-probe"
                        :title="`Probe ${method.key} in Code Paths`"
                        >Probe in Code Paths</router-link
                      >
                    </td>
                    <td>
                      <router-link
                        v-if="method.firstRequestId"
                        :to="{path: '/activity', query: {request: method.firstRequestId}}"
                      >
                        <code>{{ method.firstRoute || method.firstRequestId }}</code>
                      </router-link>
                      <span v-else class="text-muted">—</span>
                    </td>
                  </tr>
                </tbody>
              </table>
              <p v-if="moreRows(changes)" class="small text-muted">
                {{ formatNumber(moreRows(changes)) }} more not shown.
              </p>
            </div>
          </template>
        </template>
      </section>

      <section
        v-if="activeTab === 'code'"
        id="code-inventory-panel-code"
        aria-labelledby="code-inventory-tab-code"
        role="tabpanel"
        tabindex="0"
      >
        <div class="form-check mb-3">
          <input
            id="code-inventory-never-executed"
            v-model="neverExecutedOnly"
            class="form-check-input"
            type="checkbox"
          />
          <label class="form-check-label" for="code-inventory-never-executed">Never executed only</label>
        </div>
        <div v-if="codeError" class="alert alert-danger" role="alert">{{ codeError }}</div>
        <template v-else-if="code">
          <p v-if="!code.packages.length" class="text-muted small">No application method matches.</p>
          <div v-else class="table-responsive">
            <table class="table table-sm align-middle code-inventory-table">
              <caption class="visually-hidden">
                Application packages with their method counts
              </caption>
              <thead>
                <tr>
                  <th scope="col">Package</th>
                  <th scope="col" class="text-end">Classes</th>
                  <th scope="col" class="text-end">Executed</th>
                  <th scope="col" class="text-end">Never executed</th>
                  <th scope="col" class="text-end">Not tracked</th>
                </tr>
              </thead>
              <tbody>
                <template v-for="row in code.packages" :key="row.name">
                  <tr>
                    <td>
                      <button
                        :aria-expanded="selectedPackage === row.name"
                        class="btn btn-link p-0 text-start code-inventory-package"
                        type="button"
                        @click="togglePackage(row.name)"
                      >
                        <i
                          :class="['bi', selectedPackage === row.name ? 'bi-chevron-down' : 'bi-chevron-right', 'me-1']"
                          aria-hidden="true"
                        ></i>
                        <code>{{ row.name || '(default package)' }}</code>
                      </button>
                    </td>
                    <td class="text-end">{{ formatNumber(row.classes) }}</td>
                    <td class="text-end">{{ formatNumber(row.executed) }}</td>
                    <td class="text-end">{{ formatNumber(row.neverExecuted) }}</td>
                    <td class="text-end">{{ formatNumber(row.notTracked) }}</td>
                  </tr>
                  <tr v-if="selectedPackage === row.name">
                    <td colspan="5" class="code-inventory-classes">
                      <div v-if="packageError" class="alert alert-danger mb-0" role="alert">
                        {{ packageError }}
                      </div>
                      <p v-else-if="!packageMethods" class="small text-muted mb-0">Loading…</p>
                      <template v-else>
                        <details v-for="cls in packageClasses" :key="cls.className" class="mb-2">
                          <summary>
                            <code>{{ simpleName(cls.className) }}</code>
                            <span class="small text-muted ms-2">
                              {{ formatNumber(cls.executed) }} executed · {{ formatNumber(cls.neverExecuted) }} never
                              executed · {{ formatNumber(cls.notTracked) }} not tracked
                              <template v-if="cls.changed"> · {{ formatNumber(cls.changed) }} changed</template>
                            </span>
                          </summary>
                          <ul class="list-unstyled ms-4 mt-1 mb-0 small">
                            <li v-for="method in cls.methods" :key="method.key" class="mb-1">
                              <span :class="['badge', statusOf(method).badge, 'me-2']">{{
                                statusOf(method).label
                              }}</span>
                              <code class="bootui-break-anywhere">{{ methodLabel(method) }}</code>
                              <span v-if="method.notTrackedReason" class="text-muted">
                                — {{ method.notTrackedReason }}</span
                              >
                              <router-link
                                v-if="method.firstRequestId"
                                :to="{path: '/activity', query: {request: method.firstRequestId}}"
                                class="ms-2"
                              >
                                {{ method.firstRoute || method.firstRequestId }}
                              </router-link>
                            </li>
                          </ul>
                        </details>
                        <p v-if="moreRows(packageMethods)" class="small text-muted mb-0">
                          {{ formatNumber(moreRows(packageMethods)) }} more methods not shown.
                        </p>
                      </template>
                    </td>
                  </tr>
                </template>
              </tbody>
            </table>
          </div>
        </template>
      </section>

      <section
        v-if="activeTab === 'dependencies'"
        id="code-inventory-panel-dependencies"
        aria-labelledby="code-inventory-tab-dependencies"
        role="tabpanel"
        tabindex="0"
      >
        <div v-if="dependenciesError" class="alert alert-danger" role="alert">
          {{ dependenciesError }}
        </div>
        <template v-else-if="dependencies">
          <p class="small mb-2">
            <strong>{{ formatNumber(dependencies.counts?.declared) }}</strong> declared dependencies:
            <strong>{{ formatNumber(dependencies.counts?.notLoaded) }}</strong> not loaded in this run. A jar not loaded
            in this run is not proof it is unused: it may load on a route this run did not exercise.
          </p>
          <p v-if="dependencies.counts?.declaredReason" class="small text-muted">
            {{ dependencies.counts.declaredReason }}
          </p>
          <div class="table-responsive">
            <table class="table table-sm align-middle code-inventory-table">
              <caption class="visually-hidden">
                Dependencies and the classes they loaded in this run
              </caption>
              <thead>
                <tr>
                  <th scope="col">Jar</th>
                  <th scope="col">Status</th>
                  <th scope="col" class="text-end">Classes this run</th>
                  <th scope="col">First class</th>
                  <th scope="col">First route</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in dependencies.dependencies" :key="(row.jar ?? '') + (coordinates(row) ?? '')">
                  <td>
                    <code class="bootui-break-anywhere">{{ row.jar ?? row.artifactId }}</code>
                    <div v-if="coordinates(row)" class="small text-muted bootui-break-anywhere">
                      {{ coordinates(row) }}
                    </div>
                    <div v-if="!row.declared" class="small text-muted">Not declared</div>
                  </td>
                  <td>
                    <span :class="['badge', dependencyStatus(row).badge]">{{ dependencyStatus(row).label }}</span>
                  </td>
                  <td class="text-end">
                    {{ formatNumber(row.classesLoaded) }}
                    <div class="small text-muted">{{ formatNumber(row.classesLoadedTotal) }} in this JVM</div>
                  </td>
                  <td>{{ loadedWhen(row) }}</td>
                  <td>
                    <router-link
                      v-if="row.firstRequestId"
                      :to="{path: '/activity', query: {request: row.firstRequestId}}"
                    >
                      <code>{{ row.firstRoute || row.firstRequestId }}</code>
                    </router-link>
                    <span v-else class="text-muted">—</span>
                  </td>
                </tr>
              </tbody>
            </table>
            <p v-if="moreRows(dependencies)" class="small text-muted">
              {{ formatNumber(moreRows(dependencies)) }} more not shown.
            </p>
          </div>
        </template>
      </section>
    </template>
  </div>
</template>

<style scoped>
.code-inventory-counts strong {
  font-variant-numeric: tabular-nums;
}

.code-inventory-table td,
.code-inventory-table th {
  font-variant-numeric: tabular-nums;
}

.code-inventory-classes {
  background: var(--bs-tertiary-bg);
}

.code-inventory-package {
  color: inherit;
  text-decoration: none;
}

.code-inventory-package:hover code,
.code-inventory-package:focus-visible code {
  text-decoration: underline;
}

.code-inventory-limitations summary {
  cursor: pointer;
}
</style>
