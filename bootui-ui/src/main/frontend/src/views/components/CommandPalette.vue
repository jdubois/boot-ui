<script setup>
import {computed, inject, nextTick, ref, watch} from 'vue'
import {useRouter} from 'vue-router'
import {routes} from '../../routes.js'
import {
  createPanelLookup,
  navigationGroup,
  resolveRouteTitle,
  routeAvailabilityLabel,
  routeNavigationGroup,
  routePanelState
} from '../../utils/panelNavigation.js'
import {loadRecentPanels} from '../../utils/recentPanels.js'

const emit = defineEmits(['close'])
const router = useRouter()
const panels = inject('panels', ref(null))
const query = ref('')
const backdropEl = ref(null)
const inputEl = ref(null)
const activeIndex = ref(0)
const inputId = 'bootui-command-palette-input'
const listboxId = 'bootui-command-palette-listbox'

const searchableRoutes = routes.filter((r) => r.name && r.meta?.title)
const recentPanelNames = loadRecentPanels()
const panelLookup = computed(() => createPanelLookup(panels.value))
const platform = computed(() => panels.value?.platform)
const recentRouteList = computed(() =>
  recentPanelNames.map((name) => searchableRoutes.find((r) => r.name === name)).filter(Boolean)
)
const recentNames = computed(() => new Set(recentRouteList.value.map((r) => r.name)))

function routeTitle(route) {
  return resolveRouteTitle(route, platform.value)
}

function keywordMatch(keywords, needle) {
  // Match on word prefixes within a keyword rather than arbitrary substrings,
  // so "gc" finds the "gc" keyword but not the "gc" buried in "langchain4j".
  return keywords.some((k) => k.split(/\s+/).some((word) => word.startsWith(needle)))
}

// A route matches by its sidebar group title, by the group it is shown under (which may be "Disabled /
// unavailable"), and by its raw group key, which earlier versions displayed and searched.
function groupTerms(route) {
  return [navigationGroup(route.meta.group).title, routeGroup(route), route.meta.group]
    .filter(Boolean)
    .map((term) => term.toLowerCase())
}

function score(route, q) {
  const title = (routeTitle(route) || '').toLowerCase()
  const shortcut = (route.meta.shortcut || '').toLowerCase()
  const keywords = (route.meta.keywords || []).map((k) => k.toLowerCase())
  const needle = q.toLowerCase()
  if (title.startsWith(needle)) return 6
  if (shortcut.startsWith(needle)) return 6
  if (title.includes(needle)) return 4
  if (keywordMatch(keywords, needle)) return 3
  if (shortcut.includes(needle)) return 2
  if (groupTerms(route).some((term) => term.includes(needle))) return 1
  return 0
}

const showRecent = computed(() => !query.value.trim() && recentRouteList.value.length > 0)

const results = computed(() => {
  const q = query.value.trim()
  if (!q) {
    if (!recentRouteList.value.length) return searchableRoutes
    const rest = searchableRoutes.filter((r) => !recentNames.value.has(r.name))
    return [...recentRouteList.value, ...rest]
  }
  return searchableRoutes
    .map((r) => ({route: r, score: score(r, q)}))
    .filter((x) => x.score > 0)
    .sort((a, b) => b.score - a.score)
    .map((x) => x.route)
})

function isRecent(route) {
  return showRecent.value && recentNames.value.has(route.name)
}

function panelState(route) {
  return routePanelState(route, panelLookup.value)
}

function routeLabel(route) {
  return routeAvailabilityLabel(route, panelLookup.value, platform.value)
}

function routeGroup(route) {
  return routeNavigationGroup(route, panelLookup.value).title
}

watch(results, (currentResults) => {
  activeIndex.value = currentResults.length ? 0 : -1
})

function optionId(route) {
  return `bootui-command-palette-option-${route.name}`
}

const activeOptionId = computed(() => {
  const activeRoute = results.value[activeIndex.value]
  return activeRoute ? optionId(activeRoute) : undefined
})

watch(activeIndex, async (index) => {
  if (index < 0) return
  await nextTick()
  backdropEl.value?.querySelector(`[data-option-index="${index}"]`)?.scrollIntoView?.({block: 'nearest'})
})

async function navigate(route) {
  await router.push(route.path)
  emit('close', 'content')
}

const numberedNavKeys = ['1', '2', '3', '4', '5', '6', '7', '8', '9']

function onKeydown(e) {
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    if (results.value.length) {
      activeIndex.value = Math.min(Math.max(activeIndex.value + 1, 0), results.value.length - 1)
    }
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    if (results.value.length) {
      activeIndex.value = Math.max(activeIndex.value - 1, 0)
    }
  } else if (e.key === 'Home') {
    e.preventDefault()
    if (results.value.length) activeIndex.value = 0
  } else if (e.key === 'End') {
    e.preventDefault()
    if (results.value.length) activeIndex.value = results.value.length - 1
  } else if (e.key === 'Enter') {
    e.preventDefault()
    const selected = results.value[activeIndex.value]
    if (selected) navigate(selected)
  } else if (!query.value.trim() && numberedNavKeys.includes(e.key) && !e.metaKey && !e.ctrlKey && !e.altKey) {
    const idx = numberedNavKeys.indexOf(e.key)
    if (idx < results.value.length) {
      e.preventDefault()
      navigate(results.value[idx])
    }
  }
}

function close() {
  emit('close', 'invoker')
}

function onDialogKeydown(event) {
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    close()
    return
  }
  if (event.key !== 'Tab') return

  const focusable = backdropEl.value?.querySelectorAll(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
  )
  if (!focusable?.length) {
    event.preventDefault()
    return
  }
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  const active = document.activeElement
  if (event.shiftKey && (active === first || !backdropEl.value?.contains(active))) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && (active === last || !backdropEl.value?.contains(active))) {
    event.preventDefault()
    first.focus()
  }
}

function focusInput() {
  inputEl.value?.focus()
}

defineExpose({focusInput})
</script>

<template>
  <div
    ref="backdropEl"
    class="cp-backdrop"
    role="dialog"
    aria-modal="true"
    aria-label="Command palette"
    @click.self="close"
    @keydown="onDialogKeydown"
  >
    <div class="cp-panel">
      <div class="cp-search-row">
        <i aria-hidden="true" class="bi bi-search cp-search-icon"></i>
        <input
          :id="inputId"
          ref="inputEl"
          v-model="query"
          :aria-activedescendant="activeOptionId"
          aria-autocomplete="list"
          :aria-controls="listboxId"
          aria-expanded="true"
          aria-haspopup="listbox"
          aria-label="Search panels"
          class="cp-input"
          placeholder="Search panels by name or keyword…"
          role="combobox"
          type="search"
          autocomplete="off"
          @keydown="onKeydown"
        />
        <kbd class="cp-esc-hint">Esc</kbd>
      </div>
      <div v-if="showRecent" class="cp-section-label">Recent</div>
      <ul
        :id="listboxId"
        :class="{'cp-list--empty': !results.length}"
        aria-label="Panel results"
        class="cp-list"
        role="listbox"
      >
        <li
          v-for="(r, i) in results"
          :id="optionId(r)"
          :key="r.name"
          :aria-selected="i === activeIndex"
          :aria-label="routeLabel(r)"
          :class="{
            active: i === activeIndex,
            'cp-item--unavailable': ['disabled', 'unavailable'].includes(panelState(r)?.kind)
          }"
          class="cp-item"
          :data-option-index="i"
          role="option"
          @click="navigate(r)"
          @mousedown.prevent
          @mouseover="activeIndex = i"
        >
          <span v-if="i < 9 && !query.trim()" class="cp-item-num">{{ i + 1 }}</span>
          <i :class="['bi', r.meta.icon, 'cp-item-icon']"></i>
          <span class="cp-item-title">{{ routeTitle(r) }}</span>
          <i
            v-if="isRecent(r)"
            class="bi bi-clock-history cp-item-recent"
            title="Recently viewed"
            aria-hidden="true"
          ></i>
          <i
            v-if="panelState(r)"
            :class="['bi', panelState(r).icon, 'cp-item-status']"
            :title="panelState(r).label"
            aria-hidden="true"
          ></i>
          <span v-if="panelState(r)" class="visually-hidden">({{ panelState(r).label }})</span>
          <span v-if="r.meta.shortcut" class="cp-item-shortcut">{{ r.meta.shortcut }}</span>
          <span class="cp-item-group">{{ routeGroup(r) }}</span>
        </li>
      </ul>
      <div v-if="!results.length" class="cp-empty">No panels match "{{ query }}"</div>
    </div>
  </div>
</template>

<style scoped>
.cp-backdrop {
  align-items: flex-start;
  background: rgba(15, 23, 42, 0.55);
  backdrop-filter: blur(4px);
  bottom: 0;
  display: flex;
  justify-content: center;
  left: 0;
  padding-top: 15vh;
  position: fixed;
  right: 0;
  top: 0;
  z-index: 1050;
}

.cp-panel {
  background: var(--bootui-surface, #fff);
  border: 1px solid var(--bootui-border, rgba(15, 23, 42, 0.08));
  border-radius: 1.25rem;
  box-shadow: 0 2rem 5rem rgba(15, 23, 42, 0.25);
  max-height: 60vh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  width: min(600px, 92vw);
}

.cp-search-row {
  align-items: center;
  border-bottom: 1px solid var(--bootui-border, rgba(15, 23, 42, 0.08));
  display: flex;
  gap: 0.75rem;
  padding: 0.85rem 1rem;
}

/* The search input is intentionally borderless, so surface its keyboard focus
   on the row instead of an outline on the field. */
.cp-search-row:focus-within {
  border-bottom-color: var(--bootui-blue, #0d6efd);
  box-shadow: inset 0 -1px 0 0 var(--bootui-blue, #0d6efd);
}

.cp-search-icon {
  color: var(--bootui-text-muted, #56667b);
  flex-shrink: 0;
  font-size: 1rem;
}

.cp-input {
  background: none;
  border: none;
  color: var(--bootui-text, #0f172a);
  flex: 1;
  font-size: 1rem;
  outline: none;
}

.cp-input::placeholder {
  color: var(--bootui-text-subtle, #5b6b80);
  opacity: 1;
}

.cp-esc-hint {
  background: var(--bootui-surface, #fff);
  border: 1px solid var(--bootui-border, rgba(15, 23, 42, 0.12));
  border-radius: var(--bootui-radius-xs);
  color: var(--bootui-text-muted, #56667b);
  font-size: 0.7rem;
  padding: 0.15rem 0.4rem;
}

.cp-list {
  flex: 1;
  list-style: none;
  margin: 0;
  overflow-y: auto;
  padding: 0.5rem;
}

.cp-list--empty {
  flex: 0;
  padding: 0;
}

.cp-item {
  align-items: center;
  border-radius: 0.75rem;
  cursor: pointer;
  display: flex;
  gap: 0.75rem;
  padding: 0.6rem 0.75rem;
  transition: background 100ms ease;
}

.cp-item.active,
.cp-item:hover {
  background: var(--bootui-nav-hover-bg, rgba(25, 135, 84, 0.08));
}

.cp-item-icon {
  color: var(--bootui-green, #198754);
  flex-shrink: 0;
  font-size: 1rem;
}

.cp-item-title {
  flex: 1;
  font-size: 0.9rem;
  font-weight: 600;
}

/* Per-result metadata, not a group header: the uppercase tracked treatment is
   reserved for the nav-group headers themselves (.cp-section-label below). */
.cp-item-group {
  color: var(--bootui-text-muted, #56667b);
  font-size: 0.75rem;
}

.cp-item-num {
  align-items: center;
  background: var(--bootui-nav-group-bg, rgba(100, 116, 139, 0.08));
  border-radius: var(--bootui-radius-xs);
  color: var(--bootui-text-muted, #56667b);
  display: inline-flex;
  font-size: 0.65rem;
  font-weight: 700;
  height: 1.25rem;
  justify-content: center;
  line-height: 1;
  min-width: 1.25rem;
}

.cp-item-shortcut {
  background: var(--bootui-nav-group-bg, rgba(100, 116, 139, 0.08));
  border-radius: var(--bootui-radius-xs);
  color: var(--bootui-text-muted, #56667b);
  font-size: 0.65rem;
  font-weight: 600;
  letter-spacing: 0.04em;
  padding: 0.15rem 0.35rem;
}

.cp-item-recent {
  color: var(--bootui-green, #198754);
  flex-shrink: 0;
  font-size: 0.85rem;
}

.cp-item-status {
  color: var(--bootui-text-subtle, #5b6b80);
  flex-shrink: 0;
  font-size: 0.85rem;
}

.cp-item--unavailable .cp-item-title {
  color: var(--bootui-text-subtle, #5b6b80);
  font-style: italic;
}

.cp-section-label {
  color: var(--bootui-text-subtle, #5b6b80);
  font-size: 0.68rem;
  font-weight: 800;
  letter-spacing: 0.08em;
  padding: 0.5rem 1.25rem 0;
  text-transform: uppercase;
}

.cp-empty {
  color: var(--bootui-text-muted, #56667b);
  font-size: 0.9rem;
  padding: 1.5rem;
  text-align: center;
}

@media (prefers-reduced-motion: reduce) {
  .cp-item {
    transition: none;
  }
}
</style>
