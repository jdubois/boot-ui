import {describe, expect, it} from 'vitest'

import {
  applyTheme,
  nextTheme,
  normalizeThemePreference,
  readThemePreference,
  resolveTheme,
  THEME_STORAGE_KEY
} from './theme.js'

describe('theme utilities', () => {
  it('normalizes persisted theme values', () => {
    expect(normalizeThemePreference('dark')).toBe('dark')
    expect(normalizeThemePreference('light')).toBe('light')
    expect(normalizeThemePreference('system')).toBeNull()
    expect(normalizeThemePreference(null)).toBeNull()
  })

  it('reads only supported theme preferences from storage', () => {
    const storage = new Map([[THEME_STORAGE_KEY, 'dark']])

    expect(readThemePreference({getItem: (key) => storage.get(key)})).toBe('dark')
    expect(readThemePreference({getItem: () => 'unexpected'})).toBeNull()
    expect(
      readThemePreference({
        getItem: () => {
          throw new Error('storage unavailable')
        }
      })
    ).toBeNull()
  })

  it('resolves explicit preferences before the system preference', () => {
    expect(resolveTheme(null, true)).toBe('dark')
    expect(resolveTheme(null, false)).toBe('light')
    expect(resolveTheme('light', true)).toBe('light')
    expect(resolveTheme('dark', false)).toBe('dark')
  })

  it('toggles between light and dark mode', () => {
    expect(nextTheme('light')).toBe('dark')
    expect(nextTheme('dark')).toBe('light')
  })

  it('applies the resolved theme to the document root', () => {
    const root = document.createElement('html')

    applyTheme(root, 'dark')
    expect(root.dataset.bootuiTheme).toBe('dark')
    expect(root.dataset.bsTheme).toBe('dark')
    expect(root.style.colorScheme).toBe('dark')
  })
})
