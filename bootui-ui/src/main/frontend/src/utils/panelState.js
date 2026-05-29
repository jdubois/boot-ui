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

  return {readOnly, readOnlyReason}
}
