<script setup>
import {computed, defineAsyncComponent, inject, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {useRoute} from 'vue-router'
import {apiFetch, getJson} from '../api.js'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import UnavailableState from './components/UnavailableState.vue'
import AiExportPreview from './components/AiExportPreview.vue'
import FlashBanner from './components/FlashBanner.vue'
import SpinnerButton from './components/SpinnerButton.vue'
import {insightsUsable} from '../utils/insightsPanel.js'
import RequestCodePath from './components/RequestCodePath.vue'
import RequestJournalProfile from './components/RequestJournalProfile.vue'
import RuntimeJournalStatus from './components/RuntimeJournalStatus.vue'
import RuntimeResources from './components/RuntimeResources.vue'
import {formatBytes, formatClockTime, formatMillis, formatNumber} from '../utils/format.js'
import {formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {safeLocalStorage} from '../utils/safeStorage.js'
import {useConfirm} from '../utils/useConfirm.js'
import {useFlashMessage} from '../utils/useFlashMessage.js'
import {useEventStreamRefresh} from '../utils/useEventStreamRefresh.js'
import {useCopyToClipboard} from '../utils/useCopyToClipboard.js'
import {
  cacheAccessSummary,
  childTierLabel,
  profileSections,
  restCallSummary,
  securityEventExact,
  unavailableTiersText
} from '../utils/requestProfile.js'
import {loadProfileExceptionDetails, profileMarkdown} from '../utils/markdownExport.js'
import {
  appendOlderPage,
  bucketEntries,
  markerPositions,
  buildActivityQueryParams,
  deepLink,
  filterEntries,
  groupEntries,
  mergeActivityPages,
  nestEntries
} from '../utils/activityStream.js'

// Live Flow is a second reading of the same evidence, so it ships inside this panel rather than as a
// panel of its own. Keep its layout and motion code in a route-level async chunk.
const LiveFlowMode = defineAsyncComponent(() => import('./LiveFlowMode.vue'))

const TYPES = [
  'REQUEST',
  'SQL',
  'EXCEPTION',
  'SECURITY',
  'CACHE',
  'SCHEDULED',
  'MESSAGING',
  'MAIL',
  'REST_CLIENT',
  'FAULT_TOLERANCE',
  'TRANSACTION',
  'AI',
  'LOG',
  'APP_EVENT',
  'WEBSOCKET',
  'ORM',
  'ASYNC'
]
const SEVERITIES = ['OK', 'SLOW', 'WARN', 'ERROR']
const FILTERS_STORAGE_KEY = 'bootui.activity.filters'
const FLOW_COLLAPSED_STORAGE_KEY = 'bootui.activity.flowCollapsed'
const PERSISTENCE_DOCS_URL = 'https://www.julien-dubois.com/boot-ui/properties#live-activity-durable-persistence'

const props = defineProps(panelProps)
const route = useRoute()
const {readOnly, readOnlyReason, manifestAvailable, manifestUnavailableReason} = usePanelState(props)
const {confirm} = useConfirm()
const {message: banner, flash, clear: clearBanner} = useFlashMessage()

const report = ref(null)
const error = ref(null)
const lastFetched = ref(null)
const flowCollapsed = ref(safeLocalStorage.getItem(FLOW_COLLAPSED_STORAGE_KEY) === 'true')
// Incremented after every successful feed refresh so the map re-reads the same evidence off the same
// SSE tick instead of running a second poll of its own.
const flowRefreshTick = ref(0)
const typeFilter = ref('')
const severityFilter = ref('')
const textFilter = ref('')
const errorsOnly = ref(false)
// Where the feed comes from: '' leaves it to bootui.activity.feed-source, 'journal' asks for the feed rendered from the
// runtime journal, which alone can filter by route, request id, and work outside any request, and 'buffers' asks for
// 1.x's feed merged from the panel buffers (docs/PLAN-v2.md §5.3).
const feedSource = ref('')
const routeFilter = ref('')
const requestIdFilter = ref('')
const runFilter = ref('')
const noRequestOnly = ref(false)
const JOURNAL_SOURCE_LABEL = 'Runtime journal'
// The journal serves the feed when asked for, or by default when the server's last report came from it.
const fromJournal = computed(
  () =>
    feedSource.value === 'journal' ||
    (feedSource.value === '' && (report.value?.sources ?? []).includes(JOURNAL_SOURCE_LABEL))
)

// "Use a database" disclosure: reveals setup documentation (and, when a DataSource is already
// configured, the "Use the existing datasource" switch action) next to the title. Collapsed by
// default so the panel never surprises the user with an unsolicited call to action.
const showDatabaseInfo = ref(false)
// The runtime journal's status block, opened on demand so the panel makes no extra request on render.
const showJournal = ref(false)
// The CPU ledger and resource track, opened on demand like the journal status (docs/PLAN-v2.md §5.11).
const showResources = ref(false)
const switchingToDatabase = ref(false)

const profile = ref(null)
const profileLoading = ref(false)
const profileError = ref(null)
const profileRequestId = ref(null)
// The runtime journal's view of the open request, loaded right after its profile (docs/PLAN-v2.md §5.3).
const journalProfile = ref(null)
const drawerEl = ref(null)
const profileOpenerEl = ref(null)
// Bumped by every profile load and by closing the drawer, so a late response never lands on a different row.
let profileLoadToken = 0
// The "Copy for AI" preview of the open profile, or null while the profile itself is shown.
const aiExport = ref(null)

// "Load older" pagination state. Only ever populated when the backing store is durable (see
// `persistent` below); stays empty for the default in-memory mode so nothing here changes its
// behavior. `olderEntries` accumulates pages fetched by clicking "Load older"; `olderPageInfo`
// tracks the pagination cursor for the *next* such click (falls back to the live head's own
// pageInfo until the first click).
const olderEntries = ref([])
const olderPageInfo = ref(null)
const loadingOlder = ref(false)

const {copiedKey, copyToClipboard} = useCopyToClipboard(2000)

restoreFilters()

// Whether the backend served this response from the durable activity store rather than the
// default live in-memory re-merge. Only known once the first response arrives.
const persistent = computed(() => report.value?.pageInfo?.persistent === true)

// Drives the "Currently saving X events in memory" tip and "Use a database" disclosure: always
// populated on every response (see ActivityPersistenceOptionDto), independent of whether persistence
// is currently active, so the disclosure can explain how to enable it even before the first switch.
const persistenceOption = computed(() => report.value?.persistenceOption ?? null)
const dataSourceAvailable = computed(() => persistenceOption.value?.dataSourceAvailable === true)
const memoryEventCount = computed(() => report.value?.entries?.length ?? 0)

// Builds the request URL for the head (page-1) fetch. Filters are only pushed down as server-side
// query params once persistence is confirmed active, so the default in-memory mode's request never
// changes shape (it keeps fetching the bare, unfiltered endpoint exactly as before and filters
// entirely client-side). When persistent, pushing filters server-side lets search/filter reach the
// full durable history instead of only whatever fits in the live merge window.
function activityUrl(extra = {}) {
  const params = new URLSearchParams()
  if (persistent.value) {
    const filterParams = buildActivityQueryParams({
      type: typeFilter.value,
      severity: severityFilter.value,
      text: textFilter.value,
      errorsOnly: errorsOnly.value
    })
    for (const [key, value] of Object.entries(filterParams)) params.set(key, value)
  }
  if (feedSource.value) params.set('source', feedSource.value)
  if (fromJournal.value && !persistent.value) {
    if (routeFilter.value.trim()) params.set('route', routeFilter.value.trim())
    if (requestIdFilter.value.trim()) params.set('requestId', requestIdFilter.value.trim())
    if (runFilter.value.trim()) params.set('run', runFilter.value.trim())
    if (noRequestOnly.value) params.set('noRequest', 'true')
  }
  for (const [key, value] of Object.entries(extra)) {
    if (value != null) params.set(key, value)
  }
  const qs = params.toString()
  return qs ? `api/activity?${qs}` : 'api/activity'
}

async function loadActivity() {
  try {
    const response = await apiFetch(activityUrl())
    if (!response.ok) {
      throw new Error(`Request failed with status ${response.status}`)
    }
    report.value = await response.json()
    error.value = null
    lastFetched.value = Date.now()
    flowRefreshTick.value += 1
  } catch (err) {
    error.value = err.message || 'Could not load activity'
    throw err
  }
}

const {
  autoRefresh,
  initialLoading,
  loading,
  load: refreshNow,
  retryConnection,
  connectionState
} = useEventStreamRefresh('api/activity/stream', loadActivity, {
  enabled: manifestAvailable
})

// Pagination info driving the "Load older" button: once at least one older page has been fetched,
// its pageInfo takes over from the live head's, so repeated clicks keep paging further back.
const effectivePageInfo = computed(() => olderPageInfo.value ?? report.value?.pageInfo ?? null)
const canLoadOlder = computed(
  () => persistent.value && !!effectivePageInfo.value?.hasMore && !!effectivePageInfo.value?.nextCursor
)

async function loadOlder() {
  const info = effectivePageInfo.value
  if (!info?.hasMore || !info.nextCursor || loadingOlder.value) return
  loadingOlder.value = true
  try {
    const response = await apiFetch(activityUrl({cursor: info.nextCursor}))
    if (!response.ok) {
      throw new Error(`Request failed with status ${response.status}`)
    }
    const page = await response.json()
    olderEntries.value = appendOlderPage(report.value?.entries, olderEntries.value, page.entries)
    olderPageInfo.value = page.pageInfo ?? null
    error.value = null
  } catch (err) {
    error.value = err.message || 'Could not load older activity'
  } finally {
    loadingOlder.value = false
  }
}

function toggleDatabaseInfo() {
  showDatabaseInfo.value = !showDatabaseInfo.value
}

// Hot-switches this running instance from the in-memory buffer to the existing DataSource, gated by
// the same destructive-action confirmation used elsewhere (Flyway migrate/clean, cache clear, …): it
// creates a database table (if missing) and starts writing to it. Mirrors Flyway.vue's runAction shape.
async function useExistingDatasource() {
  if (readOnly.value) {
    flash(readOnlyReason.value, 'warning')
    return
  }
  const confirmed = await confirm({
    title: 'Use the existing datasource?',
    message:
      'Checks the current datasource, creates the Live Activity table if it does not already exist, and switches ' +
      'this running instance to it instead of the in-memory buffer. This switch is runtime-only: a restart reverts ' +
      'to in-memory storage unless persistence is also enabled in configuration.',
    confirmLabel: 'Use the existing datasource',
    danger: true
  })
  if (!confirmed) return

  switchingToDatabase.value = true
  clearBanner()
  try {
    const res = await apiFetch('api/activity/use-existing-datasource', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({confirm: true})
    })
    const result = await res.json().catch(() => ({}))
    if (!res.ok) {
      flash(result.message || `HTTP ${res.status}`, 'warning')
      return
    }
    flash(result.message || 'Live Activity is now saving to a database.', 'success')
    showDatabaseInfo.value = false
    await loadActivity()
  } catch (err) {
    flash(formatLoadError(err, 'Could not switch Live Activity to a database'), 'danger')
  } finally {
    switchingToDatabase.value = false
  }
}

// The combined dataset the table renders: the live, always-refreshing head page plus any
// additional older pages paged in via "Load older". Degrades to exactly `report.entries` when no
// older page has been loaded (including always, for the default in-memory mode).
const combinedEntries = computed(() => mergeActivityPages(report.value?.entries, olderEntries.value))

const available = computed(() => report.value?.available ?? false)
const kpis = computed(() => report.value?.kpis ?? null)

// The slowest request links to its route's row in the HTTP Exchanges route summary, ranked by slowest
// request so the row is on screen. A server that predates route summaries sends no route id, so the link
// falls back to a path search.
const slowestEndpointLink = computed(() => {
  const k = kpis.value
  if (!k?.slowestEndpoint) return undefined
  return k.slowestEndpointRouteId
    ? {path: '/http-exchanges', query: {route: k.slowestEndpointRouteId, rank: 'maxDurationMs'}}
    : {path: '/http-exchanges', query: {q: k.slowestEndpoint}}
})

// The slowest route's time breakdown in Runtime Insights, when that panel can be opened.
const panels = inject('panels', ref(null))
const whySlowLink = computed(() => {
  const routeId = kpis.value?.slowestEndpointRouteId
  if (!routeId || !insightsUsable(panels.value)) return null
  return {path: '/runtime-insights', query: {q: routeId}}
})

const slowestEndpointTitle = computed(() => {
  const k = kpis.value
  if (!k?.slowestEndpoint) return null
  return k.slowestEndpointRouteId
    ? `Open ${k.slowestEndpointRouteId}, the route of the slowest request (${k.slowestEndpoint}), in HTTP Exchanges`
    : `Open ${k.slowestEndpoint} in HTTP Exchanges`
})
const sources = computed(() => report.value?.sources ?? [])
const warnings = computed(() => report.value?.warnings ?? [])

const hasActiveFilters = computed(
  () => !!typeFilter.value || !!severityFilter.value || !!textFilter.value.trim() || errorsOnly.value
)

// Collapsed parent ids in the nested view. Requests are expanded by default, so we only track the
// ones the developer has explicitly folded away.
const collapsed = ref(new Set())

const visibleEntries = computed(() => {
  const all = combinedEntries.value
  // While filtering/searching, keep the flat grouped list so the search spans every signal; nesting
  // would hide children whose parent request was filtered out. When persistent, the server has
  // already scoped `all` to these same filters (see `activityUrl`); re-applying them here is a
  // harmless no-op that keeps a single filtering code path for both modes.
  if (hasActiveFilters.value) {
    return groupEntries(
      filterEntries(all, {
        type: typeFilter.value,
        severity: severityFilter.value,
        text: textFilter.value,
        errorsOnly: errorsOnly.value
      })
    )
  }
  return nestEntries(all)
})

function hasChildren(entry) {
  return Array.isArray(entry.children) && entry.children.length > 0
}

function isCollapsed(id) {
  return collapsed.value.has(id)
}

function toggleExpand(id) {
  if (collapsed.value.has(id)) {
    collapsed.value.delete(id)
  } else {
    collapsed.value.add(id)
  }
}

// Requests-over-time mini timeline: bucket the unfiltered stream so spikes and error bursts stay
// visible even while a filter narrows the table below.
const SPARKLINE_BUCKETS = 32
const SPARKLINE_HEIGHT = 36
const sparkline = computed(() => bucketEntries(report.value?.entries ?? [], SPARKLINE_BUCKETS))
const sparklineMax = computed(() => sparkline.value.reduce((max, bucket) => Math.max(max, bucket.count), 0))
// BootUI actions, availability changes, refreshes, and shutdown, drawn on the same axis to explain a change in traffic.
const sparkMarkers = computed(() => markerPositions(report.value?.entries ?? []))
const sparkBars = computed(() => {
  const data = sparkline.value
  const max = sparklineMax.value
  if (!data.length || max <= 0) return []
  const slot = 100 / data.length
  return data.map((bucket, index) => {
    const height = (bucket.count / max) * SPARKLINE_HEIGHT
    return {
      key: index,
      x: index * slot,
      width: slot,
      height,
      y: SPARKLINE_HEIGHT - height,
      errorHeight: max > 0 ? (bucket.errors / max) * SPARKLINE_HEIGHT : 0,
      count: bucket.count,
      errors: bucket.errors
    }
  })
})

const subtitle = computed(() => {
  if (!manifestAvailable.value) return manifestUnavailableReason.value
  const counts = report.value?.typeCounts ?? {}
  const total = Object.values(counts).reduce((sum, value) => sum + value, 0)
  const base = `${formatNumber(total)} recent events · ${sources.value.length} source${sources.value.length === 1 ? '' : 's'}`
  return persistent.value ? `${base} · persisted history` : base
})

const paused = computed(() => !autoRefresh.value)

const timingSummary = computed(() => {
  const timing = profile.value?.timing
  if (!timing) return ''
  let text = `${timing.sqlCount} SQL statement(s), ${formatMillis(timing.sqlMs)} ms in SQL`
  if (timing.sqlPercent != null) {
    text += ` (${timing.sqlPercent}% of request)`
  }
  if (timing.restCallCount) {
    text += `, ${timing.restCallCount} REST client call(s), ${formatDurationMs(timing.restCallMs)} outbound`
  }
  return text
})

// Per-section correlation metadata (tier, availability, truncation). Empty for an older server's profile.
const sections = computed(() => profileSections(profile.value))
const tiersNote = computed(() => unavailableTiersText(profile.value))

function togglePause() {
  autoRefresh.value = !autoRefresh.value
}

function typeIcon(type) {
  return (
    {
      REQUEST: 'bi-arrow-left-right',
      SQL: 'bi-database',
      REST_CLIENT: 'bi-globe2',
      EXCEPTION: 'bi-exclamation-octagon',
      SECURITY: 'bi-shield-lock',
      CACHE: 'bi-lightning-charge',
      SCHEDULED: 'bi-clock-history',
      MESSAGING: 'bi-diagram-3',
      MAIL: 'bi-envelope',
      FAULT_TOLERANCE: 'bi-shield-check',
      TRANSACTION: 'bi-arrow-repeat',
      AI: 'bi-cpu',
      LOG: 'bi-journal-text',
      MARKER: 'bi-flag',
      APP_EVENT: 'bi-broadcast',
      WEBSOCKET: 'bi-plug',
      ORM: 'bi-layers',
      ASYNC: 'bi-signpost-split'
    }[type] || 'bi-dot'
  )
}

// An ASYNC entry's state (docs/PLAN-v2.md M5-2): a task the BootUI agent propagated from its request to an executor.
const ENTRY_BADGES = {
  AFTER_RESPONSE: {
    label: 'after response',
    title: 'Still running once the response started',
    className: 'text-bg-warning'
  },
  RUNNING: {label: 'running', title: 'Still running now', className: 'text-bg-info'},
  CAPPED: {
    label: 'past deadline',
    title:
      'Ended more than bootui.agent.executors.max-handoff after it started: its later work is not attributed to the request',
    className: 'text-bg-secondary'
  }
}

function entryBadges(entry) {
  return (entry?.badges ?? []).map((badge) => ({
    id: badge,
    ...(ENTRY_BADGES[badge] ?? {label: badge, title: badge, className: 'text-bg-light'})
  }))
}

function severityBadgeClass(severity) {
  return (
    {
      OK: 'text-bg-success',
      SLOW: 'text-bg-warning',
      WARN: 'text-bg-warning',
      ERROR: 'text-bg-danger'
    }[severity] || 'text-bg-secondary'
  )
}

function rowClass(entry) {
  if (entry.type === 'REQUEST') {
    if (entry.severity === 'ERROR') return 'table-danger'
    const level = slowLevel(entry)
    if (level > 0) return `activity-slow-${level}`
    if (entry.severity === 'WARN') return 'table-warning'
    return ''
  }
  if (entry.severity === 'ERROR') return 'table-danger'
  if (entry.severity === 'SLOW' || entry.severity === 'WARN') return 'table-warning'
  return ''
}

// Graduated latency heat for HTTP requests: 100/200/500/1000 ms map to four levels that the row and
// duration badge color from yellow to bright red, so slow requests stand out by how slow they are.
function slowLevel(entry) {
  if (entry.type !== 'REQUEST' || entry.durationMs == null) return 0
  const ms = entry.durationMs
  if (ms >= 1000) return 4
  if (ms >= 500) return 3
  if (ms >= 200) return 2
  if (ms >= 100) return 1
  return 0
}

function latencyBadgeClass(entry) {
  return `activity-lat-${slowLevel(entry)}`
}

function formatDurationMs(durationMs) {
  if (durationMs == null) return ''
  if (durationMs < 1) return '<1 ms'
  if (durationMs < 1000) return `${durationMs} ms`
  return `${(durationMs / 1000).toFixed(2)} s`
}

function entryLink(entry) {
  return deepLink(entry)
}

function onRowClick(entry, event) {
  if (entry.profileable) {
    openProfile(entry, event.currentTarget instanceof HTMLElement ? event.currentTarget : null)
  }
}

function openProfileFromButton(entry, event) {
  openProfile(entry, event.currentTarget instanceof HTMLElement ? event.currentTarget : null)
}

async function openProfile(
  entry,
  opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
) {
  if (!entry.profileable) return
  await loadProfile(entry.id, opener, entry.type === 'SCHEDULED' || (entry.type === 'MESSAGING' && fromJournal.value))
}

async function loadProfile(id, opener, execution = false) {
  profileOpenerEl.value =
    opener?.matches?.('button, a, input, select, textarea, [tabindex]:not([tabindex="-1"])') === true
      ? opener
      : opener?.querySelector?.('.bootui-keyboard-target') || null
  profileRequestId.value = id
  const token = ++profileLoadToken
  const isCurrent = () => token === profileLoadToken
  profileLoading.value = true
  profileError.value = null
  profile.value = null
  journalProfile.value = null
  aiExport.value = null
  try {
    if (!execution) {
      const response = await apiFetch(`api/activity/request/${encodeURIComponent(id)}`)
      if (!isCurrent()) return
      if (!response.ok) {
        throw new Error(`Request failed with status ${response.status}`)
      }
      const loaded = await response.json()
      if (!isCurrent()) return
      profile.value = loaded
    }
    await loadJournalProfile(profile.value?.request?.requestId || id, isCurrent)
  } catch (err) {
    if (!isCurrent()) return
    await loadJournalProfile(id, isCurrent)
    if (isCurrent() && !journalProfile.value?.available) {
      profileError.value = err.message || 'Could not load request profile'
    }
  } finally {
    if (isCurrent()) {
      profileLoading.value = false
      focusDrawer()
    }
  }
}

async function loadJournalProfile(requestId, isCurrent) {
  try {
    const response = await apiFetch(`api/activity/request/${encodeURIComponent(requestId)}/journal`)
    if (!isCurrent()) return
    if (!response.ok) throw new Error(`status ${response.status}`)
    const loaded = await response.json()
    if (isCurrent()) journalProfile.value = loaded
  } catch (err) {
    if (!isCurrent()) return
    journalProfile.value = {
      available: false,
      unavailableReason: `Could not load the runtime journal's record of this request (${err.message}).`
    }
  }
}

function closeProfile() {
  profileLoadToken++
  profileLoading.value = false
  const opener = profileOpenerEl.value
  profileRequestId.value = null
  profile.value = null
  journalProfile.value = null
  profileError.value = null
  profileOpenerEl.value = null
  aiExport.value = null
  requestAnimationFrame(() => opener?.focus?.())
}

function focusDrawer() {
  requestAnimationFrame(() => {
    drawerEl.value?.focus?.()
  })
}

function onKeydown(event) {
  if (!profileRequestId.value) return
  if (event.key === 'Escape') {
    closeProfile()
    return
  }
  if (event.key === 'Tab') {
    trapFocus(event)
  }
}

// Minimal focus trap so keyboard users cannot tab out of the open drawer.
function trapFocus(event) {
  const root = drawerEl.value
  if (!root) return
  const focusable = root.querySelectorAll(
    'a[href], button:not([disabled]), input, select, textarea, [tabindex]:not([tabindex="-1"])'
  )
  if (!focusable.length) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  const active = document.activeElement
  if (event.shiftKey && (active === first || active === root)) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && active === last) {
    event.preventDefault()
    first.focus()
  }
}

function copyProfile() {
  if (!profile.value?.available) return
  copyToClipboard(profileMarkdown(profile.value).markdown, 'profile')
}

// "Copy for AI" previews the profile's Markdown with each correlated exception's stack trace and recent
// occurrences, loaded through the existing Exceptions read endpoint. Copying the preview sends nothing.
async function openAiExport() {
  const current = profile.value
  if (!current?.available) return
  aiExport.value = {loading: true, error: null, markdown: '', omissions: []}
  try {
    const {exceptionDetails, omissions} = await loadProfileExceptionDetails(current, getJson)
    if (profile.value !== current) return
    aiExport.value = {loading: false, error: null, ...profileMarkdown(current, {exceptionDetails, omissions})}
  } catch (err) {
    if (profile.value !== current) return
    aiExport.value = {
      loading: false,
      error: formatLoadError(err, 'Could not prepare the export'),
      markdown: '',
      omissions: []
    }
  }
}

function closeAiExport() {
  aiExport.value = null
  focusDrawer()
}

function restoreFilters() {
  const saved = safeLocalStorage.getJson(FILTERS_STORAGE_KEY, null)
  if (!saved || Array.isArray(saved) || typeof saved !== 'object') return
  if (saved.type === '' || TYPES.includes(saved.type)) typeFilter.value = saved.type
  if (saved.severity === '' || SEVERITIES.includes(saved.severity)) severityFilter.value = saved.severity
  if (typeof saved.text === 'string') textFilter.value = saved.text
  if (typeof saved.errorsOnly === 'boolean') errorsOnly.value = saved.errorsOnly
  if (['', 'journal', 'buffers'].includes(saved.source)) feedSource.value = saved.source
}

function persistFilters() {
  return safeLocalStorage.setJson(FILTERS_STORAGE_KEY, {
    type: typeFilter.value,
    severity: severityFilter.value,
    text: textFilter.value,
    errorsOnly: errorsOnly.value,
    source: feedSource.value
  })
}

// A filter change invalidates any accumulated "older" pages (they were queried under the old
// filters), and — only when persistent — needs a fresh server-side query so filtering/search reach
// the full durable history, not just the currently loaded window. Debounced so typing in the
// free-text box doesn't fire a request per keystroke; the default in-memory mode never reaches the
// `persistent` branch, so it keeps filtering purely client-side with no network calls, unchanged.
let filterReloadTimer = null

watch([typeFilter, severityFilter, textFilter, errorsOnly], () => {
  persistFilters()
  if (!persistent.value) return
  olderEntries.value = []
  olderPageInfo.value = null
  if (filterReloadTimer) clearTimeout(filterReloadTimer)
  filterReloadTimer = setTimeout(refreshNow, 300)
})

// The journal's filters run on the server, so changing one reloads the feed.
watch([feedSource, routeFilter, requestIdFilter, runFilter, noRequestOnly], () => {
  persistFilters()
  olderEntries.value = []
  olderPageInfo.value = null
  if (filterReloadTimer) clearTimeout(filterReloadTimer)
  filterReloadTimer = setTimeout(refreshNow, 300)
})

const hasJournalFilters = computed(
  () =>
    fromJournal.value &&
    (!!routeFilter.value.trim() || !!requestIdFilter.value.trim() || !!runFilter.value.trim() || noRequestOnly.value)
)

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  // HTTP Exchanges links each exchange here with ?request=<exchange id>. The profile endpoint answers
  // honestly for any id, including one that is no longer retained or carries no trace id.
  const linkedRequest = route?.query?.request
  if (typeof linkedRequest === 'string' && linkedRequest) {
    loadProfile(linkedRequest, null)
  }
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  if (filterReloadTimer) clearTimeout(filterReloadTimer)
})

function clearFilters() {
  typeFilter.value = ''
  severityFilter.value = ''
  textFilter.value = ''
  errorsOnly.value = false
  routeFilter.value = ''
  requestIdFilter.value = ''
  runFilter.value = ''
  noRequestOnly.value = false
}

function toggleFlow() {
  flowCollapsed.value = !flowCollapsed.value
  safeLocalStorage.setItem(FLOW_COLLAPSED_STORAGE_KEY, String(flowCollapsed.value))
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-broadcast"
      title="Live Activity"
      :subtitle="subtitle"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      auto-refresh-title="Stream new activity live over Server-Sent Events while this tab is visible"
      :auto-refresh-state="connectionState"
      @refresh="refreshNow"
      @retry-auto-refresh="retryConnection"
    >
      <template #subtitle-actions>
        <span v-if="report && !persistent">
          Currently saving {{ formatNumber(memoryEventCount) }} event{{ memoryEventCount === 1 ? '' : 's' }} in memory
        </span>
        <button
          v-if="report && !persistent"
          type="button"
          class="btn btn-outline-secondary btn-sm"
          :aria-expanded="showDatabaseInfo"
          @click="toggleDatabaseInfo"
        >
          <i class="bi bi-database-add me-1"></i>Use a database
        </button>
        <button
          v-if="report"
          type="button"
          class="btn btn-outline-secondary btn-sm"
          aria-controls="activity-runtime-journal"
          :aria-expanded="showJournal"
          @click="showJournal = !showJournal"
        >
          <i class="bi bi-journal-text me-1" aria-hidden="true"></i>Recording
        </button>
        <button
          v-if="report"
          type="button"
          class="btn btn-outline-secondary btn-sm"
          aria-controls="activity-runtime-resources"
          :aria-expanded="showResources"
          @click="showResources = !showResources"
        >
          <i class="bi bi-cpu me-1" aria-hidden="true"></i>Resources
        </button>
      </template>
    </PanelHeader>

    <FlashBanner :message="banner" @dismiss="clearBanner" />

    <RuntimeJournalStatus
      v-if="showJournal"
      id="activity-runtime-journal"
      :read-only="readOnly"
      :read-only-reason="readOnlyReason"
      @flash="flash"
    />

    <RuntimeResources v-if="showResources" id="activity-runtime-resources" />

    <div
      v-if="showDatabaseInfo && report && !persistent"
      class="alert alert-info activity-database-info d-flex align-items-start gap-2 mb-3"
    >
      <i class="bi bi-database-add fs-4 flex-shrink-0"></i>
      <div class="flex-grow-1 small">
        <strong class="d-block mb-1">Store Live Activity in a database</strong>
        <p class="mb-2">
          By default, Live Activity buffers the last {{ formatNumber(memoryEventCount) }} event{{
            memoryEventCount === 1 ? '' : 's'
          }}
          in memory only, and history is lost on restart. Storing entries in a database keeps them across restarts and
          lets you page back further.
        </p>
        <template v-if="dataSourceAvailable">
          <p class="mb-2">
            A <code>DataSource</code> is already configured in this application. You can configure a dedicated, second
            datasource just for Live Activity, or reuse the existing one right now.
          </p>
          <div class="d-flex flex-wrap align-items-center gap-2">
            <SpinnerButton
              :loading="switchingToDatabase"
              :disabled="readOnly || switchingToDatabase"
              :title="readOnly ? readOnlyReason : 'Switch this running instance to the existing datasource'"
              class="btn btn-sm btn-outline-primary"
              icon="bi-database-up"
              label="Use the existing datasource"
              @click="useExistingDatasource"
            />
            <a :href="PERSISTENCE_DOCS_URL" class="small" rel="noopener noreferrer" target="_blank">
              View setup documentation <i class="bi bi-box-arrow-up-right"></i>
            </a>
          </div>
        </template>
        <template v-else>
          <p class="mb-2">
            No <code>DataSource</code> bean was found in this application. Configure one and set
            <code>bootui.activity.persistence.enabled=true</code> (or add a dedicated JDBC URL) to store Live Activity
            durably.
          </p>
          <a :href="PERSISTENCE_DOCS_URL" class="small" rel="noopener noreferrer" target="_blank">
            View setup documentation <i class="bi bi-box-arrow-up-right"></i>
          </a>
        </template>
      </div>
      <button type="button" class="btn-close" aria-label="Close" @click="showDatabaseInfo = false"></button>
    </div>

    <UnavailableState v-if="!manifestAvailable" icon="bi-broadcast" :message="manifestUnavailableReason" />

    <PanelSkeleton v-else-if="initialLoading && !report" :rows="8" />

    <template v-else-if="report">
      <UnavailableState
        v-if="!available"
        icon="bi-broadcast"
        message="No live activity sources are available yet. Enable HTTP exchange recording, SQL tracing, REST client tracing, exception capture, or security logs to populate this stream."
      />

      <div v-if="available && kpis" class="row g-2 mb-3 activity-kpis">
        <div class="col-6 col-lg-3">
          <div class="card h-100">
            <div class="card-body py-2">
              <div class="text-muted small">Requests/min</div>
              <div class="fs-5">{{ formatNumber(kpis.requestsPerMinute) }}</div>
            </div>
          </div>
        </div>
        <div class="col-6 col-lg-3">
          <div class="card h-100">
            <div class="card-body py-2">
              <div class="text-muted small">Error rate</div>
              <div class="fs-5">{{ kpis.errorRatePercent }}%</div>
            </div>
          </div>
        </div>
        <div class="col-6 col-lg-3">
          <div class="card h-100">
            <div class="card-body py-2">
              <div class="text-muted small">Latency p50 / p95</div>
              <div class="fs-5">{{ kpis.p50LatencyMs ?? '—' }} / {{ kpis.p95LatencyMs ?? '—' }} ms</div>
              <div v-if="kpis.latencySampleCount != null" class="text-muted small activity-kpi-latency-samples">
                over {{ formatNumber(kpis.latencySampleCount) }} retained
                {{ kpis.latencySampleCount === 1 ? 'request' : 'requests' }}
              </div>
            </div>
          </div>
        </div>
        <div class="col-6 col-lg-3">
          <div class="card h-100">
            <div class="card-body py-2">
              <div class="text-muted small">SQL/min</div>
              <div class="fs-5">{{ formatNumber(kpis.sqlPerMinute) }}</div>
            </div>
          </div>
        </div>
        <div class="col-6 col-lg-3">
          <router-link
            class="card h-100 text-reset text-decoration-none activity-kpi-link"
            :to="{path: '/rest-client-trace'}"
            title="Open the REST Client panel"
          >
            <div class="card-body py-2">
              <div class="text-muted small">
                Outbound errors / p95
                <i class="bi bi-box-arrow-up-right ms-1"></i>
              </div>
              <div class="fs-5">
                {{ kpis.restCallErrorRatePercent ?? '—' }}% / {{ kpis.restCallP95LatencyMs ?? '—' }} ms
              </div>
            </div>
          </router-link>
        </div>
        <div class="col-6 col-lg-3">
          <component
            :is="kpis.slowestEndpoint ? 'router-link' : 'div'"
            class="card h-100 text-reset text-decoration-none activity-kpi-slowest"
            :class="{'activity-kpi-link': kpis.slowestEndpoint}"
            :to="kpis.slowestEndpoint ? slowestEndpointLink : undefined"
            :title="kpis.slowestEndpoint ? slowestEndpointTitle : null"
          >
            <div class="card-body py-2">
              <div class="text-muted small">
                Slowest endpoint
                <i v-if="kpis.slowestEndpoint" class="bi bi-box-arrow-up-right ms-1"></i>
              </div>
              <div class="fs-5 text-truncate">
                <template v-if="kpis.slowestEndpoint">
                  {{ kpis.slowestEndpointMs ?? '—' }} ms
                  <span class="text-muted small d-block text-truncate activity-kpi-slowest-route">
                    {{ kpis.slowestEndpointRouteId ?? kpis.slowestEndpoint }}
                  </span>
                </template>
                <template v-else>—</template>
              </div>
            </div>
          </component>
        </div>
        <div class="col-6 col-lg-3">
          <router-link
            class="card h-100 text-reset text-decoration-none activity-kpi-link"
            :to="{path: '/exceptions'}"
            title="Open the Exceptions panel"
          >
            <div class="card-body py-2">
              <div class="text-muted small">
                Active exceptions
                <i class="bi bi-box-arrow-up-right ms-1"></i>
              </div>
              <div class="fs-5">{{ formatNumber(kpis.activeExceptionCount) }}</div>
            </div>
          </router-link>
        </div>
        <div class="col-6 col-lg-3">
          <router-link
            class="card h-100 text-reset text-decoration-none activity-kpi-link"
            :to="{path: '/health'}"
            title="Open the Health panel"
          >
            <div class="card-body py-2">
              <div class="text-muted small">
                Health
                <i class="bi bi-box-arrow-up-right ms-1"></i>
              </div>
              <div class="fs-5">{{ kpis.healthStatus ?? '—' }}</div>
            </div>
          </router-link>
        </div>
        <div class="col-6 col-lg-3">
          <router-link
            class="card h-100 text-reset text-decoration-none activity-kpi-link"
            :to="{path: '/heap-dump'}"
            title="Open the Heap Dump panel"
          >
            <div class="card-body py-2">
              <div class="text-muted small">
                Heap used
                <i class="bi bi-box-arrow-up-right ms-1"></i>
              </div>
              <div class="fs-5">{{ formatBytes(kpis.heapUsedBytes) }}</div>
            </div>
          </router-link>
        </div>
        <div class="col-6 col-lg-3">
          <router-link
            class="card h-100 text-reset text-decoration-none activity-kpi-link"
            :to="{path: '/cache'}"
            title="Open the Cache panel"
          >
            <div class="card-body py-2">
              <div class="text-muted small">
                Cache hit ratio
                <i class="bi bi-box-arrow-up-right ms-1"></i>
              </div>
              <div class="fs-5">
                {{ kpis.cacheHitRatioPercent != null ? `${kpis.cacheHitRatioPercent}%` : '—' }}
              </div>
            </div>
          </router-link>
        </div>
        <div class="col-6 col-lg-3">
          <router-link
            class="card h-100 text-reset text-decoration-none activity-kpi-link"
            :to="{path: '/scheduled'}"
            title="Open the Scheduled Tasks panel"
          >
            <div class="card-body py-2">
              <div class="text-muted small">
                Scheduled failures
                <i class="bi bi-box-arrow-up-right ms-1"></i>
              </div>
              <div class="fs-5">{{ formatNumber(kpis.scheduledTaskFailureCount ?? 0) }}</div>
            </div>
          </router-link>
        </div>
      </div>
      <p v-if="available && whySlowLink" class="small mb-3 activity-why-slow">
        <router-link :to="whySlowLink">
          Why is <code>{{ kpis.slowestEndpointRouteId }}</code> slow? Open its time breakdown in Runtime Insights
        </router-link>
      </p>

      <section class="activity-flow mb-3" aria-labelledby="activity-flow-title">
        <div class="activity-flow__header">
          <div>
            <h3 id="activity-flow-title" class="h5 mb-1">
              <i class="bi bi-diagram-2 me-2" aria-hidden="true"></i>Live flow
            </h3>
            <p class="text-muted small mb-0">
              A live service map assembled from the same retained activity as the feed.
            </p>
          </div>
          <button
            type="button"
            class="btn btn-sm btn-outline-secondary"
            :aria-expanded="!flowCollapsed"
            aria-controls="activity-live-flow"
            @click="toggleFlow"
          >
            <i :class="['bi', flowCollapsed ? 'bi-chevron-down' : 'bi-chevron-up', 'me-1']" aria-hidden="true"></i>
            {{ flowCollapsed ? 'Show map' : 'Minimize map' }}
          </button>
        </div>
        <div id="activity-live-flow" v-show="!flowCollapsed" class="activity-flow__body">
          <LiveFlowMode :refresh-tick="flowRefreshTick" :paused="paused" :visible="!flowCollapsed" />
        </div>
      </section>

      <template v-if="available">
        <div class="activity-feed-controls d-flex flex-wrap align-items-end gap-2 mb-3">
          <div class="activity-text-filter">
            <label class="form-label small mb-1" for="activity-text-filter">Filter</label>
            <input
              id="activity-text-filter"
              v-model="textFilter"
              type="search"
              class="form-control form-control-sm"
              placeholder="Path, status, SQL, exception…"
            />
          </div>
          <div>
            <label class="form-label small mb-1" for="activity-type-filter">Type</label>
            <select id="activity-type-filter" v-model="typeFilter" class="form-select form-select-sm">
              <option value="">All types</option>
              <option v-for="type in TYPES" :key="type" :value="type">{{ type }}</option>
            </select>
          </div>
          <div>
            <label class="form-label small mb-1" for="activity-severity-filter">Severity</label>
            <select id="activity-severity-filter" v-model="severityFilter" class="form-select form-select-sm">
              <option value="">All severities</option>
              <option v-for="severity in SEVERITIES" :key="severity" :value="severity">{{ severity }}</option>
            </select>
          </div>
          <div class="form-check mb-1">
            <input id="activity-errors-only" v-model="errorsOnly" class="form-check-input" type="checkbox" />
            <label class="form-check-label small" for="activity-errors-only">Errors only</label>
          </div>
          <div>
            <label class="form-label small mb-1" for="activity-feed-source">Recorded by</label>
            <select id="activity-feed-source" v-model="feedSource" class="form-select form-select-sm">
              <option value="">Default</option>
              <option value="journal">Runtime journal</option>
              <option value="buffers">Panel buffers</option>
            </select>
          </div>
          <p v-if="fromJournal && persistent" class="small text-muted mb-0" role="note">
            Route, request, run, and no-request filters apply to the in-memory runtime journal; persisted history keeps
            no run or request grouping.
          </p>
          <template v-if="fromJournal && !persistent">
            <div>
              <label class="form-label small mb-1" for="activity-route-filter">Route</label>
              <input
                id="activity-route-filter"
                v-model="routeFilter"
                type="search"
                class="form-control form-control-sm"
                placeholder="GET /api/orders/{id}"
              />
            </div>
            <div>
              <label class="form-label small mb-1" for="activity-request-filter">Request id</label>
              <input
                id="activity-request-filter"
                v-model="requestIdFilter"
                type="search"
                class="form-control form-control-sm"
                placeholder="Request id"
              />
            </div>
            <div>
              <label class="form-label small mb-1" for="activity-run-filter">Run id</label>
              <input
                id="activity-run-filter"
                v-model="runFilter"
                type="search"
                class="form-control form-control-sm"
                placeholder="Run id (see Runtime journal)"
              />
            </div>
            <div class="form-check mb-1">
              <input id="activity-no-request" v-model="noRequestOnly" class="form-check-input" type="checkbox" />
              <label class="form-check-label small" for="activity-no-request">No request</label>
            </div>
          </template>
          <button
            v-if="hasActiveFilters || hasJournalFilters"
            class="btn btn-sm btn-outline-secondary"
            type="button"
            @click="clearFilters"
          >
            Clear
          </button>
          <div class="ms-auto">
            <button
              class="btn btn-sm"
              :class="paused ? 'btn-success' : 'btn-outline-secondary'"
              type="button"
              @click="togglePause"
            >
              <i :class="['bi', paused ? 'bi-play-fill' : 'bi-pause-fill', 'me-1']"></i>
              {{ paused ? 'Resume' : 'Pause' }}
            </button>
          </div>
        </div>

        <div v-for="warning in warnings" :key="warning" class="alert alert-warning py-2 small" role="alert">
          {{ warning }}
        </div>

        <figure v-if="sparkBars.length" class="activity-sparkline mb-3" aria-hidden="true">
          <figcaption class="text-muted small mb-1">
            Events over time (red = errors<template v-if="sparkMarkers.length"
              >, lines = BootUI actions and lifecycle changes</template
            >)
          </figcaption>
          <svg viewBox="0 0 100 36" preserveAspectRatio="none" class="w-100 activity-sparkline-svg">
            <g v-for="bar in sparkBars" :key="bar.key">
              <rect :x="bar.x" :y="bar.y" :width="bar.width" :height="bar.height" class="activity-spark-bar">
                <title>{{ bar.count }} events, {{ bar.errors }} errors</title>
              </rect>
              <rect
                v-if="bar.errors"
                :x="bar.x"
                :y="36 - bar.errorHeight"
                :width="bar.width"
                :height="bar.errorHeight"
                class="activity-spark-error"
              />
            </g>
            <line
              v-for="marker in sparkMarkers"
              :key="marker.key"
              :x1="marker.x"
              :x2="marker.x"
              y1="0"
              y2="36"
              class="activity-spark-marker"
              vector-effect="non-scaling-stroke"
            >
              <title>{{ marker.label }}</title>
            </line>
          </svg>
        </figure>

        <div class="table-responsive">
          <table class="table table-sm align-middle activity-table">
            <colgroup>
              <col class="activity-col-time" />
              <col class="activity-col-type" />
              <col class="activity-col-severity" />
              <col class="activity-col-summary" />
              <col class="activity-col-duration" />
              <col class="activity-col-actions" />
            </colgroup>
            <thead>
              <tr>
                <th class="text-nowrap">Time</th>
                <th>Type</th>
                <th>Severity</th>
                <th>Activity</th>
                <th class="text-end text-nowrap">Duration</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <template v-for="entry in visibleEntries" :key="entry.id">
                <tr
                  :class="[
                    rowClass(entry),
                    entry.profileable ? 'activity-row-clickable' : '',
                    hasChildren(entry) ? 'activity-parent-row' : ''
                  ]"
                  data-keyboard-delegate="openProfileFromButton(entry, $event)"
                  @click="onRowClick(entry, $event)"
                >
                  <td class="text-nowrap small">{{ formatClockTime(entry.timestamp) }}</td>
                  <td class="text-nowrap"><i :class="['bi', typeIcon(entry.type), 'me-1']"></i>{{ entry.type }}</td>
                  <td>
                    <span :class="['badge', severityBadgeClass(entry.severity)]">{{ entry.severity }}</span>
                  </td>
                  <td class="activity-summary-cell">
                    <button
                      v-if="hasChildren(entry)"
                      class="btn btn-link btn-sm p-0 me-2 align-baseline activity-disclosure"
                      type="button"
                      :aria-expanded="!isCollapsed(entry.id)"
                      :title="isCollapsed(entry.id) ? 'Expand correlated signals' : 'Collapse correlated signals'"
                      @click.stop="toggleExpand(entry.id)"
                    >
                      <i :class="['bi', isCollapsed(entry.id) ? 'bi-chevron-right' : 'bi-chevron-down']"></i>
                    </button>
                    <i
                      v-if="entry.securedPrincipal != null"
                      class="bi bi-lock-fill text-secondary me-1"
                      title="Authenticated request"
                    ></i>
                    <span>{{ entry.summary }}</span>
                    <span
                      v-if="entry.securedPrincipal"
                      class="badge rounded-pill text-bg-light ms-2 activity-principal-tag"
                      :title="`Authenticated as ${entry.securedPrincipal}`"
                      ><i class="bi bi-person me-1"></i>{{ entry.securedPrincipal }}</span
                    >
                    <span v-if="entry.count > 1" class="badge rounded-pill text-bg-light ms-2">×{{ entry.count }}</span>
                    <span
                      v-if="hasChildren(entry)"
                      class="badge rounded-pill text-bg-light ms-2"
                      title="Correlated SQL, exceptions and security events"
                      >+{{ entry.children.length }}</span
                    >
                    <span
                      v-if="entry.sqlNPlusOneSuspected"
                      class="badge rounded-pill text-bg-danger ms-2"
                      title="Correlated SQL includes a suspected N+1 query pattern — open the profile for details"
                      >N+1</span
                    >
                    <span
                      v-for="badge in entryBadges(entry)"
                      :key="badge.id"
                      :class="['badge', 'rounded-pill', 'ms-2', 'activity-entry-badge', badge.className]"
                      :title="badge.title"
                      >{{ badge.label }}</span
                    >
                    <span v-if="entry.detail" class="d-block text-muted small">{{ entry.detail }}</span>
                  </td>
                  <td class="text-end text-nowrap small">
                    <span v-if="slowLevel(entry) > 0" :class="['badge', latencyBadgeClass(entry)]">{{
                      formatDurationMs(entry.durationMs)
                    }}</span>
                    <template v-else>{{ formatDurationMs(entry.durationMs) }}</template>
                  </td>
                  <td class="text-end text-nowrap" @click.stop>
                    <router-link
                      v-if="entryLink(entry)"
                      :to="{path: entryLink(entry).path, query: entryLink(entry).query}"
                      class="btn btn-outline-secondary btn-sm rounded-pill me-1"
                      :title="entryLink(entry).label"
                    >
                      <i class="bi bi-box-arrow-up-right"></i>
                    </router-link>
                    <button
                      v-if="entry.profileable"
                      class="btn btn-outline-primary btn-sm rounded-pill bootui-keyboard-target"
                      type="button"
                      @click="openProfileFromButton(entry, $event)"
                    >
                      <i class="bi bi-search me-1"></i>Profile
                    </button>
                  </td>
                </tr>
                <tr
                  v-for="child in hasChildren(entry) && !isCollapsed(entry.id) ? entry.children : []"
                  :key="child.id"
                  :class="['activity-child-row']"
                >
                  <td class="text-nowrap small">{{ formatClockTime(child.timestamp) }}</td>
                  <td class="text-nowrap activity-child-type">
                    <i :class="['bi', typeIcon(child.type), 'me-1']"></i>{{ child.type }}
                  </td>
                  <td>
                    <span :class="['badge', severityBadgeClass(child.severity)]">{{ child.severity }}</span>
                  </td>
                  <td class="activity-summary-cell">
                    <span>{{ child.summary }}</span>
                    <span
                      v-for="badge in entryBadges(child)"
                      :key="badge.id"
                      :class="['badge', 'rounded-pill', 'ms-2', 'activity-entry-badge', badge.className]"
                      :title="badge.title"
                      >{{ badge.label }}</span
                    >
                    <span v-if="child.detail" class="d-block text-muted small">{{ child.detail }}</span>
                  </td>
                  <td class="text-end text-nowrap small">{{ formatDurationMs(child.durationMs) }}</td>
                  <td class="text-end text-nowrap" @click.stop>
                    <router-link
                      v-if="entryLink(child)"
                      :to="{path: entryLink(child).path, query: entryLink(child).query}"
                      class="btn btn-outline-secondary btn-sm rounded-pill"
                      :title="entryLink(child).label"
                    >
                      <i class="bi bi-box-arrow-up-right"></i>
                    </router-link>
                  </td>
                </tr>
              </template>
              <tr v-if="!visibleEntries.length">
                <td v-if="hasActiveFilters || hasJournalFilters" colspan="6" class="text-center text-muted py-4">
                  No activity matches the current filters.
                </td>
                <td v-else colspan="6" class="text-center text-muted py-4">
                  No activity recorded yet. Send a request to the application, and its requests, SQL, exceptions, and
                  security events appear here.
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="canLoadOlder || loadingOlder" class="text-center my-3">
          <button class="btn btn-sm btn-outline-secondary" type="button" :disabled="loadingOlder" @click="loadOlder">
            <span
              v-if="loadingOlder"
              class="spinner-border spinner-border-sm me-1"
              role="status"
              aria-hidden="true"
            ></span>
            {{ loadingOlder ? 'Loading…' : 'Load older activity' }}
          </button>
        </div>
      </template>
    </template>

    <!-- Per-request profiler drawer -->
    <div v-if="profileRequestId" class="activity-drawer-backdrop" @click.self="closeProfile">
      <aside
        ref="drawerEl"
        class="activity-drawer card shadow"
        tabindex="-1"
        role="dialog"
        aria-modal="true"
        aria-label="Request profile"
      >
        <div class="card-header d-flex align-items-center justify-content-between">
          <h2 class="h6 mb-0">Request profile</h2>
          <div class="d-flex align-items-center gap-2">
            <template v-if="profile && profile.available && !aiExport">
              <button class="btn btn-sm btn-outline-secondary activity-copy-ai" type="button" @click="openAiExport">
                <i class="bi bi-stars me-1" aria-hidden="true"></i>Copy for AI
              </button>
              <button class="btn btn-sm btn-outline-secondary" type="button" @click="copyProfile">
                <i class="bi bi-clipboard me-1" aria-hidden="true"></i
                >{{ copiedKey === 'profile' ? 'Copied' : 'Copy profile' }}
              </button>
            </template>
            <button class="btn-close" type="button" aria-label="Close" @click="closeProfile"></button>
          </div>
        </div>
        <div class="card-body activity-drawer-body">
          <div v-if="profileLoading" class="text-muted">Loading…</div>
          <div v-else-if="profileError" class="alert alert-danger">{{ profileError }}</div>
          <AiExportPreview
            v-else-if="aiExport"
            :error="aiExport.error"
            :loading="aiExport.loading"
            :markdown="aiExport.markdown"
            :omissions="aiExport.omissions"
            heading="Copy profile for AI"
            @close="closeAiExport"
          />
          <div v-else-if="!profile?.available">
            <section v-if="journalProfile?.available" class="mb-3">
              <h3 class="h6">
                {{
                  journalProfile.route?.startsWith('Scheduled:') || journalProfile.route?.startsWith('Message:')
                    ? 'Execution'
                    : 'Request'
                }}
              </h3>
              <dl class="row small mb-0">
                <dt class="col-4">Route</dt>
                <dd class="col-8">
                  <code>{{ journalProfile.route }}</code>
                </dd>
                <template v-if="journalProfile.status != null">
                  <dt class="col-4">Status</dt>
                  <dd class="col-8">{{ journalProfile.status }}</dd>
                </template>
                <dt class="col-4">Duration</dt>
                <dd class="col-8">{{ formatDurationMs(journalProfile.durationMicros / 1000) }}</dd>
              </dl>
            </section>
            <p v-if="profile?.unavailableReason && journalProfile?.status != null" class="small text-muted">
              HTTP-exchange details unavailable: {{ profile.unavailableReason }}
            </p>
            <div v-if="profile?.unavailableReason && !journalProfile?.available" class="alert alert-warning">
              {{ profile.unavailableReason }}
            </div>
            <RequestJournalProfile v-if="journalProfile" :profile="journalProfile" />
            <RequestCodePath :request-id="journalProfile?.requestId || profileRequestId" />
          </div>
          <div v-else-if="profile">
            <section class="mb-3">
              <h3 class="h6">Request</h3>
              <dl class="row small mb-0">
                <dt class="col-4">Method · Path</dt>
                <dd class="col-8">
                  <code>{{ profile.request.method }} {{ profile.request.path }}</code>
                </dd>
                <dt class="col-4">Status</dt>
                <dd class="col-8">{{ profile.request.status }}</dd>
                <dt class="col-4">Duration</dt>
                <dd class="col-8">{{ formatDurationMs(profile.request.durationMs) }}</dd>
                <template v-if="profile.request.principal">
                  <dt class="col-4">Principal</dt>
                  <dd class="col-8">{{ profile.request.principal }}</dd>
                </template>
                <template v-if="profile.request.traceId">
                  <dt class="col-4">Trace id</dt>
                  <dd class="col-8">
                    <code>{{ profile.request.traceId }}</code>
                  </dd>
                </template>
              </dl>
            </section>

            <section v-if="profile.timing" class="mb-3">
              <h3 class="h6">Timing</h3>
              <p class="small mb-1">{{ timingSummary }}</p>
            </section>

            <RequestJournalProfile v-if="journalProfile" :profile="journalProfile" />
            <RequestCodePath :request-id="journalProfile?.requestId || profileRequestId" />

            <p v-if="profile.approximate" class="alert alert-secondary small py-2 mb-3" role="note">
              <i class="bi bi-info-circle me-1" aria-hidden="true"></i>Parts of this profile are approximate: some
              signals were matched by time window only.
            </p>

            <section class="mb-3">
              <h3 class="h6">
                SQL
                <span
                  v-if="profile.sqlCorrelationApproximate"
                  class="badge text-bg-secondary ms-1"
                  title="Correlated by time window only (no trace id or uniquely identifiable serving thread)"
                >
                  approximate
                </span>
                <span
                  v-else-if="profile.sql && profile.sql.length"
                  class="badge text-bg-success ms-1"
                  title="Correlated exactly by trace id or the request's serving thread"
                >
                  exact
                </span>
                <span
                  v-if="sections.SQL?.tierLabel"
                  class="badge text-bg-light border fw-normal ms-1 activity-tier"
                  :title="sections.SQL.tierTitle"
                  >{{ sections.SQL.tierLabel }}</span
                >
              </h3>
              <div v-for="group in profile.sqlGroups" :key="group.sql" class="small mb-1">
                <span v-if="group.potentialNPlusOne" class="badge text-bg-danger me-1">
                  N+1 · {{ group.executions }} identical
                </span>
                <span v-else class="badge text-bg-light me-1">×{{ group.executions }}</span>
                <code>{{ group.sql }}</code>
                <div v-if="group.callSites && group.callSites.length" class="call-sites text-muted">
                  <div v-for="site in group.callSites" :key="site" class="font-monospace">at {{ site }}</div>
                </div>
              </div>
              <p v-if="sections.SQL && !sections.SQL.available" class="text-muted small mb-0">
                {{ sections.SQL.unavailableReason }}
              </p>
              <p v-else-if="!profile.sql.length" class="text-muted small mb-0">No SQL correlated to this request.</p>
              <p v-if="sections.SQL?.truncationText" class="text-muted small mb-0">
                {{ sections.SQL.truncationText }}
              </p>
            </section>

            <section
              v-if="profile.exceptions.length || (sections.EXCEPTION && !sections.EXCEPTION.available)"
              class="mb-3"
            >
              <h3 class="h6">
                Exceptions
                <span
                  v-if="sections.EXCEPTION?.tierLabel"
                  class="badge text-bg-light border fw-normal ms-1 activity-tier"
                  :title="sections.EXCEPTION.tierTitle"
                  >{{ sections.EXCEPTION.tierLabel }}</span
                >
              </h3>
              <p v-if="sections.EXCEPTION && !sections.EXCEPTION.available" class="text-muted small mb-0">
                {{ sections.EXCEPTION.unavailableReason }}
              </p>
              <div v-for="(ex, index) in profile.exceptions" :key="index" class="small mb-1">
                <code>{{ ex.exceptionClassName }}</code>
                <span v-if="ex.message" class="text-muted">: {{ ex.message }}</span>
                <span v-if="childTierLabel(sections.EXCEPTION, index)" class="text-muted activity-child-tier">
                  · {{ childTierLabel(sections.EXCEPTION, index) }}</span
                >
                <span v-if="ex.location" class="d-block text-muted">{{ ex.location }}</span>
              </div>
              <p v-if="sections.EXCEPTION?.truncationText" class="text-muted small mb-0">
                {{ sections.EXCEPTION.truncationText }}
              </p>
            </section>

            <section
              v-if="
                (profile.security && profile.security.length) || (sections.SECURITY && !sections.SECURITY.available)
              "
              class="mb-3"
            >
              <h3 class="h6">
                Security events
                <span
                  v-if="sections.SECURITY?.tierLabel"
                  class="badge text-bg-light border fw-normal ms-1 activity-tier"
                  :title="sections.SECURITY.tierTitle"
                  >{{ sections.SECURITY.tierLabel }}</span
                >
              </h3>
              <p v-if="sections.SECURITY && !sections.SECURITY.available" class="text-muted small mb-0">
                {{ sections.SECURITY.unavailableReason }}
              </p>
              <div v-for="(event, index) in profile.security" :key="index" class="small mb-1">
                <code>{{ event.type }}</code>
                <span v-if="event.principal" class="text-muted"> · {{ event.principal }}</span>
                <span
                  v-if="securityEventExact(sections.SECURITY, event, index)"
                  class="badge text-bg-success ms-1"
                  title="Correlated exactly by the request's serving thread or its BootUI request id"
                >
                  exact
                </span>
                <span
                  v-else-if="event.principalMatched"
                  class="badge text-bg-light ms-1"
                  title="Principal matches the request"
                >
                  principal
                </span>
                <span v-if="childTierLabel(sections.SECURITY, index)" class="text-muted activity-child-tier">
                  · {{ childTierLabel(sections.SECURITY, index) }}</span
                >
              </div>
              <p v-if="sections.SECURITY?.truncationText" class="text-muted small mb-0">
                {{ sections.SECURITY.truncationText }}
              </p>
            </section>

            <section v-if="sections.REST_CLIENT" class="mb-3">
              <h3 class="h6">
                REST client calls
                <span
                  v-if="sections.REST_CLIENT.tierLabel"
                  class="badge text-bg-light border fw-normal ms-1 activity-tier"
                  :title="sections.REST_CLIENT.tierTitle"
                  >{{ sections.REST_CLIENT.tierLabel }}</span
                >
              </h3>
              <p v-if="!sections.REST_CLIENT.available" class="text-muted small mb-0">
                {{ sections.REST_CLIENT.unavailableReason }}
              </p>
              <template v-else>
                <div v-for="(call, index) in profile.restCalls" :key="call.id" class="small mb-1 activity-rest-call">
                  <code>{{ restCallSummary(call) }}</code>
                  <span class="text-muted"> · {{ formatDurationMs(call.durationMillis) }}</span>
                  <span v-if="childTierLabel(sections.REST_CLIENT, index)" class="text-muted activity-child-tier">
                    · {{ childTierLabel(sections.REST_CLIENT, index) }}</span
                  >
                  <span v-if="!call.success && call.errorMessage" class="d-block text-muted">{{
                    call.errorMessage
                  }}</span>
                  <div v-if="call.callSite" class="call-sites text-muted font-monospace">at {{ call.callSite }}</div>
                </div>
                <p v-if="!profile.restCalls?.length" class="text-muted small mb-0">
                  No REST client calls correlated to this request.
                </p>
                <p v-if="sections.REST_CLIENT.truncationText" class="text-muted small mb-0">
                  {{ sections.REST_CLIENT.truncationText }}
                </p>
              </template>
            </section>

            <section v-if="sections.CACHE" class="mb-3">
              <h3 class="h6">
                Cache accesses
                <span
                  v-if="sections.CACHE.tierLabel"
                  class="badge text-bg-light border fw-normal ms-1 activity-tier"
                  :title="sections.CACHE.tierTitle"
                  >{{ sections.CACHE.tierLabel }}</span
                >
              </h3>
              <p v-if="!sections.CACHE.available" class="text-muted small mb-0">
                {{ sections.CACHE.unavailableReason }}
              </p>
              <template v-else>
                <div
                  v-for="(access, index) in profile.cacheAccesses"
                  :key="index"
                  class="small mb-1 activity-cache-access"
                >
                  <code>{{ cacheAccessSummary(access) }}</code>
                  <span v-if="access.keyHash" class="text-muted"> · key {{ access.keyHash }}</span>
                  <span v-if="childTierLabel(sections.CACHE, index)" class="text-muted activity-child-tier">
                    · {{ childTierLabel(sections.CACHE, index) }}</span
                  >
                </div>
                <p v-if="!profile.cacheAccesses?.length" class="text-muted small mb-0">
                  No cache accesses correlated to this request.
                </p>
                <p v-if="sections.CACHE.truncationText" class="text-muted small mb-0">
                  {{ sections.CACHE.truncationText }}
                </p>
              </template>
            </section>

            <section v-if="profile.trace && profile.trace.spans.length" class="mb-3">
              <h3 class="h6">Trace waterfall</h3>
              <ul class="list-unstyled small mb-0">
                <li v-for="(span, index) in profile.trace.spans" :key="index">
                  <code>{{ span.name }}</code>
                </li>
              </ul>
            </section>

            <section v-if="profile.notes.length || tiersNote">
              <h3 class="h6">Notes</h3>
              <ul class="small text-muted mb-0">
                <li v-for="(note, index) in profile.notes" :key="index">{{ note }}</li>
                <li v-if="tiersNote">{{ tiersNote }}</li>
              </ul>
            </section>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.activity-flow {
  border-top: 1px solid var(--bootui-border);
  padding-top: 1rem;
}

.activity-flow__header {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  justify-content: space-between;
  margin-bottom: 0.85rem;
}

.activity-drawer-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  display: flex;
  justify-content: flex-end;
  z-index: 1050;
}

.activity-drawer {
  width: min(560px, 100%);
  height: 100%;
  border-radius: 0;
  overflow: hidden;
}

.activity-drawer-body {
  overflow-y: auto;
}

.activity-row-clickable {
  cursor: pointer;
}

.activity-kpi-link {
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}

.activity-kpi-link:hover,
.activity-kpi-link:focus-visible {
  border-color: var(--bs-primary);
  box-shadow: 0 0 0 0.15rem rgba(var(--bs-primary-rgb), 0.25);
}

.activity-table {
  table-layout: fixed;
  width: 100%;
  min-width: 40rem;
}

.activity-col-time {
  width: 5.5rem;
}

.activity-col-type {
  width: 8.5rem;
}

.activity-col-severity {
  width: 5rem;
}

.activity-col-summary {
  width: auto;
}

.activity-col-duration {
  width: 5.5rem;
}

.activity-col-actions {
  width: 8rem;
}

.activity-summary-cell {
  overflow-wrap: anywhere;
  word-break: break-word;
  white-space: normal;
}

.activity-disclosure {
  color: var(--bs-secondary-color);
  text-decoration: none;
  line-height: 1;
}

.activity-disclosure:hover,
.activity-disclosure:focus-visible {
  color: var(--bs-primary);
}

.activity-child-row > td {
  background-color: var(--bs-secondary-bg);
  color: var(--bs-secondary-color);
  border-top-color: transparent;
}

.activity-child-row > td:first-child {
  box-shadow: inset 0.2rem 0 0 var(--bs-primary-border-subtle, var(--bs-primary));
}

.activity-child-type {
  padding-left: 0.75rem;
}

.activity-principal-tag {
  font-weight: 500;
}

/* Latency heat: shared yellow-to-red ramp used for both the row tint and the duration badge. */
.activity-slow-1 > td {
  background-color: rgba(255, 193, 7, 0.16);
}

.activity-slow-2 > td {
  background-color: rgba(253, 126, 20, 0.2);
}

.activity-slow-3 > td {
  background-color: rgba(220, 53, 69, 0.22);
}

.activity-slow-4 > td {
  background-color: rgba(176, 0, 32, 0.32);
}

.activity-lat-1 {
  color: var(--bootui-heat-low-text);
  background-color: var(--bootui-heat-low-bg);
}

.activity-lat-2 {
  color: #fff;
  background-color: var(--bootui-high);
}

.activity-lat-3 {
  color: #fff;
  background-color: var(--bootui-danger);
}

.activity-lat-4 {
  color: #fff;
  background-color: var(--bootui-critical);
}

.activity-text-filter {
  min-width: 16rem;
  flex: 1 1 16rem;
}

.activity-sparkline-svg {
  height: 36px;
  display: block;
}

.activity-spark-bar {
  fill: var(--bs-primary, #0d6efd);
  opacity: 0.55;
}

.activity-spark-error {
  fill: var(--bs-danger, #dc3545);
}
.activity-spark-marker {
  stroke: var(--bootui-text-muted);
  stroke-width: 1px;
  stroke-dasharray: 3 2;
}
@media (prefers-reduced-motion: reduce) {
  .activity-kpi-link {
    transition: none;
  }
}
</style>
