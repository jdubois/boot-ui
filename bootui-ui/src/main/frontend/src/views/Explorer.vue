<script setup>
import {computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import UnavailableState from './components/UnavailableState.vue'
import ExplorerTree from './components/ExplorerTree.vue'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {deepLink} from '../utils/activityStream.js'
import {formatClockTime} from '../utils/format.js'
import {
  buildExplorerModel,
  buildReplay,
  cacheMeaning,
  durationLabel,
  EXPLORER_TYPES,
  explorerEventVersionKey,
  layoutExplorer
} from '../utils/explorerModel.js'
import {useExplorer} from '../utils/useExplorer.js'
import {useExplorerMotion} from '../utils/useExplorerMotion.js'

const ExplorerScene = defineAsyncComponent(() => import('./components/ExplorerScene.vue'))
const props = defineProps(panelProps)
const {manifestAvailable, manifestUnavailableReason} = usePanelState(props)
const {
  report,
  error,
  lastFetched,
  type,
  severity,
  text,
  visibleEntries,
  selectedId,
  selectedEvent,
  detail,
  detailError,
  detailLoading,
  select,
  loadDetail,
  freshIds,
  follow,
  replaying,
  hidden,
  loadingOlder,
  canLoadOlder,
  loadOlder,
  autoRefresh,
  loading,
  initialLoading,
  refresh,
  connectionState,
  retryConnection
} = useExplorer(manifestAvailable)
const selectedRowId = ref(null),
  group = ref(null),
  groupPage = ref(0)
const show3d = ref(!window.matchMedia?.('(max-width: 767px)').matches)
let narrowMedia
const model = computed(() =>
  buildExplorerModel(
    visibleEntries.value,
    selectedId.value ? (detail.value?.found && detail.value.event ? detail.value : {event: selectedEvent.value}) : null
  )
)
const layout = computed(() =>
  layoutExplorer(model.value, {group: group.value, page: groupPage.value, focusId: selectedRowId.value})
)
const paused = computed(() => !autoRefresh.value || hidden.value || !manifestAvailable.value)
const motion = useExplorerMotion(model, layout, paused)
const {effects, activeRows, running, position, message, reduced} = motion
const timeline = computed(() => buildReplay(model.value))
const selectedRow = computed(() => model.value.byId.get(selectedRowId.value) || null)
const repeatedCalls = computed(() =>
  selectedRow.value?.call ? model.value.rows.filter((row) => row.nodeId === selectedRow.value.nodeId && row.call) : []
)
const sourceLink = computed(() => deepLink(selectedRow.value?.event))
const setup = computed(() => report.value?.setup)
const types = computed(() => [
  ...new Set([
    ...EXPLORER_TYPES,
    ...Object.keys(report.value?.activity?.typeCounts || {}),
    ...visibleEntries.value.map((entry) => entry.type)
  ])
])
const warnings = computed(() => [
  ...new Set([
    ...(report.value?.activity?.warnings || []),
    ...model.value.warnings,
    ...(setup.value?.limitations || [])
  ])
])
const activeWithReferences = computed(() => [
  ...new Set([
    ...activeRows.value,
    ...model.value.rows
      .filter(
        (row) =>
          row.kind === 'SQL_REFERENCE' && (activeRows.value.includes(row.parent) || selectedRowId.value === row.parent)
      )
      .map((row) => row.id)
  ])
])
const hasEvidence = computed(() => model.value.rows.length > 0)

function chooseEvent(event) {
  motion.cancel()
  group.value = null
  groupPage.value = 0
  selectedRowId.value = event ? `event:${event.id}` : null
  select(event)
}
function isSelectedEvent(event) {
  return !!selectedEvent.value && explorerEventVersionKey(event) === explorerEventVersionKey(selectedEvent.value)
}
function chooseRow(id) {
  const row = model.value.byId.get(id)
  if (!selectedId.value && row?.event) {
    chooseEvent(row.event)
    return
  }
  motion.cancel()
  selectedRowId.value = id
}
function chooseNode(node) {
  motion.cancel()
  if (node.kind === 'GROUP') {
    group.value = node.stage
    groupPage.value = groupPage.value + 1 < layout.value.pages ? groupPage.value + 1 : 0
    return
  }
  chooseRow(node.rowIds[0])
}
function allStages() {
  group.value = null
  groupPage.value = 0
}
function narrow(event) {
  show3d.value = !event.matches
}
function scrub(event) {
  motion.scrub(event.target.value)
}
watch(
  running,
  (value) => {
    replaying.value = value
  },
  {flush: 'sync'}
)
watch(freshIds, (ids) => {
  if (!follow.value || running.value || !show3d.value || paused.value) return
  const versions = new Set(ids)
  const applicable = model.value.rows
    .filter((row) => row.event && versions.has(explorerEventVersionKey(row.event)))
    .map((row) => row.id)
  if (applicable.length) {
    motion.burst(applicable)
    message.value = `${applicable.length} newly captured observations. Selection unchanged.`
  }
})
watch([show3d, follow, type, severity, text, group, groupPage], () => motion.cancel())
watch(selectedId, () => motion.cancel())
watch(model, () => {
  if (selectedRowId.value && !model.value.byId.has(selectedRowId.value))
    selectedRowId.value = selectedId.value ? `event:${selectedId.value}` : null
})
onMounted(() => {
  narrowMedia = window.matchMedia?.('(max-width: 767px)')
  narrowMedia?.addEventListener('change', narrow)
})
onBeforeUnmount(() => narrowMedia?.removeEventListener('change', narrow))
</script>

<template>
  <div class="explorer-panel">
    <!-- THESIS: Real activity expanded into a layered journey, not a simulated application city.
         OWN-WORLD: BootUI's cool neutral control room, semantic risk colors and selected-path green.
         STORY: Find an event, inspect exact relationships, replay only recorded evidence.
         FIRST VIEWPORT: Narrow activity rail, dominant spatial stage, keyboard tree and inspector below.
         FORM: Approved Operate layered journey. Existing shell, no autonomous motion or capture. -->
    <PanelHeader
      title="3D Explorer"
      icon="bi-box"
      subtitle="Captured activity, one observed call at a time."
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      :refreshable="manifestAvailable"
      :auto-refreshable="manifestAvailable"
      :auto-refresh="autoRefresh"
      :auto-refresh-state="connectionState"
      auto-refresh-title="Refresh from the Live Activity stream; pausing does not stop backend capture"
      @refresh="refresh"
      @update:auto-refresh="autoRefresh = $event"
      @retry-auto-refresh="retryConnection"
    />
    <UnavailableState v-if="!manifestAvailable" :message="manifestUnavailableReason" />
    <PanelSkeleton v-else-if="initialLoading && !report" />
    <UnavailableState
      v-else-if="report?.available === false"
      :message="setup?.reason || 'No activity source is available.'"
    />
    <template v-else>
      <div class="explorer-setup">
        <span
          ><i class="bi bi-circle-fill" aria-hidden="true"></i
          >{{ setup?.beanDetailAvailable ? 'Bean detail available' : 'Canonical activity' }}</span
        >
        <span v-if="!setup?.beanDetailAvailable">{{
          setup?.reason || 'Bean detail unavailable. Existing activity remains visible.'
        }}</span>
        <details v-if="!setup?.beanCaptureEnabled && report">
          <summary>Bean capture setup</summary>
          <p>
            Re-enable with <code>bootui.explorer.enabled=true</code> and restart the application. Requires supported
            Spring MVC JVM tracing and enabled Beans/Traces panels. Opening Explorer never enables capture.
          </p>
        </details>
      </div>
      <div class="explorer-workbench">
        <aside class="explorer-rail" aria-label="Activity selection">
          <div class="explorer-rail-heading">
            <h3>Activity</h3>
            <span>{{ visibleEntries.length }} events</span>
          </div>
          <form class="explorer-filters" @submit.prevent>
            <label class="visually-hidden" for="explorer-search">Search activity</label>
            <input
              id="explorer-search"
              v-model="text"
              type="search"
              class="form-control form-control-sm"
              placeholder="Search activity"
            />
            <label class="visually-hidden" for="explorer-type">Activity type</label>
            <select id="explorer-type" v-model="type" class="form-select form-select-sm">
              <option value="">All event types</option>
              <option v-for="value in types" :key="value" :value="value">{{ value }}</option>
            </select>
            <label class="visually-hidden" for="explorer-severity">Activity severity</label>
            <select id="explorer-severity" v-model="severity" class="form-select form-select-sm">
              <option value="">All severities</option>
              <option v-for="value in ['OK', 'SLOW', 'WARN', 'ERROR']" :key="value" :value="value">{{ value }}</option>
            </select>
          </form>
          <button class="explorer-overview-button" :aria-pressed="!selectedId" @click="chooseEvent(null)">
            <i class="bi bi-diagram-3" aria-hidden="true"></i>Activity overview
          </button>
          <ol class="explorer-events">
            <li v-for="event in visibleEntries" :key="explorerEventVersionKey(event)">
              <button
                class="explorer-event"
                :aria-pressed="isSelectedEvent(event)"
                :data-event-id="event.id"
                @click="chooseEvent(event)"
              >
                <span class="explorer-event-meta"
                  ><span>{{ event.type }}</span
                  ><span :class="`severity-${event.severity}`">{{ event.severity }}</span></span
                >
                <span class="explorer-event-summary font-monospace">{{ event.summary }}</span>
                <span class="explorer-event-meta font-monospace"
                  ><span>{{ formatClockTime(event.timestamp) }}</span
                  ><span>{{ durationLabel(event.durationMs) }}</span></span
                >
              </button>
            </li>
          </ol>
          <p v-if="!visibleEntries.length" class="explorer-empty-rail">
            {{
              type || severity || text
                ? 'No matching activity. Try another filter.'
                : 'No activity captured yet. Use your application, then refresh.'
            }}
          </p>
          <button
            v-if="canLoadOlder"
            class="btn btn-outline-secondary btn-sm m-3"
            :disabled="loadingOlder"
            @click="loadOlder"
          >
            {{ loadingOlder ? 'Loading older…' : 'Load older' }}
          </button>
        </aside>
        <section class="explorer-journey" aria-label="Captured activity journey">
          <div class="explorer-journey-toolbar">
            <div>
              <h3>{{ selectedId ? 'Selected journey' : 'Activity overview' }}</h3>
              <p>{{ selectedEvent?.summary || 'Every event retains its original identity and severity.' }}</p>
            </div>
            <div class="explorer-view-controls">
              <label class="explorer-follow"
                ><input v-model="follow" type="checkbox" :disabled="paused" />Follow live</label
              >
              <button class="btn btn-outline-secondary btn-sm" :aria-pressed="show3d" @click="show3d = !show3d">
                {{ show3d ? 'Hide 3D' : 'Show 3D' }}
              </button>
            </div>
          </div>
          <div v-if="detailError" class="alert alert-warning m-3" role="alert">
            {{ detailError }}
            <button class="btn btn-outline-secondary btn-sm" @click="loadDetail(false)">Retry detail</button>
          </div>
          <div
            v-if="detail?.partial || detail?.found === false || detail?.omittedInvocations"
            class="explorer-detail-note"
          >
            <i class="bi bi-info-circle" aria-hidden="true"></i>
            {{
              detail.found === false
                ? 'Detail expired or unavailable. The retained canonical event remains visible.'
                : 'Partial capture. Missing evidence is not a successful complete journey.'
            }}
            <span v-if="detail.omittedInvocations"
              >{{ detail.omittedInvocations }} invocations omitted by the capture limit.</span
            >
          </div>
          <div v-if="group != null || layout.grouped" class="explorer-grouping">
            <span
              >{{ layout.nodes.length }} visible topology nodes · {{ layout.grouped }} grouped · all
              {{ model.rows.length }} observations remain in the tree.</span
            >
            <template v-if="group != null">
              <button class="btn btn-outline-secondary btn-sm" @click="allStages">All stages</button>
              <button v-if="groupPage > 0" class="btn btn-outline-secondary btn-sm" @click="groupPage--">
                Previous nodes
              </button>
              <button v-if="groupPage + 1 < layout.pages" class="btn btn-outline-secondary btn-sm" @click="groupPage++">
                Next nodes
              </button>
            </template>
            <span v-else>Select a group to expand it.</span>
          </div>
          <div v-if="show3d && hasEvidence" class="explorer-viewport">
            <ExplorerScene
              :layout="layout"
              :selected-id="selectedRowId"
              :frame-key="`${selectedId || 'overview'}:${detail?.found || false}:${group}:${groupPage}`"
              :active-rows="activeWithReferences"
              :effects="effects"
              @select="chooseNode"
              @unavailable="motion.cancel"
            />
          </div>
          <div v-if="!hasEvidence" class="explorer-empty">
            <i class="bi bi-box" aria-hidden="true"></i>
            <h3>A journey starts with real activity</h3>
            <p>
              Use your application and select a captured event. HTTP, SQL, cache and background work are shown without
              fabricating missing calls.
            </p>
          </div>
          <div v-if="selectedId && hasEvidence" class="explorer-replay">
            <button
              class="btn btn-success btn-sm"
              :disabled="paused || detailLoading"
              @click="running ? motion.cancel() : motion.replay()"
            >
              <i :class="running ? 'bi bi-stop-fill' : 'bi bi-play-fill'" aria-hidden="true"></i
              >{{ running ? 'Stop replay' : 'Replay' }}
            </button>
            <label class="visually-hidden" for="explorer-replay-position">Recorded replay position</label>
            <input
              id="explorer-replay-position"
              type="range"
              min="0"
              :max="timeline.durationMs"
              :value="position"
              :disabled="detailLoading"
              :aria-valuetext="`${Math.round(position)} of ${timeline.durationMs} presentation milliseconds`"
              @input="scrub"
            />
            <span title="One step at a time in execution-tree order. Recorded durations remain in the inspector."
              >{{ reduced ? 'Static recorded evidence' : 'Execution-order replay'
              }}<span v-if="timeline.scale < 0.99"> · compact pacing</span></span
            >
          </div>
          <div v-if="hasEvidence" class="explorer-evidence">
            <ExplorerTree
              :rows="model.rows"
              :selected-id="selectedRowId"
              :active-rows="activeWithReferences"
              @select="chooseRow"
            />
            <section class="explorer-inspector" aria-labelledby="explorer-inspector-title">
              <h3 id="explorer-inspector-title">Inspector</h3>
              <p v-if="detailLoading" class="text-muted" role="status">Loading captured detail…</p>
              <template v-if="selectedRow">
                <p class="explorer-inspector-name font-monospace">{{ selectedRow.label }}</p>
                <dl class="explorer-facts">
                  <dt>Evidence</dt>
                  <dd>{{ selectedRow.relationship }}</dd>
                  <template v-if="selectedRow.event"
                    ><dt>Severity</dt>
                    <dd>{{ selectedRow.event.severity }} · unchanged from Live Activity</dd></template
                  >
                  <template v-if="['REQUEST', 'SCHEDULED'].includes(selectedRow.kind)">
                    <dt>Slow rule</dt>
                    <dd>Configured activity threshold: {{ durationLabel(setup?.requestSlowThresholdMs) }}.</dd>
                  </template>
                  <template v-if="['SQL', 'REST_CLIENT'].includes(selectedRow.kind)">
                    <dt>Slow rule</dt>
                    <dd>
                      {{ selectedRow.kind === 'SQL' ? 'SQL Trace' : 'REST Client' }} source classification, not a
                      browser latency threshold.
                    </dd>
                  </template>
                  <template v-if="selectedRow.kind !== 'SQL_REFERENCE'"
                    ><dt>Duration</dt>
                    <dd class="font-monospace">{{ durationLabel(selectedRow.durationMs) }}</dd></template
                  >
                  <template v-if="selectedRow.call">
                    <dt>Invocation</dt>
                    <dd class="font-monospace">{{ selectedRow.call.id }}</dd>
                    <dt>Type</dt>
                    <dd class="font-monospace">{{ selectedRow.call.typeName }}</dd>
                    <dt>Offset</dt>
                    <dd class="font-monospace">{{ durationLabel(selectedRow.call.offsetMs) }}</dd>
                    <dt>Outcome</dt>
                    <dd>
                      {{ selectedRow.call.failed ? 'Failed — exception escaped' : 'Returned successfully'
                      }}{{ selectedRow.call.slow ? ' · SLOW' : '' }}
                    </dd>
                    <dt>Slow rule</dt>
                    <dd>
                      Configured request threshold: {{ durationLabel(setup?.requestSlowThresholdMs) }}. Nested durations
                      overlap; do not sum them.
                    </dd>
                    <template v-if="selectedRow.call.exceptionType"
                      ><dt>Exception type</dt>
                      <dd class="font-monospace">{{ selectedRow.call.exceptionType }}</dd></template
                    >
                  </template>
                  <template v-if="selectedRow.cache">
                    <dt>Operation</dt>
                    <dd>{{ cacheMeaning(selectedRow.operation) }}</dd>
                    <dt>Manager</dt>
                    <dd class="font-monospace">
                      {{ selectedRow.cache.managerName || 'Unknown manager (not merged)' }}
                    </dd>
                    <dt>Cache</dt>
                    <dd class="font-monospace">{{ selectedRow.cache.cacheName }}</dd>
                  </template>
                  <template v-if="selectedRow.sql || selectedRow.reference">
                    <dt>Datasource</dt>
                    <dd class="font-monospace">
                      {{ (selectedRow.sql || selectedRow.reference).dataSource || 'Unknown scope (not merged)' }}
                    </dd>
                    <dt>Reference status</dt>
                    <dd>{{ (selectedRow.sql || selectedRow.reference).status }}</dd>
                  </template>
                </dl>
                <p v-if="selectedRow.call" class="explorer-inspector-note">
                  Proxy invocation: caching may skip the target method body. Self-invocation and asynchronous handoffs
                  may not be captured.
                </p>
                <p v-if="selectedRow.kind === 'SQL_REFERENCE'" class="explorer-inspector-note">
                  A lexical SQL reference, not proof of a physical table or its health.{{
                    selectedRow.scopeKnown
                      ? ''
                      : ' Database or schema scope is unresolved; this observation is kept separate.'
                  }}
                  Timing belongs to the SQL statement only.
                </p>
                <p
                  v-if="selectedRow.kind === 'SQL' && !selectedRow.sql?.identifiers?.length"
                  class="explorer-inspector-note"
                >
                  Table references unavailable. The SQL execution is still retained.
                </p>
                <p v-if="selectedRow.kind === 'MAIL'" class="explorer-inspector-note">
                  Captured send activity is not confirmation of delivery.
                </p>
                <p v-if="selectedRow.kind === 'MESSAGING'" class="explorer-inspector-note">
                  {{
                    selectedRow.direction
                      ? `Captured direction: ${selectedRow.direction}.`
                      : 'Direction unavailable; no transfer direction inferred.'
                  }}
                  No producer-to-consumer path is inferred.
                </p>
                <p v-if="selectedRow.kind === 'FAULT_TOLERANCE'" class="explorer-inspector-note">
                  One captured policy event; no additional retries or attempts are inferred.
                </p>
                <p
                  v-if="selectedRow.event?.detail && selectedRow.kind !== 'CACHE'"
                  class="font-monospace explorer-inspector-note"
                >
                  {{ selectedRow.event.detail }}
                </p>
                <RouterLink
                  v-if="sourceLink"
                  class="explorer-source-link"
                  :to="{path: sourceLink.path, query: sourceLink.query}"
                  >{{ sourceLink.label }} <i class="bi bi-arrow-up-right" aria-hidden="true"></i
                ></RouterLink>
                <RouterLink
                  v-if="selectedRow.call"
                  class="explorer-source-link"
                  :to="{path: '/beans', query: {q: selectedRow.call.beanName}}"
                  >Open in Beans <i class="bi bi-arrow-up-right" aria-hidden="true"></i
                ></RouterLink>
                <div v-if="repeatedCalls.length > 1" class="explorer-repeated-calls">
                  <h4>Same bean · {{ repeatedCalls.length }} invocations</h4>
                  <button
                    v-for="call in repeatedCalls"
                    :key="call.id"
                    class="btn btn-outline-secondary btn-sm"
                    :aria-pressed="selectedRowId === call.id"
                    @click="chooseRow(call.id)"
                  >
                    <span class="font-monospace"
                      >{{ call.call.method }} · +{{ durationLabel(call.offsetMs) }} · {{ call.call.id }}</span
                    >
                  </button>
                </div>
              </template>
              <p v-else class="explorer-inspector-note">
                Select any activity or invocation to inspect its evidence. The scene and keyboard tree share exactly the
                same relationships.
              </p>
            </section>
          </div>
          <details v-if="warnings.length" class="explorer-warnings">
            <summary>Capture notes ({{ warnings.length }})</summary>
            <ul>
              <li v-for="warning in warnings" :key="warning">{{ warning }}</li>
            </ul>
          </details>
          <p v-if="report?.activity?.sources?.length" class="explorer-source-list">
            Sources: {{ report.activity.sources.join(' · ') }}
          </p>
        </section>
      </div>
    </template>
    <div class="visually-hidden" role="status" aria-live="polite" aria-atomic="true">{{ message }}</div>
  </div>
</template>

<style scoped>
.explorer-panel {
  color: var(--bootui-text);
}
.explorer-setup {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 0.5rem 1rem;
  margin-bottom: 1rem;
  font-size: 0.85rem;
  color: var(--bootui-text-muted);
}
.explorer-setup > span:first-child {
  font-weight: 600;
  color: var(--bootui-text);
}
.explorer-setup i {
  font-size: 0.72rem;
  margin-right: 0.4rem;
}
.explorer-setup details p {
  max-width: 65ch;
  margin: 0.6rem 0 0;
}
.explorer-workbench {
  display: grid;
  grid-template-columns: 16rem minmax(0, 1fr);
  border: 1px solid var(--bootui-border-alt);
  border-radius: var(--bootui-radius-lg);
  overflow: hidden;
  background: var(--bootui-surface-solid);
}
.explorer-rail {
  min-width: 0;
  background: var(--bootui-surface-alt);
  border-right: 1px solid var(--bootui-border-alt);
  display: flex;
  flex-direction: column;
}
.explorer-rail-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 1rem 1rem 0.75rem;
}
.explorer-rail-heading h3,
.explorer-journey-toolbar h3,
.explorer-inspector h3 {
  font-size: 1rem;
  font-weight: 700;
  margin: 0;
}
.explorer-rail-heading > span {
  font-size: 0.75rem;
  color: var(--bootui-text-muted);
}
.explorer-filters {
  display: grid;
  gap: 0.5rem;
  padding: 0 0.85rem 0.85rem;
}
.explorer-events {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 51rem;
  overflow: auto;
}
.explorer-event {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  width: 100%;
  text-align: left;
  padding: 0.85rem;
  border: 0;
  border-top: 1px solid var(--bootui-border-subtle);
  color: var(--bootui-text);
  background: transparent;
}
.explorer-event:hover,
.explorer-overview-button:hover {
  background: var(--bootui-surface-solid);
}
.explorer-event[aria-pressed='true'],
.explorer-overview-button[aria-pressed='true'] {
  background: color-mix(in srgb, var(--bootui-green) 10%, var(--bootui-surface-solid));
}
.explorer-event:focus-visible,
.explorer-overview-button:focus-visible {
  outline: 2px solid var(--bootui-blue);
  outline-offset: -2px;
}
.explorer-event-meta {
  display: flex;
  justify-content: space-between;
  gap: 0.5rem;
  font-size: 0.72rem;
  color: var(--bootui-text-muted);
}
.explorer-event-summary {
  font-size: 0.85rem;
  overflow-wrap: anywhere;
}
.severity-ERROR {
  color: var(--bootui-danger-text);
  font-weight: 700;
}
.severity-SLOW,
.severity-WARN {
  color: var(--bootui-warning-text-strong);
  font-weight: 700;
}
.explorer-overview-button {
  display: flex;
  gap: 0.5rem;
  padding: 0.7rem 0.85rem;
  border: 0;
  color: var(--bootui-text);
  font-size: 0.85rem;
  background: transparent;
}
.explorer-empty-rail {
  font-size: 0.85rem;
  color: var(--bootui-text-muted);
  padding: 1rem;
}
.explorer-journey {
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.explorer-journey-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: start;
  flex-wrap: wrap;
  gap: 1rem;
  padding: 1rem 1.25rem;
  border-bottom: 1px solid var(--bootui-border-subtle);
}
.explorer-journey-toolbar > div:first-child {
  flex: 1 1 14rem;
  min-width: 0;
}
.explorer-journey-toolbar p {
  color: var(--bootui-text-muted);
  font-size: 0.85rem;
  margin: 0.25rem 0 0;
  overflow-wrap: anywhere;
}
.explorer-view-controls {
  display: flex;
  align-items: center;
  gap: 1rem;
}
.explorer-follow {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  font-size: 0.85rem;
}
.explorer-follow input {
  accent-color: var(--bootui-green);
}
.explorer-viewport {
  height: 29rem;
  background: var(--bootui-surface-alt);
}
.explorer-detail-note,
.explorer-grouping {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.5rem;
  font-size: 0.85rem;
  padding: 0.75rem 1rem;
  color: var(--bootui-text-muted);
  border-bottom: 1px solid var(--bootui-border-subtle);
}
.explorer-replay {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.8rem;
  border-top: 1px solid var(--bootui-border-subtle);
  border-bottom: 1px solid var(--bootui-border-subtle);
  padding: 0.75rem 1rem;
}
.explorer-replay input {
  flex: 1 1 8rem;
  accent-color: var(--bootui-green);
  min-width: 0;
}
.explorer-replay span {
  font-size: 0.75rem;
  color: var(--bootui-text-muted);
}
.explorer-evidence {
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(16rem, 1fr);
}
.explorer-inspector {
  padding: 1rem;
  min-width: 0;
  border-left: 1px solid var(--bootui-border-subtle);
  max-height: 29rem;
  overflow: auto;
}
.explorer-inspector-name {
  font-size: 0.85rem;
  overflow-wrap: anywhere;
  margin: 0.75rem 0;
}
.explorer-facts {
  display: grid;
  grid-template-columns: 6rem minmax(0, 1fr);
  gap: 0.5rem 0.75rem;
  font-size: 0.85rem;
  margin-bottom: 0.75rem;
}
.explorer-facts dt {
  font-weight: 500;
  color: var(--bootui-text-muted);
}
.explorer-facts dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.explorer-inspector-note {
  color: var(--bootui-text-muted);
  font-size: 0.85rem;
  overflow-wrap: anywhere;
  margin: 0.75rem 0;
}
.explorer-source-link {
  font-size: 0.85rem;
}
.explorer-repeated-calls {
  display: grid;
  gap: 0.5rem;
  margin-top: 1rem;
}
.explorer-repeated-calls h4 {
  font-size: 0.85rem;
  font-weight: 600;
  margin: 0;
}
.explorer-repeated-calls button {
  text-align: left;
  overflow-wrap: anywhere;
}
.explorer-warnings {
  padding: 0.75rem 1rem;
  border-top: 1px solid var(--bootui-border-subtle);
  font-size: 0.85rem;
  color: var(--bootui-text-muted);
}
.explorer-warnings ul {
  margin: 0.5rem 0 0;
  padding-left: 1.25rem;
}
.explorer-source-list {
  margin: 0;
  padding: 0.75rem 1rem;
  color: var(--bootui-text-muted);
  font-size: 0.75rem;
}
.explorer-empty {
  display: grid;
  align-content: center;
  justify-items: start;
  gap: 0.75rem;
  padding: 3rem;
  min-height: 25rem;
  max-width: 42rem;
  margin: auto;
}
.explorer-empty > i {
  font-size: var(--bootui-icon-size);
  color: var(--bootui-text-muted);
}
.explorer-empty h3 {
  font-size: 1.15rem;
  margin: 0;
}
.explorer-empty p {
  font-size: 0.85rem;
  color: var(--bootui-text-muted);
  margin: 0;
}
@media (max-width: 1199px) {
  .explorer-workbench {
    grid-template-columns: 14rem minmax(0, 1fr);
  }
  .explorer-evidence {
    grid-template-columns: 1fr;
  }
  .explorer-inspector {
    border-left: 0;
    border-top: 1px solid var(--bootui-border-subtle);
    max-height: none;
  }
}
@media (max-width: 767px) {
  .explorer-workbench {
    grid-template-columns: minmax(0, 1fr);
  }
  .explorer-rail {
    border-right: 0;
    border-bottom: 1px solid var(--bootui-border-alt);
  }
  .explorer-events {
    max-height: 15rem;
  }
  .explorer-filters {
    grid-template-columns: 1fr 1fr;
  }
  .explorer-filters input {
    grid-column: 1 / -1;
  }
  .explorer-viewport {
    order: 2;
  }
  .explorer-empty {
    padding: 1.5rem;
    min-height: 18rem;
  }
  .explorer-facts {
    grid-template-columns: 5rem minmax(0, 1fr);
  }
}
</style>
