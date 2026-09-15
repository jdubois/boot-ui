<script setup>
import {computed, ref} from 'vue'
import {compareCounters, exactInteger, formatCounter, formatDuration, formatRatio} from '../../utils/mysqlFormat.js'

const props = defineProps({
  rows: {type: /** @type {import('vue').PropType<Record<string, unknown>[]>} */ (Array), default: () => []},
  columns: {
    type: /** @type {import('vue').PropType<{key: string, label: string, type?: string, wrap?: boolean, emptyText?: string}[]>} */ (
      Array
    ),
    required: true
  },
  label: {type: String, required: true},
  emptyMessage: {type: String, default: 'No rows were observed within this read’s scope.'}
})

const filter = ref('')
const sort = ref({key: null, descending: false})

function value(row, column) {
  return row[column.key]
}

function display(row, column) {
  const raw = value(row, column)
  if (column.type === 'counter') return formatCounter(raw)
  if (column.type === 'ms' || column.type === 's') return formatDuration(raw, column.type)
  if (column.type === 'ratio') return formatRatio(raw)
  if (raw === null || raw === undefined || raw === '') return column.emptyText || '—'
  if (typeof raw === 'boolean') return raw ? 'Yes' : 'No'
  return String(raw)
}

const retained = computed(() => {
  const needle = filter.value.trim().toLocaleLowerCase()
  const rows = props.rows.filter((row) =>
    props.columns.some((column) => display(row, column).toLocaleLowerCase().includes(needle))
  )
  const column = props.columns.find((candidate) => candidate.key === sort.value.key)
  if (!column) return rows
  return rows.sort((left, right) => {
    const a = value(left, column)
    const b = value(right, column)
    // Unknown always sorts last, in both directions. Unknown is never measured zero.
    const missing = (raw) => raw == null || raw === '' || (column.type === 'counter' && exactInteger(raw) === null)
    if (missing(a) || missing(b)) return missing(a) === missing(b) ? 0 : missing(a) ? 1 : -1
    const comparison =
      column.type === 'counter'
        ? compareCounters(a, b)
        : ['ms', 's', 'ratio'].includes(column.type)
          ? Number(a) - Number(b)
          : String(a).localeCompare(String(b))
    return sort.value.descending ? -comparison : comparison
  })
})

function toggleSort(column) {
  sort.value = {key: column.key, descending: sort.value.key === column.key && !sort.value.descending}
}
</script>

<template>
  <div class="mysql-table">
    <div class="mysql-table__toolbar mb-2">
      <label class="mysql-table__filter form-label small mb-0">
        <span>Filter retained {{ label }}</span>
        <input v-model="filter" class="form-control form-control-sm" type="search" placeholder="Filter these rows…" />
      </label>
      <p class="small text-muted mb-0">{{ retained.length }} of {{ rows.length }} retained rows · local filter only</p>
    </div>
    <div v-if="rows.length" class="table-responsive" role="region" :aria-label="label" tabindex="0">
      <table class="table table-sm align-middle mb-0">
        <caption class="visually-hidden">
          {{
            label
          }}
          — retained observations; click a column heading to sort
        </caption>
        <thead>
          <tr>
            <th
              v-for="column in columns"
              :key="column.key"
              scope="col"
              :aria-sort="sort.key === column.key ? (sort.descending ? 'descending' : 'ascending') : 'none'"
            >
              <button class="mysql-table__sort" type="button" @click="toggleSort(column)">
                {{ column.label }}
                <i
                  v-if="sort.key === column.key"
                  class="bi"
                  :class="sort.descending ? 'bi-sort-down' : 'bi-sort-up'"
                  aria-hidden="true"
                ></i>
              </button>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in retained" :key="index">
            <td
              v-for="column in columns"
              :key="column.key"
              class="font-monospace"
              :class="{'mysql-table__text': column.wrap}"
            >
              {{ display(row, column) }}
            </td>
          </tr>
          <tr v-if="!retained.length">
            <td :colspan="columns.length" class="text-muted">No retained rows match this filter.</td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-else class="small text-muted mb-0">{{ emptyMessage }}</p>
  </div>
</template>

<style scoped>
.mysql-table__toolbar,
.mysql-table__filter {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem 1rem;
}

.mysql-table__toolbar {
  justify-content: space-between;
}

.mysql-table__filter {
  flex: 1 1 25rem;
}

.mysql-table__filter input {
  flex: 1 1 12rem;
  max-width: 20rem;
  min-width: 0;
}

.mysql-table__sort {
  background: transparent;
  border: 0;
  color: inherit;
  font: inherit;
  padding: 0.2rem 0;
  text-align: left;
  white-space: nowrap;
}

.mysql-table__sort:focus-visible,
.table-responsive:focus-visible {
  outline: 2px solid var(--bootui-blue);
  outline-offset: 2px;
}

td {
  white-space: nowrap;
}

th,
td {
  padding: 0.4rem 0.5rem;
}

.mysql-table__text {
  min-width: 24rem;
  overflow-wrap: anywhere;
  white-space: normal;
}
</style>
