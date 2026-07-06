import {apiFetch} from '../api.js'
import {computed, onMounted, reactive, ref} from 'vue'
import {formatClockTime} from './format.js'
import {describeLoadError} from './loadError.js'
import {hasScanResult, scanStatusBadgeClass, scanStatusLabel} from './scanStatus.js'
import {scoreBandLabel, scoreBandTone, scoreFromSeverityCounts} from './scannerScore.js'
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
 * Hibernate, Security, Memory, Pentesting). Each panel renders a scan status,
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
  const score = computed(() => (hasScanData.value ? scoreFromSeverityCounts(report.value?.severityCounts) : null))

  const maxSeverityCount = computed(() => {
    if (!report.value?.severityCounts?.length) return 1
    return Math.max(1, ...report.value.severityCounts.map((count) => count.count))
  })

  const emptyRuleResultsTitle = computed(() => {
    if (!hasScanData.value) return options.emptyScanPrompt
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
      const res = await apiFetch(options.apiPath)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      report.value = await res.json()
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
    try {
      const res = await apiFetch(`${options.apiPath}/scan`, {method: 'POST'})
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      report.value = await res.json()
      error.value = null
    } catch (e) {
      error.value = describeLoadError(e, options.scanErrorMessage)
    } finally {
      loading.value = false
    }
  }

  function showReadOnlyMessage() {
    actionMessage.value = readOnlyReason.value
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

  onMounted(() => {
    if (manifestAvailable.value) {
      loadReport()
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
    dismissLoading,
    dismiss,
    restore,
    hasScanData,
    score,
    scoreBandLabel,
    scoreBandTone,
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
