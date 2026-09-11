import {describe, expect, it} from 'vitest'
import {explorerKeyAction, navigateExplorer} from './explorerNavigation.js'

const nodes = [
  {id: 'root', stage: 0, z: 0, rowIds: ['event:root']},
  {id: 'controller', stage: 1, z: 0, rowIds: ['call:1', 'call:2']},
  {id: 'service-b', stage: 2, z: 3, rowIds: ['call:b']},
  {id: 'service-a', stage: 2, z: -3, rowIds: ['call:a']},
  {id: 'group', stage: 6, z: 8, kind: 'GROUP', rowIds: ['hidden:1']}
]
describe('scene-local keyboard navigation', () => {
  it('browses stages and branches spatially, with stable ties across refresh/reorder', () => {
    expect(navigateExplorer(nodes, 'event:root', 'ArrowRight').id).toBe('controller')
    expect(navigateExplorer(nodes, 'call:2', 'ArrowRight').id).toBe('service-a')
    expect(navigateExplorer([...nodes].reverse(), 'call:2', 'ArrowRight').id).toBe('service-a')
    expect(navigateExplorer(nodes, 'call:a', 'ArrowDown').id).toBe('service-b')
    expect(navigateExplorer(nodes, 'call:b', 'ArrowUp').id).toBe('service-a')
    expect(navigateExplorer(nodes, 'call:b', 'ArrowRight').id).toBe('group')
    expect(navigateExplorer(nodes, 'hidden:1', 'ArrowLeft').id).toBe('service-b')
    expect(navigateExplorer(nodes, 'event:root', 'ArrowLeft').id).toBe('root')
  })
  it('starts sensibly after retention evicts selection, and tolerates independent, empty and singleton scenes', () => {
    expect(navigateExplorer(nodes, 'evicted', 'ArrowDown').id).toBe('root')
    expect(navigateExplorer([], null, 'ArrowRight')).toBeNull()
    expect(navigateExplorer([nodes[4]], null, 'ArrowDown')).toBe(nodes[4])
    expect(navigateExplorer([nodes[4]], 'group', 'ArrowUp')).toBe(nodes[4])
    expect(navigateExplorer(nodes, null, 'Tab')).toBeNull()
  })
  it('browses a wrapped signal row left to right before moving to the next row', () => {
    const signals = [
      {id: 'z', stage: 7, x: -6, z: 10, rowIds: ['left']},
      {id: 'a', stage: 7, x: 6, z: 10, rowIds: ['right']},
      {id: 'b', stage: 7, x: 0, z: 18, rowIds: ['next']}
    ]
    expect(navigateExplorer(signals, 'left', 'ArrowDown').id).toBe('a')
    expect(navigateExplorer(signals, 'right', 'ArrowDown').id).toBe('b')
    expect(navigateExplorer(signals, 'next', 'ArrowUp').id).toBe('a')
  })
  it('keeps camera shortcuts explicit and never captures OS/browser navigation modifiers or Tab/Escape', () => {
    expect(explorerKeyAction({key: 'ArrowRight'}).type).toBe('browse')
    expect(explorerKeyAction({key: 'ArrowRight', shiftKey: true}).type).toBe('orbit')
    expect(explorerKeyAction({key: '+'}).factor).toBeLessThan(1)
    expect(explorerKeyAction({key: '-'}).factor).toBeGreaterThan(1)
    expect(explorerKeyAction({key: 'Home'}).type).toBe('reset')
    expect(explorerKeyAction({key: 'Enter'}).type).toBe('activate')
    for (const key of ['Tab', 'Escape', 'a']) expect(explorerKeyAction({key})).toBeNull()
    for (const modifier of ['ctrlKey', 'altKey', 'metaKey'])
      expect(explorerKeyAction({key: 'ArrowLeft', [modifier]: true})).toBeNull()
  })
})
