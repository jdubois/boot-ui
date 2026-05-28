<script setup>
import {computed} from 'vue'

const props = defineProps({
  shown: {
    type: Number,
    required: true
  },
  total: {
    type: Number,
    required: true
  },
  hidden: {
    type: Number,
    required: true
  },
  chunkSize: {
    type: Number,
    required: true
  },
  itemLabel: {
    type: String,
    default: 'items'
  }
})

defineEmits(['showMore', 'showAll'])

const nextCount = computed(() => Math.min(props.chunkSize, props.hidden))
</script>

<template>
  <div
    v-if="hidden > 0"
    aria-live="polite"
    class="d-flex flex-wrap justify-content-between align-items-center gap-2 text-muted small py-2"
  >
    <span>Showing {{ shown }} of {{ total }} {{ itemLabel }}. Filters search the full list.</span>
    <div class="btn-group btn-group-sm">
      <button class="btn btn-outline-secondary" type="button" @click="$emit('showMore')">
        Show next {{ nextCount }}
      </button>
      <button class="btn btn-outline-secondary" type="button" @click="$emit('showAll')">Show all</button>
    </div>
  </div>
</template>
