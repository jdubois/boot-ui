<script setup>
import {computed} from 'vue'
import {shortTraceId} from '../../utils/correlation.js'

const props = defineProps({
  traceId: {type: String, default: ''},
  clickable: {type: Boolean, default: true},
  prefix: {type: String, default: 'id'},
  short: {type: Boolean, default: true}
})

const emit = defineEmits(['correlate'])

const tagElement = computed(() => (props.clickable ? 'button' : 'span'))
const displayTraceId = computed(() => (props.short ? shortTraceId(props.traceId) : props.traceId))
const traceTitle = computed(() =>
  props.clickable ? `Correlate by trace ${props.traceId}` : `Trace id ${props.traceId}`
)

function correlate() {
  if (props.clickable && props.traceId) {
    emit('correlate', props.traceId)
  }
}
</script>

<template>
  <component
    :is="tagElement"
    v-if="traceId"
    :type="clickable ? 'button' : undefined"
    :class="{'correlation-id-btn': clickable}"
    :aria-label="traceTitle"
    :title="traceTitle"
    class="badge rounded-pill text-bg-info border-0 trace-id-tag correlation-id-tag"
    @click="correlate"
    ><span class="trace-id-prefix">{{ prefix }}:</span> {{ displayTraceId }}</component
  >
</template>

<style scoped>
.trace-id-tag {
  font-family: var(--bs-font-monospace);
  font-size: 0.75rem;
  line-height: 1.35;
  overflow-wrap: anywhere;
  text-align: left;
}

button.trace-id-tag {
  cursor: pointer;
}

button.trace-id-tag:hover,
button.trace-id-tag:focus {
  filter: brightness(0.95);
}

.trace-id-prefix {
  opacity: 0.8;
}
</style>
