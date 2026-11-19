<script setup>
import {computed, getCurrentInstance, onBeforeUnmount, ref, watch} from 'vue'
import {formatRelative} from '../../utils/format.js'
import {describeLoadError} from '../../utils/loadError.js'
import AutoRefreshToggle from './AutoRefreshToggle.vue'

const props = defineProps({
  icon: {type: String, default: null},
  title: {type: String, required: true},
  subtitle: {type: String, default: null},
  loading: {type: Boolean, default: false},
  error: {type: [String, Object], default: null},
  lastFetched: {type: Number, default: null},
  lastFetchedLabel: {type: String, default: null},
  refreshable: {type: Boolean, default: true},
  autoRefresh: {type: Boolean, default: null},
  autoRefreshable: {type: Boolean, default: true},
  autoRefreshTitle: {type: String, default: 'Refresh every 10 seconds while this tab is visible'},
  autoRefreshState: {
    type: /** @type {import('vue').PropType<'connecting'|'connected'|'reconnecting'|'paused'|'unavailable'|null>} */ (
      String
    ),
    default: null
  }
})

const emit = defineEmits(['refresh', 'update:autoRefresh', 'retryAutoRefresh', 'titleAction'])

const instance = getCurrentInstance()
const hasTitleAction = computed(() => !!instance?.vnode.props?.onTitleAction)
const hasRefresh = computed(() => props.refreshable && !!instance?.vnode.props?.onRefresh)
const hasAutoRefresh = computed(() => props.autoRefreshable && props.autoRefresh !== null)
const now = ref(Date.now())
let relativeTimer = null

function stopRelativeTimer() {
  if (relativeTimer) {
    clearInterval(relativeTimer)
    relativeTimer = null
  }
}

function startRelativeTimer() {
  stopRelativeTimer()
  if (props.lastFetched) {
    now.value = Date.now()
    relativeTimer = setInterval(() => {
      now.value = Date.now()
    }, 1000)
  }
}

const lastFetchedText = computed(() => {
  if (!props.lastFetched) return null
  return formatRelative(props.lastFetched, now.value)
})
const lastFetchedDisplayText = computed(() => {
  if (!lastFetchedText.value) return null
  return props.lastFetchedLabel ? `${props.lastFetchedLabel} ${lastFetchedText.value}` : lastFetchedText.value
})
const loadError = computed(() => {
  if (!props.error) return null
  if (typeof props.error === 'object' && props.error !== null && 'serverUnreachable' in props.error) {
    return props.error
  }
  return describeLoadError(props.error)
})
const retryButtonClass = computed(() =>
  loadError.value?.serverUnreachable ? 'btn-outline-warning' : 'btn-outline-danger'
)

function updateAutoRefresh(value) {
  emit('update:autoRefresh', value)
}

watch(() => props.lastFetched, startRelativeTimer, {immediate: true})

onBeforeUnmount(stopRelativeTimer)
</script>

<template>
  <div class="panel-header">
    <div class="panel-header__identity">
      <span v-if="icon && !hasTitleAction" class="panel-header__icon" aria-hidden="true">
        <i :class="['bi', icon]"></i>
      </span>
      <div class="panel-header__info">
        <h2 class="panel-header__title">
          <button
            v-if="hasTitleAction"
            type="button"
            class="panel-header__title-action"
            :disabled="loading"
            @click="emit('titleAction')"
          >
            <span v-if="icon" class="panel-header__icon" aria-hidden="true">
              <i :class="['bi', icon]"></i>
            </span>
            {{ title }}
          </button>
          <template v-else>{{ title }}</template>
        </h2>
        <div v-if="subtitle || $slots['subtitle-actions']" class="panel-header__subtitle-row">
          <p v-if="subtitle" class="panel-header__subtitle">{{ subtitle }}</p>
          <slot name="subtitle-actions"></slot>
        </div>
      </div>
    </div>
    <div class="panel-header__actions">
      <span v-if="lastFetchedDisplayText" class="last-fetched-text">{{ lastFetchedDisplayText }}</span>
      <AutoRefreshToggle
        v-if="hasAutoRefresh"
        :model-value="autoRefresh"
        :title="autoRefreshTitle"
        :connection-state="autoRefreshState"
        @update:model-value="updateAutoRefresh"
        @retry="emit('retryAutoRefresh')"
      />
      <button
        v-if="hasRefresh"
        :aria-busy="loading || undefined"
        :aria-label="loading ? 'Refreshing panel' : 'Refresh panel'"
        :disabled="loading"
        class="btn btn-outline-secondary btn-sm"
        title="Refresh"
        @click="emit('refresh')"
      >
        <i :class="['bi bi-arrow-clockwise', {spin: loading}]"></i>
      </button>
      <slot name="actions"></slot>
    </div>
  </div>
  <div
    v-if="loadError"
    :class="['alert', loadError.serverUnreachable ? 'alert-warning' : 'alert-danger']"
    class="d-flex align-items-start gap-2 mb-3"
    role="alert"
  >
    <i class="bi bi-exclamation-triangle-fill flex-shrink-0"></i>
    <span class="flex-grow-1">
      <strong class="d-block">{{ loadError.title }}</strong>
      <span class="small">{{ loadError.message }}</span>
    </span>
    <button v-if="hasRefresh" :class="retryButtonClass" class="btn btn-sm flex-shrink-0" @click="emit('refresh')">
      <i class="bi bi-arrow-clockwise me-1"></i>Retry
    </button>
  </div>
</template>

<style scoped>
.panel-header {
  align-items: center;
  border-bottom: 1px solid var(--bootui-border-subtle);
  display: flex;
  flex-wrap: wrap;
  gap: 1rem 1.5rem;
  justify-content: space-between;
  margin-bottom: 1.25rem;
  padding: 1.4rem 0 1.15rem;
}

.panel-header__identity {
  align-items: center;
  display: flex;
  flex: 1 1 28rem;
  gap: 0.9rem;
  min-width: 0;
}

.panel-header__icon {
  align-items: center;
  background: color-mix(in srgb, var(--bootui-green) 10%, var(--bootui-surface-solid));
  border: 1px solid color-mix(in srgb, var(--bootui-green) 18%, transparent);
  border-radius: var(--bootui-radius-md);
  color: var(--bootui-green-dark);
  display: inline-flex;
  flex: 0 0 auto;
  font-size: 1.15rem;
  height: 2.75rem;
  justify-content: center;
  width: 2.75rem;
}

.panel-header__info {
  min-width: 0;
}

.panel-header__title {
  font-size: 1.15rem;
  font-weight: 700;
  letter-spacing: -0.015em;
  line-height: 1.2;
  margin: 0;
  overflow-wrap: anywhere;
}

.panel-header__title-action {
  align-items: center;
  background: none;
  border: 0;
  border-radius: var(--bootui-radius-md);
  color: inherit;
  display: inline-flex;
  font: inherit;
  gap: 0.9rem;
  letter-spacing: inherit;
  min-height: 44px;
  padding: 0;
  text-align: left;
  text-shadow: inherit;
}

.panel-header__title-action:hover:not(:disabled) {
  text-decoration: underline;
  text-underline-offset: 0.2em;
}

.panel-header__title-action:focus-visible {
  outline: 2px solid var(--bootui-blue);
  outline-offset: 3px;
}

.panel-header__subtitle-row {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 0.45rem 1rem;
  margin-top: 0.3rem;
}

.panel-header__subtitle {
  color: var(--bootui-text-muted);
  font-size: 0.875rem;
  line-height: 1.45;
  margin: 0;
  max-width: 72ch;
}

.panel-header__actions {
  align-items: center;
  display: flex;
  flex-shrink: 0;
  flex-wrap: wrap;
  gap: 0.5rem;
}

/* Below the drawer breakpoint the action cluster can exceed the viewport, so it
   stops being an unshrinkable row and wraps under the title instead of pushing a
   horizontal scrollbar onto the workspace. */
@media (max-width: 575.98px) {
  .panel-header {
    align-items: stretch;
    padding-top: 1.1rem;
  }

  .panel-header__identity {
    align-items: flex-start;
    flex-basis: 100%;
  }

  .panel-header__icon {
    height: 2.5rem;
    width: 2.5rem;
  }

  .panel-header__actions {
    flex-shrink: 1;
    min-width: 0;
    width: 100%;
  }
}

.last-fetched-text {
  color: var(--bootui-text-subtle, #5b6b80);
  font-size: 0.72rem;
  white-space: nowrap;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.spin {
  animation: spin 900ms linear infinite;
  display: inline-block;
}

@media (prefers-reduced-motion: reduce) {
  .spin {
    animation: none;
  }
}
</style>
