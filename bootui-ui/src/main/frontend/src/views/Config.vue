<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'

const data = ref(null)
const loading = ref(true)
const filter = ref('')
const sourceFilter = ref('')
const showOnlyOverrides = ref(false)

const editingName = ref(null)
const editedValue = ref('')
const saving = ref(false)
const newRow = ref(null)
const newRowName = ref('')
const newRowValue = ref('')
const newRowError = ref(null)
const newNameInput = ref(null)
const editInput = ref(null)
const banner = ref(null)

async function load() {
  loading.value = true
  try {
    const res = await fetch('api/config')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    data.value = await res.json()
  } catch (e) {
    flash(e.message, 'danger')
  } finally {
    loading.value = false
  }
}

const filtered = computed(() => {
  if (!data.value) return []
  return data.value.properties.filter(p => {
    if (showOnlyOverrides.value && !p.override) return false
    if (sourceFilter.value && p.source !== sourceFilter.value) return false
    if (filter.value) {
      const f = filter.value.toLowerCase()
      return p.name.toLowerCase().includes(f) ||
             String(p.value ?? '').toLowerCase().includes(f)
    }
    return true
  })
})

const overrideCount = computed(() =>
  data.value ? data.value.properties.filter(p => p.override).length : 0)

function startEdit(p) {
  newRow.value = null
  editingName.value = p.name
  editedValue.value = p.value == null ? '' : String(p.value)
  nextTick(() => editInput.value?.focus())
}

function cancelEdit() {
  editingName.value = null
  editedValue.value = ''
}

async function saveEdit(p) {
  await postOverride(p.name, editedValue.value)
}

function startCreate() {
  editingName.value = null
  newRow.value = { name: '', value: '' }
  newRowName.value = ''
  newRowValue.value = ''
  newRowError.value = null
  nextTick(() => newNameInput.value?.focus())
}

function cancelCreate() {
  newRow.value = null
  newRowError.value = null
}

async function saveCreate() {
  const name = (newRowName.value || '').trim()
  if (!name) {
    newRowError.value = 'Property name is required.'
    nextTick(() => newNameInput.value?.focus())
    return
  }
  if (!/^[A-Za-z0-9._\-\[\]]+$/.test(name)) {
    newRowError.value = 'Use letters, digits, dots, dashes, underscores or brackets only.'
    return
  }
  await postOverride(name, newRowValue.value, () => {
    newRow.value = null
    newRowError.value = null
  })
}

async function postOverride(name, value, onSuccess) {
  saving.value = true
  try {
    const res = await fetch('api/config/overrides', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, value })
    })
    const result = await res.json().catch(() => ({}))
    if (!res.ok) {
      const msg = result.message || result.error || ('HTTP ' + res.status)
      flash('Could not save override: ' + msg, 'danger')
      return
    }
    flash('Override saved for ' + name + '. ' + (result.message || ''), 'success')
    editingName.value = null
    if (onSuccess) onSuccess()
    await load()
  } catch (e) {
    flash('Could not save override: ' + e.message, 'danger')
  } finally {
    saving.value = false
  }
}

async function removeOverride(name) {
  if (!confirm('Remove override "' + name + '"? The property will fall back to its underlying value.')) return
  saving.value = true
  try {
    const res = await fetch('api/config/overrides/' + encodeURIComponent(name), { method: 'DELETE' })
    const result = await res.json().catch(() => ({}))
    if (!res.ok) {
      flash('Could not remove override: ' + (result.message || ('HTTP ' + res.status)), 'danger')
      return
    }
    flash('Override removed for ' + name + '.', 'success')
    await load()
  } finally {
    saving.value = false
  }
}

function flash(text, type) {
  banner.value = { text, type }
  setTimeout(() => { banner.value = null }, 8000)
}

onMounted(load)
</script>

<template>
  <div>
    <div class="d-flex justify-content-between align-items-start mb-2">
      <div>
        <h2 class="mb-1"><i class="bi bi-sliders me-2"></i>Configuration</h2>
        <p class="text-muted mb-0 small">
          Inspect and override every Spring property the running application can see.
        </p>
      </div>
      <div class="d-flex gap-2">
        <button class="btn btn-success" @click="startCreate" :disabled="!!newRow">
          <i class="bi bi-plus-lg me-1"></i> Add override
        </button>
        <button class="btn btn-outline-secondary" @click="load" title="Refresh">
          <i class="bi bi-arrow-clockwise"></i>
        </button>
      </div>
    </div>

    <div class="alert alert-info d-flex align-items-start mb-3">
      <i class="bi bi-pencil-square fs-4 me-3"></i>
      <div class="flex-grow-1 small">
        <strong>Properties are editable.</strong>
        Click <span class="badge bg-primary"><i class="bi bi-pencil"></i> Edit</span>
        on any row to set a runtime override, or use
        <strong>Add override</strong> to add a new property.
        Overrides are persisted to
        <code>.bootui/application-bootui.properties</code> and take precedence over all other property sources.
        <span class="text-muted">
          Note: properties already bound to a <code>@ConfigurationProperties</code> bean keep
          their value until restart.
        </span>
      </div>
    </div>

    <div v-if="banner" class="alert d-flex justify-content-between align-items-center" :class="'alert-' + banner.type">
      <div><i class="bi" :class="banner.type === 'danger' ? 'bi-exclamation-triangle-fill' : 'bi-check-circle-fill'"></i>
        <span class="ms-2">{{ banner.text }}</span>
      </div>
      <button class="btn-close" @click="banner = null"></button>
    </div>

    <div class="row g-2 mb-3">
      <div class="col-md-6">
        <div class="input-group">
          <span class="input-group-text"><i class="bi bi-search"></i></span>
          <input class="form-control" v-model="filter" placeholder="Filter by name or value…" />
        </div>
      </div>
      <div class="col-md-4">
        <select class="form-select" v-model="sourceFilter" v-if="data">
          <option value="">All property sources</option>
          <option v-for="s in data.sources" :key="s" :value="s">{{ s }}</option>
        </select>
      </div>
      <div class="col-md-2">
        <div class="form-check form-switch mt-2">
          <input class="form-check-input" type="checkbox" id="onlyOverrides" v-model="showOnlyOverrides" />
          <label class="form-check-label small" for="onlyOverrides">
            Only overrides ({{ overrideCount }})
          </label>
        </div>
      </div>
    </div>

    <p class="small text-muted">
      Active profiles:
      <span v-for="p in (data?.activeProfiles || [])" :key="p" class="badge bg-success me-1">{{ p }}</span>
      <span v-if="!data?.activeProfiles?.length">(none)</span>
    </p>

    <div class="table-responsive">
      <table class="table table-sm align-middle">
        <thead class="table-light">
          <tr>
            <th style="width:35%">Property</th>
            <th style="width:35%">Value</th>
            <th style="width:15%">Source</th>
            <th style="width:15%" class="text-end">Actions</th>
          </tr>
        </thead>
        <tbody>
          <!-- New override row -->
          <tr v-if="newRow" class="table-warning">
            <td>
              <input ref="newNameInput" class="form-control form-control-sm font-monospace"
                     v-model="newRowName"
                     placeholder="my.property.name"
                     @keyup.enter="saveCreate"
                     @keyup.esc="cancelCreate" />
              <div v-if="newRowError" class="text-danger small mt-1">
                <i class="bi bi-exclamation-circle"></i> {{ newRowError }}
              </div>
            </td>
            <td>
              <input class="form-control form-control-sm font-monospace"
                     v-model="newRowValue"
                     placeholder="new value"
                     @keyup.enter="saveCreate"
                     @keyup.esc="cancelCreate" />
            </td>
            <td><span class="badge bg-warning text-dark">new override</span></td>
            <td class="text-end">
              <button class="btn btn-sm btn-success" @click="saveCreate" :disabled="saving">
                <i class="bi bi-check-lg"></i> Save
              </button>
              <button class="btn btn-sm btn-outline-secondary ms-1" @click="cancelCreate" :disabled="saving">
                Cancel
              </button>
            </td>
          </tr>

          <!-- Existing rows -->
          <tr v-for="p in filtered" :key="p.name + ':' + p.source"
              :class="{ 'table-warning': p.override, 'table-active': editingName === p.name }">
            <td class="align-top pt-2">
              <code class="text-body">{{ p.name }}</code>
              <span v-if="p.override" class="badge bg-warning text-dark ms-2">override</span>
            </td>
            <td>
              <template v-if="editingName === p.name">
                <input ref="editInput" class="form-control form-control-sm font-monospace"
                       v-model="editedValue"
                       @keyup.enter="saveEdit(p)"
                       @keyup.esc="cancelEdit" />
              </template>
              <template v-else>
                <code v-if="!p.masked" class="text-body">{{ p.value }}</code>
                <span v-else class="text-muted"><i class="bi bi-lock-fill"></i> masked</span>
              </template>
            </td>
            <td class="align-top pt-2"><small class="text-muted">{{ p.source }}</small></td>
            <td class="text-end align-top pt-1">
              <template v-if="editingName === p.name">
                <button class="btn btn-sm btn-success" @click="saveEdit(p)" :disabled="saving">
                  <i class="bi bi-check-lg"></i> Save
                </button>
                <button class="btn btn-sm btn-outline-secondary ms-1" @click="cancelEdit" :disabled="saving">
                  Cancel
                </button>
              </template>
              <template v-else>
                <button class="btn btn-sm btn-primary" @click="startEdit(p)" :disabled="saving">
                  <i class="bi bi-pencil"></i> Edit
                </button>
                <button v-if="p.override" class="btn btn-sm btn-outline-danger ms-1"
                        @click="removeOverride(p.name)" :disabled="saving" title="Remove override">
                  <i class="bi bi-trash"></i>
                </button>
              </template>
            </td>
          </tr>

          <tr v-if="!loading && filtered.length === 0 && !newRow">
            <td colspan="4" class="text-center text-muted py-4">
              No properties match your filters.
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
code.text-body {
  word-break: break-all;
}
.table-active input {
  background: #fffbe6;
}
</style>
