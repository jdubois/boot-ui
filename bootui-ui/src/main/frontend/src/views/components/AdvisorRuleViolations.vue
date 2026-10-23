<script setup>
import {computed, nextTick, ref, useId, watch} from 'vue'
import {rangeLabel, useAdvisorRuleViolations} from '../../utils/useAdvisorRuleViolations.js'
import {formatLocation, openInHref, presetLabel, useOpenInPreference} from '../../utils/sourceLocation.js'
import {useConfirm} from '../../utils/useConfirm.js'
import {useCopyToClipboard} from '../../utils/useCopyToClipboard.js'
import SpinnerButton from './SpinnerButton.vue'

const props = defineProps({
  apiPath: {type: String, required: true},
  rule: {type: Object, required: true},
  details: {type: Object, default: null},
  refreshReport: {type: Function, required: true},
  // Findings of this advisor can be dismissed one by one. The actions come from the owning panel, which also guards
  // them (read-only, busy, unavailable), so this component never mutates anything on its own.
  suppressible: {type: Boolean, default: false},
  disabled: {type: Boolean, default: false},
  // Changes after every dismissal or restore, so a page on display rereads which of its findings are dismissed.
  revision: {type: Number, default: 0},
  dismissFinding: {type: Function, default: null},
  restoreFinding: {type: Function, default: null}
})

const {page, loading, error, stale, announcement, available, load, retry, refresh, backToSamples} =
  useAdvisorRuleViolations(props)
const {confirm} = useConfirm()
const samples = computed(() => props.rule.sampleViolations || [])
// What moved for this rule since the previous scan; only the paged view can say which finding is new.
const ruleChange = computed(() => {
  const rules = props.details?.changes?.rules
  return Array.isArray(rules) ? rules.find((entry) => entry.ruleId === props.rule.id) || null : null
})
const newCount = computed(() => ruleChange.value?.newCount || 0)
const suppressedCount = computed(() => props.rule.suppressedCount || 0)
// Locations are aligned index-for-index with their texts, or absent altogether.
const rows = computed(() => {
  const texts = page.value ? page.value.violations || [] : samples.value
  const locations = page.value ? page.value.locations : props.rule.sampleLocations
  const aligned = Array.isArray(locations) && locations.length === texts.length ? locations : []
  const changes = page.value ? page.value.changes : null
  const markers = Array.isArray(changes) && changes.length === texts.length ? changes : []
  const identities = page.value?.identities
  const keyed = Array.isArray(identities) && identities.length === texts.length ? identities : []
  const flags = page.value?.suppressed
  const dismissedFlags = Array.isArray(flags) && flags.length === texts.length ? flags : []
  return texts.map((text, index) => {
    const location = aligned[index] || null
    const label = formatLocation(location)
    return {
      text,
      location: label ? location : null,
      label,
      isNew: markers[index] === 'NEW',
      // Only a finding with a canonical identity can be dismissed; an unkeyed one is counted but never suppressible.
      identity: keyed[index] || null,
      dismissed: dismissedFlags[index] === true
    }
  })
})
const {openIn} = useOpenInPreference()
const {copiedKey, copyToClipboard} = useCopyToClipboard()
const openInLabel = computed(() => presetLabel(openIn.value))
const hasMoreThanSamples = computed(() => props.rule.violationCount > samples.value.length)
// Findings that fit in the samples have no markers, so a rule with something new offers its paged view too.
const canViewViolations = computed(
  () =>
    hasMoreThanSamples.value ||
    newCount.value > 0 ||
    (props.suppressible && suppressedCount.value + samples.value.length > 0)
)
const canAct = computed(() => props.suppressible && !!page.value && !props.disabled && !!props.dismissFinding)
const regionId = `advisor-violations-${useId()}`
const heading = ref(null)
const viewButton = ref(null)
const refreshing = ref(false)

async function showPage(offset = 0) {
  if (await load(offset)) {
    await nextTick()
    heading.value?.focus()
  }
}

async function retryPage() {
  if (await retry()) {
    await nextTick()
    heading.value?.focus()
  }
}

async function showSamples() {
  backToSamples()
  await nextTick()
  viewButton.value?.$el.focus()
}

async function copyLocation(row, index) {
  const copied = await copyToClipboard(row.label, copyKey(index))
  announcement.value = copied
    ? `Copied location ${row.label}.`
    : 'Unable to copy the location. Clipboard access is unavailable.'
}

function copyKey(index) {
  return `${page.value ? page.value.page.offset : 'sample'}-${index}`
}

// The page position of the row whose control had focus, so focus can return to the same row once it rereads.
let pendingFocusIndex = null

async function dismissRow(row, index) {
  if (!canAct.value) return
  const accepted = await confirm({
    title: 'Dismiss this finding?',
    message:
      'It stops counting towards this rule, the severity bars and the score, which changes score penalties, not application safety. The finding stays listed here: restore it from this list or from Dismissed findings at any time.',
    resource: row.text,
    confirmLabel: 'Dismiss finding',
    cancelLabel: 'Keep it'
  })
  if (!accepted) return
  pendingFocusIndex = index
  if (!(await props.dismissFinding(props.rule, row.identity))) pendingFocusIndex = null
}

async function restoreRow(row, index) {
  if (!canAct.value || !props.restoreFinding) return
  pendingFocusIndex = index
  if (!(await props.restoreFinding(props.rule, row.identity))) pendingFocusIndex = null
}

watch(
  () => props.revision,
  async () => {
    const focusIndex = pendingFocusIndex
    pendingFocusIndex = null
    if (!page.value) return
    const reloaded = await refresh()
    if (!reloaded || focusIndex === null) return
    await nextTick()
    const action = document
      .getElementById(regionId)
      ?.querySelector(`[data-row="${focusIndex}"] .advisor-finding-action`)
    if (action instanceof HTMLElement) action.focus()
  }
)

async function refreshCachedReport() {
  refreshing.value = true
  try {
    await props.refreshReport()
  } finally {
    refreshing.value = false
    await nextTick()
    // The scan may have changed, replacing the stale-state button.
    if (!stale.value) viewButton.value?.$el.focus()
  }
}
</script>

<template>
  <div v-if="samples.length || canViewViolations" class="advisor-rule-violations mb-2">
    <div class="d-flex flex-wrap align-items-center gap-2 mb-1">
      <div :id="`${regionId}-label`" ref="heading" tabindex="-1" class="small fw-semibold">
        <template v-if="page">{{ rangeLabel(page) }}</template>
        <template v-else-if="!samples.length && suppressedCount > 0">
          All {{ suppressedCount }} {{ suppressedCount === 1 ? 'finding is' : 'findings are' }} dismissed
        </template>
        <template v-else>
          Sample details (showing {{ samples.length }} of {{ rule.violationCount
          }}<template v-if="suppressedCount > 0">, {{ suppressedCount }} dismissed</template>)
        </template>
      </div>
      <span v-if="!page && newCount > 0" class="advisor-rule-violations__new small">
        <i class="bi bi-plus-circle me-1" aria-hidden="true"></i>{{ newCount }} new since the previous scan
      </span>
      <SpinnerButton
        v-if="!page && available && canViewViolations"
        ref="viewButton"
        class="btn btn-sm btn-outline-secondary"
        :loading="loading"
        :disabled="loading || refreshing || stale"
        :aria-label="`View violations for ${rule.id}`"
        :aria-controls="regionId"
        label="View violations"
        @click="showPage()"
      />
      <button
        v-if="page"
        type="button"
        class="btn btn-sm btn-outline-secondary"
        :aria-label="`Back to samples for ${rule.id}`"
        :aria-controls="regionId"
        @click="showSamples"
      >
        Back to samples
      </button>
    </div>
    <div :id="regionId" :aria-labelledby="`${regionId}-label`">
      <ul v-if="rows.length" class="small mb-0 advisor-findings">
        <li v-for="(row, index) in rows" :key="index" :data-row="index" :class="{'is-dismissed': row.dismissed}">
          <div class="advisor-finding-row">
            <div class="advisor-finding-main">
              <span
                v-if="row.dismissed"
                class="advisor-rule-violations__badge badge text-bg-secondary me-1"
                data-testid="suppressed-finding"
                ><i class="bi bi-eye-slash me-1" aria-hidden="true"></i>Dismissed</span
              >
              <span
                v-if="row.isNew"
                class="advisor-rule-violations__badge badge text-bg-primary me-1"
                data-testid="new-finding"
                ><i class="bi bi-plus-circle me-1" aria-hidden="true"></i>New</span
              >
              <span :class="['font-monospace', {'text-muted': row.dismissed}]">{{ row.text }}</span>
              <span v-if="row.location" class="advisor-violation-location d-flex flex-wrap align-items-center gap-1">
                <i class="bi bi-geo-alt text-muted" aria-hidden="true"></i>
                <a
                  v-if="openInHref(row.location, openIn)"
                  :href="openInHref(row.location, openIn)"
                  class="btn btn-outline-secondary btn-sm font-monospace"
                  :aria-label="`Open ${row.label} in ${openInLabel}`"
                  >{{ row.label }}</a
                >
                <span v-else class="font-monospace text-muted">{{ row.label }}</span>
                <button
                  type="button"
                  class="btn btn-outline-secondary btn-sm advisor-copy-location"
                  :aria-label="`Copy location ${row.label}`"
                  @click="copyLocation(row, index)"
                >
                  <i
                    :class="['bi', copiedKey === copyKey(index) ? 'bi-check-lg' : 'bi-clipboard']"
                    aria-hidden="true"
                  ></i>
                  {{ copiedKey === copyKey(index) ? 'Copied' : 'Copy location' }}
                </button>
              </span>
            </div>
            <span v-if="suppressible && page && row.identity" class="advisor-finding-actions">
              <button
                v-if="row.dismissed"
                type="button"
                class="btn btn-outline-secondary btn-sm advisor-finding-action"
                :disabled="!canAct"
                title="Restore this finding"
                :aria-label="`Restore finding ${page.page.offset + index + 1} of ${rule.id}`"
                @click="restoreRow(row, index)"
              >
                <i class="bi bi-eye" aria-hidden="true"></i><span class="advisor-finding-action__label">Restore</span>
              </button>
              <button
                v-else
                type="button"
                class="btn btn-outline-secondary btn-sm advisor-finding-action"
                :disabled="!canAct"
                title="Dismiss this finding"
                :aria-label="`Dismiss finding ${page.page.offset + index + 1} of ${rule.id}`"
                @click="dismissRow(row, index)"
              >
                <i class="bi bi-eye-slash" aria-hidden="true"></i
                ><span class="advisor-finding-action__label">Dismiss</span>
              </button>
            </span>
          </div>
        </li>
      </ul>
      <p v-else-if="page" class="small text-muted mb-0">No retained violations on this page.</p>
    </div>
    <p v-if="page?.truncated" class="small text-muted mt-2 mb-0">
      Incomplete details: {{ page.retainedCount }} of {{ page.violationCount }} violations retained for this rule.
      Missing details cannot be retrieved from this scan.
    </p>
    <p v-else-if="!page && details?.truncated" class="small text-muted mt-2 mb-0">
      This scan retained {{ details.retained }} of {{ details.total }} violation details across all rules (limit
      {{ details.retentionLimit }}). Some details may be unavailable.
    </p>
    <div v-if="page && (page.page.offset > 0 || page.page.hasMore)" class="d-flex flex-wrap gap-2 mt-2">
      <SpinnerButton
        class="btn btn-sm btn-outline-secondary"
        :loading="loading"
        :disabled="loading || refreshing || stale || page.page.offset === 0"
        :aria-label="`Previous violations for ${rule.id}`"
        :aria-controls="regionId"
        label="Previous"
        @click="showPage(Math.max(0, page.page.offset - page.page.limit))"
      />
      <SpinnerButton
        class="btn btn-sm btn-outline-secondary"
        :loading="loading"
        :disabled="loading || refreshing || stale || !page.page.hasMore"
        :aria-label="`Next violations for ${rule.id}`"
        :aria-controls="regionId"
        label="Next"
        @click="showPage(page.page.offset + page.page.returned)"
      />
    </div>
    <div v-if="error" class="small mt-2">
      <p class="text-danger mb-1">{{ error }}</p>
      <SpinnerButton
        v-if="stale"
        class="btn btn-sm btn-outline-secondary"
        :loading="refreshing"
        :disabled="refreshing"
        :aria-label="`Refresh cached report for ${rule.id}`"
        label="Refresh cached report"
        @click="refreshCachedReport"
      />
      <SpinnerButton
        v-else
        class="btn btn-sm btn-outline-secondary"
        :loading="loading"
        :disabled="loading"
        :aria-label="`Retry violations for ${rule.id}`"
        label="Retry"
        @click="retryPage"
      />
    </div>
    <div role="status" aria-atomic="true" class="visually-hidden">{{ announcement }}</div>
  </div>
</template>

<style scoped>
.advisor-rule-violations li {
  overflow-wrap: anywhere;
}

.advisor-rule-violations__new {
  color: var(--bootui-text-muted);
}

.advisor-rule-violations__badge {
  vertical-align: baseline;
}

.advisor-violation-location {
  font-size: 0.8125rem;
}

.advisor-finding-row {
  display: flex;
  align-items: flex-start;
  gap: 0.75rem;
}

.advisor-finding-main {
  flex: 1 1 auto;
  min-width: 0;
}

.advisor-finding-actions {
  flex: none;
  white-space: nowrap;
}

.advisor-finding-action {
  font-size: inherit;
  vertical-align: baseline;
}

/* Hairline-divided rows: the text leads, and the action sits in a fixed right rail that stays quiet until the row
   is hovered or focused, so it reads as a row-level action next to the outlined, rule-level "Dismiss rule" button. */
.advisor-findings {
  list-style: none;
  padding-left: 0;
  border-top: 1px solid var(--bootui-border);
}

.advisor-findings > li {
  padding: 0.55rem 0.25rem;
  border-bottom: 1px solid var(--bootui-border);
}

.advisor-findings > li.is-dismissed {
  background: color-mix(in srgb, var(--bootui-text-muted) 6%, transparent);
}

.advisor-findings .advisor-finding-action {
  --bs-btn-padding-y: 0.1rem;
  --bs-btn-padding-x: 0.5rem;
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  border-color: transparent;
  color: var(--bootui-text-muted);
}

.advisor-findings > li:hover .advisor-finding-action:not(:disabled),
.advisor-findings .advisor-finding-action:focus-visible {
  border-color: var(--bs-btn-border-color);
  color: var(--bs-btn-color);
}

.advisor-copy-location {
  font-size: inherit;
  vertical-align: baseline;
}

.advisor-rule-violations [tabindex='-1']:focus-visible {
  outline: 2px solid var(--bootui-blue);
  outline-offset: 2px;
}
</style>
