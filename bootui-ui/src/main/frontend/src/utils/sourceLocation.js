import {ref} from 'vue'
import {safeLocalStorage} from './safeStorage.js'

export const OPEN_IN_STORAGE_KEY = 'bootui.sourceLocations.openIn'

/**
 * The only editors a location can open in. Each preset is a fixed URL scheme handled by a locally installed
 * editor; there is deliberately no custom template and no web URL, so a local path is never sent to a network
 * address.
 */
export const OPEN_IN_PRESETS = Object.freeze([
  Object.freeze({id: 'none', label: 'None'}),
  Object.freeze({id: 'vscode', label: 'VS Code'}),
  Object.freeze({id: 'idea', label: 'IntelliJ IDEA'})
])

const PRESET_IDS = new Set(OPEN_IN_PRESETS.map((preset) => preset.id))
const WINDOWS_DRIVE = /^[A-Za-z]:[\\/]/
const CONTROL = /[\u0000-\u001f\u007f-\u009f]/
const MAX_PATH_LENGTH = 1024

const openIn = ref(null)

function storedPreference() {
  const stored = safeLocalStorage.getItem(OPEN_IN_STORAGE_KEY)
  if (stored === null) return 'none'
  if (PRESET_IDS.has(stored)) return stored
  safeLocalStorage.removeItem(OPEN_IN_STORAGE_KEY)
  return 'none'
}

/** The shared per-browser "Open in" preference; off (`none`) until the user picks an editor. */
export function useOpenInPreference() {
  if (openIn.value === null) openIn.value = storedPreference()
  function setOpenIn(value) {
    const next = PRESET_IDS.has(value) ? value : 'none'
    openIn.value = next
    if (next === 'none') safeLocalStorage.removeItem(OPEN_IN_STORAGE_KEY)
    else safeLocalStorage.setItem(OPEN_IN_STORAGE_KEY, next)
  }
  return {openIn, setOpenIn}
}

/** Test seam: forget the cached preference so the next use rereads storage. */
export function resetOpenInPreference() {
  openIn.value = null
}

function positiveLine(location) {
  return Number.isInteger(location?.line) && location.line > 0 ? location.line : null
}

/**
 * The copyable text of a location, for example `com.example.OrderService#place (OrderService.java:42)`.
 * Returns an empty string when there is no class.
 */
export function formatLocation(location) {
  if (!location || typeof location.className !== 'string' || !location.className) return ''
  let text = location.className
  if (typeof location.memberName === 'string' && location.memberName) text += `#${location.memberName}`
  const line = positiveLine(location)
  if (typeof location.sourceFile === 'string' && location.sourceFile) {
    text += ` (${location.sourceFile}${line ? `:${line}` : ''})`
  } else if (line) {
    text += ` (line ${line})`
  }
  return text
}

function localPath(location) {
  const path = location?.sourcePath
  if (typeof path !== 'string' || path.length === 0 || path.length > MAX_PATH_LENGTH || CONTROL.test(path)) return null
  if (path.startsWith('/') && !path.startsWith('//')) return path
  if (WINDOWS_DRIVE.test(path)) return path
  return null
}

function vscodePath(path) {
  const segments = path.replace(/\\/g, '/').split('/')
  return segments
    .map((segment, index) => (index === 0 && /^[A-Za-z]:$/.test(segment) ? segment : encodeURIComponent(segment)))
    .join('/')
}

/**
 * The editor link for a location, built only from a fixed preset's URL scheme and an absolute local path, or
 * `null` when the preference is off, the preset is unknown, or the location has no usable local path.
 */
export function openInHref(location, preset) {
  const path = localPath(location)
  if (!path) return null
  const line = positiveLine(location)
  if (preset === 'vscode') {
    const encoded = vscodePath(path)
    return `vscode://file${encoded.startsWith('/') ? '' : '/'}${encoded}${line ? `:${line}` : ''}`
  }
  if (preset === 'idea') {
    return `idea://open?file=${encodeURIComponent(path)}${line ? `&line=${line}` : ''}`
  }
  return null
}

/** The label of a preset, for link text such as "Open in VS Code". */
export function presetLabel(preset) {
  return OPEN_IN_PRESETS.find((candidate) => candidate.id === preset)?.label ?? ''
}
