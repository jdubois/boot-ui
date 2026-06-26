import {afterEach, describe, expect, it} from 'vitest'

import {confirmState, settleConfirm, useConfirm} from './useConfirm.js'

const {confirm} = useConfirm()

afterEach(() => {
  // Defensively settle anything a failed expectation left open (shared singleton).
  settleConfirm(false)
})

describe('useConfirm', () => {
  it('opens the dialog and merges options over the defaults', () => {
    confirm({title: 'Restart container?', resource: 'redis-1', danger: true})
    expect(confirmState.open).toBe(true)
    expect(confirmState.options.title).toBe('Restart container?')
    expect(confirmState.options.resource).toBe('redis-1')
    expect(confirmState.options.danger).toBe(true)
    // Untouched defaults survive the merge.
    expect(confirmState.options.cancelLabel).toBe('Cancel')
    expect(confirmState.options.irreversible).toBe(false)
  })

  it('resolves true and closes when confirmed', async () => {
    const result = confirm({title: 'Go?'})
    settleConfirm(true)
    await expect(result).resolves.toBe(true)
    expect(confirmState.open).toBe(false)
  })

  it('resolves false when dismissed', async () => {
    const result = confirm({title: 'Go?'})
    settleConfirm(false)
    await expect(result).resolves.toBe(false)
  })

  it('coerces a non-boolean settle value to a boolean', async () => {
    const result = confirm()
    settleConfirm('truthy')
    await expect(result).resolves.toBe(true)
  })

  it('settles a superseded prompt as false when a new one opens', async () => {
    const first = confirm({title: 'First'})
    const second = confirm({title: 'Second'})
    await expect(first).resolves.toBe(false)
    expect(confirmState.open).toBe(true)
    expect(confirmState.options.title).toBe('Second')
    settleConfirm(true)
    await expect(second).resolves.toBe(true)
  })

  it('accepts a bare string as the message shorthand', () => {
    confirm('Clear the buffer?')
    expect(confirmState.options.message).toBe('Clear the buffer?')
    expect(confirmState.options.title).toBe('Are you sure?')
  })

  it('ignores a settle when no prompt is in flight', () => {
    expect(() => settleConfirm(true)).not.toThrow()
    expect(confirmState.open).toBe(false)
  })
})
