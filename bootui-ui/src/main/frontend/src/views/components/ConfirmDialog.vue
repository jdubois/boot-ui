<script setup>
import {nextTick, ref, watch} from 'vue'
import {confirmState, settleConfirm} from '../../utils/useConfirm.js'

const dialogEl = ref(null)
const confirmBtn = ref(null)
const cancelBtn = ref(null)
let trigger = null

function supportsModal() {
  return dialogEl.value && typeof dialogEl.value.showModal === 'function'
}

watch(
  () => confirmState.open,
  async (open) => {
    const el = dialogEl.value
    if (!el) return
    if (open) {
      // Remember the control that launched the prompt so focus can return to it.
      trigger = document.activeElement
      if (!el.open) {
        if (supportsModal()) el.showModal()
        else el.setAttribute('open', '')
      }
      await nextTick()
      // Default focus to the safe choice: Cancel for destructive prompts.
      const target = confirmState.options.danger ? cancelBtn.value : confirmBtn.value
      target?.focus()
    } else if (el.open) {
      if (typeof el.close === 'function') el.close()
      else el.removeAttribute('open')
      if (trigger && typeof trigger.focus === 'function') trigger.focus()
      trigger = null
    }
  }
)

// Escape on a modal <dialog> fires a native `cancel` event; handle it (and the
// fallback keydown) through the shared settle path so the promise resolves false.
function onCancel(e) {
  e.preventDefault()
  settleConfirm(false)
}

function onBackdropClick(e) {
  if (e.target === dialogEl.value) settleConfirm(false)
}
</script>

<template>
  <dialog
    ref="dialogEl"
    class="confirm-dialog"
    :class="{'confirm-dialog--danger': confirmState.options.danger}"
    aria-labelledby="confirm-dialog-title"
    aria-describedby="confirm-dialog-message"
    @cancel="onCancel"
    @keydown.esc="settleConfirm(false)"
    @click="onBackdropClick"
  >
    <div class="confirm-panel">
      <div class="confirm-head">
        <span v-if="confirmState.options.danger" class="confirm-icon" aria-hidden="true">
          <i class="bi bi-exclamation-triangle-fill"></i>
        </span>
        <h2 id="confirm-dialog-title" class="confirm-title">{{ confirmState.options.title }}</h2>
      </div>

      <div id="confirm-dialog-message" class="confirm-body">
        <p v-if="confirmState.options.message" class="confirm-message">{{ confirmState.options.message }}</p>
        <p v-if="confirmState.options.resource" class="confirm-resource">
          <code>{{ confirmState.options.resource }}</code>
        </p>
        <p v-if="confirmState.options.irreversible" class="confirm-irreversible">
          <i class="bi bi-info-circle" aria-hidden="true"></i>
          This action cannot be undone.
        </p>
      </div>

      <div class="confirm-actions">
        <button ref="cancelBtn" type="button" class="btn btn-outline-secondary" @click="settleConfirm(false)">
          {{ confirmState.options.cancelLabel }}
        </button>
        <button
          ref="confirmBtn"
          type="button"
          class="btn"
          :class="confirmState.options.danger ? 'btn-danger' : 'btn-primary'"
          @click="settleConfirm(true)"
        >
          {{ confirmState.options.confirmLabel }}
        </button>
      </div>
    </div>
  </dialog>
</template>

<style scoped>
.confirm-dialog {
  border: 1px solid var(--bootui-border, rgba(15, 23, 42, 0.1));
  border-radius: var(--bootui-radius-lg);
  box-shadow: var(--bootui-shadow-md, 0 1.2rem 3rem rgba(15, 23, 42, 0.18));
  background: var(--bootui-surface-solid, #fff);
  color: var(--bootui-text, #0f172a);
  padding: 0;
  width: min(440px, calc(100vw - 2rem));
  max-width: 440px;
}

/* Native modal centering + backdrop (showModal path). */
.confirm-dialog::backdrop {
  background: rgba(15, 23, 42, 0.55);
  backdrop-filter: blur(4px);
}

.confirm-panel {
  padding: 1.5rem;
}

.confirm-head {
  align-items: center;
  display: flex;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
}

.confirm-icon {
  align-items: center;
  background: rgba(220, 53, 69, 0.12);
  border-radius: var(--bootui-radius-sm);
  color: var(--bootui-danger, #dc3545);
  display: inline-flex;
  flex-shrink: 0;
  font-size: 1.1rem;
  height: 2.4rem;
  justify-content: center;
  width: 2.4rem;
}

.confirm-title {
  font-size: 1.1rem;
  font-weight: 700;
  letter-spacing: -0.01em;
  margin: 0;
}

.confirm-body {
  margin-bottom: 1.5rem;
}

.confirm-message {
  color: var(--bootui-text-muted, #475569);
  font-size: 0.92rem;
  line-height: 1.5;
  margin: 0;
}

.confirm-resource {
  margin: 0.6rem 0 0;
}

.confirm-resource code {
  background: var(--bootui-surface-alt, rgba(100, 116, 139, 0.1));
  border: 1px solid var(--bootui-border-subtle, rgba(15, 23, 42, 0.08));
  border-radius: var(--bootui-radius-xs);
  color: var(--bootui-text, #0f172a);
  display: inline-block;
  font-size: 0.82rem;
  overflow-wrap: anywhere;
  padding: 0.2rem 0.45rem;
}

.confirm-irreversible {
  align-items: baseline;
  color: var(--bootui-warning-text-strong, #6f5300);
  display: flex;
  font-size: 0.82rem;
  gap: 0.4rem;
  margin: 0.7rem 0 0;
}

.confirm-actions {
  display: flex;
  gap: 0.6rem;
  justify-content: flex-end;
}

.confirm-actions .btn {
  font-weight: 600;
  min-width: 6rem;
}

/* Entrance: subtle scale + fade. Suppressed under reduced-motion. */
.confirm-dialog[open] {
  animation: confirm-in 160ms cubic-bezier(0.22, 1, 0.36, 1);
}

@keyframes confirm-in {
  from {
    opacity: 0;
    transform: translateY(0.4rem) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

@media (prefers-reduced-motion: reduce) {
  .confirm-dialog[open] {
    animation: none;
  }
}
</style>
