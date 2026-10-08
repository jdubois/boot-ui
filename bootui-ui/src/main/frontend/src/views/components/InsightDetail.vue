<script setup>
import {inject} from 'vue'
import {formatNumber} from '../../utils/format.js'
import {isListed, isMachineColumn} from '../../utils/runtimeInsights.js'
import AiExportPreview from './AiExportPreview.vue'
import InsightText from './InsightText.vue'

// The open observation of Runtime Insights: its sentence, what to check, exemplar requests, evidence, and limits. The
// panel owns the state and provides it; its sentence names the region that holds this body.
const ctx = inject('runtimeInsights')
</script>

<template>
  <AiExportPreview
    v-if="ctx.aiExport"
    class="mb-3"
    heading="Copy observation for AI"
    :markdown="ctx.aiExport.markdown"
    :omissions="ctx.aiExport.omissions"
    @close="ctx.aiExport = null"
  />
  <div class="d-flex flex-wrap justify-content-between align-items-start gap-2">
    <p id="insight-sentence" class="insight-sentence mb-2"><InsightText :text="ctx.selected.sentence" /></p>
    <button
      v-if="!ctx.aiExport"
      type="button"
      class="btn btn-sm btn-outline-secondary text-nowrap insight-copy-ai"
      :disabled="!ctx.detail || ctx.detail.observation?.id !== ctx.selected.id"
      @click="ctx.openAiExport"
    >
      <i class="bi bi-robot me-1" aria-hidden="true"></i>Copy for AI
    </button>
  </div>
  <p v-if="!isListed(ctx.selected)" class="small mb-2 insight-unlisted-reason">
    {{
      ctx.selected.unlistedReason ? `Not listed by default: ${ctx.selected.unlistedReason}` : 'Not listed by default'
    }}
  </p>
  <p class="small text-muted mb-3">
    <template v-if="ctx.selected.eligible > 0">
      {{ formatNumber(ctx.selected.affected) }} of {{ formatNumber(ctx.selected.eligible) }} requests · linked by
      {{ ctx.tierLabel(ctx.selected.minimumTier) }} ·
    </template>
    {{ ctx.statusLabel(ctx.selected.status) }}
  </p>

  <h3 class="h6">What to check</h3>
  <ol class="small mb-3 insight-checks">
    <li v-for="check in ctx.selected.whatToCheck" :key="check"><InsightText :text="check" /></li>
  </ol>

  <template v-if="ctx.selected.exemplarRequestIds.length">
    <h3 class="h6">Open a request</h3>
    <ul class="list-inline small mb-3">
      <li v-for="requestId in ctx.selected.exemplarRequestIds" :key="requestId" class="list-inline-item">
        <router-link :to="{path: '/activity', query: {request: requestId}}">
          <code>{{ requestId }}</code>
        </router-link>
      </li>
    </ul>
  </template>

  <h3 class="h6">Evidence</h3>
  <div v-if="ctx.detailLoading" class="small text-muted mb-3" role="status">Loading evidence…</div>
  <div v-else-if="ctx.detailError" class="alert alert-warning small py-2">{{ ctx.detailError }}</div>
  <div v-else-if="ctx.detail && !ctx.detail.available" class="small text-muted mb-3">
    {{ ctx.detail.unavailableReason }}
  </div>
  <template v-else-if="ctx.detail">
    <div class="table-responsive mb-2">
      <table class="table table-sm align-middle insight-evidence mb-0" :class="{'insight-evidence-shares': ctx.shares}">
        <thead>
          <tr>
            <th
              v-for="(column, index) in ctx.detail.columns"
              :key="column"
              scope="col"
              :class="{'insight-number': ctx.numeric.has(index)}"
            >
              {{ column }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(row, index) in ctx.detail.rows"
            :key="index"
            :class="{'insight-evidence-top': ctx.shares && ctx.shares.top === index}"
          >
            <td
              v-for="(cell, column) in row.cells"
              :key="column"
              :class="{
                'insight-share-cell': ctx.shares && ctx.shares.column === column,
                'insight-number': ctx.numeric.has(column)
              }"
            >
              <span
                v-if="ctx.shares && ctx.shares.column === column && ctx.shares.shares[index] != null"
                class="insight-share"
              >
                <span class="insight-share-track" aria-hidden="true">
                  <span
                    class="insight-share-bar"
                    :class="{'insight-share-bar-top': ctx.shares.top === index}"
                    :style="{width: `${ctx.shares.shares[index]}%`}"
                  ></span>
                </span>
                <span class="insight-share-value">{{ cell }}</span>
              </span>
              <code v-else-if="isMachineColumn(ctx.detail.columns[column])" class="bootui-break-anywhere">{{
                cell
              }}</code>
              <InsightText v-else :text="cell" />
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="ctx.detail.truncated > 0" class="small text-muted mb-3">
      {{ formatNumber(ctx.detail.truncated) }} more rows not shown.
    </p>
  </template>

  <template v-if="ctx.selected.limitations.length">
    <h3 class="h6">Limits</h3>
    <ul class="small text-muted mb-0">
      <li v-for="limitation in ctx.selected.limitations" :key="limitation">
        <InsightText :text="limitation" />
      </li>
    </ul>
  </template>
</template>

<style scoped>
.insight-sentence {
  flex: 1 1 16rem;
  font-size: 1.15rem;
  font-weight: 700;
  max-width: 75ch;
}

.insight-share-cell {
  min-width: 11rem;
  width: 40%;
}

.insight-share {
  display: flex;
  align-items: center;
  gap: 0.6rem;
}

.insight-share-track {
  position: relative;
  flex: 1 1 auto;
  height: 0.6rem;
  background: var(--bs-secondary-bg);
  border-radius: var(--bootui-radius-xs);
  overflow: hidden;
}

.insight-share-bar {
  position: absolute;
  inset: 0 auto 0 0;
  min-width: 2px;
  background: var(--bootui-text-muted);
  border-radius: var(--bootui-radius-xs);
}

.insight-share-bar-top {
  background: var(--bootui-green-dark);
}

.insight-share-value {
  flex: 0 0 3.25rem;
  text-align: end;
  font-variant-numeric: tabular-nums;
}

.insight-evidence-shares td:first-child {
  white-space: nowrap;
}

@media (max-width: 575.98px) {
  .insight-evidence-shares td:first-child {
    white-space: normal;
  }

  .insight-share-cell {
    min-width: 7rem;
  }

  .insight-share-value {
    flex-basis: 2.75rem;
  }
}

.insight-number {
  text-align: end;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.insight-evidence-top td:first-child,
.insight-evidence-top .insight-share-value {
  font-weight: 700;
}

.insight-checks {
  max-width: 75ch;
  padding-left: 1.25rem;
}

.insight-unlisted-reason {
  color: var(--bs-secondary-color);
  font-style: italic;
}
</style>
