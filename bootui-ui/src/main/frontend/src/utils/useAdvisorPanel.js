import {actionBusyMessage, getJson, isActionBusyError} from '../api.js'
import {computed, nextTick, onMounted, reactive, ref, watch} from 'vue'
import {formatClockTime} from './format.js'
import {describeLoadError} from './loadError.js'
import {hasScanResult, scanStatusBadgeClass, scanStatusLabel} from './scanStatus.js'
import {advisorAssessment} from './scannerScore.js'
import {usePanelState} from './panelState.js'
import {useDismissedFindings} from './useDismissedFindings.js'
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

  const dismissedRules = useDismissedRules(loadReport)
  const dismissedFindings = useDismissedFindings(loadReport)
  const {dismissLoading} = dismissedRules
  const {findingLoading} = dismissedFindings
  // Incremented after every finding mutation, so an open page of retained findings reloads its dismissed flags.
  const dismissalRevision = ref(0)
  // A polite message for a panel-level live region, which outlives the rule card a dismissal may remove.
  const announcement = ref('')
  // Incremented when a mutation removed the control that had focus, so the view can move focus somewhere stable.
  const focusFallbackToken = ref(0)
  const actionsDisabled = computed(
    () =>
      initialLoading.value ||
      loading.value ||
      dismissLoading.value ||
      findingLoading.value ||
      readOnly.value ||
      !manifestAvailable.value
  )

  const hasScanData = computed(() => hasScanResult(report.value?.scan?.status))

  const violations = computed(() =>
    [...(report.value?.results || [])].filter((result) => result.status === 'VIOLATION').sort(compareImportance)
  )

  // A rule whose every finding was dismissed one by one stays in the report so it can be restored, but it is no
  // longer an active finding: it sits in its own list, like a dismissed rule does.
  const visibleResults = computed(() =>
    violations.value.filter((result) => !result.dismissed && !isFullySuppressed(result))
  )

  const dismissedResults = computed(() => violations.value.filter((result) => result.dismissed))

  const suppressedResults = computed(() =>
    violations.value.filter((result) => !result.dismissed && isFullySuppressed(result))
  )

  const suppression = computed(() => report.value?.suppression || null)
  const suppressedFindingCount = computed(() => suppression.value?.suppressedFindings || 0)

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
    if (suppressedResults.value.length > 0 && visibleResults.value.length === 0) {
      return 'Every finding is dismissed'
    }
    if (score.value === null) return 'No findings in the available results'
    if (assessment.value.partial) return 'No findings in the assessed evidence'
    if (!report.value?.rulesEvaluated) return 'No rules were evaluated'
    return options.emptyNoFindings
  })

  function isFullySuppressed(result) {
    return (result.suppressedCount || 0) > 0 && result.violationCount === 0
  }

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
      return true
    } catch (e) {
      error.value = describeLoadError(e, options.loadErrorMessage)
      return false
    }
  }

  async function runScan() {
    if (initialLoading.value || loading.value || dismissLoading.value || findingLoading.value) return
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

  async function mutateRule(ruleId, restore = false) {
    if (initialLoading.value || loading.value || dismissLoading.value || findingLoading.value) return
    if (readOnly.value) {
      showReadOnlyMessage()
      return
    }
    if (!manifestAvailable.value) {
      showUnavailableMessage()
      return
    }
    actionMessage.value = null
    try {
      await (restore ? dismissedRules.restore(ruleId) : dismissedRules.dismiss(ruleId))
    } catch (e) {
      error.value = describeLoadError(e, restore ? 'Unable to restore rule' : 'Unable to dismiss rule')
    }
  }

  /**
   * Dismisses or restores one finding of `rule`, then reloads the report. Resolves to whether it took effect.
   * Guarded exactly like a rule dismissal: nothing happens while a scan or another mutation is running, or when
   * the panel is read-only or unavailable.
   */
  async function mutateFinding(rule, identity, restore = false) {
    if (initialLoading.value || loading.value || dismissLoading.value || findingLoading.value) return false
    if (readOnly.value) {
      showReadOnlyMessage()
      return false
    }
    if (!manifestAvailable.value) {
      showUnavailableMessage()
      return false
    }
    actionMessage.value = null
    let changed = false
    try {
      changed = await (restore
        ? dismissedFindings.restoreFinding(rule.id, identity)
        : dismissedFindings.dismissFinding(rule.id, identity))
    } catch (e) {
      error.value = describeLoadError(e, restore ? 'Unable to restore finding' : 'Unable to dismiss finding')
      return false
    }
    if (!changed) return false
    dismissalRevision.value++
    const current = (report.value?.results || []).find((result) => result.id === rule.id)
    const remaining = current ? current.violationCount : 0
    announcement.value = restore
      ? `Restored a finding of ${rule.id}. ${remaining} ${pluralize(remaining, countNoun)} active.`
      : `Dismissed a finding of ${rule.id}. ${remaining} ${pluralize(remaining, countNoun)} active.`
    await nextTick()
    const stillActive = visibleResults.value.some((result) => result.id === rule.id)
    if (!stillActive) focusFallbackToken.value++
    return true
  }

  /** Dismisses or restores a finding from the list of dismissed findings, whose rule may no longer be listed. */
  async function mutateListedFinding(ruleId, identity, restore = true) {
    return mutateFinding({id: ruleId}, identity, restore)
  }

  /** Replaces the note of a dismissed finding. The note stays out of the report, so there is nothing to announce. */
  async function saveFindingNote(ruleId, identity, note) {
    if (initialLoading.value || loading.value || dismissLoading.value || findingLoading.value) return false
    if (readOnly.value) {
      showReadOnlyMessage()
      return false
    }
    try {
      return await dismissedFindings.dismissFinding(ruleId, identity, note)
    } catch (e) {
      error.value = describeLoadError(e, 'Unable to save the note')
      return false
    }
  }

  // A dismissal may remove the rule card that had focus, so focus moves to the list the rule went to, or else to the
  // rule results heading, rather than falling back to the page.
  watch(focusFallbackToken, async () => {
    await nextTick()
    const target =
      document.getElementById('advisor-all-dismissed-heading') ||
      document.getElementById('advisor-rule-results-heading')
    target?.focus()
  })

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
    actionsDisabled,
    dismiss: (ruleId) => mutateRule(ruleId),
    restore: (ruleId) => mutateRule(ruleId, true),
    findingDismissal: options.findingDismissal === true,
    dismissFinding: (rule, identity) => mutateFinding(rule, identity),
    restoreFinding: (rule, identity) => mutateFinding(rule, identity, true),
    removeListedFinding: (ruleId, identity) => mutateListedFinding(ruleId, identity),
    saveFindingNote,
    listDismissedFindings: dismissedFindings.list,
    dismissalRevision,
    announcement,
    focusFallbackToken,
    suppression,
    suppressedFindingCount,
    suppressedResults,
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
    loadReport,
    runScan,
    scanStatusBadgeClass,
    scanStatusLabel
  })
}
