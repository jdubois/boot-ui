<script setup>
import { computed, onMounted, ref } from 'vue'

const data = ref(null)
const error = ref(null)
const search = ref('')
const expandedGroups = ref(new Set())

const filteredGroups = computed(() => {
  if (!data.value) return []
  const q = search.value.trim().toLowerCase()
  if (!q) return data.value.groups
  return data.value.groups
    .map(group => {
      const matchesGroup = group.groupId.toLowerCase().includes(q)
      const filteredArtifacts = matchesGroup
        ? group.artifacts
        : group.artifacts.filter(a =>
            a.artifactId.toLowerCase().includes(q) ||
            a.version.toLowerCase().includes(q)
          )
      if (filteredArtifacts.length === 0) return null
      return { ...group, artifacts: filteredArtifacts }
    })
    .filter(Boolean)
})

const severityClasses = {
  CRITICAL: 'text-bg-danger',
  HIGH: 'text-bg-warning',
  MEDIUM: 'text-bg-info',
  LOW: 'text-bg-secondary',
  UNKNOWN: 'text-bg-light',
  NONE: 'text-bg-success'
}

function severityClass(severity) {
  return severityClasses[severity] || 'text-bg-light'
}

function toggleGroup(groupId) {
  if (expandedGroups.value.has(groupId)) {
    expandedGroups.value.delete(groupId)
  } else {
    expandedGroups.value.add(groupId)
  }
}

function expandAll() {
  data.value?.groups?.forEach(g => expandedGroups.value.add(g.groupId))
}

function collapseAll() {
  expandedGroups.value.clear()
}

async function load() {
  try {
    const res = await fetch('api/dependencies/tree')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    data.value = await res.json()
    error.value = null
  } catch (e) {
    error.value = e.message
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="d-flex flex-wrap justify-content-between align-items-start gap-2 mb-3">
      <div>
        <h2 class="h4 mb-1"><i class="bi bi-diagram-2 me-2"></i>Dependency Tree</h2>
        <p class="text-muted mb-0">
          Runtime classpath artifacts organized by Maven <code>groupId</code>.
          Expand a group to see individual artifacts and versions.
          Vulnerability counts come from a prior
          <a href="#/vulnerabilities">Vulnerabilities scan</a>.
        </p>
      </div>
      <button class="btn btn-outline-secondary" @click="load" title="Refresh">
        <i class="bi bi-arrow-clockwise"></i>
      </button>
    </div>

    <div v-if="error" class="alert alert-danger">{{ error }}</div>

    <template v-if="data">
      <div class="row g-3 mb-3">
        <div class="col-sm-4">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small">Groups</div>
              <div class="display-6">{{ data.totalGroups }}</div>
            </div>
          </div>
        </div>
        <div class="col-sm-4">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small">Artifacts</div>
              <div class="display-6">{{ data.totalArtifacts }}</div>
            </div>
          </div>
        </div>
        <div class="col-sm-4">
          <div class="card h-100">
            <div class="card-body">
              <div class="text-muted small">Vulnerable</div>
              <div class="display-6" :class="data.totalVulnerable > 0 ? 'text-danger' : 'text-muted'">
                {{ data.totalVulnerable }}
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="card mb-3">
        <div class="card-body d-flex flex-wrap align-items-center gap-3">
          <input
            v-model="search"
            class="form-control form-control-sm tree-search"
            placeholder="Filter by groupId, artifactId or version" />
          <div class="ms-auto d-flex gap-2">
            <button class="btn btn-sm btn-outline-secondary" @click="expandAll">Expand all</button>
            <button class="btn btn-sm btn-outline-secondary" @click="collapseAll">Collapse all</button>
          </div>
        </div>
      </div>

      <div v-if="filteredGroups.length === 0" class="card">
        <div class="card-body text-center text-muted py-4">
          <i class="bi bi-search fs-2 d-block mb-2"></i>
          No groups match the current filter.
        </div>
      </div>

      <div v-for="group in filteredGroups" :key="group.groupId" class="card mb-2">
        <div
          class="card-header d-flex align-items-center gap-3 cursor-pointer"
          role="button"
          @click="toggleGroup(group.groupId)">
          <i :class="['bi', expandedGroups.has(group.groupId) ? 'bi-chevron-down' : 'bi-chevron-right']"></i>
          <i class="bi bi-collection text-muted"></i>
          <span class="fw-semibold font-monospace small flex-grow-1">{{ group.groupId }}</span>
          <span class="badge text-bg-secondary">{{ group.count }} artifact{{ group.count !== 1 ? 's' : '' }}</span>
          <span v-if="group.vulnerableCount > 0" class="badge text-bg-danger">
            <i class="bi bi-exclamation-triangle-fill me-1"></i>{{ group.vulnerableCount }} vulnerable
          </span>
        </div>

        <div v-if="expandedGroups.has(group.groupId)" class="table-responsive">
          <table class="table table-sm align-middle mb-0">
            <tbody>
              <tr v-for="artifact in group.artifacts" :key="`${artifact.packageName}:${artifact.version}`">
                <td class="ps-5" style="width: 1.5rem">
                  <i class="bi bi-box text-muted"></i>
                </td>
                <td class="font-monospace small">{{ artifact.artifactId }}</td>
                <td class="small text-muted">{{ artifact.version }}</td>
                <td class="text-end pe-3">
                  <span class="badge" :class="severityClass(artifact.highestSeverity)">
                    {{ artifact.highestSeverity === 'NONE' ? '✓' : artifact.highestSeverity }}
                  </span>
                  <span v-if="artifact.vulnerabilityCount > 0" class="ms-1 small text-danger">
                    {{ artifact.vulnerabilityCount }} issue{{ artifact.vulnerabilityCount !== 1 ? 's' : '' }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.cursor-pointer {
  cursor: pointer;
  user-select: none;
}

.tree-search {
  max-width: 24rem;
  min-width: 14rem;
}
</style>
