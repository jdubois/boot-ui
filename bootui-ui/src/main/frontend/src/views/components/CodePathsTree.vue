<script setup>
import {computed, nextTick, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {formatMillis, formatNumber} from '../../utils/format.js'
import {hotPath, methodLabel, nodeKeys} from '../../utils/codePaths.js'

// One route's call tree (docs/PLAN-v2.md §5.14) as a treegrid: each method under its caller, collapsible, with the hot
// path marked and the selected method's detail opened right under its row, where Method probes puts its action.
const props = defineProps({
  tree: {type: Object, required: true},
  // The selected node's key (see nodeKeys), or null.
  selected: {type: String, default: null}
})

const emit = defineEmits(['select', 'select-route', 'action-target'])

/** Indent guides stop here; deeper nodes say how deep they are instead of running off the row. */
const MAX_GUIDES = 12
/** Below this width, calls, self time, and the median leave the grid for the method's detail. */
const NARROW_PX = 640

const root = ref(null)
const narrow = ref(false)
// Columns are added or removed rather than hidden with CSS, so the detail row's colspan always matches the grid.
const columns = computed(() => (narrow.value ? 3 : 6))
let resizeObserver = null

onMounted(() => {
  if (typeof ResizeObserver === 'undefined' || !root.value) return
  resizeObserver = new ResizeObserver(([entry]) => {
    narrow.value = entry.contentRect.width < NARROW_PX
  })
  resizeObserver.observe(root.value)
})
onBeforeUnmount(() => resizeObserver?.disconnect())

const collapsed = ref(new Set())
const focusKey = ref(null)
const grid = ref(null)
// The detail row is rendered inside the v-for, so its slot is reached through a function ref rather than an array ref.
const actionTarget = ref(null)
watch(actionTarget, (element) => emit('action-target', element))
onBeforeUnmount(() => emit('action-target', null))

function setActionTarget(element) {
  actionTarget.value = element ?? null
}

const keys = computed(() => nodeKeys(props.tree.nodes))
const hot = computed(() => hotPath(props.tree.nodes))
const methodsByKey = computed(() => new Map((props.tree.methods ?? []).map((method) => [method.method, method])))
const shareLabel = computed(() => (props.tree.shareOf === 'request' ? 'Share of the request' : 'Share of the handler'))
const hasChildren = computed(() => {
  const parents = new Set()
  for (const node of props.tree.nodes) if (node.parent != null) parents.add(node.parent)
  return parents
})

function expandable(node) {
  return hasChildren.value.has(node.id) || !!node.calls?.length
}

/** The visible rows, in order: each node, its calls, the selected method's detail, then its children. */
const rows = computed(() => {
  const list = []
  let hiddenBelow = Infinity
  for (const node of props.tree.nodes) {
    if (node.depth > hiddenBelow) continue
    hiddenBelow = Infinity
    const key = keys.value.get(node.id)
    const open = !collapsed.value.has(key)
    list.push({type: 'node', key, node, level: node.depth + 1, expandable: expandable(node), open})
    if (open) {
      for (const call of node.calls ?? []) {
        list.push({type: 'call', key: `${key}>${call.kind}`, node, call, level: node.depth + 2})
      }
    }
    // A selected method shows its detail whether or not its branch is open.
    if (key === props.selected && node.kind === 'METHOD') {
      list.push({type: 'detail', key: `${key}>detail`, node, level: node.depth + 2})
    }
    if (!open) hiddenBelow = node.depth
  }
  return list
})

const navigable = computed(() => rows.value.filter((row) => row.type !== 'detail'))
const tabStop = computed(() => {
  const wanted = focusKey.value ?? props.selected
  return navigable.value.some((row) => row.key === wanted) ? wanted : (navigable.value[0]?.key ?? null)
})

/** The largest share among the tree's nodes below the request, which the share bars are drawn against. */
const topShare = computed(() => {
  let top = 0
  for (const node of props.tree.nodes) {
    if (node.kind !== 'REQUEST' && node.share != null) top = Math.max(top, node.share)
  }
  return top
})

const selectedNode = computed(() => rows.value.find((row) => row.type === 'detail')?.node ?? null)
const selectedDetail = computed(() =>
  selectedNode.value ? (methodsByKey.value.get(selectedNode.value.method) ?? null) : null
)

function nodeLabel(node) {
  switch (node.kind) {
    case 'REQUEST':
      return 'Request'
    case 'ASYNC':
      return 'Executor work'
    case 'OTHER':
      return 'Other methods'
    default:
      return methodLabel(node.method)
  }
}

/** A method label split after its class, so a narrow row wraps between the class and the method, never inside them. */
function labelParts(node) {
  const label = methodLabel(node.method)
  const dot = label.lastIndexOf('.')
  return dot < 0 ? ['', label] : [label.slice(0, dot + 1), label.slice(dot + 1)]
}

function phaseLabel(phase) {
  return {FILTERS: 'filters', HANDLER: 'handler', RESPONSE: 'response'}[phase] ?? null
}

function callerLabel(caller) {
  if (caller === 'REQUEST') return 'The request itself'
  if (caller === 'ASYNC') return 'Executor work'
  return methodLabel(caller)
}

/** What a node's recorded calls of one kind are called, singular or plural by calls per request. */
const CALL_LABELS = {
  SQL: ['SQL statement', 'SQL statements', 'bi-database'],
  REST: ['REST client call', 'REST client calls', 'bi-arrow-left-right'],
  CACHE: ['Cache access', 'Cache accesses', 'bi-lightning-charge'],
  AI: ['AI call', 'AI calls', 'bi-stars']
}

function callLabel(call) {
  const labels = CALL_LABELS[call.kind] ?? [call.kind, call.kind]
  return call.callsPerRequest === 1 ? labels[0] : labels[1]
}

function callIcon(call) {
  return CALL_LABELS[call.kind]?.[2] ?? 'bi-dot'
}

function guides(level) {
  return Math.min(level - 1, MAX_GUIDES)
}

function onHotPath(node) {
  return node.kind !== 'REQUEST' && hot.value.has(node.id)
}

function toggleOpen(row) {
  if (!row.expandable) return
  const next = new Set(collapsed.value)
  if (next.has(row.key)) next.delete(row.key)
  else next.add(row.key)
  collapsed.value = next
}

function expandAll() {
  collapsed.value = new Set()
}

/** Folds every branch off the hot path, so the path from the request to its slowest leaf reads on its own. */
function collapseToHotPath() {
  const next = new Set()
  for (const node of props.tree.nodes) {
    if (expandable(node) && !hot.value.has(node.id)) next.add(keys.value.get(node.id))
  }
  collapsed.value = next
}

function select(row) {
  if (row.type !== 'node' || row.node.kind !== 'METHOD') return
  emit('select', props.selected === row.key ? null : {key: row.key, method: row.node.method})
}

async function focusRow(key) {
  if (!key) return
  focusKey.value = key
  await nextTick()
  /** @type {HTMLElement[]} */
  const rowElements = [...(grid.value?.querySelectorAll('[data-row-key]') ?? [])]
  rowElements.find((element) => element.dataset.rowKey === key)?.focus()
}

function onRowClick(row) {
  focusKey.value = row.key
  if (row.type === 'node' && row.node.kind === 'METHOD') select(row)
  else if (row.type === 'node') toggleOpen(row)
}

function parentRow(row) {
  const parentId = row.type === 'call' ? row.node.id : row.node.parent
  return navigable.value.find((candidate) => candidate.type === 'node' && candidate.node.id === parentId)
}

function onKeydown(event, row) {
  const list = navigable.value
  const index = list.findIndex((candidate) => candidate.key === row.key)
  let target = null
  switch (event.key) {
    case 'ArrowDown':
      target = list[index + 1]
      break
    case 'ArrowUp':
      target = list[index - 1]
      break
    case 'Home':
      target = list[0]
      break
    case 'End':
      target = list[list.length - 1]
      break
    case 'ArrowRight':
      if (row.expandable && !row.open) toggleOpen(row)
      else if (row.expandable) target = list[index + 1]
      break
    case 'ArrowLeft':
      if (row.expandable && row.open) toggleOpen(row)
      else target = parentRow(row)
      break
    case 'Enter':
    case ' ':
      if (row.type === 'node' && row.node.kind === 'METHOD') select(row)
      else if (row.type === 'node') toggleOpen(row)
      break
    default:
      return
  }
  event.preventDefault()
  if (target) focusRow(target.key)
}

function closeDetail() {
  const key = props.selected
  emit('select', null)
  focusRow(key)
}

function moreNodes() {
  const page = props.tree.page
  return page && page.hasMore ? page.matched - page.offset - page.returned : 0
}

defineExpose({focusRow})
</script>

<template>
  <div ref="root" class="code-paths-tree-block" :class="{'code-paths-tree-narrow': narrow}">
    <div class="code-paths-tree-tools">
      <p class="small text-muted mb-0 code-paths-hot-legend">
        <span class="code-paths-hot-dot" aria-hidden="true"></span>
        Hot path: the call that took the most time at each level.
      </p>
      <div class="d-flex flex-wrap gap-2">
        <button class="btn btn-sm btn-outline-secondary code-paths-hot-only" type="button" @click="collapseToHotPath">
          <i class="bi bi-arrows-collapse me-1" aria-hidden="true"></i>Collapse to the hot path
        </button>
        <button class="btn btn-sm btn-outline-secondary code-paths-expand-all" type="button" @click="expandAll">
          <i class="bi bi-arrows-expand me-1" aria-hidden="true"></i>Expand all
        </button>
      </div>
    </div>
    <div class="table-responsive code-paths-tree-scroll">
      <table
        ref="grid"
        :aria-label="`Call tree of ${tree.route}, each method under its caller`"
        aria-describedby="code-paths-tree-keys"
        class="table table-sm align-middle mb-0 code-paths-table code-paths-tree"
        role="treegrid"
      >
        <thead>
          <tr>
            <th scope="col">Method</th>
            <th v-if="!narrow" scope="col" class="text-end code-paths-col-number">Calls / req</th>
            <th scope="col" class="text-end code-paths-col-number">Total (ms)</th>
            <th v-if="!narrow" scope="col" class="text-end code-paths-col-number">Self (ms)</th>
            <th v-if="!narrow" scope="col" class="text-end code-paths-col-number">
              <span title="Approximate: interpolated within log2 buckets of per-request time">Median (≈ ms)</span>
            </th>
            <th scope="col" class="code-paths-col-share">
              <template v-if="narrow"
                ><span aria-hidden="true">Share</span><span class="visually-hidden">{{ shareLabel }}</span></template
              >
              <template v-else>{{ shareLabel }}</template>
            </th>
          </tr>
        </thead>
        <tbody>
          <template v-for="row in rows" :key="row.key">
            <tr
              v-if="row.type === 'node'"
              :aria-expanded="row.expandable ? (row.open ? 'true' : 'false') : undefined"
              :aria-level="row.level"
              :aria-selected="row.node.kind === 'METHOD' ? (row.key === selected ? 'true' : 'false') : undefined"
              :class="{
                'code-paths-async': row.node.async,
                'code-paths-node-selected': row.key === selected,
                'code-paths-node-hot': onHotPath(row.node),
                'code-paths-node-method': row.node.kind === 'METHOD'
              }"
              :data-row-key="row.key"
              :tabindex="row.key === tabStop ? 0 : -1"
              class="code-paths-node"
              role="row"
              @click="onRowClick(row)"
              @focus="focusKey = row.key"
              @keydown="onKeydown($event, row)"
            >
              <td role="gridcell" class="code-paths-method-cell">
                <div class="code-paths-indent">
                  <span
                    v-if="guides(row.level)"
                    class="code-paths-guides"
                    :style="{'--code-paths-guides': guides(row.level)}"
                    aria-hidden="true"
                  ></span>
                  <span v-if="row.level - 1 > MAX_GUIDES" class="code-paths-depth" :title="`Level ${row.level}`"
                    >+{{ row.level - 1 - MAX_GUIDES }}</span
                  >
                  <span
                    class="code-paths-twisty"
                    :class="{open: row.open}"
                    aria-hidden="true"
                    @click.stop="toggleOpen(row)"
                  >
                    <i v-if="row.expandable" class="bi bi-chevron-right"></i>
                  </span>
                  <span v-if="onHotPath(row.node)" class="code-paths-hot-dot" aria-hidden="true"></span>
                  <code
                    v-if="row.node.kind === 'METHOD'"
                    :title="row.node.method"
                    class="code-paths-method code-paths-label"
                    >{{ labelParts(row.node)[0] }}<wbr />{{ labelParts(row.node)[1] }}</code
                  >
                  <span v-else class="code-paths-label" :class="{'fw-semibold': row.node.kind === 'REQUEST'}">{{
                    nodeLabel(row.node)
                  }}</span>
                  <span v-if="onHotPath(row.node)" class="visually-hidden">, on the hot path</span>
                  <span v-if="row.node.kind === 'ASYNC'" class="badge text-bg-info ms-2">Async, shown apart</span>
                  <span v-else-if="row.node.async" class="badge text-bg-info ms-2">Async</span>
                  <span
                    v-if="phaseLabel(row.node.phase) && row.node.kind === 'METHOD'"
                    class="small text-muted ms-2 code-paths-phase"
                    :class="{'visually-hidden': narrow}"
                  >
                    {{ phaseLabel(row.node.phase) }}
                  </span>
                </div>
              </td>
              <td v-if="!narrow" role="gridcell" class="text-end">
                {{ row.node.kind === 'REQUEST' ? '—' : formatNumber(row.node.callsPerRequest) }}
              </td>
              <td role="gridcell" class="text-end">{{ formatMillis(row.node.totalMillis) }}</td>
              <td v-if="!narrow" role="gridcell" class="text-end">{{ formatMillis(row.node.selfMillis) }}</td>
              <td v-if="!narrow" role="gridcell" class="text-end code-paths-median">
                <template v-if="row.node.p50Millis != null">≈ {{ formatMillis(row.node.p50Millis) }}</template>
                <span v-else class="text-muted">—</span>
              </td>
              <td role="gridcell">
                <span v-if="row.node.share != null && row.node.kind !== 'REQUEST'" class="code-paths-share">
                  <span class="code-paths-share-track" aria-hidden="true">
                    <span
                      class="code-paths-share-bar"
                      :class="{'code-paths-share-bar-top': row.node.share === topShare}"
                      :style="{width: `${Math.min(100, row.node.share)}%`}"
                    ></span>
                  </span>
                  <span class="code-paths-share-value">{{ row.node.share }} %</span>
                </span>
                <span v-else class="text-muted">—</span>
              </td>
            </tr>

            <tr
              v-else-if="row.type === 'call'"
              :aria-level="row.level"
              :class="{'code-paths-async': row.node.async}"
              :data-row-key="row.key"
              :tabindex="row.key === tabStop ? 0 : -1"
              class="code-paths-call"
              role="row"
              @focus="focusKey = row.key"
              @keydown="onKeydown($event, row)"
            >
              <td role="gridcell" class="code-paths-method-cell">
                <div class="code-paths-indent">
                  <span
                    class="code-paths-guides"
                    :style="{'--code-paths-guides': guides(row.level)}"
                    aria-hidden="true"
                  ></span>
                  <span class="code-paths-twisty" aria-hidden="true"></span>
                  <span
                    :title="`Issued while ${nodeLabel(row.node)} was the innermost instrumented method open on their thread`"
                    class="code-paths-call-label"
                  >
                    <i :class="['bi', callIcon(row.call), 'me-1']" aria-hidden="true"></i>{{ callLabel(row.call) }}
                  </span>
                  <span class="visually-hidden">, issued while {{ nodeLabel(row.node) }} was open</span>
                  <span class="code-paths-call-count" aria-hidden="true"
                    >× {{ formatNumber(row.call.callsPerRequest) }}</span
                  >
                </div>
              </td>
              <td v-if="!narrow" role="gridcell" class="text-end">
                {{ formatNumber(row.call.callsPerRequest) }}
              </td>
              <td role="gridcell" class="text-end">
                <template v-if="row.call.totalMillis != null">{{ formatMillis(row.call.totalMillis) }}</template>
                <span v-else class="text-muted" title="Cache accesses carry no duration">—</span>
              </td>
              <td v-if="!narrow" role="gridcell" class="text-end text-muted">—</td>
              <td v-if="!narrow" role="gridcell" class="text-end text-muted">—</td>
              <td role="gridcell" class="text-muted">—</td>
            </tr>

            <tr v-else :aria-level="row.level" class="code-paths-detail-row" role="row">
              <td :colspan="columns" role="gridcell">
                <section class="code-paths-method-detail" aria-labelledby="code-paths-method-heading">
                  <div class="code-paths-method-detail-head">
                    <div class="code-paths-method-title">
                      <h4 id="code-paths-method-heading" class="h6 mb-1">
                        <code>{{ methodLabel(row.node.method) }}</code>
                      </h4>
                      <p class="small text-muted bootui-break-anywhere mb-0 code-paths-method-key">
                        {{ row.node.method }}
                      </p>
                    </div>
                    <button
                      aria-label="Close the method detail"
                      class="btn btn-sm btn-outline-secondary code-paths-method-close"
                      type="button"
                      @click="closeDetail"
                    >
                      <i class="bi bi-x-lg" aria-hidden="true"></i>
                    </button>
                  </div>
                  <dl class="code-paths-method-stats">
                    <div>
                      <dt>Calls per request</dt>
                      <dd>{{ formatNumber(row.node.callsPerRequest) }}</dd>
                    </div>
                    <div>
                      <dt>Total</dt>
                      <dd>{{ formatMillis(row.node.totalMillis) }} ms</dd>
                    </div>
                    <div>
                      <dt>Self</dt>
                      <dd>{{ formatMillis(row.node.selfMillis) }} ms</dd>
                    </div>
                    <div v-if="row.node.p50Millis != null">
                      <dt>Median</dt>
                      <dd>≈ {{ formatMillis(row.node.p50Millis) }} ms</dd>
                    </div>
                    <div v-if="row.node.p95Millis != null">
                      <dt>p95</dt>
                      <dd>≈ {{ formatMillis(row.node.p95Millis) }} ms</dd>
                    </div>
                    <div v-if="row.node.share != null">
                      <dt>{{ shareLabel }}</dt>
                      <dd>{{ row.node.share }} %</dd>
                    </div>
                  </dl>
                  <div v-if="selectedDetail" class="code-paths-method-links">
                    <div>
                      <h5 class="small fw-semibold text-muted mb-1">Called by, in this route</h5>
                      <ul class="list-unstyled small mb-0 code-paths-callers">
                        <li v-for="caller in selectedDetail.callers" :key="caller">
                          <code :title="caller">{{ callerLabel(caller) }}</code>
                        </li>
                      </ul>
                    </div>
                    <div>
                      <h5 class="small fw-semibold text-muted mb-1">Routes that reach it</h5>
                      <ul class="list-unstyled small mb-0 code-paths-reach">
                        <li v-for="name in selectedDetail.routes" :key="name">
                          <button
                            v-if="name !== tree.route"
                            class="btn btn-outline-secondary btn-sm text-start"
                            type="button"
                            @click="emit('select-route', name)"
                          >
                            <code>{{ name }}</code>
                          </button>
                          <span v-else
                            ><code>{{ name }}</code> <span class="text-muted">(this route)</span></span
                          >
                        </li>
                      </ul>
                    </div>
                  </div>
                  <div :ref="setActionTarget" class="code-paths-probe-slot"></div>
                </section>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
    <p v-if="moreNodes()" class="small text-muted mt-2 mb-0">{{ formatNumber(moreNodes()) }} more nodes not shown.</p>
    <p id="code-paths-tree-keys" class="visually-hidden">
      Arrow keys move between rows; Right opens a branch and Left closes it; Enter shows a method's detail.
    </p>
  </div>
</template>

<style scoped>
.code-paths-tree-tools {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem 1rem;
  margin-bottom: 0.5rem;
}

.code-paths-hot-legend {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}

/* The tree scrolls inside its drawer, so the routes listed after the open one stay a short scroll away. */
.code-paths-tree-scroll {
  max-height: min(70vh, 44rem);
  overflow: auto;
  overscroll-behavior: contain;
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-md);
  background: var(--bootui-surface-solid);
}

.code-paths-tree {
  table-layout: fixed;
  min-width: 46rem;
  font-variant-numeric: tabular-nums;
}

.code-paths-tree thead th {
  position: sticky;
  top: 0;
  z-index: 2;
  background: var(--bootui-surface-solid);
  box-shadow: inset 0 -1px 0 var(--bootui-border);
  vertical-align: bottom;
}

.code-paths-col-number {
  width: 6.5rem;
}

.code-paths-col-share {
  width: 11rem;
}

.code-paths-node,
.code-paths-call {
  cursor: default;
}

.code-paths-node-method {
  cursor: pointer;
}

.code-paths-node > td,
.code-paths-call > td {
  padding-block: 0.4rem;
}

.code-paths-node:hover > td {
  --bs-table-bg-state: var(--bootui-nav-hover-bg);
}

.code-paths-node:focus-visible,
.code-paths-call:focus-visible {
  outline: 2px solid var(--bootui-blue);
  outline-offset: -2px;
}

.code-paths-node-selected > td,
.code-paths-detail-row > td {
  --bs-table-bg-state: color-mix(in srgb, var(--bootui-blue) 10%, transparent);
}

.code-paths-indent {
  display: flex;
  align-items: center;
  min-width: 0;
}

.code-paths-tree-block {
  --code-paths-guide-step: 1rem;
}

.code-paths-guides {
  flex: 0 0 auto;
  width: calc(var(--code-paths-guides) * var(--code-paths-guide-step));
  align-self: stretch;
  min-height: 1.4rem;
  background-image: linear-gradient(to right, var(--bootui-border-alt) 1px, transparent 1px);
  background-size: var(--code-paths-guide-step) 100%;
  background-position: calc(var(--code-paths-guide-step) / 2 - 0.05rem) 0;
}

.code-paths-depth {
  flex: 0 0 auto;
  margin-inline-end: 0.25rem;
  padding: 0 0.3rem;
  border: 1px solid var(--bootui-border-alt);
  border-radius: var(--bootui-radius-pill);
  color: var(--bootui-text-muted);
  font-size: 0.72rem;
}

.code-paths-twisty {
  flex: 0 0 1.1rem;
  display: inline-flex;
  justify-content: center;
  color: var(--bootui-text-muted);
  cursor: pointer;
}

.code-paths-twisty .bi {
  transition: transform 150ms ease;
}

.code-paths-twisty.open .bi {
  transform: rotate(90deg);
}

.code-paths-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.code-paths-node-hot .code-paths-label {
  font-weight: 700;
}

.code-paths-hot-dot {
  flex: 0 0 auto;
  width: 0.45rem;
  height: 0.45rem;
  margin-inline-end: 0.4rem;
  border-radius: var(--bootui-radius-pill);
  background: var(--bootui-green-dark);
}

.code-paths-phase {
  flex: 0 0 auto;
}

.code-paths-async > td {
  --bs-table-bg-type: var(--bootui-surface-alt);
}

.code-paths-share {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.code-paths-share-track {
  position: relative;
  flex: 1 1 auto;
  height: 0.5rem;
  background: var(--bootui-border-alt);
  border-radius: var(--bootui-radius-xs);
  overflow: hidden;
}

.code-paths-share-bar {
  position: absolute;
  inset: 0 auto 0 0;
  min-width: 2px;
  background: var(--bootui-text-muted);
  border-radius: var(--bootui-radius-xs);
}

.code-paths-node-hot .code-paths-share-bar,
.code-paths-share-bar-top {
  background: var(--bootui-green-dark);
}

.code-paths-share-value {
  flex: 0 0 3.5rem;
  text-align: end;
}

.code-paths-call-count {
  display: none;
  margin-inline-start: 0.4rem;
}

.code-paths-call > td {
  color: var(--bootui-text-muted);
  font-size: 0.875em;
}

.code-paths-detail-row > td {
  padding: 0;
}

.code-paths-method-detail {
  display: grid;
  gap: 0.85rem;
  padding: 0.85rem 1rem 1rem 1.25rem;
  border-top: 1px solid var(--bootui-border);
  white-space: normal;
}

.code-paths-method-detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
}

.code-paths-method-title {
  min-width: 0;
}

.code-paths-method-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem 1.5rem;
  margin: 0;
}

.code-paths-method-stats dt {
  color: var(--bootui-text-muted);
  font-size: 0.72rem;
  font-weight: 600;
}

.code-paths-method-stats dd {
  margin: 0;
  font-weight: 700;
}

.code-paths-method-links {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(14rem, 1fr));
  gap: 0.85rem 1.5rem;
}

.code-paths-probe-slot:empty {
  display: none;
}

.code-paths-probe-slot {
  padding-top: 0.85rem;
  border-top: 1px solid var(--bootui-border);
}

/* Narrow: the method, its total, and its share; calls, self, and the median stay in the method's detail. */
.code-paths-tree-narrow {
  --code-paths-guide-step: 0.6rem;
}

.code-paths-tree-narrow .code-paths-tree {
  min-width: 0;
}

.code-paths-tree-narrow .code-paths-col-number {
  width: 4.75rem;
}

.code-paths-tree-narrow .code-paths-col-share {
  width: 4.5rem;
}

.code-paths-tree-narrow .code-paths-share-track {
  display: none;
}

.code-paths-tree-narrow .code-paths-label {
  overflow: visible;
  overflow-wrap: anywhere;
  white-space: normal;
}

.code-paths-tree-narrow .code-paths-call-count {
  display: inline;
}

@media (prefers-reduced-motion: reduce) {
  .code-paths-twisty .bi {
    transition: none;
  }
}
</style>
