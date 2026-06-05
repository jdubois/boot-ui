<script setup>
defineProps({
  message: {type: String, default: ''},
  icon: {type: String, default: 'bi-info-circle'},
  variant: {type: String, default: 'secondary'}
})
</script>

<template>
  <div :class="['alert', `alert-${variant}`]" role="alert">
    <i :class="['bi', icon, 'me-2']"></i><slot>{{ message }}</slot>
  </div>
</template>
