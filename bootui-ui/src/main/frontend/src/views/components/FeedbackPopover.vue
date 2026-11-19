<script setup>
import {nextTick, onBeforeUnmount, onMounted, ref, useId, watch} from 'vue'

const props = defineProps({
  collapsed: Boolean,
  disabled: Boolean
})

const rootRef = ref(null)
const triggerRef = ref(null)
const popoverRef = ref(null)
const open = ref(false)
const position = ref({})
const popoverId = `bootui-feedback-${useId()}`
let resizeObserver

function positionPopover() {
  if (!open.value) return
  const trigger = triggerRef.value.getBoundingClientRect()
  const margin = 12
  const width = Math.min(336, window.innerWidth - margin * 2)
  const height = Math.min(popoverRef.value.offsetHeight, window.innerHeight - margin * 2)
  position.value = {
    width: `${width}px`,
    maxHeight: `${window.innerHeight - margin * 2}px`,
    left: `${Math.max(margin, Math.min(trigger.left, window.innerWidth - width - margin))}px`,
    top: `${Math.max(margin, trigger.top - height - 12)}px`
  }
}

function close({restoreFocus = false} = {}) {
  if (!open.value) return
  popoverRef.value.hidePopover()
  if (restoreFocus) triggerRef.value.focus({preventScroll: true})
}

function onBeforeToggle(event) {
  if (event.newState === 'open' && props.disabled) {
    event.preventDefault()
    return
  }
  open.value = event.newState === 'open'
  // Exit animation keeps the element rendered, but its links must stop taking focus immediately.
  popoverRef.value.inert = !open.value
  if (open.value) nextTick(positionPopover)
}

function onFocusIn(event) {
  if (!rootRef.value.contains(event.target)) close()
}

function onKeydown(event) {
  if (event.key !== 'Escape' || !open.value) return
  event.preventDefault()
  event.stopPropagation()
  close({restoreFocus: true})
}

async function onTriggerKeydown(event) {
  if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
  event.preventDefault()
  if (!open.value) triggerRef.value.click()
  await nextTick()
  if (!open.value) return
  const links = popoverRef.value.querySelectorAll('a[href]')
  links[event.key === 'ArrowUp' ? links.length - 1 : 0]?.focus()
}

function removeOpenListeners() {
  document.removeEventListener('focusin', onFocusIn, true)
  window.removeEventListener('resize', positionPopover)
  window.removeEventListener('scroll', positionPopover, true)
}

watch(
  open,
  (value) => {
    if (value) {
      document.addEventListener('focusin', onFocusIn, true)
      window.addEventListener('resize', positionPopover)
      window.addEventListener('scroll', positionPopover, true)
    } else {
      removeOpenListeners()
    }
  },
  {flush: 'sync'}
)

watch(
  () => props.disabled,
  (disabled) => {
    if (disabled) close()
  }
)

watch(
  () => props.collapsed,
  () => nextTick(positionPopover)
)

onMounted(() => {
  if (typeof ResizeObserver === 'function') {
    resizeObserver = new ResizeObserver(positionPopover)
    resizeObserver.observe(triggerRef.value)
  }
})

onBeforeUnmount(() => {
  open.value = false
  removeOpenListeners()
  resizeObserver?.disconnect()
})

defineExpose({close})
</script>

<template>
  <div ref="rootRef" class="feedback" :class="{'feedback--collapsed': collapsed}" @keydown="onKeydown">
    <button
      ref="triggerRef"
      type="button"
      class="feedback-trigger contribute-card"
      :disabled="disabled"
      :popovertarget="popoverId"
      :aria-controls="popoverId"
      :aria-expanded="open"
      aria-label="Help improve BootUI"
      :title="collapsed ? 'Help improve BootUI' : undefined"
      @click="triggerRef.focus({preventScroll: true})"
      @keydown="onTriggerKeydown"
    >
      <span class="feedback-trigger__icon"><i class="bi bi-chat-left-text" aria-hidden="true"></i></span>
      <span class="feedback-trigger__label">Help improve BootUI</span>
      <i class="bi bi-chevron-up feedback-trigger__chevron" aria-hidden="true"></i>
    </button>
    <section
      :id="popoverId"
      ref="popoverRef"
      popover="auto"
      class="feedback-popover"
      :aria-labelledby="`${popoverId}-title`"
      :inert="open ? undefined : true"
      :style="position"
      @beforetoggle="onBeforeToggle"
      @toggle="positionPopover"
    >
      <h2 :id="`${popoverId}-title`">Feedback &amp; contributions</h2>
      <a
        class="feedback-action feedback-action--survey"
        href="https://docs.google.com/forms/d/e/1FAIpQLScFH1nLhhcOStIBi_PFKSj3nqSVZ-PRajCrXeLx3pb3HySbSg/viewform?usp=dialog"
        target="_blank"
        rel="noopener noreferrer"
        @click="close({restoreFocus: true})"
      >
        <i class="bi bi-chat-left-text feedback-action__icon" aria-hidden="true"></i>
        <span class="feedback-action__copy">
          <strong>Take the user survey</strong>
          <span>Hosted on Google Forms</span>
        </span>
        <i class="bi bi-box-arrow-up-right" aria-hidden="true"></i>
        <span class="visually-hidden">(opens in a new tab)</span>
      </a>
      <a
        class="feedback-action"
        href="https://github.com/jdubois/boot-ui"
        target="_blank"
        rel="noopener noreferrer"
        @click="close({restoreFocus: true})"
      >
        <i class="bi bi-github feedback-action__icon" aria-hidden="true"></i>
        <span class="feedback-action__copy">
          <strong>Contribute on GitHub</strong>
          <span>Explore the code or open an issue</span>
        </span>
        <i class="bi bi-box-arrow-up-right" aria-hidden="true"></i>
        <span class="visually-hidden">(opens in a new tab)</span>
      </a>
      <p class="feedback-popover__footer">
        <i class="bi bi-box-arrow-up-right" aria-hidden="true"></i>
        Both links open in a new tab
      </p>
    </section>
  </div>
</template>

<style scoped>
.feedback {
  border-top: 1px solid var(--bootui-border);
  padding-top: 12px;
  color: var(--bootui-text);
  font-family: var(--bootui-font-sans);
}

.feedback-trigger {
  display: flex;
  align-items: center;
  width: 100%;
  min-height: 48px;
  gap: 8px;
  padding: 8px;
  border: 1px solid transparent;
  border-radius: var(--bootui-radius-md);
  background: transparent;
  color: var(--bootui-text-muted);
  text-align: left;
  transition:
    background-color 160ms ease,
    color 160ms ease,
    border-color 160ms ease;
}

.feedback-trigger__icon {
  display: grid;
  place-items: center;
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  font-size: 1.25rem;
}

.feedback-trigger__label {
  flex: 1;
  font-size: 0.82rem;
  font-weight: 600;
  line-height: 1.4;
}

.feedback-trigger__chevron {
  flex-shrink: 0;
  font-size: 0.75rem;
  transition: transform 160ms cubic-bezier(0.16, 1, 0.3, 1);
}

.feedback-trigger:hover,
.feedback-trigger[aria-expanded='true'] {
  background: var(--bootui-nav-hover-bg);
  color: var(--bootui-nav-hover-color);
}

.feedback-trigger[aria-expanded='true'] .feedback-trigger__chevron {
  transform: rotate(180deg);
}

.feedback-trigger:focus-visible,
.feedback-action:focus-visible {
  outline: 3px solid var(--bootui-blue);
  outline-offset: 3px;
}

.feedback-popover {
  position: fixed;
  inset: auto;
  margin: 0;
  box-sizing: border-box;
  width: min(21rem, calc(100vw - 24px));
  max-width: calc(100vw - 24px);
  overflow: auto;
  padding: 8px;
  border: 1px solid var(--bootui-border-alt);
  border-radius: var(--bootui-radius-lg);
  background: var(--bootui-surface-solid);
  color: var(--bootui-text);
  box-shadow: var(--bootui-shadow-md);
  font-family: var(--bootui-font-sans);
  transform-origin: bottom left;
  opacity: 0;
  transform: translateY(6px) scale(0.985);
  transition:
    opacity 160ms ease,
    transform 180ms cubic-bezier(0.16, 1, 0.3, 1),
    display 180ms allow-discrete,
    overlay 180ms allow-discrete;
}

.feedback-popover:popover-open {
  opacity: 1;
  transform: none;
}

@starting-style {
  .feedback-popover:popover-open {
    opacity: 0;
    transform: translateY(6px) scale(0.985);
  }
}

.feedback-popover h2 {
  margin: 0;
  padding: 12px 12px 16px;
  font-size: 1rem;
  font-weight: 700;
  line-height: 1.3;
  letter-spacing: -0.01em;
}

.feedback-action {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 72px;
  padding: 12px;
  border-radius: var(--bootui-radius-sm);
  color: var(--bootui-text);
  text-decoration: none;
  transition: background-color 140ms ease;
}

.feedback-action + .feedback-action {
  margin-top: 4px;
}

.feedback-action:hover {
  background: var(--bootui-nav-hover-bg);
  color: var(--bootui-text);
}

.feedback-action__icon {
  display: grid;
  place-items: center;
  flex-shrink: 0;
  width: 32px;
  height: 32px;
  font-size: 1.25rem;
  color: var(--bootui-text-muted);
}

.feedback-action--survey {
  background: var(--bootui-nav-hover-bg);
}

.feedback-action--survey .feedback-action__icon,
.feedback-action--survey strong {
  color: var(--bootui-green-dark);
}

.feedback-action--survey:hover {
  box-shadow: inset 0 0 0 1px var(--bootui-border-alt);
}

.feedback-action__copy {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  gap: 4px;
}

.feedback-action__copy strong {
  font-size: 0.875rem;
  font-weight: 650;
  line-height: 1.4;
}

.feedback-action__copy > span {
  font-size: 0.75rem;
  line-height: 1.5;
  color: var(--bootui-text-muted);
}

.feedback-action > .bi-box-arrow-up-right {
  flex-shrink: 0;
  font-size: 0.75rem;
  color: var(--bootui-text-muted);
}

.feedback-popover__footer {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 12px 12px 0;
  padding: 12px 0 6px;
  border-top: 1px solid var(--bootui-border);
  font-size: 0.75rem;
  line-height: 1.5;
  color: var(--bootui-text-muted);
}

.feedback-popover__footer .bi {
  font-size: 0.6875rem;
}

.feedback--collapsed .feedback-trigger__label,
.feedback--collapsed .feedback-trigger__chevron {
  display: none;
}

.feedback--collapsed .feedback-trigger {
  justify-content: center;
  padding: 8px 0;
}

@media (prefers-reduced-motion: reduce) {
  .feedback-trigger,
  .feedback-trigger__chevron,
  .feedback-popover,
  .feedback-action {
    transition: none;
  }
}
</style>
