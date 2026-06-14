import {describe, expect, it} from 'vitest'
import {filterEntries, groupEntries} from './activityStream.js'

const entries = [
  {id: 'r2', type: 'REQUEST', severity: 'ERROR', summary: 'GET /b → 500', timestamp: 3000},
  {id: 'e1', type: 'EXCEPTION', severity: 'ERROR', summary: 'Boom', timestamp: 2500},
  {id: 's1', type: 'SQL', severity: 'OK', summary: 'SELECT 1', timestamp: 2000},
  {id: 'r1', type: 'REQUEST', severity: 'OK', summary: 'GET /a → 200', timestamp: 1000}
]

describe('filterEntries', () => {
  it('returns everything when no filters are set', () => {
    expect(filterEntries(entries)).toHaveLength(4)
  })

  it('filters by type case-insensitively', () => {
    expect(filterEntries(entries, {type: 'request'}).map((e) => e.id)).toEqual(['r2', 'r1'])
  })

  it('filters by severity', () => {
    expect(filterEntries(entries, {severity: 'ERROR'}).map((e) => e.id)).toEqual(['r2', 'e1'])
  })

  it('combines type and severity filters', () => {
    expect(filterEntries(entries, {type: 'REQUEST', severity: 'ERROR'}).map((e) => e.id)).toEqual(['r2'])
  })
})

describe('groupEntries', () => {
  it('collapses adjacent identical entries with a count', () => {
    const repeated = [
      {id: 'a', type: 'SQL', severity: 'OK', summary: 'SELECT 1'},
      {id: 'b', type: 'SQL', severity: 'OK', summary: 'SELECT 1'},
      {id: 'c', type: 'SQL', severity: 'OK', summary: 'SELECT 1'},
      {id: 'd', type: 'REQUEST', severity: 'OK', summary: 'GET /a'}
    ]
    const grouped = groupEntries(repeated)
    expect(grouped).toHaveLength(2)
    expect(grouped[0]).toMatchObject({id: 'a', count: 3})
    expect(grouped[1]).toMatchObject({id: 'd', count: 1})
  })

  it('does not merge non-adjacent identical entries', () => {
    const list = [
      {id: 'a', type: 'SQL', severity: 'OK', summary: 'SELECT 1'},
      {id: 'b', type: 'REQUEST', severity: 'OK', summary: 'GET /a'},
      {id: 'c', type: 'SQL', severity: 'OK', summary: 'SELECT 1'}
    ]
    expect(groupEntries(list)).toHaveLength(3)
  })
})
