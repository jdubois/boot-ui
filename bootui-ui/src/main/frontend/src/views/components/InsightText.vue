<script setup>
import {computed} from 'vue'
import {textParts} from '../../utils/runtimeInsights.js'

// Engine text marks routes, statements, and classes with backticks; they render as code, never as literal marks.
const props = defineProps({text: {type: String, default: ''}})
const parts = computed(() => textParts(props.text))
</script>

<template>
  <template v-for="(part, index) in parts" :key="index">
    <code v-if="part.code" class="bootui-break-anywhere">{{ part.value }}</code>
    <template v-else>{{ part.value }}</template>
  </template>
</template>
