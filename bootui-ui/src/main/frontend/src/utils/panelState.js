import {computed} from 'vue'

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
