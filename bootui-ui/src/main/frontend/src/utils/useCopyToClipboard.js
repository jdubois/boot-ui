import {onBeforeUnmount, ref} from 'vue'

/**
 * Shared "copy to clipboard" behaviour used by panels that expose copy buttons.
 *
 * Tracks which key was most recently copied so the UI can flash a confirmation,
 * then clears it after `resetMs`. The reset timer is cleared on unmount.
 *
 * @param {number} resetMs how long the copied indicator stays set
 * @returns {{copiedKey: import('vue').Ref<string|null>, copyToClipboard: (text: string, key?: string) => Promise<void>}}
 */
export function useCopyToClipboard(resetMs = 1500) {
  const copiedKey = ref(null)
  let copiedTimer = null

  async function copyToClipboard(text, key) {
    try {
      await navigator.clipboard.writeText(text)
      copiedKey.value = key ?? text
      if (copiedTimer) clearTimeout(copiedTimer)
      copiedTimer = setTimeout(() => {
        copiedKey.value = null
      }, resetMs)
    } catch (_) {
      // Clipboard access can be denied; ignore and leave the indicator unchanged.
    }
  }

  onBeforeUnmount(() => {
    if (copiedTimer) clearTimeout(copiedTimer)
  })

  return {copiedKey, copyToClipboard}
}
