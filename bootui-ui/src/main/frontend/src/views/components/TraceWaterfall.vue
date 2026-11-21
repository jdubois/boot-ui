<script setup>
import {computed, ref, watch} from 'vue'
import {formatDuration} from '../../utils/format.js'

const props = defineProps({
  spans: {type: /** @type {import('vue').PropType<any[]>} */ (Array), required: true},
  compact: {type: Boolean, default: false},
  focusOnOverflow: {type: Boolean, default: false}
})
const scrollRegion = ref(null)
const hasHorizontalOverflow = ref(false)

const waterfall = computed(() => {
  if (!props.spans || props.spans.length === 0) return null
  const spans = [...props.spans]
  const minStart = spans.reduce((m, s) => Math.min(s.startEpochNanos, m), spans[0].startEpochNanos)
  const maxEnd = spans.reduce((m, s) => Math.max(s.endEpochNanos, m), spans[0].endEpochNanos)
  const totalNanos = Math.max(maxEnd - minStart, 1)
  const byStart = (a, b) => a.startEpochNanos - b.startEpochNanos
  const ids = new Set(spans.map((s) => s.spanId))
  const children = new Map()
  const roots = []
  for (const s of spans) {
    if (s.parentSpanId && ids.has(s.parentSpanId) && s.parentSpanId !== s.spanId) {
      if (!children.has(s.parentSpanId)) children.set(s.parentSpanId, [])
      children.get(s.parentSpanId).push(s)
    } else {
      roots.push(s)
    }
  }
  const ordered = []
  const seen = new Set()
  const visit = (s, depth) => {
    if (seen.has(s)) return
    seen.add(s)
    ordered.push({
      ...s,
      depth: Math.min(depth, 8),
      offsetPct: ((s.startEpochNanos - minStart) / totalNanos) * 100,
      widthPct: Math.max((s.durationNanos / totalNanos) * 100, 0.5)
    })
    for (const c of (children.get(s.spanId) || []).sort(byStart)) visit(c, depth + 1)
  }
  for (const r of roots.sort(byStart)) visit(r, 0)
  for (const s of spans.sort(byStart)) visit(s, 0)
  const ticks = [0, 25, 50, 75, 100].map((pct) => ({pct, label: formatDuration((totalNanos * pct) / 100)}))
  return {spans: ordered, totalNanos, ticks}
})

// Dialogs need an explicit Tab stop for a scroll region, but only while it actually scrolls.
watch(
  [scrollRegion, () => props.focusOnOverflow, waterfall],
  ([region, focusOnOverflow], _, onCleanup) => {
    if (!region || !focusOnOverflow) {
      hasHorizontalOverflow.value = false
      return
    }
    const measure = () => {
      hasHorizontalOverflow.value = region.scrollWidth > region.clientWidth
    }
    measure()
    if (typeof ResizeObserver === 'function') {
      const observer = new ResizeObserver(measure)
      observer.observe(region)
      onCleanup(() => observer.disconnect())
    }
  },
  {flush: 'post'}
)

const kindLegend = [
  {cls: 'kind-server', label: 'Server'},
  {cls: 'kind-client', label: 'Client'},
  {cls: 'kind-async', label: 'Producer / consumer'},
  {cls: 'kind-internal', label: 'Internal'},
  {cls: 'kind-error', label: 'Error'}
]

function spanColor(span) {
  if (span.statusCode === 'ERROR') return 'kind-error'
  if ((span.kind || '').includes('SERVER')) return 'kind-server'
  if ((span.kind || '').includes('CLIENT')) return 'kind-client'
  if ((span.kind || '').includes('PRODUCER') || (span.kind || '').includes('CONSUMER')) return 'kind-async'
  return 'kind-internal'
}
</script>

<template>
  <div v-if="waterfall" :class="{'waterfall-compact': compact}" class="trace-waterfall">
    <div class="waterfall-legend" aria-hidden="true">
      <span v-for="k in kindLegend" :key="k.cls" class="waterfall-legend-item">
        <i :class="k.cls" class="waterfall-swatch"></i>{{ k.label }}
      </span>
    </div>
    <div
      ref="scrollRegion"
      aria-label="Trace span waterfall"
      class="waterfall"
      :class="{'bootui-keyboard-target': hasHorizontalOverflow}"
      :tabindex="hasHorizontalOverflow ? 0 : null"
      role="table"
    >
      <div class="waterfall-row waterfall-ruler" role="row">
        <div role="columnheader">Span</div>
        <div class="waterfall-ticks" role="columnheader">
          <span
            v-for="t in waterfall.ticks"
            :key="t.pct"
            :class="{'tick-end': t.pct === 100, 'tick-minor': t.pct === 25 || t.pct === 75, 'tick-start': t.pct === 0}"
            :style="{left: t.pct + '%'}"
            class="waterfall-tick"
            >{{ t.label }}</span
          >
        </div>
        <div class="text-end" role="columnheader">Time</div>
      </div>
      <div
        v-for="(s, i) in waterfall.spans"
        :key="s.spanId + '-' + i"
        :style="{'--depth': s.depth}"
        class="waterfall-row waterfall-span"
        role="row"
      >
        <div :title="s.name" class="waterfall-label" role="cell">
          <span :class="spanColor(s)" aria-hidden="true" class="waterfall-dot"></span>
          <span class="waterfall-label-text">
            <span class="text-truncate d-block waterfall-name">{{ s.name }}</span>
            <span class="waterfall-service text-truncate d-block">{{ s.serviceName || '—' }}</span>
          </span>
        </div>
        <div class="waterfall-track" role="cell">
          <div
            :class="spanColor(s)"
            :style="{left: s.offsetPct + '%', width: s.widthPct + '%'}"
            :title="s.name + ' — ' + formatDuration(s.durationNanos)"
            class="waterfall-bar"
          ></div>
        </div>
        <div class="waterfall-duration" role="cell">{{ formatDuration(s.durationNanos) }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.trace-waterfall {
  --wf-server: var(--bs-primary);
  --wf-client: var(--bootui-info-text, var(--bootui-info));
  --wf-async: var(--bootui-warning);
  --wf-internal: var(--bootui-text-subtle);
  --wf-error: var(--bootui-danger);
}

.waterfall-compact .waterfall {
  --wf-label: 176px;
}

.waterfall-compact .waterfall-tick.tick-minor {
  display: none;
}

.waterfall-compact .waterfall-label {
  padding-left: calc(var(--depth, 0) * 8px);
}

.waterfall-compact .waterfall-row {
  grid-template-columns: var(--wf-label) minmax(120px, 1fr) 64px;
  min-width: 0;
  column-gap: 8px;
  padding: 0 8px;
}

.waterfall {
  --wf-label: 240px;
  display: flex;
  flex-direction: column;
  overflow-x: auto;
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-sm);
  font-size: 0.8125rem;
}

.waterfall-row {
  display: grid;
  grid-template-columns: var(--wf-label) minmax(260px, 1fr) 72px;
  align-items: center;
  column-gap: 12px;
  min-width: 600px;
  padding: 0 12px;
}

.waterfall-ruler {
  position: sticky;
  top: 0;
  padding-block: 6px;
  border-bottom: 1px solid var(--bootui-border);
  background: var(--bootui-surface-alt);
  color: var(--bootui-text-muted);
  font-size: 0.6875rem;
  font-weight: 600;
  letter-spacing: 0.02em;
  font-variant-numeric: tabular-nums;
}

.waterfall-ticks {
  position: relative;
  height: 1em;
}

.waterfall-tick {
  position: absolute;
  top: 0;
  transform: translateX(-50%);
  white-space: nowrap;
  font-weight: 500;
}

.waterfall-tick.tick-start {
  transform: none;
}

.waterfall-tick.tick-end {
  transform: translateX(-100%);
}

.waterfall-span {
  min-height: 32px;
  border-bottom: 1px solid var(--bootui-border-subtle, var(--bootui-border));
  transition: background-color 0.12s ease;
}

.waterfall-span:last-child {
  border-bottom: 0;
}

.waterfall-span:hover {
  background: var(--bootui-surface-alt);
}

.waterfall-label {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding-left: calc(var(--depth, 0) * 14px);
  overflow: hidden;
}

.waterfall-label-text {
  min-width: 0;
  line-height: 1.25;
}

.waterfall-name {
  color: var(--bootui-text);
  font-weight: 500;
}

.waterfall-service {
  color: var(--bootui-text-muted);
  font-size: 0.6875rem;
}

.waterfall-dot,
.waterfall-swatch {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}

.waterfall-track {
  position: relative;
  height: 32px;
  background-image: repeating-linear-gradient(
    to right,
    var(--bootui-border-subtle, var(--bootui-border)) 0,
    var(--bootui-border-subtle, var(--bootui-border)) 1px,
    transparent 1px,
    transparent 25%
  );
  background-position: left top;
}

.waterfall-bar {
  position: absolute;
  top: 9px;
  height: 14px;
  min-width: 3px;
  max-width: calc(100% - 0px);
  border-radius: var(--bootui-radius-xs);
  box-shadow: 0 0 0 1px color-mix(in srgb, currentColor 0%, transparent);
  transition: filter 0.12s ease;
}

.waterfall-span:hover .waterfall-bar {
  filter: brightness(1.12);
}

.waterfall-duration {
  text-align: right;
  color: var(--bootui-text);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.kind-server {
  background-color: var(--wf-server);
}

.kind-client {
  background-color: var(--wf-client);
}

.kind-async {
  background-color: var(--wf-async);
}

.kind-internal {
  background-color: var(--wf-internal);
}

.kind-error {
  background-color: var(--wf-error);
}

.waterfall-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;
  margin-bottom: 8px;
  color: var(--bootui-text-muted);
  font-size: 0.75rem;
}

.waterfall-legend-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

@media (prefers-reduced-motion: reduce) {
  .waterfall-span,
  .waterfall-bar {
    transition: none;
  }
}
</style>
