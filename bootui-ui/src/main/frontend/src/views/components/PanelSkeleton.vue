<script setup>
defineProps({
  rows: {type: Number, default: 5},
  card: {type: Boolean, default: false}
})
</script>

<template>
  <div :class="card ? 'card p-3' : ''" class="skeleton-wrapper" aria-busy="true" aria-label="Loading…">
    <div class="skeleton-line skeleton-line--title mb-3"></div>
    <div v-for="i in rows" :key="i" :style="{width: `${65 + (i * 17) % 35}%`}" class="skeleton-line mb-2"></div>
  </div>
</template>

<style scoped>
@keyframes shimmer {
  0% {
    background-position: -400px 0;
  }
  100% {
    background-position: 400px 0;
  }
}

.skeleton-line {
  background: linear-gradient(
    90deg,
    var(--bootui-skeleton-base, #e2e8f0) 25%,
    var(--bootui-skeleton-shine, #f1f5f9) 50%,
    var(--bootui-skeleton-base, #e2e8f0) 75%
  );
  background-size: 800px 100%;
  animation: shimmer 1.4s ease-in-out infinite;
  border-radius: 4px;
  height: 0.9rem;
}

.skeleton-line--title {
  height: 1.4rem;
  width: 40%;
}
</style>
