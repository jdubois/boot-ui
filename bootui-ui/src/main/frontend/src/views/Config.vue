<script setup>
import {computed, nextTick, onMounted, ref} from 'vue'
import {apiFetch} from '../api.js'

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

const propertySuggestions = computed(() => data.value?.propertySuggestions || [])

const suggestionByName = computed(() => {
  const byName = new Map()
  propertySuggestions.value.forEach((s) => byName.set(s.name, s))
  return byName
})

const selectedNewSuggestion = computed(() => suggestionByName.value.get((newRowName.value || '').trim()) || null)

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
  return data.value.properties.filter((p) => {
    if (showOnlyOverrides.value && !p.override) return false
    if (sourceFilter.value && p.source !== sourceFilter.value) return false
    if (filter.value) {
      const f = filter.value.toLowerCase()
      return (
        p.name.toLowerCase().includes(f) ||
        String(p.value ?? '')
          .toLowerCase()
          .includes(f) ||
        String(p.description ?? '')
          .toLowerCase()
          .includes(f) ||
        String(p.defaultValue ?? '')
          .toLowerCase()
          .includes(f)
      )
    }
    return true
  })
})

const overrideCount = computed(() => (data.value ? data.value.properties.filter((p) => p.override).length : 0))

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
  newRow.value = {name: '', value: ''}
  newRowName.value = ''
  newRowValue.value = ''
  newRowError.value = null
  nextTick(() => newNameInput.value?.focus())
}

function cancelCreate() {
  newRow.value = null
  newRowError.value = null
}

function hasDefaultValue(suggestion) {
  return suggestion && suggestion.defaultValue !== null && suggestion.defaultValue !== undefined
}

function formatDefaultValue(value) {
  if (value === null || value === undefined) return ''
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function suggestionLabel(suggestion) {
  const parts = []
  if (hasDefaultValue(suggestion)) parts.push('default: ' + formatDefaultValue(suggestion.defaultValue))
  if (suggestion.type) parts.push(suggestion.type)
  if (suggestion.description) parts.push(suggestion.description)
  return parts.join(' - ')
}

function useSelectedDefault() {
  if (hasDefaultValue(selectedNewSuggestion.value)) {
    newRowValue.value = formatDefaultValue(selectedNewSuggestion.value.defaultValue)
  }
}

function metadataFor(name) {
  return suggestionByName.value.get(name)
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
    const res = await apiFetch('api/config/overrides', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({name, value})
    })
    const result = await res.json().catch(() => ({}))
    if (!res.ok) {
      const msg = result.message || result.error || 'HTTP ' + res.status
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
    const res = await apiFetch('api/config/overrides/' + encodeURIComponent(name), {method: 'DELETE'})
    const result = await res.json().catch(() => ({}))
    if (!res.ok) {
      flash('Could not remove override: ' + (result.message || 'HTTP ' + res.status), 'danger')
      return
    }
    flash('Override removed for ' + name + '.', 'success')
    await load()
  } finally {
    saving.value = false
  }
}

function flash(text, type) {
  banner.value = {text, type}
  setTimeout(() => {
    banner.value = null
  }, 8000)
}

onMounted(load)
</script>

<template>
  <div>
    <div class="d-flex justify-content-between align-items-start mb-2">
      <div>
        <h2 class="mb-1"><i class="bi bi-sliders me-2"></i>Configuration</h2>
        <p class="text-muted mb-0 small">Inspect and override every Spring property the running application can see.</p>
      </div>
      <div class="d-flex gap-2">
        <button :disabled="!!newRow" class="btn btn-success" @click="startCreate">
          <i class="bi bi-plus-lg me-1"></i> Add override
        </button>
        <button class="btn btn-outline-secondary" title="Refresh" @click="load">
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
        <strong>Add override</strong> to add a new property. The new-property picker includes known Spring Boot
        configuration keys and their defaults. Overrides are persisted to
        <code>.bootui/application-bootui.properties</code> and take precedence over all other property sources.
        <span class="text-muted">
          Note: properties already bound to a <code>@ConfigurationProperties</code> bean keep their value until restart.
        </span>
      </div>
    </div>

    <div v-if="banner" :class="'alert-' + banner.type" class="alert d-flex justify-content-between align-items-center">
      <div>
        <i :class="banner.type === 'danger' ? 'bi-exclamation-triangle-fill' : 'bi-check-circle-fill'" class="bi"></i>
        <span class="ms-2">{{ banner.text }}</span>
      </div>
      <button class="btn-close" @click="banner = null"></button>
    </div>

    <div class="row g-2 mb-3">
      <div class="col-md-6">
        <div class="input-group">
          <span class="input-group-text"><i class="bi bi-search"></i></span>
          <input v-model="filter" class="form-control" placeholder="Filter by name or value…" />
        </div>
      </div>
      <div class="col-md-4">
        <select v-if="data" v-model="sourceFilter" class="form-select">
          <option value="">All property sources</option>
          <option v-for="s in data.sources" :key="s" :value="s">{{ s }}</option>
        </select>
      </div>
      <div class="col-md-2">
        <div class="form-check form-switch mt-2">
          <input id="onlyOverrides" v-model="showOnlyOverrides" class="form-check-input" type="checkbox" />
          <label class="form-check-label small" for="onlyOverrides"> Only overrides ({{ overrideCount }}) </label>
        </div>
      </div>
    </div>

    <p class="small text-muted">
      Active profiles:
      <span v-for="p in data?.activeProfiles || []" :key="p" class="badge bg-success me-1">{{ p }}</span>
      <span v-if="!data?.activeProfiles?.length">(none)</span>
    </p>

    <div class="table-responsive">
      <table class="table table-sm align-middle">
        <thead class="table-light">
          <tr>
            <th style="width: 30%">Property</th>
            <th style="width: 25%">Value</th>
            <th style="width: 20%">Default</th>
            <th style="width: 13%">Source</th>
            <th class="text-end" style="width: 12%">Actions</th>
          </tr>
        </thead>
        <tbody>
          <!-- New override row -->
          <tr v-if="newRow" class="table-warning">
            <td>
              <input
                ref="newNameInput"
                v-model="newRowName"
                class="form-control form-control-sm font-monospace"
                list="bootPropertySuggestions"
                placeholder="spring.application.name"
                @keyup.enter="saveCreate"
                @keyup.esc="cancelCreate"
              />
              <datalist id="bootPropertySuggestions">
                <option
                  v-for="s in propertySuggestions"
                  :key="s.name"
                  :label="suggestionLabel(s)"
                  :value="s.name"
                ></option>
              </datalist>
              <div v-if="selectedNewSuggestion" class="small text-muted mt-1">
                <div v-if="selectedNewSuggestion.description">{{ selectedNewSuggestion.description }}</div>
                <div>
                  <span v-if="selectedNewSuggestion.type"
                    >Type: <code>{{ selectedNewSuggestion.type }}</code></span
                  >
                  <span v-if="selectedNewSuggestion.type && hasDefaultValue(selectedNewSuggestion)" class="mx-1"
                    >·</span
                  >
                  <span v-if="hasDefaultValue(selectedNewSuggestion)">
                    Default: <code>{{ formatDefaultValue(selectedNewSuggestion.defaultValue) }}</code>
                  </span>
                </div>
              </div>
              <div v-if="newRowError" class="text-danger small mt-1">
                <i class="bi bi-exclamation-circle"></i> {{ newRowError }}
              </div>
            </td>
            <td>
              <div class="input-group input-group-sm">
                <input
                  v-model="newRowValue"
                  :placeholder="
                    hasDefaultValue(selectedNewSuggestion)
                      ? 'default: ' + formatDefaultValue(selectedNewSuggestion.defaultValue)
                      : 'new value'
                  "
                  class="form-control font-monospace"
                  @keyup.enter="saveCreate"
                  @keyup.esc="cancelCreate"
                />
                <button
                  v-if="hasDefaultValue(selectedNewSuggestion)"
                  class="btn btn-outline-secondary"
                  type="button"
                  @click="useSelectedDefault"
                >
                  Use default
                </button>
              </div>
            </td>
            <td>
              <code v-if="hasDefaultValue(selectedNewSuggestion)" class="text-body">
                {{ formatDefaultValue(selectedNewSuggestion.defaultValue) }}
              </code>
              <span v-else class="text-muted">—</span>
            </td>
            <td><span class="badge bg-warning text-dark">new override</span></td>
            <td class="text-end">
              <button :disabled="saving" class="btn btn-sm btn-success" @click="saveCreate">
                <i class="bi bi-check-lg"></i> Save
              </button>
              <button :disabled="saving" class="btn btn-sm btn-outline-secondary ms-1" @click="cancelCreate">
                Cancel
              </button>
            </td>
          </tr>

          <!-- Existing rows -->
          <tr
            v-for="p in filtered"
            :key="p.name + ':' + p.source"
            :class="{'table-warning': p.override, 'table-active': editingName === p.name}"
          >
            <td class="align-top pt-2">
              <code class="text-body">{{ p.name }}</code>
              <span v-if="p.override" class="badge bg-warning text-dark ms-2">override</span>
              <div v-if="p.description" class="small text-muted mt-1">{{ p.description }}</div>
            </td>
            <td>
              <template v-if="editingName === p.name">
                <input
                  ref="editInput"
                  v-model="editedValue"
                  class="form-control form-control-sm font-monospace"
                  @keyup.enter="saveEdit(p)"
                  @keyup.esc="cancelEdit"
                />
              </template>
              <template v-else>
                <code v-if="!p.masked" class="text-body">{{ p.value }}</code>
                <span v-else class="text-muted"><i class="bi bi-lock-fill"></i> masked</span>
              </template>
            </td>
            <td class="align-top pt-2">
              <code v-if="p.defaultValue !== null && p.defaultValue !== undefined" class="text-body">
                {{ formatDefaultValue(p.defaultValue) }}
              </code>
              <span v-else-if="metadataFor(p.name)?.type" class="text-muted small">
                {{ metadataFor(p.name).type }}
              </span>
              <span v-else class="text-muted">—</span>
            </td>
            <td class="align-top pt-2">
              <small class="text-muted">{{ p.source }}</small>
            </td>
            <td class="text-end align-top pt-1">
              <template v-if="editingName === p.name">
                <button :disabled="saving" class="btn btn-sm btn-success" @click="saveEdit(p)">
                  <i class="bi bi-check-lg"></i> Save
                </button>
                <button :disabled="saving" class="btn btn-sm btn-outline-secondary ms-1" @click="cancelEdit">
                  Cancel
                </button>
              </template>
              <template v-else>
                <button :disabled="saving" class="btn btn-sm btn-primary" @click="startEdit(p)">
                  <i class="bi bi-pencil"></i> Edit
                </button>
                <button
                  v-if="p.override"
                  :disabled="saving"
                  class="btn btn-sm btn-outline-danger ms-1"
                  title="Remove override"
                  @click="removeOverride(p.name)"
                >
                  <i class="bi bi-trash"></i>
                </button>
              </template>
            </td>
          </tr>

          <tr v-if="!loading && filtered.length === 0 && !newRow">
            <td class="text-center text-muted py-4" colspan="5">No properties match your filters.</td>
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
