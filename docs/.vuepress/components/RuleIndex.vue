<script setup>
import {computed, onMounted, onUnmounted, ref, watch} from 'vue'
import {onContentUpdated, usePageData} from 'vuepress/client'

const SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO']

const page = usePageData()
const query = ref('')
const activeSeverities = ref([])

const rules = computed(() => page.value.ruleCatalog ?? [])

const severityCounts = computed(() => {
  const counts = new Map(SEVERITIES.map((severity) => [severity, 0]))
  for (const rule of rules.value) {
    counts.set(rule.severity, (counts.get(rule.severity) ?? 0) + 1)
  }
  return counts
})

const availableSeverities = computed(() => SEVERITIES.filter((severity) => severityCounts.value.get(severity) > 0))

const matches = computed(() => {
  const needle = query.value.trim().toLowerCase()
  const severities = activeSeverities.value
  return rules.value.filter((rule) => {
    if (severities.length && !severities.includes(rule.severity)) {
      return false
    }
    if (!needle) {
      return true
    }
    return `${rule.id} ${rule.title} ${rule.category ?? ''}`.toLowerCase().includes(needle)
  })
})

const isFiltered = computed(() => query.value.trim().length > 0 || activeSeverities.value.length > 0)

function toggleSeverity(severity) {
  const index = activeSeverities.value.indexOf(severity)
  if (index < 0) {
    activeSeverities.value = [...activeSeverities.value, severity]
  } else {
    activeSeverities.value = activeSeverities.value.filter((value) => value !== severity)
  }
}

function reset() {
  query.value = ''
  activeSeverities.value = []
}

/*
 * The rule detail lives in the markdown below this component, so filtering also collapses the page body: the DOM is
 * grouped into one block per rule plus one per `##` category, and non-matching blocks are hidden rather than removed.
 */
let blocks = []

function indexPageBlocks() {
  blocks = []
  const first = rules.value.map((rule) => rule.slug).find((slug) => slug && document.getElementById(slug))
  const container = first ? document.getElementById(first)?.parentElement : null
  if (!container) {
    return
  }

  const slugs = new Set(rules.value.map((rule) => rule.slug))
  let category = null
  let rule = null

  for (const node of Array.from(container.children)) {
    if (node.tagName === 'H2') {
      category = {type: 'category', nodes: [node], ruleIds: []}
      rule = null
      blocks.push(category)
      continue
    }

    if (node.tagName === 'H3' && slugs.has(node.id)) {
      rule = {type: 'rule', slug: node.id, nodes: [node]}
      category?.ruleIds.push(node.id)
      blocks.push(rule)
      continue
    }

    if (rule) {
      rule.nodes.push(node)
    } else if (category) {
      category.nodes.push(node)
    }
  }
}

function applyPageFilter() {
  if (!blocks.length) {
    return
  }

  const visible = new Set(matches.value.map((rule) => rule.slug))
  const showAll = !isFiltered.value

  for (const block of blocks) {
    const shown =
      showAll || (block.type === 'rule' ? visible.has(block.slug) : block.ruleIds.some((slug) => visible.has(slug)))
    for (const node of block.nodes) {
      node.hidden = !shown
    }
  }
}

function refresh() {
  indexPageBlocks()
  applyPageFilter()
}

onMounted(refresh)
onContentUpdated(refresh)
watch(matches, applyPageFilter)
onUnmounted(() => {
  for (const block of blocks) {
    for (const node of block.nodes) {
      node.hidden = false
    }
  }
  blocks = []
})
</script>

<template>
  <section v-if="rules.length" class="rule-index" aria-label="Filter checks">
    <div class="rule-index__controls">
      <label class="rule-index__search">
        <span class="rule-index__label">Filter {{ rules.length }} checks</span>
        <input v-model="query" type="search" placeholder="Search by id, title, or category…" autocomplete="off" />
      </label>

      <div class="rule-index__severities" role="group" aria-label="Filter by severity">
        <button
          v-for="severity in availableSeverities"
          :key="severity"
          type="button"
          class="rule-index__chip"
          :class="[`rule-index__chip--${severity.toLowerCase()}`, {'is-active': activeSeverities.includes(severity)}]"
          :aria-pressed="activeSeverities.includes(severity)"
          @click="toggleSeverity(severity)"
        >
          {{ severity }}
          <span class="rule-index__count">{{ severityCounts.get(severity) }}</span>
        </button>
      </div>
    </div>

    <p class="rule-index__status" role="status">
      <template v-if="isFiltered">
        Showing {{ matches.length }} of {{ rules.length }} checks.
        <button type="button" class="rule-index__reset" @click="reset">Clear filters</button>
      </template>
      <template v-else> Filter the list, then jump to a check — the detail below narrows to match. </template>
    </p>

    <ol v-if="matches.length" class="rule-index__list">
      <li v-for="rule in matches" :key="rule.id">
        <a :href="`#${rule.slug}`">
          <code class="rule-index__id">{{ rule.id }}</code>
          <span class="rule-index__badge" :class="`rule-index__badge--${rule.severity.toLowerCase()}`">{{
            rule.severity
          }}</span>
          <span class="rule-index__title">{{ rule.title }}</span>
        </a>
      </li>
    </ol>
    <p v-else class="rule-index__empty">No check matches those filters.</p>
  </section>
</template>

<style scoped>
.rule-index {
  margin: 2rem 0 2.5rem;
  padding: 1.25rem 1.35rem 1.1rem;
  border: 1px solid var(--bootui-border);
  border-radius: 0.9rem;
  background: var(--bootui-surface);
  box-shadow: var(--bootui-shadow-sm);
}

.rule-index__controls {
  display: flex;
  flex-wrap: wrap;
  gap: 0.85rem 1.25rem;
  align-items: flex-end;
}

.rule-index__search {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
  flex: 1 1 18rem;
  min-width: 0;
}

.rule-index__label {
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--bootui-text-muted);
}

.rule-index__search input {
  width: 100%;
  padding: 0.5rem 0.75rem;
  font: inherit;
  font-size: 0.95rem;
  color: var(--bootui-text);
  background: var(--bootui-surface-solid);
  border: 1px solid var(--bootui-border-alt);
  border-radius: 0.45rem;
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}

.rule-index__search input:focus {
  outline: none;
  border-color: var(--bootui-green);
  box-shadow: 0 0 0 3px rgba(25, 135, 84, 0.16);
}

.rule-index__severities {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
}

.rule-index__chip {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.4rem 0.65rem;
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: 0.03em;
  color: var(--bootui-text-muted);
  background: transparent;
  border: 1px solid var(--bootui-border-alt);
  border-radius: 999px;
  cursor: pointer;
  transition:
    color 0.15s ease,
    border-color 0.15s ease,
    background 0.15s ease;
}

.rule-index__chip:hover {
  border-color: currentcolor;
}

.rule-index__chip--critical.is-active,
.rule-index__chip--critical:hover {
  color: var(--bootui-severity-critical);
}
.rule-index__chip--high.is-active,
.rule-index__chip--high:hover {
  color: var(--bootui-severity-high);
}
.rule-index__chip--medium.is-active,
.rule-index__chip--medium:hover {
  color: var(--bootui-severity-medium);
}
.rule-index__chip--low.is-active,
.rule-index__chip--low:hover {
  color: var(--bootui-severity-low);
}
.rule-index__chip--info.is-active,
.rule-index__chip--info:hover {
  color: var(--bootui-severity-info);
}

.rule-index__chip.is-active {
  border-color: currentcolor;
  background: color-mix(in srgb, currentcolor 10%, transparent);
}

.rule-index__count {
  font-variant-numeric: tabular-nums;
  opacity: 0.7;
}

.rule-index__status {
  margin: 0.9rem 0 0;
  font-size: 0.82rem;
  color: var(--bootui-text-muted);
}

.rule-index__reset {
  padding: 0;
  font: inherit;
  color: var(--bootui-green);
  background: none;
  border: 0;
  text-decoration: underline;
  cursor: pointer;
}

.rule-index__list {
  margin: 0.65rem 0 0;
  padding: 0;
  list-style: none;
  max-height: 22rem;
  overflow-y: auto;
  border-top: 1px solid var(--bootui-border-subtle);
}

.rule-index__list li + li a {
  border-top: 1px solid var(--bootui-border-subtle);
}

.rule-index__list a {
  display: grid;
  grid-template-columns: minmax(7.5rem, auto) minmax(4.5rem, auto) 1fr;
  gap: 0.75rem;
  align-items: baseline;
  padding: 0.45rem 0.2rem;
  font-size: 0.95rem;
  color: inherit;
  text-decoration: none;
  border-radius: 0.45rem;
}

.rule-index__list a:hover {
  background: var(--bootui-nav-hover-bg);
  color: inherit;
}

.rule-index__id {
  font-size: 0.82rem;
  color: var(--bootui-text-muted);
  background: none;
  padding: 0;
}

.rule-index__badge {
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 0.05em;
}

.rule-index__badge--critical {
  color: var(--bootui-severity-critical);
}
.rule-index__badge--high {
  color: var(--bootui-severity-high);
}
.rule-index__badge--medium {
  color: var(--bootui-severity-medium);
}
.rule-index__badge--low {
  color: var(--bootui-severity-low);
}
.rule-index__badge--info {
  color: var(--bootui-severity-info);
}

.rule-index__title {
  color: var(--bootui-text);
}

.rule-index__empty {
  margin: 0.9rem 0 0;
  font-size: 0.95rem;
  color: var(--bootui-text-muted);
}

@media (max-width: 40rem) {
  .rule-index__list a {
    grid-template-columns: 1fr;
    gap: 0.15rem;
    padding: 0.55rem 0.2rem;
  }
}
</style>
