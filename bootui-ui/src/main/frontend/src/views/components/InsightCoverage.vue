<script setup>
import {inject} from 'vue'
import {formatNumber} from '../../utils/format.js'

// How this run's retained events are linked to their request, and by which source. Every layout of Runtime Insights
// renders this same body; the panel owns the state and provides it.
defineProps({
  /** Leaves out the run's one-line window, for a layout that already states it. */
  hideWindow: {type: Boolean, default: false}
})

const ctx = inject('runtimeInsights')
</script>

<template>
  <p v-if="!hideWindow" class="mb-2 small insight-window-text">
    <span class="fw-semibold">This run</span>
    <span class="text-muted"> · {{ ctx.windowText }}</span>
  </p>
  <div v-if="ctx.coverage.events > 0">
    <div
      class="insight-coverage-bar"
      role="img"
      :aria-label="
        'Events linked by ' + ctx.coverage.segments.map((segment) => `${segment.label} ${segment.share} %`).join(', ')
      "
    >
      <span
        v-for="segment in ctx.coverage.segments.filter((segment) => segment.count > 0)"
        :key="segment.id"
        :class="`insight-coverage-${segment.id}`"
        :style="{flexGrow: segment.count}"
      ></span>
    </div>
    <ul class="list-inline small mb-0 mt-2 insight-coverage-legend">
      <li class="list-inline-item text-muted">Linked by</li>
      <li v-for="segment in ctx.coverage.segments" :key="segment.id" class="list-inline-item">
        <span :class="`insight-coverage-swatch insight-coverage-${segment.id}`" aria-hidden="true"></span>
        {{ segment.label }} <span class="fw-semibold">{{ segment.share }} %</span>
      </li>
    </ul>
    <details class="small mt-2 insight-coverage-sources">
      <summary>By source</summary>
      <div class="table-responsive mt-2">
        <table class="table table-sm align-middle mb-0">
          <thead>
            <tr>
              <th scope="col">Source</th>
              <th scope="col" class="text-end">Events</th>
              <th scope="col" class="text-end">Request id</th>
              <th scope="col" class="text-end">Trace id</th>
              <th scope="col" class="text-end">Run or message</th>
              <th scope="col" class="text-end">Outside requests</th>
              <th scope="col" class="text-end">Dropped</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="source in ctx.sources" :key="source.source">
              <td>
                <code>{{ source.source }}</code>
              </td>
              <td class="text-end">{{ formatNumber(source.events) }}</td>
              <td class="text-end">{{ formatNumber(source.byRequestId) }}</td>
              <td class="text-end">{{ formatNumber(source.byTraceId) }}</td>
              <td class="text-end">{{ formatNumber(source.byExecutionId) }}</td>
              <td class="text-end">{{ formatNumber(source.unlinked) }}</td>
              <td class="text-end">{{ formatNumber(source.dropped) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </details>
  </div>
</template>

<style scoped>
.insight-coverage-bar {
  display: flex;
  height: 0.5rem;
  border-radius: var(--bootui-radius-pill);
  overflow: hidden;
  background: var(--bs-secondary-bg);
}

.insight-coverage-bar > span {
  flex-basis: 0;
  min-width: 2px;
}

.insight-coverage-swatch {
  display: inline-block;
  width: 0.65rem;
  height: 0.65rem;
  border-radius: 50%;
  margin-right: 0.25rem;
  vertical-align: baseline;
}

.insight-coverage-request {
  background: var(--bootui-green);
}

.insight-coverage-trace {
  background: var(--bootui-blue);
}

.insight-coverage-execution {
  background: var(--bs-secondary-color);
}

.insight-coverage-none {
  background: var(--bs-border-color);
}
</style>
