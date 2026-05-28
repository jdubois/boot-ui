import {nextTick, ref} from 'vue'
import {describe, expect, it} from 'vitest'

import {useVisibleItems} from './useVisibleItems.js'

describe('useVisibleItems', () => {
  it('limits the initially visible items and reports hidden counts', () => {
    const source = ref(['beans', 'loggers', 'mappings', 'config'])
    const list = useVisibleItems(source, {initialLimit: 2, chunkSize: 2})

    expect(list.visibleItems.value).toEqual(['beans', 'loggers'])
    expect(list.totalCount.value).toBe(4)
    expect(list.shownCount.value).toBe(2)
    expect(list.hiddenCount.value).toBe(2)
    expect(list.hasHiddenItems.value).toBe(true)
  })

  it('reveals more items without exceeding the available total', () => {
    const source = ref(['a', 'b', 'c', 'd', 'e'])
    const list = useVisibleItems(source, {initialLimit: 2, chunkSize: 2})

    list.showMore()

    expect(list.visibleItems.value).toEqual(['a', 'b', 'c', 'd'])
    expect(list.hiddenCount.value).toBe(1)

    list.showMore()

    expect(list.visibleItems.value).toEqual(['a', 'b', 'c', 'd', 'e'])
    expect(list.hiddenCount.value).toBe(0)
    expect(list.hasHiddenItems.value).toBe(false)
  })

  it('resets the visible limit when the filtered source changes', async () => {
    const source = ref(['one', 'two', 'three', 'four'])
    const list = useVisibleItems(source, {initialLimit: 2, chunkSize: 2})

    list.showAll()
    expect(list.shownCount.value).toBe(4)

    source.value = ['alpha', 'beta', 'gamma']
    await nextTick()

    expect(list.visibleItems.value).toEqual(['alpha', 'beta'])
    expect(list.hiddenCount.value).toBe(1)
  })
})
