<script setup>
import {computed, nextTick, ref, watch} from 'vue'
import {useRouter} from 'vue-router'
import {routes} from '../../routes.js'
import {loadRecentPanels} from '../../utils/recentPanels.js'

const emit = defineEmits(['close'])
const router = useRouter()
const query = ref('')
const inputEl = ref(null)
const activeIndex = ref(0)

const searchableRoutes = routes.filter((r) => r.name && r.meta?.title)
const recentRouteList = loadRecentPanels()
  .map((name) => searchableRoutes.find((r) => r.name === name))
  .filter(Boolean)
const recentNames = new Set(recentRouteList.map((r) => r.name))

function score(route, q) {
  const title = (route.meta.title || '').toLowerCase()
  const group = (route.meta.group || '').toLowerCase()
  const needle = q.toLowerCase()
  if (title.startsWith(needle)) return 3
  if (title.includes(needle)) return 2
  if (group.includes(needle)) return 1
  return 0
}

const showRecent = computed(() => !query.value.trim() && recentRouteList.length > 0)

const results = computed(() => {
  const q = query.value.trim()
  if (!q) {
    if (!recentRouteList.length) return searchableRoutes
    const rest = searchableRoutes.filter((r) => !recentNames.has(r.name))
    return [...recentRouteList, ...rest]
  }
  return searchableRoutes
    .map((r) => ({route: r, score: score(r, q)}))
    .filter((x) => x.score > 0)
    .sort((a, b) => b.score - a.score)
    .map((x) => x.route)
})

function isRecent(route) {
  return showRecent.value && recentNames.has(route.name)
}

watch(query, () => {
  activeIndex.value = 0
})

function navigate(route) {
  router.push(route.path)
  emit('close')
}

function onKeydown(e) {
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    activeIndex.value = Math.min(activeIndex.value + 1, results.value.length - 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    activeIndex.value = Math.max(activeIndex.value - 1, 0)
  } else if (e.key === 'Enter') {
    e.preventDefault()
    const selected = results.value[activeIndex.value]
    if (selected) navigate(selected)
  } else if (e.key === 'Escape') {
    emit('close')
  }
}

function focusInput() {
  nextTick(() => inputEl.value?.focus())
}

defineExpose({focusInput})
</script>

<template>
  <div class="cp-backdrop" role="dialog" aria-modal="true" aria-label="Command palette" @click.self="$emit('close')">
    <div class="cp-panel">
      <div class="cp-search-row">
        <i class="bi bi-search cp-search-icon"></i>
        <input
          ref="inputEl"
          v-model="query"
          class="cp-input"
          placeholder="Go to panel…"
          type="search"
          autocomplete="off"
          @keydown="onKeydown"
        />
        <kbd class="cp-esc-hint">Esc</kbd>
      </div>
      <div v-if="showRecent" class="cp-section-label">Recent</div>
      <ul v-if="results.length" class="cp-list" role="listbox">
        <li
          v-for="(r, i) in results"
          :key="r.name"
          :class="{active: i === activeIndex}"
          class="cp-item"
          role="option"
          :aria-selected="i === activeIndex"
          @click="navigate(r)"
          @mouseover="activeIndex = i"
        >
          <i :class="['bi', r.meta.icon, 'cp-item-icon']"></i>
          <span class="cp-item-title">{{ r.meta.title }}</span>
          <i
            v-if="isRecent(r)"
            class="bi bi-clock-history cp-item-recent"
            title="Recently viewed"
            aria-hidden="true"
          ></i>
          <span class="cp-item-group">{{ r.meta.group }}</span>
        </li>
      </ul>
      <div v-else class="cp-empty">No panels match "{{ query }}"</div>
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

.cp-search-icon {
  color: var(--bootui-text-muted, #64748b);
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
  color: var(--bootui-text-muted, #94a3b8);
}

.cp-esc-hint {
  background: var(--bootui-surface, #fff);
  border: 1px solid var(--bootui-border, rgba(15, 23, 42, 0.12));
  border-radius: 0.3rem;
  color: var(--bootui-text-muted, #94a3b8);
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

.cp-item-group {
  color: var(--bootui-text-muted, #94a3b8);
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.cp-item-recent {
  color: var(--bootui-green, #198754);
  flex-shrink: 0;
  font-size: 0.85rem;
}

.cp-section-label {
  color: var(--bootui-text-subtle, #94a3b8);
  font-size: 0.68rem;
  font-weight: 800;
  letter-spacing: 0.08em;
  padding: 0.5rem 1.25rem 0;
  text-transform: uppercase;
}

.cp-empty {
  color: var(--bootui-text-muted, #94a3b8);
  font-size: 0.9rem;
  padding: 1.5rem;
  text-align: center;
}
</style>
