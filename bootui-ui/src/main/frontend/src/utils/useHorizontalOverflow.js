import {ref, watch} from 'vue'

/**
 * @param {import('vue').Ref<HTMLElement|null>} region
 * @param {import('vue').WatchSource<unknown>} content Remeasure after content changes that leave the scrollport the same size.
 * @param {import('vue').WatchSource<boolean>} [enabled]
 */
export function useHorizontalOverflow(region, content, enabled = () => true) {
  const overflowing = ref(false)
  watch(
    [region, enabled, content],
    ([element, observe], _, onCleanup) => {
      if (!element || !observe) {
        overflowing.value = false
        return
      }
      const measure = () => {
        overflowing.value = element.scrollWidth > element.clientWidth
      }
      measure()
      if (typeof ResizeObserver === 'function') {
        const observer = new ResizeObserver(measure)
        observer.observe(element)
        onCleanup(() => observer.disconnect())
      }
    },
    {flush: 'post'}
  )
  return overflowing
}
