export const THEME_QUERY = '(prefers-color-scheme: dark)'
export const THEME_STORAGE_KEY = 'bootui.theme'

export function normalizeThemePreference(value) {
  return value === 'dark' || value === 'light' ? value : null
}

export function readThemePreference(storage) {
  try {
    return normalizeThemePreference(storage?.getItem(THEME_STORAGE_KEY))
  } catch {
    return null
  }
}

export function resolveTheme(preference, prefersDark) {
  return normalizeThemePreference(preference) ?? (prefersDark ? 'dark' : 'light')
}

export function nextTheme(currentTheme) {
  return currentTheme === 'dark' ? 'light' : 'dark'
}

export function applyTheme(root, theme) {
  const resolvedTheme = resolveTheme(theme, false)
  root.dataset.bootuiTheme = resolvedTheme
  root.dataset.bsTheme = resolvedTheme
  root.style.colorScheme = resolvedTheme
}
