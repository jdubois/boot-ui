<script setup>
import {computed, inject, ref} from 'vue'

import {formatBytes, formatDuration, formatNumber} from '../../utils/format.js'
import RouteWhySlow from './RouteWhySlow.vue'

// The runtime journal's view of one request (docs/PLAN-v2.md §5.3, §5.11): its work on one timeline, the collections
// that completed while it ran, the CPU time and memory it used, how it compares with its route, and what it touched.
// Purely presentational: the drawer fetches the profile when the developer opens a request. Its handoffs are the tasks
// the BootUI agent propagated from this request to an executor (M5-2).
const props = defineProps({
  profile: {type: Object, required: true},
  method: {type: String, default: null},
  path: {type: String, default: null},
  status: {type: Number, default: null}
})

const emit = defineEmits(['open-request', 'compare-with'])

const HTTP_METHODS = new Set(['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'])
const panels = inject('panels', ref(null))

const UNMEASURED_REASONS = {
  VIRTUAL_THREAD: 'it ran on a virtual thread, which the JVM does not measure',
  THREAD_ENDED: 'a thread it ran on ended before it could be read',
  UNSUPPORTED: 'this JVM does not measure per-thread CPU time and allocation'
}

const STANDINGS = {
  AT_OR_BELOW_P50: 'At or below the route median',
  ABOVE_P50: 'Slower than the route median',
  ABOVE_P95: 'Slower than 95% of the route'
}

const TOUCHED = [
  ['tables', 'Tables'],
  ['dataSources', 'Data sources'],
  ['transactions', 'Transactions'],
  ['caches', 'Caches'],
  ['messages', 'Messages sent'],
  ['restCalls', 'Hosts called'],
  ['logTemplates', 'Log events'],
  ['models', 'AI models']
]

const timeline = computed(() => props.profile.timeline ?? [])
const authorizations = computed(() => props.profile.authorizations ?? [])
const handoffs = computed(() => (props.profile.handoffs ?? []).map(handoffRow))
const lateHandoffs = computed(() => props.profile.lateHandoffs ?? 0)
const gcPauses = computed(() => props.profile.gcPauses ?? [])
const notes = computed(() => props.profile.notes ?? [])

const springSecurityUsable = computed(() => {
  if (!Array.isArray(panels.value?.panels)) {
    return (panels.value?.platform ?? 'spring-boot') !== 'quarkus'
  }
  const panel = panels.value.panels.find((candidate) => candidate.id === 'spring-security')
  return !!panel && panel.available !== false && panel.enabled !== false
})

const springSecurityExplainLink = computed(() => {
  const status = props.profile.status ?? props.status
  if ((status !== 401 && status !== 403) || !springSecurityUsable.value) return null
  const method = (props.method || '').trim().toUpperCase()
  const path = (props.path || '').trim()
  if (!HTTP_METHODS.has(method) || !path.startsWith('/')) return null
  return {path: '/spring-security', query: {method, path}}
})

const durationMillis = computed(() => Math.max(1, (props.profile.durationMicros ?? 0) / 1000))

const resourcesSummary = computed(() => {
  const r = props.profile.resources
  if (!r) return null
  const pauses = `${formatNumber(r.gcPauses)} GC ${r.gcPauses === 1 ? 'pause' : 'pauses'}${r.gcPausesTruncated ? ' or more' : ''}`
  if (r.availability === 'UNAVAILABLE') {
    return `CPU time and memory unavailable: ${UNMEASURED_REASONS[r.unmeasuredReason] || 'not measured'}. ${pauses}.`
  }
  const measured = `${formatDuration(r.cpuNanos)} CPU · ${formatBytes(r.allocatedBytes)} allocated · ${pauses}`
  if (r.availability === 'PARTIAL') {
    return `${measured}, at least: ${r.unmeasuredSegments} of ${r.segments} segments were not measured because ${
      UNMEASURED_REASONS[r.unmeasuredReason] || 'they could not be read'
    }.`
  }
  return measured
})

const comparison = computed(() => {
  const c = props.profile.routeComparison
  if (!c) return null
  const figures = `p50 ${formatDuration(micros(c.p50Micros))} · p95 ${formatDuration(micros(c.p95Micros))} over ${formatNumber(
    c.requests
  )} ${c.requests === 1 ? 'request' : 'requests'}`
  return {
    standing: c.standing
      ? STANDINGS[c.standing]
      : `Too few requests to compare (${formatNumber(c.requests)} of ${c.minimumRequests})`,
    figures,
    slow: c.standing === 'ABOVE_P95'
  }
})

const baselineComparison = computed(() => {
  const b = props.profile.baselineComparison
  if (!b || !b.baselineRequestId) return null
  const deltas = []
  if (b.durationDeltaMicros != null) {
    deltas.push({
      label: 'Duration',
      value: formatSignedDurationMicros(b.durationDeltaMicros),
      slow: b.durationDeltaMicros > 0
    })
  }
  if (b.sqlExecutionsDelta != null) {
    const duration =
      b.sqlDurationMicrosDelta != null ? ` (${formatSignedDurationMicros(b.sqlDurationMicrosDelta)})` : ''
    deltas.push({
      label: 'SQL',
      value: `${formatSignedCount(b.sqlExecutionsDelta, 'execution', 'executions')}${duration}`,
      slow: b.sqlExecutionsDelta > 0
    })
  }
  if (b.connectionWaitMicrosDelta != null) {
    deltas.push({
      label: 'Connection wait',
      value: formatSignedDurationMicros(b.connectionWaitMicrosDelta),
      slow: b.connectionWaitMicrosDelta > 0
    })
  }
  if (b.cacheHitsDelta != null || b.cacheMissesDelta != null) {
    const parts = []
    if (b.cacheMissesDelta != null) parts.push(formatSignedCount(b.cacheMissesDelta, 'miss', 'misses'))
    if (b.cacheHitsDelta != null) parts.push(formatSignedCount(b.cacheHitsDelta, 'hit', 'hits'))
    deltas.push({
      label: 'Cache',
      value: parts.join(', '),
      slow: (b.cacheMissesDelta ?? 0) > 0
    })
  }
  if (b.restClientCallsDelta != null) {
    const duration =
      b.restClientDurationMicrosDelta != null ? ` (${formatSignedDurationMicros(b.restClientDurationMicrosDelta)})` : ''
    deltas.push({
      label: 'REST client',
      value: `${formatSignedCount(b.restClientCallsDelta, 'call', 'calls')}${duration}`,
      slow: b.restClientCallsDelta > 0
    })
  }
  if (b.ormFlushesDelta != null || b.ormAutoFlushesDelta != null || b.ormMaxEntitiesDelta != null) {
    const parts = []
    if (b.ormFlushesDelta != null) parts.push(formatSignedCount(b.ormFlushesDelta, 'flush', 'flushes'))
    if (b.ormAutoFlushesDelta != null) {
      parts.push(formatSignedCount(b.ormAutoFlushesDelta, 'auto-flush', 'auto-flushes'))
    }
    if (b.ormMaxEntitiesDelta != null) {
      parts.push(`${formatSignedNumber(b.ormMaxEntitiesDelta)} peak entities in context`)
    }
    deltas.push({
      label: 'Hibernate',
      value: parts.join(', '),
      slow: (b.ormAutoFlushesDelta ?? 0) > 0
    })
  }
  if (b.gcPausesTarget != null && b.gcPausesBaseline != null) {
    deltas.push({
      label: 'JVM-wide GC pauses',
      value: `${formatNumber(b.gcPausesTarget)} vs ${formatNumber(b.gcPausesBaseline)} completed during the request`,
      slow: b.gcPausesTarget > b.gcPausesBaseline
    })
  }
  const candidates = (b.candidates ?? []).map((candidate) => ({
    requestId: candidate.requestId,
    label: `${candidate.requestId} · ${formatDuration(micros(candidate.durationMicros))} (${candidate.status})`
  }))
  return {
    baselineRequestId: b.baselineRequestId,
    baselineDuration: formatDuration(micros(b.baselineDurationMicros)),
    routeP50: b.routeP50Micros != null ? formatDuration(micros(b.routeP50Micros)) : null,
    deltas,
    distinctSqlShapes: b.distinctSqlShapesOnlyInTarget ?? [],
    candidates
  }
})

// The request's Hibernate sessions (docs/PLAN-v2.md §5.18, M4-9): statements, flushes, and the persistence context.
const ormSummary = computed(() => {
  const o = props.profile.orm
  if (!o) return null
  const parts = [
    `${formatNumber(o.statements)} ${o.statements === 1 ? 'statement' : 'statements'} in ${formatDuration(
      micros(o.statementMicros)
    )}`,
    `${formatNumber(o.flushes)} ${o.flushes === 1 ? 'flush' : 'flushes'}, ${formatNumber(o.autoFlushes)} ${
      o.autoFlushes === 1 ? 'auto-flush' : 'auto-flushes'
    } before a query (${formatDuration(micros(o.flushMicros + o.autoFlushMicros))} flushing)`
  ]
  if (o.entitiesInContext >= 0) parts.push(`up to ${formatNumber(o.entitiesInContext)} entities in context`)
  if (o.l2Hits + o.l2Misses + o.l2Puts > 0) {
    parts.push(`second-level cache ${formatNumber(o.l2Hits)} hits, ${formatNumber(o.l2Misses)} misses`)
  }
  return {
    sessions: `${formatNumber(o.sessions)} ${o.sessions === 1 ? 'session' : 'sessions'}`,
    details: parts.join(' · '),
    flushing: o.autoFlushes >= 3
  }
})

const touched = computed(() =>
  TOUCHED.map(([key, label]) => ({key, label, values: props.profile.touched?.[key] ?? []})).filter(
    (group) => group.values.length
  )
)

function micros(value) {
  return value == null ? null : value * 1000
}

function formatSignedNumber(value) {
  if (value == null) return ''
  const n = Number(value)
  if (!Number.isFinite(n)) return ''
  return n > 0 ? `+${formatNumber(n)}` : formatNumber(n)
}

function formatSignedCount(count, singular, pluralForm) {
  const abs = Math.abs(Number(count))
  return `${formatSignedNumber(count)} ${abs === 1 ? singular : pluralForm}`
}

function formatSignedDurationMicros(valueMicros) {
  if (valueMicros == null) return ''
  if (valueMicros === 0) return '0.0 µs'
  const sign = valueMicros > 0 ? '+' : '-'
  return `${sign}${formatDuration(Math.abs(valueMicros) * 1000)}`
}

// Position on the request's own axis, clamped so work that ends after the response still shows.
function barStyle(offsetMillis, durationMicros) {
  const left = Math.min(100, Math.max(0, (offsetMillis / durationMillis.value) * 100))
  const width = durationMicros == null ? 0 : Math.max(0.6, (durationMicros / 1000 / durationMillis.value) * 100)
  return {left: `${left}%`, width: durationMicros == null ? undefined : `${Math.min(width, 100 - left)}%`}
}

function plural(count, noun, pluralForm = `${noun}s`) {
  return `${formatNumber(count)} ${count === 1 ? noun : pluralForm}`
}

function simpleName(type) {
  if (!type) return 'task'
  return type.slice(type.lastIndexOf('.') + 1)
}

// A task the BootUI agent propagated from this request to an executor (docs/PLAN-v2.md M5-2): where it ran, what it
// did under its own execution, and how it ended.
function handoffRow(handoff, index) {
  const work = [
    handoff.sqlCount ? plural(handoff.sqlCount, 'SQL statement') : null,
    handoff.restClientCount ? plural(handoff.restClientCount, 'REST call') : null,
    handoff.messagingCount ? plural(handoff.messagingCount, 'message') : null
  ].filter(Boolean)
  return {
    key: `${handoff.executionId}:${index}`,
    task: simpleName(handoff.taskClass),
    taskClass: handoff.taskClass,
    hook: handoff.hook,
    thread: handoff.thread,
    timing: `${formatDuration(micros(handoff.durationMicros))} after ${formatDuration(
      micros(handoff.queuedMicros)
    )} queued`,
    did:
      handoff.executionId === handoff.parentExecutionId
        ? 'work shares the parent execution; per-task counts unavailable'
        : work.length
          ? work.join(', ')
          : 'nothing recorded',
    failed: handoff.failed,
    outcome: handoff.failed ? `failed: ${simpleName(handoff.exceptionClass)}` : 'completed',
    afterResponse: handoff.afterResponse
      ? `after response${
          handoff.afterResponseMicros ? ` by ${formatDuration(micros(handoff.afterResponseMicros))}` : ''
        }`
      : null,
    capped: handoff.capped,
    allocated: handoff.allocatedBytes == null ? null : formatBytes(handoff.allocatedBytes)
  }
}

function itemTitle(item) {
  const when = `at ${formatNumber(item.offsetMillis)} ms`
  return item.durationMicros == null ? when : `${when} for ${formatDuration(micros(item.durationMicros))}`
}

function onBaselineChange(event) {
  emit('compare-with', event.target.value)
}
</script>

<template>
  <section class="mb-3 request-journal" aria-labelledby="request-journal-title">
    <h3 id="request-journal-title" class="h6">Recorded by the runtime journal</h3>
    <p v-if="!profile.available" class="small text-muted mb-0">{{ profile.unavailableReason }}</p>
    <template v-else>
      <dl class="row small mb-2">
        <dt class="col-4">Route</dt>
        <dd class="col-8">
          <code>{{ profile.route }}</code>
        </dd>
        <template v-if="comparison">
          <dt class="col-4">Against its route</dt>
          <dd class="col-8">
            <span :class="{'request-journal__slow': comparison.slow}">{{ comparison.standing }}</span>
            <span class="text-muted"> · {{ comparison.figures }}</span>
          </dd>
        </template>
        <template v-if="ormSummary">
          <dt class="col-4">Hibernate</dt>
          <dd class="col-8">
            <span :class="{'request-journal__slow': ormSummary.flushing}">{{ ormSummary.sessions }}</span>
            <span class="text-muted"> · {{ ormSummary.details }}</span>
          </dd>
        </template>
        <template v-if="resourcesSummary">
          <dt class="col-4">Resources</dt>
          <dd class="col-8">{{ resourcesSummary }}</dd>
        </template>
      </dl>
      <RouteWhySlow
        v-if="profile.route && !profile.route.startsWith('Scheduled:') && !profile.route.startsWith('Message:')"
        :route="profile.route"
      />

      <div v-if="baselineComparison" class="request-journal__baseline mb-2">
        <div class="request-journal__baseline-header mb-1">
          <div class="d-flex flex-wrap align-items-center gap-2">
            <h4 class="h6 small text-muted mb-0">Compared with baseline request</h4>
            <button
              type="button"
              class="btn btn-outline-secondary btn-sm request-journal__baseline-link"
              :title="`Open baseline request ${baselineComparison.baselineRequestId}`"
              @click="emit('open-request', baselineComparison.baselineRequestId)"
            >
              <code>{{ baselineComparison.baselineRequestId }}</code>
            </button>
            <span class="text-muted small">
              {{ baselineComparison.baselineDuration
              }}<template v-if="baselineComparison.routeP50"> · route p50 {{ baselineComparison.routeP50 }}</template>
            </span>
          </div>
          <div v-if="baselineComparison.candidates.length" class="request-journal__baseline-picker">
            <label class="visually-hidden" :for="`baseline-select-${profile.requestId}`">
              Compare with baseline request
            </label>
            <select
              :id="`baseline-select-${profile.requestId}`"
              class="form-select form-select-sm request-journal__baseline-select"
              aria-label="Compare with baseline request"
              :value="baselineComparison.baselineRequestId"
              @change="onBaselineChange"
            >
              <option
                v-for="candidate in baselineComparison.candidates"
                :key="candidate.requestId"
                :value="candidate.requestId"
              >
                {{ candidate.label }}
              </option>
            </select>
          </div>
        </div>
        <dl v-if="baselineComparison.deltas.length" class="row small mb-1 request-journal__baseline-deltas">
          <template v-for="delta in baselineComparison.deltas" :key="delta.label">
            <dt class="col-4">{{ delta.label }}</dt>
            <dd class="col-8 mb-1">
              <span :class="{'request-journal__slow': delta.slow}">{{ delta.value }}</span>
            </dd>
          </template>
        </dl>
        <div v-if="baselineComparison.distinctSqlShapes.length" class="small mt-1">
          <div class="text-muted mb-1">Distinguishing SQL shapes in this request</div>
          <ul class="list-unstyled mb-0 request-journal__baseline-shapes">
            <li v-for="(shape, index) in baselineComparison.distinctSqlShapes" :key="index">
              <code>{{ shape }}</code>
            </li>
          </ul>
        </div>
      </div>

      <template v-if="authorizations.length">
        <h4 class="h6 small text-muted mb-1">Authorization decisions</h4>
        <div class="table-responsive mb-2">
          <table class="table table-sm small align-middle mb-0 request-journal__authorizations">
            <thead>
              <tr>
                <th scope="col">Target</th>
                <th scope="col">Authentication</th>
                <th scope="col">Rule</th>
                <th scope="col">Outcome</th>
                <th scope="col" class="text-end">Duration</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(decision, index) in authorizations" :key="index">
                <td>
                  <code>{{ decision.target }}</code>
                  <code v-if="decision.subject" class="ms-1">{{ decision.subject }}</code>
                </td>
                <td>
                  <code>{{ decision.authentication }}</code>
                  <span class="text-muted"> · {{ plural(decision.authorities ?? 0, 'authority', 'authorities') }}</span>
                </td>
                <td>
                  <code v-if="decision.rule">{{ decision.rule }}</code>
                  <span v-else class="text-muted">—</span>
                </td>
                <td>
                  <span class="badge" :class="decision.granted ? 'text-bg-success' : 'text-bg-warning'">
                    {{ decision.granted ? 'Granted' : 'Denied' }}
                  </span>
                </td>
                <td class="text-end text-nowrap">
                  {{
                    decision.durationMicros != null && decision.durationMicros > 0
                      ? formatDuration(micros(decision.durationMicros))
                      : '—'
                  }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
      <div v-if="springSecurityExplainLink" class="small mb-2 request-journal__security-explain">
        <router-link :to="springSecurityExplainLink" class="btn btn-outline-secondary btn-sm">
          Explain in Spring Security
        </router-link>
        <p class="text-muted mb-0 mt-1">
          Static filter-chain matching for this method and path; does not replay runtime credentials or method security.
        </p>
      </div>

      <h4 class="h6 small text-muted mb-1">Timeline</h4>
      <p v-if="!timeline.length && !gcPauses.length" class="small text-muted">No work of this request was recorded.</p>
      <ol v-else class="list-unstyled small mb-2 request-journal__timeline">
        <li class="request-journal__row request-journal__ruler" aria-hidden="true">
          <span>Source</span>
          <span>Work</span>
          <span class="request-journal__ticks">
            <span>0</span>
            <span>{{ formatDuration(durationMillis * 500000) }}</span>
            <span>{{ formatDuration(durationMillis * 1000000) }}</span>
          </span>
          <span class="text-end">Time</span>
        </li>
        <li v-for="(item, index) in timeline" :key="index" class="request-journal__row">
          <span class="request-journal__source">{{ item.source }}</span>
          <span class="request-journal__label" :title="item.label">
            <code>{{ item.label }}</code>
            <span v-if="item.detail" class="text-muted"> · {{ item.detail }}</span>
          </span>
          <span class="request-journal__track" aria-hidden="true">
            <span
              class="request-journal__bar"
              :class="[
                `request-journal__bar--${(item.severity || 'OK').toLowerCase()}`,
                {'request-journal__bar--instant': item.durationMicros == null}
              ]"
              :style="barStyle(item.offsetMillis, item.durationMicros)"
            ></span>
          </span>
          <span class="request-journal__when">{{ itemTitle(item) }}</span>
        </li>
        <li v-for="pause in gcPauses" :key="`${pause.collector}-${pause.gcId}`" class="request-journal__row">
          <span class="request-journal__source">gc</span>
          <span class="request-journal__label">
            {{ pause.collector }} #{{ pause.gcId }} completed during this request
            <span v-if="pause.cause" class="text-muted"> · {{ pause.cause }}</span>
          </span>
          <span class="request-journal__track" aria-hidden="true">
            <span
              v-if="pause.retained"
              class="request-journal__bar request-journal__bar--gc"
              :style="barStyle(pause.offsetMillis, (pause.pauseMillis ?? 0) * 1000)"
            ></span>
          </span>
          <span class="request-journal__when">
            {{ pause.retained ? `${formatNumber(pause.pauseMillis)} ms pause` : 'no longer retained' }}
          </span>
        </li>
      </ol>

      <template v-if="handoffs.length || lateHandoffs">
        <h4 class="h6 small text-muted mb-1">Handoffs</h4>
        <ul class="list-unstyled small mb-2 request-journal__handoffs">
          <li v-for="handoff in handoffs" :key="handoff.key" class="request-journal__handoff">
            <div>
              <code :title="handoff.taskClass">{{ handoff.task }}</code>
              <span class="text-muted"> on </span>
              <code>{{ handoff.thread }}</code>
              <span v-if="handoff.afterResponse" class="badge rounded-pill text-bg-warning ms-2">{{
                handoff.afterResponse
              }}</span>
              <span
                v-if="handoff.capped"
                class="badge rounded-pill text-bg-secondary ms-2"
                title="Ended more than bootui.agent.executors.max-handoff after it started: its later work is not attributed to this request"
                >past deadline</span
              >
            </div>
            <div class="text-muted">
              {{ handoff.timing }} · {{ handoff.did }} ·
              <span :class="{'request-journal__failed': handoff.failed}">{{ handoff.outcome }}</span>
              <template v-if="handoff.allocated"> · {{ handoff.allocated }} allocated</template>
              <template v-if="handoff.hook"> · via {{ handoff.hook }}</template>
            </div>
          </li>
        </ul>
        <p v-if="lateHandoffs" class="small text-muted request-journal__late-handoffs">
          {{ plural(lateHandoffs, 'later task') }} started more than bootui.agent.executors.max-handoff after this
          request ended: counted, not shown.
        </p>
      </template>

      <template v-if="touched.length">
        <h4 class="h6 small text-muted mb-1">Touched</h4>
        <dl class="row small mb-2">
          <template v-for="group in touched" :key="group.key">
            <dt class="col-4">{{ group.label }}</dt>
            <dd class="col-8">
              <code v-for="(value, index) in group.values" :key="index" class="request-journal__value">{{
                value
              }}</code>
            </dd>
          </template>
        </dl>
      </template>

      <ul v-if="notes.length" class="small text-muted mb-0">
        <li v-for="(note, index) in notes" :key="index">{{ note }}</li>
      </ul>
    </template>
  </section>
</template>

<style scoped>
.request-journal__timeline {
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-sm);
  overflow: hidden;
  font-size: 0.8125rem;
}

.request-journal__row {
  display: grid;
  grid-template-columns: 6.75rem minmax(0, 1fr) 34% 6.5rem;
  gap: 0.75rem;
  align-items: center;
  min-height: 2rem;
  padding: 0.2rem 0.75rem;
  border-bottom: 1px solid var(--bootui-border-subtle, var(--bootui-border));
  transition: background-color 0.12s ease;
}

.request-journal__row:last-child {
  border-bottom: 0;
}

.request-journal__row:not(.request-journal__ruler):hover {
  background: var(--bootui-surface-alt);
}

.request-journal__ruler {
  min-height: 0;
  padding-block: 0.375rem;
  background: var(--bootui-surface-alt);
  border-bottom-color: var(--bootui-border);
  color: var(--bootui-text-muted);
  font-size: 0.6875rem;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.request-journal__ticks {
  display: flex;
  justify-content: space-between;
  font-weight: 500;
}

.request-journal__source {
  justify-self: start;
  padding: 0.05rem 0.4rem;
  border-radius: var(--bootui-radius-pill);
  background: var(--bootui-surface-alt);
  border: 1px solid var(--bootui-border);
  font-family: var(--bs-font-monospace);
  font-size: 0.6875rem;
  color: var(--bootui-text-muted);
}

.request-journal__label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.request-journal__track {
  position: relative;
  height: 1.25rem;
  background-image: repeating-linear-gradient(
    to right,
    var(--bootui-border-subtle, var(--bootui-border)) 0,
    var(--bootui-border-subtle, var(--bootui-border)) 1px,
    transparent 1px,
    transparent 50%
  );
}

.request-journal__bar {
  position: absolute;
  top: 0.3rem;
  bottom: 0.3rem;
  min-width: 3px;
  background: var(--bootui-green-dark);
  border-radius: var(--bootui-radius-xs);
}

.request-journal__bar--instant {
  top: 0.15rem;
  bottom: 0.15rem;
  width: 2px;
  min-width: 2px;
}

.request-journal__bar--slow,
.request-journal__bar--warn {
  background: var(--bootui-warning);
}

.request-journal__bar--error {
  background: var(--bootui-danger);
}

.request-journal__bar--gc {
  min-width: 2px;
  background: var(--bootui-text-muted);
}

.request-journal__when {
  color: var(--bootui-text);
  text-align: end;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

@media (prefers-reduced-motion: reduce) {
  .request-journal__row {
    transition: none;
  }
}

.request-journal__slow {
  color: var(--bootui-warning-text-strong);
  font-weight: 600;
}

.request-journal__handoff {
  padding: 0.25rem 0;
  border-bottom: 1px solid var(--bootui-border);
}

.request-journal__failed {
  color: var(--bootui-danger-text);
  font-weight: 600;
}

.request-journal__value {
  display: inline-block;
  margin-inline-end: 0.5rem;
}

.request-journal__baseline {
  padding: 0.5rem 0.75rem;
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-sm);
  background: var(--bootui-surface-alt);
}

.request-journal__baseline-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
}

.request-journal__baseline-link {
  padding: 0.05rem 0.4rem;
  font-size: 0.75rem;
}

.request-journal__baseline-select {
  min-width: 11rem;
  font-family: var(--bs-font-monospace);
  font-size: 0.75rem;
}
</style>
