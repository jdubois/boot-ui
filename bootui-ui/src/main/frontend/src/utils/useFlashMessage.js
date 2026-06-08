import {onBeforeUnmount, ref} from 'vue'

/**
 * Shared transient "flash" banner state used by panels that surface action results.
 *
 * Holds a single `{text, type}` message. `flash()` shows it and auto-dismisses after
 * `timeoutMs`; `show()` displays it until cleared (for sticky errors); `clear()` removes
 * it immediately. The pending dismiss timer is always cleared on unmount.
 *
 * @param {number} timeoutMs default auto-dismiss delay for `flash`
 * @returns {{message: import('vue').Ref<{text: string, type: string}|null>, flash: Function, show: Function, clear: Function}}
 */
export function useFlashMessage(timeoutMs = 6000) {
  const message = ref(null)
  let timer = null

  function cancelTimer() {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
  }

  function clear() {
    cancelTimer()
    message.value = null
  }

  function show(text, type = 'info') {
    cancelTimer()
    message.value = {text, type}
  }

  function flash(text, type = 'info', {timeout = timeoutMs} = {}) {
    show(text, type)
    if (timeout > 0) {
      timer = setTimeout(() => {
        message.value = null
        timer = null
      }, timeout)
    }
  }

  onBeforeUnmount(cancelTimer)

  return {message, flash, show, clear}
}
