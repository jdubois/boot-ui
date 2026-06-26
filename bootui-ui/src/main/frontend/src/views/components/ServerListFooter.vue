<script setup>
import {computed} from 'vue'

const props = defineProps({
  shown: {
    type: Number,
    required: true
  },
  matched: {
    type: Number,
    required: true
  },
  total: {
    type: Number,
    required: true
  },
  pageSize: {
    type: Number,
    required: true
  },
  loading: {
    type: Boolean,
    default: false
  },
  itemLabel: {
    type: String,
    default: 'items'
  }
})

defineEmits(['loadMore'])

const hidden = computed(() => Math.max(props.matched - props.shown, 0))
const nextCount = computed(() => Math.min(props.pageSize, hidden.value))
const summary = computed(() =>
  props.matched === props.total
    ? `Showing ${props.shown} of ${props.total} ${props.itemLabel}.`
    : `Showing ${props.shown} of ${props.matched} matching ${props.itemLabel} (${props.total} total).`
)
</script>

<template>
  <div
    aria-live="polite"
    class="d-flex flex-wrap justify-content-between align-items-center gap-2 text-muted small py-2"
  >
    <span>{{ summary }} Filters run on the server.</span>
    <button
      v-if="hidden > 0"
      :disabled="loading"
      class="btn btn-sm btn-outline-secondary"
      type="button"
      @click="$emit('loadMore')"
    >
      {{ loading ? 'Loading…' : 'Load next ' + nextCount }}
    </button>
  </div>
</template>
