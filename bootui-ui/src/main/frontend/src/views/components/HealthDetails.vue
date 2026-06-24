<script setup>
import {formatBytes, isPlainObject} from '../../utils/format.js'

defineProps({
  value: {required: false, default: null}
})

const BYTE_KEYS = new Set(['total', 'free', 'threshold', 'used', 'available', 'size'])

function isByteKey(key) {
  return BYTE_KEYS.has(String(key).toLowerCase())
}

function isScalar(value) {
  return value === null || value === undefined || ['string', 'number', 'boolean'].includes(typeof value)
}

function entries(value) {
  return isPlainObject(value) ? Object.entries(value) : []
}

function labelFor(key) {
  const label = String(key)
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[._-]+/g, ' ')
    .trim()
  return label ? label.charAt(0).toUpperCase() + label.slice(1) : key
}

function formatScalar(value, key = null) {
  if (value === null || value === undefined || value === '') return '—'
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  if (key !== null && isByteKey(key) && typeof value === 'number') return formatBytes(value)
  return String(value)
}
</script>

<template>
  <span v-if="isScalar(value)">{{ formatScalar(value) }}</span>

  <div v-else-if="Array.isArray(value)">
    <span v-if="value.length === 0" class="text-muted">None</span>
    <ol v-else class="mb-0 ps-3">
      <li v-for="(item, index) in value" :key="index" class="mb-1">
        <HealthDetails :value="item" />
      </li>
    </ol>
  </div>

  <div v-else-if="isPlainObject(value)" class="table-responsive">
    <table class="table table-sm table-borderless align-middle mb-0">
      <tbody>
        <tr v-for="[key, item] in entries(value)" :key="key">
          <th class="text-muted fw-normal ps-0" style="width: 34%">{{ labelFor(key) }}</th>
          <td class="pe-0">
            <code v-if="isScalar(item)">{{ formatScalar(item, key) }}</code>
            <div v-else class="border rounded bg-light-subtle p-2">
              <HealthDetails :value="item" />
            </div>
          </td>
        </tr>
      </tbody>
    </table>
  </div>

  <code v-else>{{ formatScalar(value) }}</code>
</template>
