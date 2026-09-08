<script setup>
import {computed, ref} from 'vue'
import {useAdvisorPanel} from '../utils/useAdvisorPanel.js'
import {panelProps} from '../utils/panelState.js'
import AdvisorSummary from './components/AdvisorSummary.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import SpinnerButton from './components/SpinnerButton.vue'

const props = defineProps(panelProps)
const panel = useAdvisorPanel(props, {
  apiPath: 'api/database-advisor',
  loadErrorMessage: 'Unable to load Database report',
  scanErrorMessage: 'Unable to run Database checks',
  emptyScanPrompt: 'Run Database checks to inspect the physical schema',
  emptyNoFindings: 'No Database findings',
  countNoun: 'finding'
})

const showDiagnostics = ref(false)

const DATA_SOURCE_STATUS_CLASSES = {
  AVAILABLE: 'text-bg-success',
  PARTIAL: 'text-bg-warning',
  FAILED: 'text-bg-danger'
}

const DATA_SOURCE_STATUS_LABELS = {
  AVAILABLE: 'Read',
  PARTIAL: 'Partly read',
  FAILED: 'Unreadable'
}

const DIAGNOSTIC_CLASSES = {
  ERROR: 'text-bg-danger',
  WARNING: 'text-bg-warning',
  INFO: 'text-bg-secondary'
}

// Falls back to the plain name list so a report from an older adapter still renders every datasource.
const dataSources = computed(() => {
  const detailed = panel.report?.dataSources
  if (detailed && detailed.length > 0) return detailed
  return (panel.report?.dataSourceNames || []).map((name) => ({name, status: 'AVAILABLE'}))
})

const diagnostics = computed(() => panel.report?.diagnostics || [])

const incompleteScanMessage = computed(() => {
  const report = panel.report
  if (!report || !panel.hasScanData) return null
  const reasons = []
  const unreadable = dataSources.value.filter((dataSource) => dataSource.status === 'FAILED').length
  if (unreadable > 0) {
    reasons.push(`${unreadable} ${panel.pluralize(unreadable, 'datasource')} could not be read`)
  }
  if (report.truncated) {
    reasons.push('a scan bound was reached, so some findings may be missing')
  }
  if (report.rulesErrored > 0) {
    reasons.push(`${report.rulesErrored} ${panel.pluralize(report.rulesErrored, 'rule')} failed to evaluate`)
  }
  if (reasons.length === 0) return null
  return `${reasons.join('; ')}. These are reported below as diagnostics and are not counted as findings.`
})

function dataSourceStatusClass(status) {
  return DATA_SOURCE_STATUS_CLASSES[status] || 'text-bg-secondary'
}

function dataSourceStatusLabel(status) {
  return DATA_SOURCE_STATUS_LABELS[status] || status
}

function diagnosticClass(level) {
  return DIAGNOSTIC_CLASSES[level] || 'text-bg-light border text-dark'
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-hdd-rack"
      title="Database"
      subtitle="Read-only JDBC schema introspection (tables, columns, keys, indexes) plus Hibernate mapping cross-reference."
      :loading="panel.loading"
      :error="panel.error"
    >
      <template #actions>
        <SpinnerButton
          :loading="panel.loading"
          :disabled="panel.loading || panel.readOnly"
          class="btn btn-primary"
          type="button"
          label="Run Database checks"
          loading-label="Running..."
          @click="panel.runScan"
        />
      </template>
    </PanelHeader>
    <div v-if="panel.actionMessage" class="alert alert-warning" role="status" aria-live="polite">
      {{ panel.actionMessage }}
    </div>

    <PanelSkeleton v-if="panel.initialLoading" />

    <template v-if="panel.report">
      <AdvisorSummary
        :score="panel.score"
        :score-completeness="panel.assessment.completeness"
        :score-label="panel.assessment.label"
        :score-reason="panel.assessment.reason"
        :dismissed-count="panel.dismissedResults.length"
        :scan-status-label="panel.scanStatusLabel(panel.report.scan.status)"
        :scan-status-class="panel.scanStatusBadgeClass(panel.report.scan.status)"
        :scan-time="panel.scanTime()"
        :metrics="[
          {label: 'Rules evaluated', value: panel.report.rulesEvaluated},
          {label: 'Advisor findings', value: panel.report.violationsFound},
          {label: 'Tables analysed', value: panel.report.tablesAnalyzed},
          {
            label: 'Rules not run',
            value: (panel.report.rulesSkipped || 0) + (panel.report.rulesErrored || 0),
            hint: 'Skipped or errored — see diagnostics'
          }
        ]"
      />
      <div class="alert alert-info">
        <strong>Heuristic database schema rules.</strong>
        {{ panel.report.disclaimer }}
        <span v-if="panel.readOnly">Scanning is read-only. {{ panel.readOnlyReason }}</span>
      </div>

      <div v-if="incompleteScanMessage" class="alert alert-warning" role="status">
        <i class="bi bi-exclamation-triangle me-1"></i>
        <strong>Incomplete scan.</strong>
        {{ incompleteScanMessage }}
      </div>

      <div class="row g-3 mb-3">
        <div class="col-lg-5">
          <div class="card h-100">
            <div class="card-header"><h3>Findings by severity</h3></div>
            <div class="card-body">
              <div v-if="!panel.hasScanData" class="text-center text-muted py-4">
                <i class="bi bi-search fs-2 d-block mb-2"></i>
                <div class="fw-semibold text-body">No Database data yet</div>
                <div>Run Database checks to populate advisor findings.</div>
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
            <div class="card-header"><h3>Datasources</h3></div>
            <div class="card-body">
              <div v-if="dataSources.length === 0" class="text-muted">No DataSource bean was detected.</div>
              <ul v-else class="list-unstyled mb-0">
                <li v-for="dataSource in dataSources" :key="dataSource.name" class="mb-2">
                  <div class="d-flex flex-wrap align-items-center gap-2">
                    <span :class="dataSourceStatusClass(dataSource.status)" class="badge">{{
                      dataSourceStatusLabel(dataSource.status)
                    }}</span>
                    <span class="font-monospace small"><i class="bi bi-hdd-stack me-1"></i>{{ dataSource.name }}</span>
                    <span v-if="dataSource.product" class="text-muted small">{{ dataSource.product }}</span>
                    <span v-if="dataSource.identifierCase" class="text-muted small"
                      >{{ dataSource.identifierCase.toLowerCase() }}-case identifiers</span
                    >
                    <span v-if="dataSource.truncated" class="badge text-bg-warning">Truncated</span>
                  </div>
                  <div v-if="dataSource.message" class="small text-muted font-monospace">{{ dataSource.message }}</div>
                </li>
              </ul>
            </div>
          </div>
        </div>
      </div>

      <div v-if="diagnostics.length > 0" class="card mb-3">
        <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
          <div>
            <div class="fw-semibold">Scan diagnostics</div>
            <div class="text-muted small">
              {{ diagnostics.length }} {{ panel.pluralize(diagnostics.length, 'note') }} — not counted as findings
            </div>
          </div>
          <button
            class="btn btn-sm btn-outline-secondary"
            type="button"
            :aria-expanded="showDiagnostics"
            @click="showDiagnostics = !showDiagnostics"
          >
            {{ showDiagnostics ? 'Hide' : 'Show' }} diagnostics
          </button>
        </div>
        <ul v-if="showDiagnostics" class="list-group list-group-flush">
          <li v-for="(diagnostic, index) in diagnostics" :key="index" class="list-group-item small">
            <span :class="diagnosticClass(diagnostic.level)" class="badge me-2">{{ diagnostic.level }}</span>
            <span class="font-monospace">{{ diagnostic.source }}</span>
            <span class="ms-2">{{ diagnostic.message }}</span>
          </li>
        </ul>
      </div>

      <div class="card">
        <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
          <div>
            <h3 class="fs-6 fw-semibold mb-0">Rule results</h3>
            <div class="text-muted small">
              <template v-if="panel.hasScanData && panel.visibleResults.length > 0">
                {{ panel.visibleResults.length }} {{ panel.pluralize(panel.visibleResults.length, 'violating rule') }},
                sorted by importance
              </template>
              <template v-else>{{ panel.visibleResults.length }} advisor finding(s)</template>
            </div>
          </div>
          <span
            v-if="panel.score !== null && panel.visibleResults.length === 0 && panel.dismissedResults.length === 0"
            class="badge text-bg-success"
            >{{
              panel.assessment.completeness === 'partial' ? 'No findings in evaluated evidence' : 'No findings'
            }}</span
          >
        </div>
        <div v-if="panel.visibleResults.length === 0" class="card-body text-center text-muted py-5">
          <i class="bi bi-hdd-rack fs-2 d-block mb-2"></i>
          <div class="fw-semibold text-body">{{ panel.emptyRuleResultsTitle }}</div>
          <div>These are review prompts, not verdicts; verify against the live schema before changing it.</div>
        </div>
        <div v-else class="list-group list-group-flush">
          <div v-for="result in panel.visibleResults" :key="result.id" class="list-group-item">
            <div class="d-flex flex-wrap align-items-center gap-2 mb-2">
              <span :class="panel.statusClass(result.status)" class="badge">{{ result.status }}</span>
              <span :class="panel.severityClass(result.severity)" class="badge">{{ result.severity }}</span>
              <span class="badge text-bg-light border font-monospace">{{ result.category }}</span>
              <span class="text-muted small font-monospace">{{ result.id }}</span>
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
                <span class="badge text-bg-light border font-monospace">{{ result.category }}</span>
                <span class="text-muted small font-monospace">{{ result.id }}</span>
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
