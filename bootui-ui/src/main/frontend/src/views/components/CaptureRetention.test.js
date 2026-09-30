import {mount} from '@vue/test-utils'
import {describe, expect, it} from 'vitest'

import CaptureRetention from './CaptureRetention.vue'

function owned(overrides = {}) {
  return {
    applicationManaged: false,
    capacity: 200,
    reservedCapacity: 50,
    retained: 187,
    reserved: 12,
    evicted: 1204,
    slowThresholdMillis: 1000,
    ...overrides
  }
}

describe('CaptureRetention', () => {
  it('states the kept, reserved and evicted counts of a BootUI-owned buffer', () => {
    const wrapper = mount(CaptureRetention, {
      props: {retention: owned(), noun: 'exchanges', reservedFor: '5xx or slow'}
    })

    const text = wrapper.text()
    expect(text).toContain('Retention:')
    expect(text).toContain(`keeping 187 of 200 exchanges`)
    expect(text).toContain(
      `12 of 50 reserved for recent 5xx or slow exchanges (slow means ≥ ${(1000).toLocaleString()} ms)`
    )
    expect(text).toContain(`${(1204).toLocaleString()} evicted since startup`)
    expect(wrapper.find('i.bi-archive').attributes('aria-hidden')).toBe('true')
  })

  it('says when slow classification is disabled', () => {
    const wrapper = mount(CaptureRetention, {
      props: {retention: owned({slowThresholdMillis: 0}), noun: 'executions', reservedFor: 'failed or slow'}
    })

    expect(wrapper.text()).toContain('reserved for recent failed or slow executions (slow classification is off)')
  })

  it('says when no share is reserved', () => {
    const wrapper = mount(CaptureRetention, {
      props: {retention: owned({reservedCapacity: 0, reserved: 0}), noun: 'calls', reservedFor: 'failed'}
    })

    expect(wrapper.text()).toContain('no reserved share, so the oldest are evicted first')
  })

  it('reports an application-managed recorder without inventing counts', () => {
    const wrapper = mount(CaptureRetention, {
      props: {
        retention: {
          applicationManaged: true,
          capacity: null,
          reservedCapacity: null,
          retained: 3,
          reserved: null,
          evicted: null,
          slowThresholdMillis: null
        },
        noun: 'exchanges',
        reservedFor: '5xx or slow'
      }
    })

    const text = wrapper.text()
    expect(text).toContain('Retention is managed by the application')
    expect(text).toContain('BootUI read 3 exchanges from its recorder and cannot reserve failures or count evictions')
    expect(text).not.toContain('evicted since startup')
  })

  it('renders nothing without retention', () => {
    const wrapper = mount(CaptureRetention, {props: {retention: null, noun: 'exchanges', reservedFor: '5xx'}})

    expect(wrapper.find('.capture-retention').exists()).toBe(false)
  })
})
