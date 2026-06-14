import {describe, expect, it} from 'vitest'
import {bucketEntries, deepLink, filterEntries, groupEntries} from './activityStream.js'

const entries = [
  {id: 'r2', type: 'REQUEST', severity: 'ERROR', summary: 'GET /b → 500', path: '/b', method: 'GET', timestamp: 3000},
  {id: 'e1', type: 'EXCEPTION', severity: 'ERROR', summary: 'Boom', timestamp: 2500},
  {id: 's1', type: 'SQL', severity: 'OK', summary: 'SELECT 1', timestamp: 2000},
  {id: 'r1', type: 'REQUEST', severity: 'OK', summary: 'GET /a → 200', path: '/a', method: 'GET', timestamp: 1000}
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

  it('filters by free text across summary and path case-insensitively', () => {
    expect(filterEntries(entries, {text: '/A'}).map((e) => e.id)).toEqual(['r1'])
    expect(filterEntries(entries, {text: 'boom'}).map((e) => e.id)).toEqual(['e1'])
  })

  it('keeps only error-severity entries when errorsOnly is set', () => {
    expect(filterEntries(entries, {errorsOnly: true}).map((e) => e.id)).toEqual(['r2', 'e1'])
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

describe('bucketEntries', () => {
  it('returns an empty array when there are no entries', () => {
    expect(bucketEntries([], 5)).toEqual([])
  })

  it('distributes entries into equal-width buckets oldest first', () => {
    const sample = [
      {timestamp: 0, severity: 'OK'},
      {timestamp: 10, severity: 'OK'},
      {timestamp: 95, severity: 'ERROR'},
      {timestamp: 100, severity: 'ERROR'}
    ]
    const buckets = bucketEntries(sample, 10)
    expect(buckets).toHaveLength(10)
    // width = 100/10 = 10: ts 0 -> bucket 0, ts 10 -> bucket 1, ts 95/100 -> bucket 9
    expect(buckets[0].count).toBe(1)
    expect(buckets[1].count).toBe(1)
    expect(buckets[9].count).toBe(2)
    expect(buckets[9].errors).toBe(2)
  })

  it('puts all entries in the last bucket when timestamps are identical', () => {
    const buckets = bucketEntries([{timestamp: 5}, {timestamp: 5}], 4)
    expect(buckets).toHaveLength(4)
    expect(buckets[3].count).toBe(2)
  })
})

describe('deepLink', () => {
  it('links a request entry to HTTP Exchanges filtered by path', () => {
    expect(deepLink({type: 'REQUEST', path: '/users', summary: 'GET /users → 200'})).toEqual({
      path: '/http-exchanges',
      query: {q: '/users'},
      label: 'Open in HTTP Exchanges'
    })
  })

  it('links a SQL entry to SQL Trace and strips the truncation ellipsis', () => {
    expect(deepLink({type: 'SQL', summary: 'select * from orders …'})).toEqual({
      path: '/sql-trace',
      query: {q: 'select * from orders'},
      label: 'Open in SQL Trace'
    })
  })

  it('links an exception entry to Exceptions using the class name', () => {
    expect(deepLink({type: 'EXCEPTION', summary: 'java.lang.IllegalStateException: boom'})).toEqual({
      path: '/exceptions',
      query: {q: 'java.lang.IllegalStateException'},
      label: 'Open in Exceptions'
    })
  })

  it('returns null for security entries and unknown types', () => {
    expect(deepLink({type: 'SECURITY', summary: 'AUTHENTICATION_FAILURE'})).toBeNull()
    expect(deepLink(null)).toBeNull()
  })
})
