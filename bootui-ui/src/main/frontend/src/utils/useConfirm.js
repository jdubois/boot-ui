import {reactive} from 'vue'

/**
 * Branded, accessible confirmation prompt — a styled replacement for the native
 * `window.confirm()` used to guard destructive actions (restarting a container,
 * deleting a file, running a migration, clearing a buffer).
 *
 * A single {@link ConfirmDialog} is mounted once at the app root and reads the
 * shared {@link confirmState}. Any panel calls `confirm(options)` and awaits a
 * boolean, so call sites stay a drop-in swap for `if (!confirm(...)) return`:
 *
 * ```js
 * const {confirm} = useConfirm()
 * if (!(await confirm({title: 'Restart container?', danger: true}))) return
 * ```
 */

const DEFAULTS = {
  title: 'Are you sure?',
  message: '',
  // Optional resource the action targets, rendered as a monospace chip
  // (a container id, file name, property key, …).
  resource: '',
  confirmLabel: 'Confirm',
  cancelLabel: 'Cancel',
  // `danger` gives the confirm button the red "earned-red" treatment and a
  // warning glyph; `irreversible` adds an explicit "cannot be undone" note.
  danger: false,
  irreversible: false
}

export const confirmState = reactive({
  open: false,
  options: {...DEFAULTS}
})

let resolver = null

/**
 * Resolve the in-flight prompt (if any) and close the dialog. Called by the
 * dialog component for the confirm button, cancel button, Escape, and backdrop.
 *
 * @param {boolean} result
 */
export function settleConfirm(result) {
  if (!confirmState.open && !resolver) return
  confirmState.open = false
  const resolve = resolver
  resolver = null
  if (resolve) resolve(Boolean(result))
}

export function useConfirm() {
  /**
   * Show the confirmation dialog and resolve to the user's choice.
   *
   * @param {Partial<typeof DEFAULTS>} [options]
   * @returns {Promise<boolean>} true when confirmed, false when dismissed
   */
  function confirm(options = {}) {
    // Defensively settle a prior prompt so we never strand a pending promise.
    if (resolver) {
      const previous = resolver
      resolver = null
      previous(false)
    }
    // Accept a bare string as shorthand for `{message}`.
    const normalized = typeof options === 'string' ? {message: options} : options
    confirmState.options = {...DEFAULTS, ...normalized}
    confirmState.open = true
    return new Promise((resolve) => {
      resolver = resolve
    })
  }

  return {confirm}
}
