import {onBeforeUnmount, ref, watch} from 'vue'

/**
 * Composable that runs a callback on a configurable interval.
 *
 * @param {Function} callback - function to call on each tick (and on interval change when immediate is true)
 * @param {number[]} [intervals] - available interval options in seconds; 0 means "off"
 * @param {number} [defaultInterval] - index into the intervals array to use by default
 * @returns {{ interval, intervalOptions, isRunning, stop }}
 */
export function useAutoRefresh(callback, intervals = [0, 5, 10, 30, 60], defaultInterval = 1) {
  const interval = ref(intervals[defaultInterval] ?? intervals[0])
  let timer = null

  function stop() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  function start() {
    stop()
    if (interval.value > 0) {
      timer = setInterval(() => {
        if (document.visibilityState !== 'hidden') {
          callback()
        }
      }, interval.value * 1000)
    }
  }

  watch(interval, () => start(), {immediate: true})

  onBeforeUnmount(stop)

  return {
    interval,
    intervalOptions: intervals,
    stop
  }
}
