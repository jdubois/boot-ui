<script setup>
import {computed, ref} from 'vue'

/**
 * The one tab strip every panel uses: a WAI-ARIA tablist with roving tabindex and
 * ArrowLeft/ArrowRight/Home/End navigation. The owner keeps the panels and decides
 * what selecting a tab means (a lazy load, a filter reset), so selection is emitted
 * rather than bound. Colors come from the `--bootui-tab-*` tokens; each skin adds
 * only its geometry in its own `theme-<id>.css`.
 */
const props = defineProps({
  /** Tab entries in display order; the `tab` slot receives the whole entry. */
  tabs: {
    type: /** @type {import('vue').PropType<{id: string, label?: string, icon?: string, disabled?: boolean, [key: string]: any}[]>} */ (
      Array
    ),
    required: true
  },
  selected: {type: String, default: null},
  /** Accessible name of the tablist. */
  label: {type: String, required: true},
  /** Builds `<prefix>-tab-<id>` and `<prefix>-panel-<id>` unless `tabId`/`panelId` are given. */
  idPrefix: {type: String, default: ''},
  tabId: {type: Function, default: null},
  panelId: {type: Function, default: null}
})

const emit = defineEmits(['select'])
const list = ref(null)

// A tablist always keeps exactly one tab in the tab order, even when the owner's
// remembered selection is no longer among the tabs it passes.
const selectedId = computed(() => {
  const enabled = props.tabs.filter((tab) => !tab.disabled)
  return (enabled.find((tab) => tab.id === props.selected) ?? enabled[0])?.id ?? null
})

function tabIdFor(tab) {
  return props.tabId ? props.tabId(tab.id) : `${props.idPrefix}-tab-${tab.id}`
}

function panelIdFor(tab) {
  return props.panelId ? props.panelId(tab.id) : `${props.idPrefix}-panel-${tab.id}`
}

function select(tab) {
  if (tab.disabled || tab.id === props.selected) return
  emit('select', tab.id)
}

function onKeydown(event, index) {
  const enabled = props.tabs.map((tab, position) => ({tab, position})).filter(({tab}) => !tab.disabled)
  if (!enabled.length) return
  const current = Math.max(
    enabled.findIndex(({position}) => position === index),
    0
  )
  const next = {
    ArrowRight: (current + 1) % enabled.length,
    ArrowLeft: (current - 1 + enabled.length) % enabled.length,
    Home: 0,
    End: enabled.length - 1
  }[event.key]
  if (next === undefined) return
  event.preventDefault()
  const target = enabled[next]
  select(target.tab)
  list.value?.querySelectorAll('[role="tab"]')[target.position]?.focus()
}
</script>

<template>
  <ul ref="list" class="bootui-tabs" role="tablist" :aria-label="label">
    <li v-for="(tab, index) in tabs" :key="tab.id" class="bootui-tabs__item" role="presentation">
      <button
        :id="tabIdFor(tab)"
        :aria-controls="panelIdFor(tab)"
        :aria-selected="tab.id === selectedId ? 'true' : 'false'"
        :class="{active: tab.id === selectedId}"
        :disabled="tab.disabled || null"
        :tabindex="tab.id === selectedId ? 0 : -1"
        class="bootui-tabs__tab bootui-keyboard-target"
        role="tab"
        type="button"
        @click="select(tab)"
        @keydown="onKeydown($event, index)"
      >
        <slot name="tab" :tab="tab" :selected="tab.id === selectedId">
          <i v-if="tab.icon" :class="['bi', tab.icon]" aria-hidden="true"></i>
          <span>{{ tab.label }}</span>
        </slot>
      </button>
    </li>
  </ul>
</template>

<style scoped>
/* A sunken tray that hugs its tabs; the selected tab is the one raised, filled
   chip in it. Tabs wrap instead of scrolling so no section hides off-screen. */
.bootui-tabs {
  background: var(--bootui-tab-tray-bg);
  border: 1px solid var(--bootui-tab-tray-border);
  border-radius: var(--bootui-radius-md);
  box-shadow: var(--bootui-tab-tray-shadow);
  display: flex;
  flex-wrap: wrap;
  gap: var(--bootui-tab-gap);
  list-style: none;
  margin: 0;
  max-width: 100%;
  padding: var(--bootui-tab-tray-padding);
  width: fit-content;
}

.bootui-tabs__item {
  display: flex;
  flex: 0 0 auto;
  min-width: 0;
}

.bootui-tabs__tab {
  align-items: center;
  background: var(--bootui-tab-bg);
  border: 0;
  border-radius: var(--bootui-radius-sm);
  color: var(--bootui-tab-color);
  display: inline-flex;
  font-size: 0.875rem;
  font-weight: 600;
  gap: 0.45rem;
  justify-content: center;
  line-height: 1.25;
  min-height: 2.25rem;
  padding: 0.4rem 0.8rem;
  text-align: center;
  text-decoration: none;
  transition:
    background-color 150ms ease,
    box-shadow 150ms ease,
    color 150ms ease;
}

.bootui-tabs__tab:not(.active):not(:disabled):hover {
  background: var(--bootui-tab-hover-bg);
  color: var(--bootui-tab-hover-color);
}

.bootui-tabs__tab.active {
  background: var(--bootui-tab-active-bg);
  box-shadow: var(--bootui-tab-active-shadow);
  color: var(--bootui-tab-active-color);
}

.bootui-tabs__tab:disabled {
  color: var(--bootui-tab-disabled-color);
  cursor: not-allowed;
}

/* Count chips are slot content, so they are styled through the slot boundary. An
   owner recolors one by setting --bootui-tab-count-bg on the chip itself. */
.bootui-tabs__tab :slotted(.bootui-tabs__count) {
  align-items: center;
  background: var(--bootui-tab-count-bg, color-mix(in srgb, currentColor 10%, transparent));
  border-radius: var(--bootui-radius-pill);
  display: inline-flex;
  font-size: 0.75rem;
  font-variant-numeric: tabular-nums;
  height: 1.4rem;
  justify-content: center;
  min-width: 1.4rem;
  padding: 0 0.35rem;
}

.bootui-tabs__tab.active :slotted(.bootui-tabs__count) {
  background: var(--bootui-tab-count-bg, color-mix(in srgb, currentColor 18%, transparent));
}

@media (max-width: 575.98px) {
  .bootui-tabs {
    width: 100%;
  }

  .bootui-tabs__item {
    flex: 1 1 auto;
  }

  .bootui-tabs__tab {
    width: 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .bootui-tabs__tab {
    transition: none;
  }
}
</style>
