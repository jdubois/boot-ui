<script setup>
import {computed, onMounted, ref, watch} from 'vue'
import {useRoute} from 'vue-router'
import {formatLoadErrorDescription} from '../utils/loadError.js'
import {useAdvisorPanel} from '../utils/useAdvisorPanel.js'
import {panelProps} from '../utils/panelState.js'
import {useServerPagedList} from '../utils/useServerPagedList.js'
import AdvisorSummary from './components/AdvisorSummary.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import ServerListFooter from './components/ServerListFooter.vue'
import SpinnerButton from './components/SpinnerButton.vue'
import UnavailableState from './components/UnavailableState.vue'

const props = defineProps(panelProps)
const panel = useAdvisorPanel(props, {
  apiPath: 'api/rest-api',
  loadErrorMessage: 'Unable to load REST API Advisor report',
  scanErrorMessage: 'Unable to run REST API checks',
  emptyScanPrompt: 'Run REST API checks to see rule findings',
  emptyNoFindings: 'No REST API rule findings',
  countNoun: 'finding'
})

// --- Declared error contract -----------------------------------------------------------------
// A read-only catalogue of the exception handlers the application declares. It is loaded on mount
// (a plain read, never a scan or a mutation) and paged/filtered entirely on the server.
// useRoute() is undefined when the panel is mounted without a router, as the unit tests do.
const route = useRoute()
// The Exceptions panel links here with the declaring component of a retained failure's handler, so the
// catalogue opens already filtered to that declaration instead of loading everything first.
const linkedComponent = typeof route?.query?.errorContract === 'string' ? route.query.errorContract.trim() : ''
const contractFilter = ref(linkedComponent)
const contract = useServerPagedList(
  'api/rest-api/error-contract',
  'entries',
  () => ({q: contractFilter.value.trim()}),
  {errorContext: 'Could not load the declared error contract'}
)

const contractReport = contract.data
const contractErrorMessage = computed(() => formatLoadErrorDescription(contract.error.value))
const contractAvailable = computed(() => contractReport.value?.available === true)
const contractEntries = contract.items
const contractFilterActive = computed(() => contractFilter.value.trim().length > 0)
const contractEmpty = computed(() => contractAvailable.value && (contractReport.value?.total ?? 0) === 0)
const contractFilteredEmpty = computed(
  () => contractAvailable.value && !contractEmpty.value && contractEntries.value.length === 0
)

const BODY_LABELS = {
  PROBLEM_DETAIL: 'Problem detail',
  CUSTOM_OBJECT: 'Custom object',
  STRING: 'String',
  EMPTY: 'Empty',
  DYNAMIC: 'Runtime-decided',
  UNRESOLVED: 'Unresolved'
}

const SOURCE_LABELS = {
  SPRING_CONTROLLER_ADVICE: '@ControllerAdvice',
  SPRING_CONTROLLER: '@Controller',
  JAKARTA_REST_EXCEPTION_MAPPER: 'ExceptionMapper',
  QUARKUS_SERVER_EXCEPTION_MAPPER: '@ServerExceptionMapper'
}

const SCOPE_LABELS = {
  GLOBAL: 'Application-wide',
  SCOPED: 'Selector-scoped',
  CONTROLLER: 'Controller-local',
  UNKNOWN: 'Unknown'
}

const bodyLabel = (value) => BODY_LABELS[value] || value
const sourceLabel = (value) => SOURCE_LABELS[value] || value
const scopeLabel = (value) => SCOPE_LABELS[value] || value

const bodyClass = (value) =>
  ({
    PROBLEM_DETAIL: 'text-bg-success',
    CUSTOM_OBJECT: 'text-bg-primary',
    STRING: 'text-bg-secondary',
    EMPTY: 'text-bg-secondary',
    DYNAMIC: 'text-bg-warning',
    UNRESOLVED: 'text-bg-light border'
  })[value] || 'text-bg-light border'

/** The declared status, or an honest marker when the declaration cannot prove one. */
function statusLabel(entry) {
  if (entry.status) return entry.status
  return entry.statusSource === 'DYNAMIC' ? 'Runtime' : 'Unresolved'
}

/** Precedence is only meaningful once the engine could resolve a winner for the exception type. */
function precedenceLabel(entry) {
  if (entry.precedenceSource === 'UNRESOLVED') return 'Ambiguous'
  return entry.precedence === 1 ? 'Wins' : `#${entry.precedence}`
}

function clearContractFilter() {
  contractFilter.value = ''
}

onMounted(() => contract.load())
watch(contractFilter, () => contract.scheduleReload())
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-signpost-split"
      title="REST API"
      subtitle="Run curated, project-agnostic REST best-practice rules against the host application's own controllers."
      :loading="panel.loading"
      :error="panel.error"
    >
      <template #actions>
        <SpinnerButton
          :loading="panel.loading"
          :disabled="panel.loading || panel.readOnly"
          class="btn btn-primary"
          type="button"
          label="Run REST API checks"
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
        :score-label="panel.assessment.label"
        :score-reason="panel.assessment.reason"
        :incomplete="panel.assessment.incomplete"
        :dismissed-count="panel.dismissedResults.length"
        :scan-status-label="panel.scanStatusLabel(panel.report.scan.status)"
        :scan-status-class="panel.scanStatusBadgeClass(panel.report.scan.status)"
        :scan-time="panel.scanTime()"
        :metrics="[
          {label: 'Rules evaluated', value: panel.report.rulesEvaluated},
          {label: 'Findings', value: panel.report.violationsFound},
          {
            label: 'Controllers analysed',
            value: panel.report.controllersAnalyzed,
            hint: panel.report.handlersAnalyzed + ' handler method(s)'
          }
        ]"
      />
      <div class="alert alert-info">
        <strong>Heuristic REST API design rules.</strong>
        {{ panel.report.disclaimer }}
        <span v-if="panel.readOnly">Scanning is read-only. {{ panel.readOnlyReason }}</span>
      </div>

      <div class="row g-3 mb-3">
        <div class="col-lg-5">
          <div class="card h-100">
            <div class="card-header"><h3>Findings by severity</h3></div>
            <div class="card-body">
              <div v-if="!panel.hasScanData" class="text-center text-muted py-4">
                <i class="bi bi-search fs-2 d-block mb-2"></i>
                <div class="fw-semibold text-body">No REST API data yet</div>
                <div>Run REST API checks to populate rule findings.</div>
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
            <div class="card-header"><h3>Base packages</h3></div>
            <div class="card-body">
              <div v-if="!panel.report.basePackages || panel.report.basePackages.length === 0" class="text-muted">
                No application base package was detected.
              </div>
              <ul v-else class="list-unstyled mb-0">
                <li v-for="pkg in panel.report.basePackages" :key="pkg" class="font-monospace small">
                  <i class="bi bi-box me-1"></i>{{ pkg }}
                </li>
              </ul>
            </div>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
          <div>
            <h3 class="fs-6 fw-semibold mb-0">Rule findings</h3>
            <div class="text-muted small">
              <template v-if="panel.hasScanData && panel.visibleResults.length > 0">
                {{ panel.visibleResults.length }} {{ panel.pluralize(panel.visibleResults.length, 'flagged rule') }},
                sorted by importance
              </template>
              <template v-else>{{ panel.visibleResults.length }} rule finding(s)</template>
            </div>
          </div>
          <span
            v-if="panel.score !== null && panel.visibleResults.length === 0 && panel.dismissedResults.length === 0"
            class="badge text-bg-secondary"
            >{{ panel.noFindingsLabel }}</span
          >
        </div>
        <div v-if="panel.visibleResults.length === 0" class="card-body text-center text-muted py-5">
          <i class="bi bi-signpost-split fs-2 d-block mb-2"></i>
          <div class="fw-semibold text-body">{{ panel.emptyRuleResultsTitle }}</div>
          <div>These heuristics complement, but do not replace, an API design review or contract testing.</div>
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

    <div class="card mt-3">
      <div class="card-header d-flex flex-wrap justify-content-between align-items-center gap-2">
        <div>
          <h3 class="fs-6 fw-semibold mb-0">Declared error contract</h3>
          <div class="text-muted small">
            The exception handlers this application declares, read from its own declarations. Handlers the framework
            provides itself are not listed. Nothing is executed and no error is triggered.
          </div>
        </div>
        <span v-if="contractAvailable && contractReport" class="badge text-bg-light border">
          {{ contractReport.handlerCount }} handler(s) · {{ contractReport.exceptionTypeCount }} exception type(s)
        </span>
      </div>

      <div class="card-body">
        <UnavailableState
          v-if="contractReport && !contractAvailable"
          icon="bi-shield-exclamation"
          :message="contractReport.unavailableReason"
          variant="info"
        />
        <UnavailableState
          v-else-if="contractErrorMessage && !contractReport"
          icon="bi-exclamation-triangle"
          :message="contractErrorMessage"
          variant="warning"
        />
        <PanelSkeleton v-else-if="!contract.hasLoaded.value" label="Loading the declared error contract…" />

        <template v-else-if="contractAvailable">
          <UnavailableState
            v-if="contractEmpty"
            icon="bi-shield-slash"
            message="This application declares no exception handlers, so every unhandled failure falls through to the framework default."
          />
          <template v-else>
            <input
              v-model="contractFilter"
              aria-label="Filter declared error contract"
              class="form-control mb-3"
              placeholder="Filter by exception, handler or status…"
            />
            <UnavailableState v-if="contractFilteredEmpty" icon="bi-search">
              <span
                >No declared handler matches <strong>{{ contractFilter.trim() }}</strong
                >.</span
              >
              <button class="btn btn-sm btn-outline-secondary ms-2" type="button" @click="clearContractFilter">
                Clear filter
              </button>
            </UnavailableState>
            <template v-else>
              <div class="table-responsive">
                <table class="table table-sm table-hover align-middle mb-0">
                  <thead>
                    <tr>
                      <th scope="col">Exception</th>
                      <th scope="col">Declared by</th>
                      <th scope="col" style="width: 130px">Scope</th>
                      <th scope="col" style="width: 110px">Status</th>
                      <th scope="col" style="width: 150px">Response body</th>
                      <th scope="col" style="width: 110px">Precedence</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="entry in contractEntries" :key="entry.id">
                      <td>
                        <code class="small" :title="entry.exceptionType">{{ entry.exceptionSimpleName }}</code>
                      </td>
                      <td>
                        <code class="small" :title="entry.component"
                          >{{ entry.componentSimpleName }}#{{ entry.method }}</code
                        >
                        <div class="text-muted small">{{ sourceLabel(entry.source) }}</div>
                      </td>
                      <td>
                        <span class="small">{{ scopeLabel(entry.scope) }}</span>
                        <div v-if="entry.scopeTarget" class="text-muted small font-monospace text-truncate">
                          {{ entry.scopeTarget }}
                        </div>
                      </td>
                      <td>
                        <span class="small font-monospace">{{ statusLabel(entry) }}</span>
                      </td>
                      <td>
                        <span :class="bodyClass(entry.bodyCategory)" class="badge">{{
                          bodyLabel(entry.bodyCategory)
                        }}</span>
                        <div v-if="entry.bodyType" class="text-muted small font-monospace text-truncate">
                          {{ entry.bodyType }}
                        </div>
                        <div v-if="entry.produces?.length" class="text-muted small font-monospace text-truncate">
                          {{ entry.produces.join(', ') }}
                        </div>
                      </td>
                      <td>
                        <span class="small">{{ precedenceLabel(entry) }}</span>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div v-if="contractReport.truncated" class="alert alert-warning mt-3 mb-0" role="status">
                Only the first {{ contractReport.maxEntries }} declarations are catalogued, so this list is incomplete.
              </div>
              <ServerListFooter
                :loading="contract.loadingMore.value"
                :matched="contract.matchedCount.value"
                :page-size="contract.pageSize"
                :shown="contract.shownCount.value"
                :total="contract.totalCount.value"
                item-label="declared handlers"
                @load-more="contract.loadMore"
              />
            </template>
          </template>
        </template>
      </div>
    </div>
  </div>
</template>
