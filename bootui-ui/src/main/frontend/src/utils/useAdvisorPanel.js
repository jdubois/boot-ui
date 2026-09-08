import {actionBusyMessage, getJson, isActionBusyError} from '../api.js'
import {computed, onMounted, reactive, ref} from 'vue'
import {formatClockTime} from './format.js'
import {describeLoadError} from './loadError.js'
import {hasScanResult, scanStatusBadgeClass, scanStatusLabel} from './scanStatus.js'
import {advisorAssessment} from './scannerScore.js'
import {usePanelState} from './panelState.js'
import {useDismissedRules} from './useDismissedRules.js'

const DEFAULT_SEVERITY_CLASSES = {
  CRITICAL: 'text-bg-danger',
  HIGH: 'text-bg-danger',
  MEDIUM: 'text-bg-warning',
  LOW: 'text-bg-info',
  INFO: 'text-bg-secondary'
}

const DEFAULT_STATUS_CLASSES = {
  PASS: 'text-bg-success',
  VIOLATION: 'text-bg-danger',
  SKIPPED: 'text-bg-secondary',
  ERROR: 'text-bg-warning'
}

const DEFAULT_SEVERITY_ORDER = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO']

/**
 * Shared logic for the rule-based advisor panels (Spring, REST API, Architecture,
 * Hibernate, Database, Security, Memory, Pentesting). Each panel renders a scan status,
 * severity breakdown, and rule/finding results from the same advisor report shape,
 * so the only per-panel differences are the API path and the user-facing copy passed
 * via `options`.
 *
 * Returns a `reactive` object whose members are consumed directly in the template as
 * `panel.<member>`; refs and computeds are unwrapped on access.
 */
export function useAdvisorPanel(props, options) {
  const severityClasses = options.severityClasses || DEFAULT_SEVERITY_CLASSES
  const statusClasses = options.statusClasses || DEFAULT_STATUS_CLASSES
  const severityOrder = options.severityOrder || DEFAULT_SEVERITY_ORDER
  const countNoun = options.countNoun || 'finding'

  const {readOnly, readOnlyReason, manifestAvailable, manifestUnavailableReason} = usePanelState(props)
  const report = ref(null)
  const error = ref(null)
  const actionMessage = ref(null)
  const loading = ref(false)
  // True only until the mount-time report GET settles, so a panel can show its
  // skeleton on first paint without flashing it on every later refresh or scan.
  const initialLoading = ref(true)

  const {dismissLoading, dismiss, restore} = useDismissedRules(loadReport)

  const hasScanData = computed(() => hasScanResult(report.value?.scan?.status))

  const violations = computed(() =>
    [...(report.value?.results || [])].filter((result) => result.status === 'VIOLATION').sort(compareImportance)
  )

  const visibleResults = computed(() => violations.value.filter((result) => !result.dismissed))

  const dismissedResults = computed(() => violations.value.filter((result) => result.dismissed))

  // 0-100 advisor score derived from the same weighted-penalty model the Overview
  // dashboard uses. The server recomputes severityCounts with dismissed rules excluded,
  // so dismissing or restoring a rule (which reloads the report) updates this score too.
  const assessment = computed(() => advisorAssessment(report.value))
  const score = computed(() => assessment.value.score)

  const maxSeverityCount = computed(() => {
    if (!report.value?.severityCounts?.length) return 1
    return Math.max(1, ...report.value.severityCounts.map((count) => count.count))
  })

  const emptyRuleResultsTitle = computed(() => {
    if (!hasScanData.value) return options.emptyScanPrompt
    if (score.value === null) return 'No findings in the available results'
    if (assessment.value.partial) return 'No findings in the assessed evidence'
    if (!report.value?.rulesEvaluated) return 'No rules were evaluated'
    return options.emptyNoFindings
  })

  function severityClass(severity) {
    return severityClasses[severity] || 'text-bg-light border text-dark'
  }

  function statusClass(status) {
    return statusClasses[status] || 'text-bg-light border text-dark'
  }

  function severityWidth(count) {
    if (count === 0) return '0%'
    return `${Math.max(3, (count / maxSeverityCount.value) * 100)}%`
  }

  function compareImportance(left, right) {
    const severityDiff = severityRank(left.severity) - severityRank(right.severity)
    if (severityDiff !== 0) return severityDiff
    const countDiff = right.violationCount - left.violationCount
    if (countDiff !== 0) return countDiff
    return left.id.localeCompare(right.id)
  }

  function severityRank(severity) {
    const index = severityOrder.indexOf(severity)
    return index === -1 ? severityOrder.length : index
  }

  function pluralize(count, singular, plural = `${singular}s`) {
    return count === 1 ? singular : plural
  }

  function violationCountLabel(count) {
    return `${count} ${pluralize(count, countNoun)} found`
  }

  function scanTime() {
    if (!report.value?.scan?.scannedAt) return ''
    return formatClockTime(report.value.scan.scannedAt)
  }

  async function loadReport() {
    if (!manifestAvailable.value) return
    try {
      report.value = await getJson(options.apiPath)
      error.value = null
    } catch (e) {
      error.value = describeLoadError(e, options.loadErrorMessage)
    }
  }

  async function runScan() {
    if (readOnly.value) {
      showReadOnlyMessage()
      return
    }
    if (!manifestAvailable.value) {
      showUnavailableMessage()
      return
    }
    loading.value = true
    actionMessage.value = null
    try {
      report.value = await getJson(`${options.apiPath}/scan`, {method: 'POST'})
      error.value = null
    } catch (e) {
      if (isActionBusyError(e)) {
        showActionMessage(actionBusyMessage(e))
        error.value = null
      } else {
        error.value = describeLoadError(e, options.scanErrorMessage)
      }
    } finally {
      loading.value = false
    }
  }

  function showReadOnlyMessage() {
    showActionMessage(readOnlyReason.value)
  }

  function showActionMessage(message) {
    actionMessage.value = message
    setTimeout(() => {
      actionMessage.value = null
    }, 6000)
  }

  function showUnavailableMessage() {
    actionMessage.value = manifestUnavailableReason.value
    setTimeout(() => {
      actionMessage.value = null
    }, 6000)
  }

  onMounted(async () => {
    if (!manifestAvailable.value) {
      initialLoading.value = false
      return
    }
    try {
      await loadReport()
    } finally {
      initialLoading.value = false
    }
  })

  return reactive({
    readOnly,
    readOnlyReason,
    manifestAvailable,
    manifestUnavailableReason,
    report,
    error,
    actionMessage,
    loading,
    initialLoading,
    dismissLoading,
    dismiss,
    restore,
    hasScanData,
    score,
    assessment,
    noFindingsLabel: computed(() =>
      assessment.value.partial ? 'No findings in the assessed evidence' : 'No findings'
    ),
    visibleResults,
    dismissedResults,
    emptyRuleResultsTitle,
    severityClass,
    statusClass,
    severityWidth,
    pluralize,
    violationCountLabel,
    scanTime,
    runScan,
    scanStatusBadgeClass,
    scanStatusLabel
  })
}
