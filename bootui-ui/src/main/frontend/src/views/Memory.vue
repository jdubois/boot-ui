<script setup>
import {computed} from 'vue'
import {useAdvisorPanel} from '../utils/useAdvisorPanel.js'
import {panelProps} from '../utils/panelState.js'
import AdvisorSummary from './components/AdvisorSummary.vue'
import PanelHeader from './components/PanelHeader.vue'
import SpinnerButton from './components/SpinnerButton.vue'

const props = defineProps(panelProps)
const panel = useAdvisorPanel(props, {
  apiPath: 'api/memory',
  loadErrorMessage: 'Unable to load Memory Advisor report',
  scanErrorMessage: 'Unable to run memory checks',
  emptyScanPrompt: 'Run memory checks to see advisor findings',
  emptyNoFindings: 'No Memory Advisor findings',
  countNoun: 'observation'
})

const summary = computed(() => panel.report?.summary || null)

function formatBytes(value) {
  if (value === null || value === undefined || value < 0) return 'n/a'
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
  let size = value
  let unit = 0
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024
    unit += 1
  }
  return `${size.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-clipboard2-pulse"
      title="Memory"
      subtitle="Rule-based JVM memory, GC, and thread health findings from the live management beans."
      :loading="panel.loading"
      :error="panel.error"
    >
      <template #actions>
        <SpinnerButton
          :loading="panel.loading"
          :disabled="panel.loading || panel.readOnly"
          class="btn btn-primary"
          type="button"
          label="Run memory checks"
          loading-label="Running..."
          @click="panel.runScan"
        />
      </template>
    </PanelHeader>
    <div v-if="panel.actionMessage" class="alert alert-warning">{{ panel.actionMessage }}</div>

    <template v-if="panel.report">
      <AdvisorSummary
        :score="panel.score"
        :dismissed-count="panel.dismissedResults.length"
        :scan-status-label="panel.scanStatusLabel(panel.report.scan.status)"
        :scan-status-class="panel.scanStatusBadgeClass(panel.report.scan.status)"
        :scan-time="panel.scanTime()"
        :metrics="[
          {label: 'Rules evaluated', value: panel.report.rulesEvaluated},
          {label: 'Advisor findings', value: panel.report.violationsFound},
          {label: 'Heap used', value: summary ? summary.heapUsedPercent + '%' : '—'}
        ]"
      />
      <div class="alert alert-info">
        <strong>Heuristic JVM memory and thread health rules.</strong>
        {{ panel.report.disclaimer }}
        <span v-if="panel.readOnly">Scanning is read-only. {{ panel.readOnlyReason }}</span>
      </div>

      <div class="row g-3 mb-3">
        <div class="col-lg-5">
          <div class="card h-100">
            <div class="card-header fw-semibold">Findings by severity</div>
            <div class="card-body">
              <div v-if="!panel.hasScanData" class="text-center text-muted py-4">
                <i class="bi bi-search fs-2 d-block mb-2"></i>
                <div class="fw-semibold text-body">No Memory Advisor data yet</div>
                <div>Run memory checks to populate advisor findings.</div>
              </div>
              <div
                v-for="item in panel.report.severityCounts"
                v-else
                :key="item.severity"
                class="row align-items-center g-2 mb-2"
              >
                <div class="col-3">
                  <span :class="panel.severityClass(item.severity)" class="badge">{{ item.severity }}</span>
                </div>
                <div class="col">
                  <div :aria-label="`${item.severity} findings: ${item.count}`" class="progress" role="img">
                    <div
                      :class="panel.severityClass(item.severity)"
                      :style="{width: panel.severityWidth(item.count)}"
                      class="progress-bar"
                    ></div>
                  </div>
                </div>
                <div class="col-auto small text-muted">{{ item.count }}</div>
              </div>
            </div>
          </div>
        </div>

        <div class="col-lg-7">
          <div class="card h-100">
            <div class="card-header fw-semibold">Runtime snapshot</div>
            <div class="card-body">
              <div v-if="!summary" class="text-muted">Run memory checks to capture a runtime snapshot.</div>
              <dl v-else class="row mb-0 small">
                <dt class="col-6">Heap used</dt>
                <dd class="col-6 text-end">
                  {{ formatBytes(summary.heapUsedBytes) }} / {{ formatBytes(summary.heapMaxBytes) }} ({{
                    summary.heapUsedPercent
                  }}%)
                </dd>
                <dt class="col-6">Live threads</dt>
                <dd class="col-6 text-end">{{ summary.liveThreads }}</dd>
                <dt class="col-6">Peak threads</dt>
                <dd class="col-6 text-end">{{ summary.peakThreads }}</dd>
                <dt class="col-6">Loaded classes</dt>
                <dd class="col-6 text-end">{{ summary.loadedClasses }}</dd>
                <dt class="col-6">Deadlock detected</dt>
                <dd class="col-6 text-end">
                  <span :class="summary.deadlockDetected ? 'text-bg-danger' : 'text-bg-success'" class="badge">
                    {{ summary.deadlockDetected ? 'Yes' : 'No' }}
                  </span>
                </dd>
                <dt class="col-6">Heap histogram</dt>
                <dd class="col-6 text-end">
                  <span :class="summary.histogramAvailable ? 'text-bg-success' : 'text-bg-secondary'" class="badge">
                    {{ summary.histogramAvailable ? 'Collected' : 'Not collected' }}
                  </span>
                </dd>
              </dl>
            </div>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
          <div>
            <div class="fw-semibold">Rule results</div>
            <div class="text-muted small">
              <template v-if="panel.hasScanData && panel.visibleResults.length > 0">
                {{ panel.visibleResults.length }} {{ panel.pluralize(panel.visibleResults.length, 'finding') }}, sorted
                by importance
              </template>
              <template v-else>{{ panel.visibleResults.length }} advisor finding(s)</template>
            </div>
          </div>
          <span
            v-if="panel.hasScanData && panel.visibleResults.length === 0 && panel.dismissedResults.length === 0"
            class="badge text-bg-success"
            >No findings</span
          >
        </div>
        <div v-if="panel.visibleResults.length === 0" class="card-body text-center text-muted py-5">
          <i class="bi bi-clipboard2-pulse fs-2 d-block mb-2"></i>
          <div class="fw-semibold text-body">{{ panel.emptyRuleResultsTitle }}</div>
          <div>Validate findings against the application's workload and a profiler before acting.</div>
        </div>
        <div v-else class="list-group list-group-flush">
          <div v-for="result in panel.visibleResults" :key="result.id" class="list-group-item">
            <div class="d-flex flex-wrap align-items-center gap-2 mb-2">
              <span :class="panel.statusClass(result.status)" class="badge">{{ result.status }}</span>
              <span :class="panel.severityClass(result.severity)" class="badge">{{ result.severity }}</span>
              <span class="badge text-bg-light border">{{ result.category }}</span>
              <span class="text-muted small">{{ result.id }}</span>
              <button
                class="btn btn-sm btn-outline-secondary ms-auto"
                type="button"
                :disabled="panel.dismissLoading"
                @click="panel.dismiss(result.id)"
                title="Dismiss this rule"
              >
                <i class="bi bi-eye-slash me-1"></i>Dismiss
              </button>
            </div>
            <h3 class="h6 mb-1">{{ result.name }}</h3>
            <div class="small text-muted mb-2">{{ result.description }}</div>
            <div class="small mb-2">
              <strong>What happened:</strong>
              {{ panel.violationCountLabel(result.violationCount) }} for this rule.
            </div>
            <div v-if="result.sampleViolations && result.sampleViolations.length" class="mb-2">
              <div class="small fw-semibold">
                Sample details (showing {{ result.sampleViolations.length }} of {{ result.violationCount }})
              </div>
              <ul class="small mb-0">
                <li v-for="(sample, index) in result.sampleViolations" :key="index" class="font-monospace">
                  {{ sample }}
                </li>
              </ul>
            </div>
            <div class="small">
              <strong>Recommendation:</strong>
              {{ result.recommendation }}
              <a
                v-if="result.learnMoreUrl"
                :href="result.learnMoreUrl"
                class="ms-1"
                rel="noopener noreferrer"
                target="_blank"
              >
                Learn more
              </a>
            </div>
          </div>
        </div>
        <template v-if="panel.report.analysisErrors && panel.report.analysisErrors.length > 0">
          <div class="card-header text-muted small">
            <i class="bi bi-exclamation-triangle me-1"></i>Analysis errors ({{ panel.report.analysisErrors.length }}) —
            rules that could not be evaluated and were excluded from the findings above
          </div>
          <div class="list-group list-group-flush">
            <div v-for="result in panel.report.analysisErrors" :key="result.id" class="list-group-item">
              <div class="d-flex flex-wrap align-items-center gap-2 mb-1">
                <span class="badge text-bg-secondary">{{ result.status }}</span>
                <span class="badge text-bg-light border">{{ result.category }}</span>
                <span class="text-muted small">{{ result.id }}</span>
              </div>
              <div class="small fw-semibold">{{ result.name }}</div>
              <ul v-if="result.sampleViolations && result.sampleViolations.length" class="small mb-0 mt-1">
                <li v-for="(sample, index) in result.sampleViolations" :key="index">{{ sample }}</li>
              </ul>
            </div>
          </div>
        </template>
        <template v-if="panel.dismissedResults.length > 0">
          <div class="card-header text-muted small">
            <i class="bi bi-eye-slash me-1"></i>Dismissed rules ({{ panel.dismissedResults.length }}) — not counted in
            score
          </div>
          <div class="list-group list-group-flush">
            <div v-for="result in panel.dismissedResults" :key="result.id" class="list-group-item opacity-50">
              <div class="d-flex flex-wrap align-items-center gap-2 mb-1">
                <span :class="panel.statusClass(result.status)" class="badge">{{ result.status }}</span>
                <span :class="panel.severityClass(result.severity)" class="badge">{{ result.severity }}</span>
                <span class="badge text-bg-light border">{{ result.category }}</span>
                <span class="text-muted small">{{ result.id }}</span>
                <button
                  class="btn btn-sm btn-outline-secondary ms-auto"
                  type="button"
                  :disabled="panel.dismissLoading"
                  @click="panel.restore(result.id)"
                  title="Restore this rule"
                >
                  <i class="bi bi-eye me-1"></i>Restore
                </button>
              </div>
              <div class="small fw-semibold">{{ result.name }}</div>
            </div>
          </div>
        </template>
      </div>
    </template>
  </div>
</template>
