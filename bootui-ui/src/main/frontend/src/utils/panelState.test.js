import {ref} from 'vue'
import {describe, expect, it} from 'vitest'

import {panelActionReason, useDataState, usePanelState} from './panelState.js'

function dataState(overrides = {}) {
  const values = {
    loading: ref(false),
    loaded: ref(true),
    error: ref(null),
    hasData: ref(true),
    available: ref(true),
    total: ref(3),
    matched: ref(3),
    filterActive: ref(false),
    partial: ref(false),
    ...overrides
  }
  return {values, panel: useDataState(values)}
}

describe('panelState', () => {
  it.each([null, {}, {id: 'memory'}, {readOnly: 'false'}])('fails closed for unresolved action policy %j', (panel) => {
    expect(panelActionReason(panel)).toContain('Action permissions are unavailable')
  })

  it('distinguishes known writable, disabled, unavailable and read-only action targets', () => {
    expect(panelActionReason({id: 'memory', readOnly: false})).toBeNull()
    expect(panelActionReason({id: 'memory', enabled: false, readOnly: false})).toContain('enabled=false')
    expect(panelActionReason({available: false, unavailableReason: 'No support'})).toBe('No support')
    expect(panelActionReason({readOnly: true, readOnlyReason: 'Global policy'})).toBe('Global policy')
    expect(panelActionReason({readOnly: true})).toBe('This panel is read-only.')
  })

  it('derives manifest availability and read-only reasons', () => {
    const state = usePanelState({
      panel: {
        id: 'mappings',
        available: false,
        unavailableReason: 'Mappings are not available.',
        readOnly: true,
        readOnlyReason: 'Changes are disabled.'
      }
    })

    expect(state.manifestAvailable.value).toBe(false)
    expect(state.manifestUnavailableReason.value).toBe('Mappings are not available.')
    expect(state.readOnly.value).toBe(true)
    expect(state.readOnlyReason.value).toBe('Changes are disabled.')
  })

  it('distinguishes initial loading from a refresh with successful data', () => {
    const first = dataState({loading: ref(true), loaded: ref(false), hasData: ref(false)})
    expect(first.panel.initialLoading.value).toBe(true)

    const refresh = dataState({loading: ref(true)})
    expect(refresh.panel.initialLoading.value).toBe(false)
    expect(refresh.panel.hasSuccessfulData.value).toBe(true)
  })

  it('distinguishes retryable initial errors from stale refresh failures', () => {
    const error = ref(new Error('offline'))
    const first = dataState({error, hasData: ref(false)})
    const refresh = dataState({error})

    expect(first.panel.retryableError.value).toBe(true)
    expect(first.panel.stale.value).toBe(false)
    expect(refresh.panel.retryableError.value).toBe(false)
    expect(refresh.panel.stale.value).toBe(true)
  })

  it('distinguishes unavailable, true empty, and filtered empty results', () => {
    expect(dataState({available: ref(false), hasData: ref(false)}).panel.unavailable.value).toBe(true)
    expect(dataState({total: ref(0), matched: ref(0)}).panel.empty.value).toBe(true)

    const filtered = dataState({total: ref(7), matched: ref(0), filterActive: ref(true)})
    expect(filtered.panel.filteredEmpty.value).toBe(true)
    expect(filtered.panel.empty.value).toBe(false)
  })

  it('surfaces partial success without replacing successful content', () => {
    const {panel} = dataState({partial: ref(true)})

    expect(panel.partialSuccess.value).toBe(true)
    expect(panel.hasSuccessfulData.value).toBe(true)
  })
})
