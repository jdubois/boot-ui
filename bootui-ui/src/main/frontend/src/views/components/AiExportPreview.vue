<script setup>
import {nextTick, ref} from 'vue'
import {useCopyToClipboard} from '../../utils/useCopyToClipboard.js'

// The "Copy for AI" preview: the exact Markdown document the clipboard receives, what it leaves out,
// and the copy action. Copying sends nothing; if the browser denies clipboard access the document stays
// selectable here.
const props = defineProps({
  heading: {type: String, default: 'Copy for AI'},
  loading: {type: Boolean, default: false},
  error: {type: String, default: null},
  markdown: {type: String, default: ''},
  omissions: {type: /** @type {import('vue').PropType<string[]>} */ (Array), default: () => []}
})

const emit = defineEmits(['close'])

const {copiedKey, copyToClipboard} = useCopyToClipboard(2000)
const copyFailed = ref(false)
const status = ref('')
const textareaEl = ref(null)

async function copy() {
  if (!props.markdown) return
  status.value = ''
  copyFailed.value = false
  const copied = await copyToClipboard(props.markdown, 'ai-export')
  if (copied) {
    status.value = 'Markdown copied to the clipboard.'
    return
  }
  copyFailed.value = true
  await nextTick()
  textareaEl.value?.focus()
  textareaEl.value?.select()
}
</script>

<template>
  <section class="ai-export" aria-labelledby="ai-export-heading">
    <div class="d-flex flex-wrap align-items-center justify-content-between gap-2 mb-2">
      <h3 id="ai-export-heading" class="h6 mb-0">{{ heading }}</h3>
      <div class="d-flex align-items-center gap-2">
        <button
          :disabled="loading || !markdown"
          class="btn btn-sm btn-primary ai-export-copy"
          type="button"
          @click="copy"
        >
          <i :class="['bi', copiedKey === 'ai-export' ? 'bi-check2' : 'bi-clipboard', 'me-1']" aria-hidden="true"></i
          >{{ copiedKey === 'ai-export' ? 'Copied' : 'Copy Markdown' }}
        </button>
        <button class="btn btn-sm btn-outline-secondary ai-export-back" type="button" @click="emit('close')">
          Back
        </button>
      </div>
    </div>
    <p class="small text-muted mb-2">
      A Markdown document built from what BootUI already shows, ready to paste into an agent. Nothing is sent and no
      state changes.
    </p>
    <p role="status" aria-live="polite" class="visually-hidden">{{ status }}</p>
    <div v-if="loading" class="text-muted small">Preparing the export…</div>
    <div v-else-if="error" class="alert alert-danger small py-2 mb-0" role="alert">{{ error }}</div>
    <template v-else>
      <div class="ai-export-omissions small mb-2">
        <h4 class="fs-6 fw-semibold mb-1">Left out of this export</h4>
        <ul v-if="omissions.length" class="mb-0 ps-3">
          <li v-for="text in omissions" :key="text">{{ text }}</li>
        </ul>
        <p v-else class="text-muted mb-0">Nothing was masked, truncated, or left out.</p>
      </div>
      <div v-if="copyFailed" class="alert alert-danger small py-2 mb-2" role="alert">
        The browser blocked clipboard access, so nothing was copied. The document below is selected; copy it manually.
      </div>
      <label class="visually-hidden" for="ai-export-markdown">Markdown export preview</label>
      <textarea
        id="ai-export-markdown"
        ref="textareaEl"
        :value="markdown"
        class="form-control font-monospace ai-export-markdown"
        readonly
        rows="16"
        spellcheck="false"
      ></textarea>
    </template>
  </section>
</template>

<style scoped>
.ai-export-markdown {
  font-size: 0.85rem;
  min-height: 16rem;
  overflow-wrap: anywhere;
  resize: vertical;
  white-space: pre-wrap;
}

.ai-export-omissions li + li {
  margin-top: 0.15rem;
}
</style>
