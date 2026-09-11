<script setup>
import {computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch} from 'vue'
import {createExplorerScene} from '../../utils/explorerScene.js'
import {explorerModelFor} from '../../utils/explorerModels.js'

const props = defineProps({
  layout: {type: Object, required: true},
  selectedId: {type: String, default: null},
  frameKey: {type: String, default: 'overview'},
  activeRows: {type: Array, default: () => []},
  effects: {type: Array, default: () => []}
})
const emit = defineEmits(['select', 'unavailable'])
const container = ref(null),
  fullscreenButton = ref(null),
  fullscreen = ref(false),
  fullscreenSupported = ref(false),
  fullscreenError = ref(''),
  host = ref(null),
  failure = ref(''),
  browsedNode = ref(null)
const descriptionId = useId()
const hintId = useId()
const selectedNode = computed(() => props.layout.nodes.find((node) => node.rowIds.includes(props.selectedId)) || null)
const description = computed(() => {
  const node = browsedNode.value?.kind === 'GROUP' ? browsedNode.value : selectedNode.value
  if (!node) return 'No node selected. Use an arrow key to start browsing.'
  return `${explorerModelFor(node).name}: ${node.label}. ${node.rowIds.length} observation${node.rowIds.length === 1 ? '' : 's'}.${node.kind === 'GROUP' ? ' Press Enter to expand.' : ' Details in the inspector.'}`
})
let scene
function reset() {
  scene?.reset()
}
async function toggleFullscreen() {
  fullscreenError.value = ''
  try {
    if (document.fullscreenElement === container.value) await document.exitFullscreen()
    else await container.value.requestFullscreen()
  } catch (err) {
    fullscreenError.value = `Full screen could not be ${fullscreen.value ? 'closed' : 'opened'}. ${err.message || 'Try again using a browser that supports full screen.'}`
  }
}
async function fullscreenChanged() {
  const wasFullscreen = fullscreen.value
  fullscreen.value = document.fullscreenElement === container.value
  await nextTick()
  if (fullscreen.value) host.value?.focus({preventScroll: true})
  else if (wasFullscreen) fullscreenButton.value?.focus({preventScroll: true})
}
function fullscreenKeydown(event) {
  if (!fullscreen.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    toggleFullscreen()
  } else if (event.key === 'Tab') {
    const controls = [
      ...container.value.querySelectorAll('button:not([disabled]):not([tabindex="-1"]), [tabindex="0"]')
    ]
    const first = controls[0],
      last = controls.at(-1)
    if ((event.shiftKey && event.target === first) || (!event.shiftKey && event.target === last)) {
      event.preventDefault()
      const next = event.shiftKey ? last : first
      next?.focus()
    }
  }
}
function failed(reason) {
  failure.value = reason
  emit('unavailable')
}
function initialize() {
  scene?.dispose()
  failure.value = ''
  try {
    scene = createExplorerScene(host.value, {
      onSelect: (node) => emit('select', node),
      onNavigate: (node) => (browsedNode.value = node),
      onFailure: failed
    })
    scene.update(props.layout, props.selectedId, props.activeRows)
    scene.setEffects(props.effects)
  } catch (err) {
    scene?.dispose()
    failed(err.message || '3D could not initialize. The execution tree remains available.')
  }
}
watch(
  () => props.layout,
  (next) => {
    if (!next.nodes.some((node) => node.id === browsedNode.value?.id)) browsedNode.value = null
    scene?.update(next, props.selectedId, props.activeRows)
  }
)
watch(
  () => [props.selectedId, props.activeRows],
  () => scene?.select(props.selectedId, props.activeRows)
)
watch(
  () => props.selectedId,
  () => (browsedNode.value = null)
)
watch(
  () => props.effects,
  (next) => scene?.setEffects(next)
)
watch(() => props.frameKey, reset)
onMounted(() => {
  fullscreenSupported.value =
    typeof container.value.requestFullscreen === 'function' && document.fullscreenEnabled !== false
  document.addEventListener('fullscreenchange', fullscreenChanged)
  initialize()
})
onBeforeUnmount(() => {
  document.removeEventListener('fullscreenchange', fullscreenChanged)
  scene?.dispose()
})
</script>

<template>
  <div ref="container" class="explorer-scene" data-testid="explorer-scene" @keydown="fullscreenKeydown">
    <div
      ref="host"
      class="explorer-scene-host"
      :tabindex="failure ? -1 : 0"
      role="application"
      aria-label="3D captured journey"
      :aria-describedby="failure ? descriptionId : `${hintId} ${descriptionId}`"
      aria-keyshortcuts="ArrowLeft ArrowRight ArrowUp ArrowDown Shift+ArrowLeft Shift+ArrowRight Shift+ArrowUp Shift+ArrowDown + - Home Enter Escape"
    ></div>
    <p :id="descriptionId" class="visually-hidden" aria-live="polite" aria-atomic="true">{{ description }}</p>
    <div v-if="failure" class="explorer-fallback" role="status">
      <i class="bi bi-box" aria-hidden="true"></i>
      <h3>Execution tree available</h3>
      <p>{{ failure }}</p>
      <button class="btn btn-outline-secondary btn-sm" @click="initialize">Retry 3D</button>
    </div>
    <div v-if="!failure || fullscreen" class="explorer-scene-toolbar">
      <button v-if="!failure" class="btn btn-outline-secondary btn-sm" @click="reset">
        <i class="bi bi-bounding-box me-1" aria-hidden="true"></i>Reset view
      </button>
      <button
        ref="fullscreenButton"
        class="btn btn-outline-secondary btn-sm"
        :aria-pressed="fullscreen"
        :disabled="!fullscreenSupported"
        :title="fullscreenSupported ? null : 'Full screen is unavailable in this browser'"
        @click="toggleFullscreen"
      >
        <i :class="['bi me-1', fullscreen ? 'bi-fullscreen-exit' : 'bi-arrows-fullscreen']" aria-hidden="true"></i
        >{{ fullscreen ? 'Exit full screen' : 'Full screen' }}
      </button>
    </div>
    <p v-if="fullscreenError" class="explorer-fullscreen-error" role="alert">{{ fullscreenError }}</p>
    <template v-if="!failure">
      <p :id="hintId" class="explorer-camera-hint">
        <span><i class="bi bi-arrow-left-right" aria-hidden="true"></i> ← → stages · ↑ ↓ branches</span>
        <span>Shift + arrows orbit · + / − zoom · Home reset · Tab / Esc leave</span>
        <span class="explorer-pointer-hint">Drag to orbit · Scroll to zoom</span>
      </p>
    </template>
  </div>
</template>

<style scoped>
.explorer-scene {
  position: relative;
  min-height: 29rem;
  height: 100%;
  overflow: hidden;
}
.explorer-scene:fullscreen {
  width: 100vw;
  height: 100vh;
  background: var(--bootui-surface-solid);
}
.explorer-scene-host {
  position: absolute;
  inset: 0;
}
.explorer-scene-host:focus-visible {
  outline: 2px solid var(--bootui-blue);
  outline-offset: -3px;
}
.explorer-scene-toolbar {
  position: absolute;
  right: 1rem;
  top: 1rem;
  display: flex;
  gap: 0.5rem;
}
.explorer-scene-toolbar .btn {
  background: var(--bootui-surface-solid);
  color: var(--bootui-text-muted);
}
.explorer-scene-toolbar .btn:hover,
.explorer-scene-toolbar .btn:focus-visible {
  color: var(--bootui-text);
  border-color: var(--bootui-blue);
}
.explorer-fullscreen-error {
  position: absolute;
  top: 3.5rem;
  right: 1rem;
  left: 1rem;
  padding: 0.5rem 0.75rem;
  background: var(--bootui-surface-solid);
  color: var(--bootui-danger-text);
  font-size: 0.875rem;
}
.explorer-camera-hint {
  position: absolute;
  bottom: 0;
  left: 1rem;
  right: 1rem;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.15rem 1rem;
  padding: 0.5rem 0;
  background: var(--bootui-surface-alt);
  margin: 0;
  font-size: 0.75rem;
  color: var(--bootui-text-muted);
  pointer-events: none;
}
.explorer-camera-hint span {
  min-width: 0;
  max-width: 100%;
}
.explorer-pointer-hint {
  margin-left: auto;
}
.explorer-fallback {
  position: relative;
  display: grid;
  align-content: center;
  justify-items: start;
  min-height: 29rem;
  max-width: 38rem;
  margin: auto;
  padding: 2rem;
  gap: 0.75rem;
}
.explorer-fallback > i {
  font-size: var(--bootui-icon-size);
  color: var(--bootui-text-muted);
}
.explorer-fallback h3 {
  font-size: 1.15rem;
  margin: 0;
}
.explorer-fallback p {
  color: var(--bootui-text-muted);
  margin: 0;
}
.explorer-scene-host :deep(.explorer-scene-labels) {
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
}
.explorer-scene-host :deep(.explorer-node-label),
.explorer-scene-host :deep(.explorer-stage-label) {
  position: absolute;
  top: 0;
  left: 0;
  white-space: nowrap;
  background: var(--bootui-surface-solid);
  color: var(--bootui-text);
}
.explorer-scene-host :deep(.explorer-node-label) {
  pointer-events: auto;
  width: 8.5rem;
  white-space: normal;
  border: 1px solid var(--bootui-border-alt);
  border-radius: var(--bootui-radius-xs);
  padding: 0.3rem 0.45rem;
  font-size: 0.75rem;
  font-family: var(--bs-font-monospace);
  line-height: 1.35;
  text-align: center;
}
.explorer-scene-host :deep(.explorer-node-category) {
  display: block;
  font-family: var(--bs-body-font-family);
  font-size: 0.72rem;
  color: var(--bootui-text-muted);
  margin-bottom: 0.1rem;
}
.explorer-scene-host :deep(.explorer-node-name) {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  overflow-wrap: anywhere;
}
.explorer-scene-host :deep(.explorer-node-meta) {
  display: block;
  font-size: 0.72rem;
  color: var(--bootui-text-muted);
}
.explorer-scene-host :deep(.explorer-node-label[data-selected='true']) {
  width: 14rem;
  max-width: min(19rem, 80%);
  min-width: 7.3rem;
  z-index: 2;
}
.explorer-scene-host :deep(.explorer-label-leader) {
  position: absolute;
  width: 1px;
  background: var(--bootui-text-subtle);
  opacity: 0.55;
  pointer-events: none;
}
.explorer-scene-host :deep(.explorer-node-label[data-selected='true'] .explorer-node-name) {
  -webkit-line-clamp: 3;
}
.explorer-scene-host :deep(.explorer-node-label:hover),
.explorer-scene-host :deep(.explorer-node-label[data-selected='true']) {
  color: var(--bootui-green-dark);
  border-color: var(--bootui-green);
}
.explorer-scene-host :deep(.explorer-stage-label) {
  padding: 0.25rem 0.5rem;
  max-width: 8rem;
  white-space: normal;
  text-align: center;
  font-size: 0.75rem;
  font-weight: 600;
  color: var(--bootui-text-muted);
}
</style>
