<script setup>
import {computed, ref} from 'vue'
import {durationLabel} from '../../utils/explorerModel.js'

const props = defineProps({
  rows: {type: /** @type {import('vue').PropType<Array<Record<string, any>>>} */ (Array), required: true},
  selectedId: {type: String, default: null},
  activeRows: {type: Array, default: () => []}
})
const emit = defineEmits(['select'])
const tree = ref(null),
  collapsed = ref(new Set())
const visible = computed(() => {
  const hidden = new Set()
  return props.rows.filter((row) => {
    if (hidden.has(row.parent) || collapsed.value.has(row.parent)) {
      hidden.add(row.id)
      return false
    }
    return true
  })
})
const roving = computed(() =>
  visible.value.some((row) => row.id === props.selectedId) ? props.selectedId : visible.value[0]?.id
)
const hasChildren = (id) => props.rows.some((row) => row.parent === id)
function toggle(id) {
  const next = new Set(collapsed.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  collapsed.value = next
}
function keydown(event, row) {
  const index = visible.value.indexOf(row)
  let next
  if (event.key === 'ArrowDown') next = Math.min(visible.value.length - 1, index + 1)
  if (event.key === 'ArrowUp') next = Math.max(0, index - 1)
  if (event.key === 'Home') next = 0
  if (event.key === 'End') next = visible.value.length - 1
  if (event.key === 'ArrowRight') {
    if (collapsed.value.has(row.id)) toggle(row.id)
    else if (hasChildren(row.id)) next = index + 1
  }
  if (event.key === 'ArrowLeft') {
    if (hasChildren(row.id) && !collapsed.value.has(row.id)) toggle(row.id)
    else next = visible.value.findIndex((candidate) => candidate.id === row.parent)
  }
  if (['ArrowDown', 'ArrowUp', 'ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) {
    event.preventDefault()
    if (next >= 0) tree.value?.querySelectorAll('[role="treeitem"]')[next]?.focus()
  }
}
</script>

<template>
  <section class="explorer-tree-section" aria-labelledby="explorer-tree-title">
    <div class="explorer-section-heading">
      <h3 id="explorer-tree-title">Execution tree</h3>
      <span>{{ rows.length }} observations</span>
    </div>
    <p class="explorer-tree-help">Arrow keys navigate · Enter selects · Nested timings overlap</p>
    <div ref="tree" class="explorer-tree" role="tree" aria-label="Captured execution tree">
      <button
        v-for="row in visible"
        :key="row.id"
        class="explorer-tree-row"
        :class="[`tone-${row.tone}`, {'is-active': activeRows.includes(row.id)}]"
        role="treeitem"
        :aria-level="row.depth + 1"
        :aria-selected="selectedId === row.id"
        :aria-expanded="hasChildren(row.id) ? !collapsed.has(row.id) : undefined"
        :tabindex="roving === row.id ? 0 : -1"
        :data-row-id="row.id"
        :style="{'--depth': Math.min(row.depth, 12)}"
        @click="emit('select', row.id)"
        @keydown="keydown($event, row)"
      >
        <span class="explorer-tree-branch" aria-hidden="true">{{
          hasChildren(row.id) ? (collapsed.has(row.id) ? '▸' : '▾') : '·'
        }}</span>
        <span class="explorer-tree-label">
          <span class="explorer-tree-kind">{{ row.kind === 'CALL' ? row.call.role || 'COMPONENT' : row.kind }}</span>
          <span class="font-monospace">{{ row.label }}</span>
          <span v-if="row.operation" class="explorer-operation"
            >{{ row.operation
            }}{{
              row.operation === 'HIT'
                ? ' ↔'
                : row.operation === 'MISS'
                  ? ' → no return'
                  : row.operation === 'PUT'
                    ? ' → cache'
                    : ' · invalidation'
            }}</span
          >
          <span v-if="row.call?.failed || row.event?.severity === 'ERROR'" class="explorer-row-state">Failed</span>
          <span v-if="row.call?.slow || row.event?.severity === 'SLOW'" class="explorer-row-state">Slow</span>
          <span v-if="row.kind === 'SQL_REFERENCE'" class="explorer-tree-note">SQL reference · untimed</span>
        </span>
        <span v-if="row.kind !== 'SQL_REFERENCE'" class="explorer-tree-duration font-monospace">{{
          durationLabel(row.durationMs)
        }}</span>
      </button>
    </div>
  </section>
</template>

<style scoped>
.explorer-tree-section {
  min-width: 0;
}
.explorer-section-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 0.75rem;
  padding: 1rem 1rem 0;
}
.explorer-section-heading h3 {
  font-size: 1rem;
  font-weight: 700;
  margin: 0;
}
.explorer-section-heading > span,
.explorer-tree-help {
  color: var(--bootui-text-muted);
  font-size: 0.75rem;
}
.explorer-tree-help {
  padding: 0.4rem 1rem 0.5rem;
  margin: 0;
}
.explorer-tree {
  overflow: auto;
  max-height: 24rem;
  padding-bottom: 0.75rem;
}
.explorer-tree-row {
  display: flex;
  align-items: baseline;
  gap: 0.4rem;
  width: 100%;
  border: 0;
  border-top: 1px solid var(--bootui-border-subtle);
  background: transparent;
  color: var(--bootui-text);
  text-align: left;
  padding: 0.65rem 1rem 0.65rem calc(0.6rem + var(--depth) * 0.85rem);
}
.explorer-tree-row:hover {
  background: var(--bootui-surface-alt);
}
.explorer-tree-row[aria-selected='true'],
.explorer-tree-row.is-active {
  background: color-mix(in srgb, var(--bootui-green) 10%, var(--bootui-surface-solid));
}
.explorer-tree-row:focus-visible {
  outline: 2px solid var(--bootui-blue);
  outline-offset: -2px;
}
.explorer-tree-branch {
  width: 0.65rem;
  flex-shrink: 0;
  color: var(--bootui-text-muted);
}
.explorer-tree-label {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.15rem 0.4rem;
  font-size: 0.85rem;
  overflow-wrap: anywhere;
}
.explorer-tree-kind {
  display: block;
  width: 100%;
  font-size: 0.72rem;
  color: var(--bootui-text-muted);
}
.explorer-tree-duration {
  flex-shrink: 0;
  font-size: 0.75rem;
}
.explorer-tree-note {
  font-size: 0.75rem;
  color: var(--bootui-text-muted);
}
.explorer-row-state,
.explorer-operation {
  font-size: 0.75rem;
  font-weight: 600;
}
.tone-failed .explorer-row-state {
  color: var(--bootui-danger-text);
}
.tone-slow .explorer-row-state {
  color: var(--bootui-warning-text-strong);
}
</style>
