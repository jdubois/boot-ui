import {flushPromises, mount} from '@vue/test-utils'
import {ref} from 'vue'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import LowFootprintBanner from './LowFootprintBanner.vue'
import {resetLowFootprintForTests} from '../../utils/useLowFootprint.js'

function respond(body) {
  return Promise.resolve(
    new Response(JSON.stringify(body), {status: 200, headers: {'content-type': 'application/json'}})
  )
}

describe('LowFootprintBanner', () => {
  beforeEach(() => resetLowFootprintForTests())
  afterEach(() => vi.unstubAllGlobals())

  it('renders when the mode is active', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => respond({active: true, components: [], warnings: []}))
    )
    const wrapper = mount(LowFootprintBanner, {
      global: {provide: {panels: ref({panels: [{id: 'live-memory', enabled: true, available: true}]})}}
    })
    await flushPromises()
    expect(wrapper.get('[data-testid="low-footprint-banner"]').text()).toContain('Paused by low-footprint mode')
  })

  it('renders nothing when the mode is off', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(() => respond({active: false, components: [], warnings: []}))
    )
    const wrapper = mount(LowFootprintBanner, {
      global: {provide: {panels: ref({panels: [{id: 'live-memory', enabled: true, available: true}]})}}
    })
    await flushPromises()
    expect(wrapper.find('[data-testid="low-footprint-banner"]').exists()).toBe(false)
  })
})
