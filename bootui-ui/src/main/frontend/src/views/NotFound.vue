<script setup>
import {inject} from 'vue'

const openCommandPalette = inject('openCommandPalette', null)

function openSearch(event) {
  openCommandPalette?.(event)
}
</script>

<template>
  <section class="not-found-state" aria-labelledby="not-found-title">
    <span class="not-found-icon fs-5" aria-hidden="true"><i class="bi bi-signpost-split"></i></span>
    <div>
      <h2 id="not-found-title">Page not found</h2>
      <p class="text-muted mb-0">
        This BootUI panel does not exist. Return to the overview or search for another panel.
      </p>
    </div>
    <div class="not-found-actions">
      <router-link class="btn btn-primary" to="/scorecard">Go to Scorecard</router-link>
      <button class="btn btn-outline-secondary" type="button" @click="openSearch">
        <i class="bi bi-search me-1" aria-hidden="true"></i>
        Search panels
      </button>
    </div>
  </section>
</template>

<style scoped>
.not-found-state {
  align-items: flex-start;
  background: var(--bootui-surface);
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-lg);
  box-shadow: var(--bootui-shadow-sm);
  display: grid;
  gap: 1.25rem;
  grid-template-columns: auto minmax(0, 1fr);
  padding: clamp(1.5rem, 4vw, 2.5rem);
}

.not-found-icon {
  align-items: center;
  background: var(--bootui-nav-group-bg);
  border-radius: var(--bootui-radius-md);
  color: var(--bootui-text-muted);
  display: inline-flex;
  height: 2.75rem;
  justify-content: center;
  width: 2.75rem;
}

.not-found-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  grid-column: 2;
}

@media (max-width: 575.98px) {
  .not-found-state {
    grid-template-columns: 1fr;
  }

  .not-found-actions {
    grid-column: 1;
  }
}
</style>
