<script setup>
import {computed, nextTick, reactive, ref, useId, watch} from 'vue'
import {formatLoadError} from '../../utils/loadError.js'

const props = defineProps({
  // What the advisor report says about its dismissed findings: their state in the latest scan.
  suppression: {type: Object, default: null},
  disabled: {type: Boolean, default: false},
  // Changes after every dismissal or restore, so the notes shown here are read again.
  revision: {type: Number, default: 0},
  listFindings: {type: Function, required: true},
  removeFinding: {type: Function, required: true},
  saveNote: {type: Function, required: true}
})

const MAX_NOTE_LENGTH = 200
const STATE_LABELS = {
  MATCHED: 'Still reported by the last scan',
  ORPHANED: 'No longer reported by the last scan',
  UNVERIFIED: 'The last scan cannot confirm it',
  UNKNOWN_RULE: 'This advisor has no such rule'
}

const idBase = useId()
const open = ref(false)
const loading = ref(false)
const loadError = ref(null)
const ignored = ref(0)
// The notes live only behind GET api/dismissed-findings, never in the report, so they are read once the list is opened.
const notes = reactive({})
const drafts = reactive({})
const announcement = ref('')
const summary = ref(null)

const entries = computed(() => (Array.isArray(props.suppression?.findings) ? props.suppression.findings : []))
const omitted = computed(() => props.suppression?.omitted || 0)
const total = computed(() => entries.value.length + omitted.value)

function keyOf(entry) {
  return `${entry.ruleId} ${entry.identity}`
}

function stateLabel(entry) {
  return STATE_LABELS[entry.state] || STATE_LABELS.UNVERIFIED
}

async function loadNotes() {
  loading.value = true
  loadError.value = null
  try {
    const result = await props.listFindings()
    for (const key of Object.keys(notes)) delete notes[key]
    for (const finding of result?.dismissed || []) {
      notes[`${finding.ruleId} ${finding.identity}`] = finding.note || ''
    }
    // A draft for an entry that is gone is dropped; what is being typed into the others is kept.
    for (const key of Object.keys(drafts)) if (!(key in notes)) delete drafts[key]
    ignored.value = result?.ignored || 0
  } catch (cause) {
    loadError.value = formatLoadError(cause, 'Unable to load the dismissed findings')
  } finally {
    loading.value = false
  }
}

function onToggle(event) {
  const wasOpen = open.value
  open.value = event.target.open
  if (open.value && !wasOpen) loadNotes()
}

// The list unmounts without a toggle event when its last entry goes, so it must not stay marked open.
watch(
  total,
  (count) => {
    if (count === 0) open.value = false
  },
  {flush: 'sync'}
)

watch(
  () => props.revision,
  () => {
    if (open.value && total.value > 0) loadNotes()
  }
)

function draftFor(entry) {
  const key = keyOf(entry)
  return key in drafts ? drafts[key] : notes[key] || ''
}

function noteChanged(entry) {
  return draftFor(entry).trim() !== (notes[keyOf(entry)] || '').trim()
}

async function save(entry) {
  const saved = await props.saveNote(entry.ruleId, entry.identity, draftFor(entry).trim())
  announcement.value = saved ? `Saved the note for ${entry.ruleId}.` : ''
  if (saved) {
    delete drafts[keyOf(entry)]
    await loadNotes()
  }
}

async function remove(entry) {
  const removed = await props.removeFinding(entry.ruleId, entry.identity)
  // The panel announces the removal. The entry that had focus is gone, so focus returns to the list's own heading,
  // or to the rule results heading once the list itself is gone.
  if (removed) {
    await nextTick()
    const target = summary.value || document.getElementById('advisor-rule-results-heading')
    target?.focus()
  }
}

function noteId(index) {
  return `${idBase}-note-${index}`
}
</script>

<template>
  <details v-if="total > 0" class="advisor-dismissed-findings" data-testid="dismissed-findings" @toggle="onToggle">
    <summary ref="summary" class="advisor-dismissed-findings__summary">
      <i class="bi bi-eye-slash me-1" aria-hidden="true"></i>Dismissed findings ({{ total }})
    </summary>
    <p class="small text-muted mt-2 mb-2">
      A dismissed finding stops counting towards the rule, the severity bars and the score. That changes score
      penalties, not application safety. Restore it to count it again; remove an entry the scan no longer reports.
    </p>
    <p v-if="loading" class="small text-muted mb-2" role="status">Loading notes…</p>
    <p v-if="loadError" class="small text-danger mb-2" role="alert">{{ loadError }}</p>
    <p v-if="ignored > 0" class="small text-muted mb-2">
      {{ ignored }} stored {{ ignored === 1 ? 'entry' : 'entries' }} could not be read and
      {{ ignored === 1 ? 'was' : 'were' }} left untouched in the file.
    </p>
    <ul class="list-unstyled mb-0">
      <li v-for="(entry, index) in entries" :key="keyOf(entry)" class="advisor-dismissed-findings__entry">
        <div class="d-flex flex-wrap align-items-center gap-2">
          <span class="font-monospace small">{{ entry.ruleId }}</span>
          <span class="badge text-bg-light border" :data-state="entry.state">{{ stateLabel(entry) }}</span>
          <button
            type="button"
            class="btn btn-sm btn-outline-secondary ms-auto"
            :disabled="disabled"
            :aria-label="`${entry.state === 'MATCHED' ? 'Restore' : 'Remove'} dismissed finding ${entry.ruleId} ${entry.identity}`"
            @click="remove(entry)"
          >
            <template v-if="entry.state === 'MATCHED'"
              ><i class="bi bi-eye me-1" aria-hidden="true"></i>Restore</template
            >
            <template v-else><i class="bi bi-x-lg me-1" aria-hidden="true"></i>Remove</template>
          </button>
        </div>
        <div class="font-monospace small text-muted advisor-dismissed-findings__identity">{{ entry.identity }}</div>
        <div class="d-flex flex-wrap align-items-center gap-2 mt-1">
          <label :for="noteId(index)" class="visually-hidden">Note for {{ entry.ruleId }} {{ entry.identity }}</label>
          <input
            :id="noteId(index)"
            type="text"
            class="form-control form-control-sm advisor-dismissed-findings__note"
            :maxlength="MAX_NOTE_LENGTH"
            placeholder="Why was this dismissed? (stays on this machine)"
            :value="draftFor(entry)"
            :disabled="disabled || loading"
            @input="drafts[keyOf(entry)] = /** @type {HTMLInputElement} */ ($event.target).value"
          />
          <button
            type="button"
            class="btn btn-sm btn-outline-secondary"
            :disabled="disabled || loading || !noteChanged(entry)"
            :aria-label="`Save note for ${entry.ruleId} ${entry.identity}`"
            @click="save(entry)"
          >
            Save note
          </button>
        </div>
      </li>
    </ul>
    <p v-if="omitted > 0" class="small text-muted mt-2 mb-0">
      {{ omitted }} further dismissed {{ omitted === 1 ? 'finding is' : 'findings are' }} not listed here.
    </p>
    <div class="visually-hidden" role="status" aria-live="polite" aria-atomic="true">{{ announcement }}</div>
  </details>
</template>

<style scoped>
.advisor-dismissed-findings {
  border-top: 1px solid var(--bootui-border, var(--bs-border-color));
  padding: 0.75rem 1rem;
}

.advisor-dismissed-findings__summary {
  cursor: pointer;
  color: var(--bootui-text-muted);
  font-size: 0.875rem;
}

.advisor-dismissed-findings__entry {
  padding: 0.5rem 0;
  border-top: 1px solid var(--bootui-border, var(--bs-border-color));
}

.advisor-dismissed-findings__identity {
  overflow-wrap: anywhere;
}

.advisor-dismissed-findings__note {
  flex: 1 1 14rem;
  min-width: 0;
  max-width: 32rem;
}
</style>
