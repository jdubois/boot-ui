import {flushPromises, mount} from '@vue/test-utils'
import {afterEach, describe, expect, it, vi} from 'vitest'
import AdvisorDismissedFindings from './AdvisorDismissedFindings.vue'

const suppression = {
  suppressedFindings: 1,
  incompleteRules: [],
  omitted: 0,
  findings: [
    {ruleId: 'HIB-ID-006', identity: 'v1:unit=app|entity=A|member=id', state: 'MATCHED'},
    {ruleId: 'HIB-MAP-006', identity: 'v1:unit=app|entity=B|member=x', state: 'ORPHANED'},
    {ruleId: 'HIB-OLD-001', identity: 'v1:unit=app|entity=C|member=y', state: 'UNKNOWN_RULE'}
  ]
}

const wrappers = []
function render(props = {}) {
  const wrapper = mount(AdvisorDismissedFindings, {
    attachTo: document.body,
    props: {
      suppression,
      listFindings: vi.fn().mockResolvedValue({
        dismissed: [{ruleId: 'HIB-ID-006', identity: 'v1:unit=app|entity=A|member=id', note: 'Intentional'}],
        ignored: 0
      }),
      removeFinding: vi.fn().mockResolvedValue(true),
      saveNote: vi.fn().mockResolvedValue(true),
      ...props
    }
  })
  wrappers.push(wrapper)
  return wrapper
}

async function open(wrapper) {
  wrapper.get('details').element.open = true
  await wrapper.get('details').trigger('toggle')
  await flushPromises()
}

afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()))

describe('AdvisorDismissedFindings', () => {
  it('is absent without dismissed findings and collapsed, without any request, otherwise', () => {
    expect(
      render({suppression: {suppressedFindings: 0, findings: [], omitted: 0}})
        .find('details')
        .exists()
    ).toBe(false)
    const listFindings = vi.fn()
    const wrapper = render({listFindings})
    expect(wrapper.get('details').element.open).toBe(false)
    expect(wrapper.text()).toContain('Dismissed findings (3)')
    expect(listFindings).not.toHaveBeenCalled()
  })

  it('reads the notes once it is opened and shows each entry with a text state and an accessible note field', async () => {
    const listFindings = vi.fn().mockResolvedValue({
      dismissed: [{ruleId: 'HIB-ID-006', identity: 'v1:unit=app|entity=A|member=id', note: 'Intentional'}],
      ignored: 2
    })
    const wrapper = render({listFindings})
    await open(wrapper)

    expect(listFindings).toHaveBeenCalledOnce()
    const inputs = wrapper.findAll('input[type="text"]')
    expect(inputs).toHaveLength(3)
    expect(inputs[0].element.value).toBe('Intentional')
    for (const input of inputs) {
      expect(wrapper.find(`label[for="${input.attributes('id')}"]`).exists()).toBe(true)
    }
    const states = wrapper.findAll('[data-state]').map((badge) => badge.text())
    expect(states).toEqual([
      'Still reported by the last scan',
      'No longer reported by the last scan',
      'This advisor has no such rule'
    ])
    expect(wrapper.text()).toContain('2 stored entries could not be read')
  })

  it('offers Restore for a matched entry and Remove for an orphaned or unknown one', async () => {
    const wrapper = render()
    await open(wrapper)
    const labels = wrapper.findAll('li button[aria-label*="dismissed finding"]').map((b) => b.text())
    expect(labels).toEqual(['Restore', 'Remove', 'Remove'])
  })

  it('removes an entry through the panel and keeps the focus on the list heading', async () => {
    const removeFinding = vi.fn().mockResolvedValue(true)
    const wrapper = render({removeFinding})
    await open(wrapper)

    await wrapper.findAll('li button[aria-label*="dismissed finding"]')[1].trigger('click')
    await flushPromises()

    expect(removeFinding).toHaveBeenCalledWith('HIB-MAP-006', 'v1:unit=app|entity=B|member=x')
    expect(document.activeElement).toBe(wrapper.get('summary').element)
  })

  it('saves an edited note only, limited to 200 characters, and rereads the notes', async () => {
    const saveNote = vi.fn().mockResolvedValue(true)
    const listFindings = vi.fn().mockResolvedValue({dismissed: [], ignored: 0})
    const wrapper = render({saveNote, listFindings})
    await open(wrapper)
    const save = () => wrapper.findAll('button').find((b) => b.text() === 'Save note')

    expect(save().attributes('disabled')).toBeDefined()
    expect(wrapper.get('input').attributes('maxlength')).toBe('200')
    await wrapper.get('input').setValue('  Legacy table  ')
    expect(save().attributes('disabled')).toBeUndefined()
    await save().trigger('click')
    await flushPromises()

    expect(saveNote).toHaveBeenCalledWith('HIB-ID-006', 'v1:unit=app|entity=A|member=id', 'Legacy table')
    expect(listFindings).toHaveBeenCalledTimes(2)
  })

  it('shows a read failure without hiding the entries, and counts entries beyond the listed ones', async () => {
    const wrapper = render({
      listFindings: vi.fn().mockRejectedValue(new Error('Request failed')),
      suppression: {...suppression, omitted: 5}
    })
    await open(wrapper)

    expect(wrapper.get('[role="alert"]').text()).toContain('Unable to load the dismissed findings')
    expect(wrapper.findAll('li')).toHaveLength(3)
    expect(wrapper.text()).toContain('Dismissed findings (8)')
    expect(wrapper.text()).toContain('5 further dismissed findings are not listed here.')
  })

  it('disables every control while the panel is read-only or busy', async () => {
    const wrapper = render({disabled: true})
    await open(wrapper)
    expect(wrapper.findAll('li button, li input').every((el) => el.attributes('disabled') !== undefined)).toBe(true)
  })
})
