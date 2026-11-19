<script setup>
import {computed, getCurrentInstance, nextTick, provide, reactive, ref, useId, watch} from 'vue'
import {advisorEvidenceKey, advisorRows, matchesAdvisorRule} from '../../utils/advisorFindings.js'
import {advisorFindingsMarkdown} from '../../utils/markdownExport.js'
import {useCopyToClipboard} from '../../utils/useCopyToClipboard.js'
import AiExportPreview from './AiExportPreview.vue'
import AdvisorRuleViolations from './AdvisorRuleViolations.vue'

const props = defineProps({
  panel: {type: Object, required: true},
  apiPath: {type: String, required: true}
})
const globals = getCurrentInstance().appContext.config.globalProperties
const route = computed(() => globals.$route)
const router = globals.$router
const search = ref('')
const severity = ref('')
const expanded = reactive(new Set())
const evidence = reactive(new Map())
const evidenceOwners = new Map()
provide(advisorEvidenceKey, (id, value, owner) => {
  if (value) {
    evidenceOwners.set(id, owner)
    evidence.set(id, value)
  } else if (evidenceOwners.get(id) === owner) {
    // A restore can move a rule between the suppressed and active lists in one render.
    // An old component's cleanup must not delete its replacement's evidence.
    evidenceOwners.delete(id)
    evidence.delete(id)
  }
})
const prefix = `advisor-rules-${useId()}`
const hasRuleQuery = computed(() => Object.prototype.hasOwnProperty.call(route.value?.query || {}, 'rule'))
const invalidRuleQuery = computed(
  () => hasRuleQuery.value && route.value.query.rule !== null && typeof route.value.query.rule !== 'string'
)
const linked = computed(() => {
  const value = route.value?.query?.rule
  return typeof value === 'string' ? value : null
})
const target = computed(() =>
  linked.value === null ? null : props.panel.report?.results?.find((rule) => rule.id === linked.value)
)
const revealedTargets = reactive(new Set())
watch(
  [() => props.panel.report?.violationDetails?.scanId, () => target.value?.id],
  ([scanId, id], previous) => {
    if (scanId !== previous?.[0]) revealedTargets.clear()
    if (id) revealedTargets.add(id)
  },
  {immediate: true, flush: 'sync'}
)
const matching = computed(() =>
  props.panel.visibleResults.filter((rule) =>
    matchesAdvisorRule(rule, search.value, severity.value, evidence.get(rule.id)?.rows)
  )
)
const displayed = computed(() => {
  const rules = [...matching.value]
  if (target.value && !rules.some((rule) => rule.id === target.value.id)) rules.push(target.value)
  return rules
})
const mountedRules = computed(() => {
  const rules = [...props.panel.visibleResults]
  // Keep previously revealed page owners for this snapshot, not just while their link is active.
  // Visibility/export selection still use displayed; retaining an owner never loads its detail page.
  for (const rule of props.panel.report?.results || []) {
    if (revealedTargets.has(rule.id) && !rules.some((mounted) => mounted.id === rule.id)) rules.push(rule)
  }
  return rules
})
const shownIds = computed(() => new Set(displayed.value.map((rule) => rule.id)))
const activeCount = computed(() => matching.value.reduce((sum, rule) => sum + rule.violationCount, 0))
const section = ref(null)
const preview = ref(null)
const previewRegion = ref(null)
let returnFocus = null
const {copyToClipboard} = useCopyToClipboard()
const announcement = ref('')

function ruleId(rule) {
  return `${prefix}-${encodeURIComponent(rule.id)}`
}
function isInactive(rule) {
  return rule.dismissed || (rule.suppressedCount > 0 && rule.violationCount === 0)
}
function snapshot(rule) {
  return evidence.get(rule.id) || {rows: advisorRows(rule), page: null}
}
function navigate(id) {
  if (!router || !route.value) return
  const query = {...route.value.query}
  if (id) query.rule = id
  else delete query.rule
  return router.push({path: route.value.path, query, hash: route.value.hash})
}
async function toggle(rule, event) {
  if (expanded.has(rule.id)) {
    const trigger = event.currentTarget
    const hadFocus = document.activeElement === trigger
    expanded.delete(rule.id)
    if (linked.value === rule.id) await navigate(null)
    await nextTick()
    // Removing a reveal-only link can hide or unmount its toggle. Do not leave keyboard focus behind,
    // and do not steal it if navigation has already moved it to another control.
    if (
      hadFocus &&
      (!trigger.isConnected || !shownIds.value.has(rule.id)) &&
      (document.activeElement === trigger || document.activeElement === document.body)
    ) {
      section.value?.querySelector('input')?.focus()
    }
  } else {
    expanded.add(rule.id)
    navigate(rule.id)
  }
}
watch(
  [linked, () => target.value?.id],
  async () => {
    if (!target.value) return
    const id = ruleId(target.value)
    expanded.add(target.value.id)
    await nextTick()
    // Link navigation and Back/Forward reveal the target, but never request details.
    section.value?.querySelector(`[id="${id}-toggle"]`)?.focus()
  },
  {immediate: true}
)
watch(
  () => props.panel.report,
  async () => {
    const focused = document.activeElement
    const container = section.value?.closest('.card') || section.value
    if (!(focused instanceof HTMLElement) || !container?.contains(focused)) return
    const row = focused.closest('[data-rule-id]')
    const id = row?.getAttribute('data-rule-id') || linked.value
    await nextTick()
    // A rule dismissal can replace its expanded actions while retaining a linked, dismissed summary.
    if (!section.value || focused.isConnected) return
    const toggle = [...section.value.querySelectorAll('.advisor-rule-toggle')].find(
      (button) => button.closest('[data-rule-id]')?.getAttribute('data-rule-id') === id
    )
    const destination = toggle || section.value.querySelector('input')
    destination?.focus()
  }
)

async function copyLink(rule) {
  if (!router || !route.value) {
    announcement.value = 'Unable to copy the rule link. Router is unavailable.'
    return
  }
  const href = router.resolve({
    path: route.value.path,
    query: {...route.value.query, rule: rule.id},
    hash: route.value.hash
  }).href
  const ok = await copyToClipboard(new URL(href, window.location.href).href, rule.id)
  announcement.value = ok
    ? `Copied link to ${rule.id}.`
    : 'Unable to copy the rule link. Clipboard access is unavailable.'
}
async function showExport(rules, event) {
  returnFocus = event.currentTarget
  preview.value = advisorFindingsMarkdown(
    props.panel.report,
    rules.map((rule) => ({
      rule,
      ...snapshot(rule),
      expanded: expanded.has(rule.id)
    })),
    {
      apiPath: props.apiPath,
      search: search.value,
      severity: severity.value,
      scanInProgress: props.panel.loading === true,
      panelError: props.panel.error || null,
      linkedRule: linked.value,
      matchingRules: matching.value.length,
      availableRules: props.panel.visibleResults.length
    }
  )
  await nextTick()
  previewRegion.value?.querySelector('h3')?.focus()
}
async function closePreview() {
  preview.value = null
  await nextTick()
  if (canRestoreFocus(returnFocus)) returnFocus.focus()
  else section.value?.querySelector('input')?.focus()
}
function canRestoreFocus(element) {
  if (
    !element?.isConnected ||
    element.matches(':disabled, [aria-disabled="true"]') ||
    element.closest('[hidden], [inert]')
  ) {
    return false
  }
  for (let ancestor = element; ancestor; ancestor = ancestor.parentElement) {
    const style = getComputedStyle(ancestor)
    if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse') return false
  }
  return true
}
</script>

<template>
  <section ref="section" class="advisor-rule-results" :aria-labelledby="`${prefix}-heading`">
    <div class="p-3 border-bottom">
      <h3 :id="`${prefix}-heading`" class="visually-hidden">Find and share rule results</h3>
      <div class="d-flex flex-wrap align-items-end gap-2">
        <div class="advisor-rule-search flex-grow-1">
          <label :for="`${prefix}-search`" class="form-label small">Search rules and loaded evidence</label>
          <input :id="`${prefix}-search`" v-model="search" type="search" class="form-control form-control-sm" />
        </div>
        <div>
          <label :for="`${prefix}-severity`" class="form-label small">Severity</label>
          <select :id="`${prefix}-severity`" v-model="severity" class="form-select form-select-sm">
            <option value="">All severities</option>
            <option v-for="level in ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO']" :key="level">{{ level }}</option>
          </select>
        </div>
        <button
          type="button"
          class="btn btn-sm btn-outline-secondary"
          :disabled="!displayed.length"
          @click="showExport(displayed, $event)"
        >
          Copy visible findings
        </button>
      </div>
      <p class="small text-muted mb-0 mt-2">
        {{ matching.length }} of {{ panel.visibleResults.length }} active rules match · {{ activeCount }} active
        findings. Search covers rule text and loaded samples/current pages only, not unloaded violations. Copy includes
        these rules and their cached samples or current loaded page, even in collapsed rows. Filters select rules, not
        individual occurrences.
      </p>
      <p v-if="hasRuleQuery && !target" class="small text-warning-emphasis mb-0 mt-2" role="status">
        <template v-if="invalidRuleQuery">
          Rule link has an invalid or repeated id. Choose a single rule link or remove the rule query. No scan was run.
        </template>
        <template v-else>
          Rule {{ linked || '(empty id)' }} is not present in this cached report. It may be unknown, unavailable, or
          absent from this assessment. No scan was run.
        </template>
      </p>
      <p v-else-if="target && !matching.some((rule) => rule.id === target.id)" class="small text-muted mb-0 mt-2">
        Linked rule {{ target.id }} is revealed separately from the active filter matches. Dismissals are unchanged.
      </p>
      <p v-if="!displayed.length && panel.visibleResults.length" class="small mb-0 mt-2">
        No rules match these filters.
      </p>
      <div role="status" aria-atomic="true" class="visually-hidden">{{ announcement }}</div>
    </div>
    <div v-if="preview" ref="previewRegion" class="p-3 border-bottom">
      <AiExportPreview :markdown="preview.markdown" :omissions="preview.omissions" @close="closePreview" />
    </div>
    <div class="list-group list-group-flush">
      <div
        v-for="result in mountedRules"
        v-show="shownIds.has(result.id)"
        :key="result.id"
        class="list-group-item advisor-rule-result"
        :data-rule-id="result.id"
      >
        <div class="d-flex flex-wrap align-items-center gap-2">
          <h4 class="h6 mb-0 advisor-rule-heading">
            <button
              :id="`${ruleId(result)}-toggle`"
              type="button"
              class="btn btn-sm btn-outline-secondary advisor-rule-toggle text-start"
              :aria-expanded="expanded.has(result.id)"
              :aria-controls="ruleId(result)"
              @click="toggle(result, $event)"
            >
              <i
                :class="['bi', expanded.has(result.id) ? 'bi-chevron-down' : 'bi-chevron-right', 'me-1']"
                aria-hidden="true"
              ></i>
              <span class="font-monospace me-2">{{ result.id }}</span>
              <span class="advisor-rule-title">{{ result.name }}</span>
            </button>
          </h4>
          <span :class="panel.severityClass(result.severity)" class="badge">{{ result.severity }}</span>
          <span v-if="result.status !== 'VIOLATION'" class="badge text-bg-secondary">{{ result.status }}</span>
          <span v-if="isInactive(result)" class="badge text-bg-secondary">Dismissed — not counted in score</span>
          <span class="small text-muted">{{ result.dismissed ? 0 : result.violationCount }} active findings</span>
          <button
            type="button"
            class="btn btn-sm btn-outline-secondary ms-auto"
            :aria-label="`Copy link to ${result.id}`"
            @click="copyLink(result)"
          >
            Copy link
          </button>
          <button
            type="button"
            class="btn btn-sm btn-outline-secondary"
            :aria-label="`Copy ${result.id} for AI`"
            @click="showExport([result], $event)"
          >
            Copy for AI
          </button>
        </div>
        <div
          :id="ruleId(result)"
          v-show="expanded.has(result.id)"
          :aria-labelledby="`${ruleId(result)}-toggle`"
          class="mt-3"
        >
          <template v-if="isInactive(result) || result.status !== 'VIOLATION'">
            <p class="small">{{ result.description }}</p>
            <p class="small"><strong>Recommendation:</strong> {{ result.recommendation }}</p>
            <p v-if="result.status !== 'VIOLATION'" class="small text-muted">
              This rule has no active violations in this report.
            </p>
            <p v-else-if="!result.dismissed" class="small text-muted">
              All findings are dismissed. Use the All findings dismissed list below to explicitly view or restore them.
            </p>
            <AdvisorRuleViolations
              v-if="result.dismissed"
              :api-path="apiPath"
              :rule="result"
              :details="panel.report.violationDetails"
              :refresh-report="panel.loadReport"
            />
            <button
              v-if="result.dismissed"
              type="button"
              class="btn btn-sm btn-outline-secondary"
              :disabled="panel.actionsDisabled"
              @click="panel.restore(result.id)"
            >
              Restore rule {{ result.id }}
            </button>
          </template>
          <slot v-else :result="result" :active="shownIds.has(result.id) && expanded.has(result.id)" />
        </div>
      </div>
    </div>
    <slot name="suppressed" />
  </section>
</template>

<style scoped>
.advisor-rule-search {
  min-width: min(18rem, 100%);
}
.advisor-rule-heading {
  min-width: 0;
}
.advisor-rule-toggle {
  overflow-wrap: anywhere;
  min-width: 0;
}
.advisor-rule-results :is(button, input, select, h3):focus-visible {
  outline: 2px solid var(--bootui-blue);
  outline-offset: 2px;
}
</style>
