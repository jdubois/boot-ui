import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it} from 'vitest'

import ConfirmDialog from './ConfirmDialog.vue'
import {confirmState, settleConfirm, useConfirm} from '../../utils/useConfirm.js'

const {confirm} = useConfirm()

function mountDialog() {
  return mount(ConfirmDialog, {attachTo: document.body})
}

afterEach(() => settleConfirm(false))

describe('ConfirmDialog', () => {
  it('renders the prompt options when opened', async () => {
    const wrapper = mountDialog()
    confirm({
      title: 'Restart container?',
      message: 'In-flight connections drop while it restarts.',
      resource: 'redis-1',
      confirmLabel: 'Restart',
      danger: true,
      irreversible: true
    })
    await flushPromises()

    expect(wrapper.get('.confirm-title').text()).toBe('Restart container?')
    expect(wrapper.text()).toContain('In-flight connections drop while it restarts.')
    expect(wrapper.get('.confirm-resource code').text()).toBe('redis-1')
    expect(wrapper.text()).toContain('cannot be undone')
    expect(wrapper.find('.confirm-dialog--danger').exists()).toBe(true)
    expect(wrapper.get('.confirm-actions .btn-danger').text()).toBe('Restart')
    // jsdom lacks showModal(); the fallback `open` attribute drives visibility.
    expect(wrapper.get('dialog').attributes('open')).toBeDefined()
    wrapper.unmount()
  })

  it('uses the primary (non-danger) button for safe prompts', async () => {
    const wrapper = mountDialog()
    confirm({title: 'Apply?'})
    await flushPromises()

    expect(wrapper.find('.btn-danger').exists()).toBe(false)
    expect(wrapper.get('.confirm-actions .btn-primary').exists()).toBe(true)
    wrapper.unmount()
  })

  it('resolves true when the confirm button is clicked', async () => {
    const wrapper = mountDialog()
    const result = confirm({title: 'Go?'})
    await flushPromises()

    await wrapper.get('.confirm-actions .btn-primary').trigger('click')
    await expect(result).resolves.toBe(true)
    expect(confirmState.open).toBe(false)
    wrapper.unmount()
  })

  it('resolves false when the cancel button is clicked', async () => {
    const wrapper = mountDialog()
    const result = confirm({title: 'Go?'})
    await flushPromises()

    await wrapper.get('.btn-outline-secondary').trigger('click')
    await expect(result).resolves.toBe(false)
    wrapper.unmount()
  })

  it('resolves false on Escape', async () => {
    const wrapper = mountDialog()
    const result = confirm({title: 'Go?'})
    await flushPromises()

    await wrapper.get('dialog').trigger('keydown', {key: 'Escape'})
    await expect(result).resolves.toBe(false)
    wrapper.unmount()
  })

  it('resolves false on a backdrop click', async () => {
    const wrapper = mountDialog()
    const result = confirm({title: 'Go?'})
    await flushPromises()

    // A click whose target is the <dialog> itself is a backdrop click.
    await wrapper.get('dialog').trigger('click')
    await expect(result).resolves.toBe(false)
    wrapper.unmount()
  })

  it('defaults focus to Cancel for destructive prompts', async () => {
    const wrapper = mountDialog()
    confirm({title: 'Delete?', danger: true})
    await flushPromises()

    expect(document.activeElement).toBe(wrapper.get('.btn-outline-secondary').element)
    wrapper.unmount()
  })
})
