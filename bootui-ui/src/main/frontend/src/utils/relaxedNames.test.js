import {describe, expect, it} from 'vitest'
import {canonicalizeName} from './relaxedNames.js'

describe('canonicalizeName', () => {
  it('maps the dotted, kebab-case and UPPER_SNAKE_CASE spellings of one property onto the same form', () => {
    expect(canonicalizeName('BOOTUI_MCP_ENABLED')).toBe('bootui.mcp.enabled')
    expect(canonicalizeName('bootui.mcp.enabled')).toBe('bootui.mcp.enabled')
    expect(canonicalizeName('spring.datasource.hikari.maximum-pool-size')).toBe(
      canonicalizeName('SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE')
    )
  })

  it('preserves length, so a literal prefix stays a prefix once canonicalized', () => {
    const name = 'bootui.mcp.max-results'
    expect(canonicalizeName(name)).toHaveLength(name.length)
    expect(canonicalizeName(name).startsWith(canonicalizeName('BOOTUI_MCP'))).toBe(true)
  })

  it('returns an empty string for a missing name rather than throwing', () => {
    expect(canonicalizeName(null)).toBe('')
    expect(canonicalizeName(undefined)).toBe('')
  })
})
