import {computed, toValue} from 'vue'
import {panelDisabledReason} from './panelNavigation.js'

export function panelActionReason(panel) {
  if (!panel) return 'Action permissions are unavailable. Wait for panel availability or retry loading it.'
  if (panel.enabled === false) return panelDisabledReason(panel)
  if (panel.available === false) return panel.unavailableReason || 'This panel is not available.'
  if (panel.readOnly === true) return panel.readOnlyReason || 'This panel is read-only.'
  if (panel.readOnly !== false) return 'Action permissions are unavailable for this panel.'
  return null
}

export const panelProps = {
  panel: {
    type: Object,
    default: null
  }
}

export function usePanelState(props) {
  const readOnly = computed(() => props.panel?.readOnly === true)
  const readOnlyReason = computed(() => props.panel?.readOnlyReason || 'This panel is read-only.')

  // Whether the panel manifest (`/bootui/api/panels`, already fetched before this view ever mounts)
  // already knows this panel can't be used right now — either explicitly disabled via
  // `bootui.panels.<id>.enabled=false`, or structurally unavailable for this stack/configuration
  // (`available:false`, e.g. a panel not yet ported to the active adapter). Views whose backing
  // endpoint may not even be wired in that case (so a fetch would 404 rather than answer a graceful
  // `{available:false}` body) should gate their data fetch/subscription on this instead of discovering
  // the same fact the hard way via a failed request. Defaults to available when no panel info is
  // present (e.g. component tests that render the view standalone).
  const manifestDisabled = computed(() => props.panel?.enabled === false)
  const manifestAvailable = computed(() => !manifestDisabled.value && props.panel?.available !== false)
  const manifestUnavailableReason = computed(() => {
    if (manifestDisabled.value) {
      return `Panel is disabled via bootui.panels.${props.panel?.id || 'panel'}.enabled=false`
    }
    return props.panel?.unavailableReason || 'This panel is not available.'
  })

  return {readOnly, readOnlyReason, manifestAvailable, manifestUnavailableReason}
}

/**
 * Derives the mutually exclusive presentation states shared by data-heavy panels.
 *
 * @param {Object} state
 * @param {import('vue').MaybeRefOrGetter<boolean>} state.loading
 * @param {import('vue').MaybeRefOrGetter<boolean>} state.loaded
 * @param {import('vue').MaybeRefOrGetter<unknown>} state.error
 * @param {import('vue').MaybeRefOrGetter<boolean>} state.hasData
 * @param {import('vue').MaybeRefOrGetter<boolean>} [state.available]
 * @param {import('vue').MaybeRefOrGetter<number>} [state.total]
 * @param {import('vue').MaybeRefOrGetter<number>} [state.matched]
 * @param {import('vue').MaybeRefOrGetter<boolean>} [state.filterActive]
 * @param {import('vue').MaybeRefOrGetter<boolean>} [state.partial]
 */
export function useDataState({
  loading,
  loaded,
  error,
  hasData,
  available = true,
  total = 0,
  matched = total,
  filterActive = false,
  partial = false
}) {
  const initialLoading = computed(() => Boolean(toValue(loading)) && !toValue(loaded))
  const hasSuccessfulData = computed(() => Boolean(toValue(hasData)))
  const retryableError = computed(() => Boolean(toValue(error)) && !hasSuccessfulData.value)
  const stale = computed(() => Boolean(toValue(error)) && hasSuccessfulData.value)
  const unavailable = computed(
    () => toValue(loaded) && !toValue(loading) && !toValue(error) && toValue(available) === false
  )
  const empty = computed(
    () =>
      toValue(loaded) &&
      !toValue(loading) &&
      !toValue(error) &&
      toValue(available) !== false &&
      Number(toValue(total)) === 0
  )
  const filteredEmpty = computed(
    () =>
      toValue(loaded) &&
      !toValue(loading) &&
      !toValue(error) &&
      toValue(available) !== false &&
      Boolean(toValue(filterActive)) &&
      Number(toValue(total)) > 0 &&
      Number(toValue(matched)) === 0
  )
  const partialSuccess = computed(() => hasSuccessfulData.value && Boolean(toValue(partial)))

  return {
    empty,
    filteredEmpty,
    hasSuccessfulData,
    initialLoading,
    partialSuccess,
    retryableError,
    stale,
    unavailable
  }
}
