<script setup>
import AdvisorRuleViolations from './AdvisorRuleViolations.vue'

defineProps({
  results: {type: Array, required: true},
  apiPath: {type: String, required: true},
  details: {type: Object, default: null},
  refreshReport: {type: Function, required: true},
  disabled: {type: Boolean, default: false},
  revision: {type: Number, default: 0},
  dismissFinding: {type: Function, required: true},
  restoreFinding: {type: Function, required: true},
  statusClass: {type: Function, required: true},
  severityClass: {type: Function, required: true}
})
</script>

<template>
  <section v-if="results.length > 0" aria-labelledby="advisor-all-dismissed-heading" data-testid="all-dismissed">
    <h4
      id="advisor-all-dismissed-heading"
      tabindex="-1"
      class="card-header text-muted small fw-normal mb-0 advisor-all-dismissed__heading"
    >
      <i class="bi bi-eye-slash me-1" aria-hidden="true"></i>All findings dismissed ({{ results.length }}
      {{ results.length === 1 ? 'rule' : 'rules' }}) — not counted in score
    </h4>
    <div class="list-group list-group-flush">
      <div v-for="result in results" :key="result.id" class="list-group-item">
        <div class="d-flex flex-wrap align-items-center gap-2 mb-1">
          <span :class="statusClass(result.status)" class="badge">{{ result.status }}</span>
          <span :class="severityClass(result.severity)" class="badge">{{ result.severity }}</span>
          <span class="badge text-bg-light border font-monospace">{{ result.category }}</span>
          <span class="text-muted small font-monospace">{{ result.id }}</span>
        </div>
        <div class="small fw-semibold mb-1">{{ result.name }}</div>
        <div class="small text-muted mb-2">
          {{ result.suppressedCount }} {{ result.suppressedCount === 1 ? 'finding is' : 'findings are' }} dismissed, so
          this rule no longer counts.
        </div>
        <AdvisorRuleViolations
          :api-path="apiPath"
          :rule="result"
          :details="details"
          :refresh-report="refreshReport"
          suppressible
          :disabled="disabled"
          :revision="revision"
          :dismiss-finding="dismissFinding"
          :restore-finding="restoreFinding"
        />
      </div>
    </div>
  </section>
</template>

<style scoped>
.advisor-all-dismissed__heading:focus-visible {
  outline: 2px solid var(--bootui-blue);
  outline-offset: -2px;
}
</style>
