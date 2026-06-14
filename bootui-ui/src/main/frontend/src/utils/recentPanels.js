const STORAGE_KEY = 'bootui.recentPanels'
const MAX_RECENT_PANELS = 5

function storage() {
  try {
    return typeof window !== 'undefined' ? window.localStorage : null
  } catch {
    return null
  }
}

export function loadRecentPanels() {
  try {
    const raw = storage()?.getItem(STORAGE_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed.filter((name) => typeof name === 'string') : []
  } catch {
    return []
  }
}

export function recordRecentPanel(name) {
  if (!name) return loadRecentPanels()
  const next = [name, ...loadRecentPanels().filter((entry) => entry !== name)].slice(0, MAX_RECENT_PANELS)
  try {
    storage()?.setItem(STORAGE_KEY, JSON.stringify(next))
  } catch {
    // Ignore unavailable storage (e.g. private mode / quota); recents are best-effort.
  }
  return next
}
