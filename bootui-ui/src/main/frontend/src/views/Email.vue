<script setup>
import {apiFetch, getJson} from '../api.js'
import {computed, inject, onMounted, ref} from 'vue'
import {useRoute} from 'vue-router'
import {formatBytes} from '../utils/format.js'
import {describeLoadError, formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import {useConfirm} from '../utils/useConfirm.js'
import {useFlashMessage} from '../utils/useFlashMessage.js'
import FlashBanner from './components/FlashBanner.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import ReadOnlyNotice from './components/ReadOnlyNotice.vue'
import SpinnerButton from './components/SpinnerButton.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const panels = inject('panels', ref(null))
const isQuarkus = computed(() => (panels.value?.platform || 'spring-boot') === 'quarkus')
const {confirm} = useConfirm()
const report = ref(null)
const error = ref(null)
const {message: banner, flash, show, clear} = useFlashMessage(4000)
const filter = ref('')
const selectedId = ref(null)
const busy = ref(false)
const lastFetched = ref(null)

async function fetchEmails() {
  error.value = null
  try {
    report.value = await getJson('api/email')
    lastFetched.value = Date.now()
  } catch (e) {
    error.value = describeLoadError(e, 'Unable to load captured emails')
  }
}

const {autoRefresh, loading, load} = useAutoRefresh(fetchEmails)

const messages = computed(() => report.value?.messages ?? [])

const filteredMessages = computed(() => {
  const v = filter.value.trim().toLowerCase()
  if (!v) return messages.value
  return messages.value.filter(
    (m) =>
      (m.from || '').toLowerCase().includes(v) ||
      (m.to || []).join(' ').toLowerCase().includes(v) ||
      (m.cc || []).join(' ').toLowerCase().includes(v) ||
      (m.subject || '').toLowerCase().includes(v)
  )
})

const selected = computed(() => messages.value.find((m) => m.id === selectedId.value) || null)

// Deep-linked from Live Activity (see `deepLink` in utils/activityStream.js): a MAIL entry there
// shares its id with the captured message here, so `?id=` opens that message's detail drawer directly
// instead of only prefilling a filter like the other panels' `?q=` deep links do.
const route = useRoute()
onMounted(() => {
  const targetId = route?.query?.id
  if (typeof targetId === 'string' && targetId) {
    selectedId.value = targetId
  }
})

function toggle(id) {
  selectedId.value = id === selectedId.value ? null : id
}

function closeDrawer() {
  selectedId.value = null
}

function formatTimestamp(timestamp) {
  if (!timestamp) return '—'
  return new Date(timestamp).toLocaleString()
}

function addresses(list) {
  return list && list.length ? list.join(', ') : '—'
}

function showReadOnlyMessage() {
  flash(readOnlyReason.value, 'warning')
}

async function clearAll() {
  if (readOnly.value) {
    showReadOnlyMessage()
    return
  }
  if (
    !(await confirm({
      title: 'Clear captured emails?',
      message: 'Clear every captured outgoing email from the in-memory buffer.',
      confirmLabel: 'Clear all',
      danger: true,
      irreversible: true
    }))
  )
    return
  busy.value = true
  try {
    const res = await apiFetch('api/email', {method: 'DELETE'})
    if (!res.ok && res.status !== 204) throw new Error(`HTTP ${res.status}`)
    closeDrawer()
    await load()
    flash('Cleared captured emails.', 'success')
  } catch (e) {
    show(formatLoadError(e, 'Could not clear captured emails'), 'danger')
  } finally {
    busy.value = false
  }
}

function downloadUrl(id) {
  return `api/email/${encodeURIComponent(id)}/eml`
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-envelope"
      title="Email"
      :subtitle="report ? `${report.total} captured · buffer of ${report.maxEntries}` : null"
      :loading="loading"
      :error="error"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    >
      <template #actions>
        <SpinnerButton
          :loading="busy"
          :disabled="!report || readOnly || !report.total || busy"
          class="btn btn-sm btn-outline-danger"
          icon="bi-trash"
          label="Clear"
          @click="clearAll"
        />
      </template>
    </PanelHeader>

    <FlashBanner :message="banner" @dismiss="clear" />

    <PanelSkeleton v-if="loading && !report" />

    <template v-else-if="report">
      <div v-if="!report.available" class="alert alert-warning">
        <strong>Email capture is unavailable.</strong>
        <span class="d-block small">{{ report.unavailableReason }}</span>
      </div>

      <template v-else>
        <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason">Clearing captured emails is read-only.</ReadOnlyNotice>

        <div v-if="report.devTrapEnabled" class="alert alert-info small">
          <i class="bi bi-sign-stop-fill me-1"></i>
          <template v-if="isQuarkus">
            Mock mail mode is enabled (<code>quarkus.mailer.mock=true</code>): captured messages are recorded but not
            handed to a real mail transport.
          </template>
          <template v-else>
            Dev-trap mode is enabled (<code>bootui.email.dev-trap=true</code>): captured messages are recorded but not
            handed to the real mail transport.
          </template>
        </div>

        <div v-if="report.total === 0" class="alert alert-secondary">
          No outgoing emails captured yet. Send an email through the application's
          <code>{{ isQuarkus ? 'Mailer' : 'JavaMailSender' }}</code> and refresh this panel.
        </div>

        <template v-else>
          <div class="mb-3">
            <input
              v-model="filter"
              class="form-control form-control-sm"
              placeholder="Filter by sender, recipient, or subject…"
            />
          </div>

          <div class="table-responsive">
            <table class="table table-sm table-hover align-middle">
              <thead>
                <tr>
                  <th>Time</th>
                  <th>From</th>
                  <th>To</th>
                  <th>Subject</th>
                  <th>Attachments</th>
                  <th>Status</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                <template v-for="m in filteredMessages" :key="m.id">
                  <tr :class="{'table-active': m.id === selectedId}">
                    <td class="text-muted small text-nowrap">{{ formatTimestamp(m.timestamp) }}</td>
                    <td class="text-truncate email-address-cell">{{ m.from || '—' }}</td>
                    <td class="text-truncate email-address-cell">{{ addresses(m.to) }}</td>
                    <td class="text-truncate email-subject-cell fw-semibold">{{ m.subject || '(no subject)' }}</td>
                    <td>
                      <span v-if="m.attachments?.length" class="badge text-bg-secondary"
                        ><i class="bi bi-paperclip"></i> {{ m.attachments.length }}</span
                      >
                      <span v-else class="text-muted">—</span>
                    </td>
                    <td>
                      <span v-if="m.sent" class="badge text-bg-success">sent</span>
                      <span
                        v-else
                        class="badge text-bg-warning"
                        :title="
                          isQuarkus ? 'Recorded in mock mail mode, not sent' : 'Recorded by dev-trap mode, not sent'
                        "
                        >{{ isQuarkus ? 'mock' : 'dev-trap' }}</span
                      >
                    </td>
                    <td class="text-end text-nowrap">
                      <a
                        :href="downloadUrl(m.id)"
                        :download="`email-${m.id}.eml`"
                        class="btn btn-sm btn-outline-secondary me-1"
                        title="Download .eml"
                      >
                        <i class="bi bi-download"></i>
                      </a>
                      <button
                        :aria-expanded="m.id === selectedId"
                        class="btn btn-sm btn-outline-primary"
                        @click="toggle(m.id)"
                      >
                        {{ m.id === selectedId ? 'Close' : 'View' }}
                      </button>
                    </td>
                  </tr>
                  <tr v-if="m.id === selectedId" class="email-detail-row">
                    <td class="p-0" colspan="7">
                      <div class="email-drawer card m-2">
                        <div class="card-header d-flex justify-content-between align-items-center">
                          <div class="text-truncate">
                            <i class="bi bi-envelope-open me-2"></i>
                            {{ m.subject || '(no subject)' }}
                          </div>
                          <button class="btn btn-sm btn-outline-secondary" @click="closeDrawer">Close</button>
                        </div>
                        <div class="card-body">
                          <dl class="row small mb-3">
                            <dt class="col-sm-2">From</dt>
                            <dd class="col-sm-10">{{ m.from || '—' }}</dd>
                            <dt class="col-sm-2">To</dt>
                            <dd class="col-sm-10">{{ addresses(m.to) }}</dd>
                            <template v-if="m.cc?.length">
                              <dt class="col-sm-2">Cc</dt>
                              <dd class="col-sm-10">{{ addresses(m.cc) }}</dd>
                            </template>
                            <template v-if="m.bcc?.length">
                              <dt class="col-sm-2">Bcc</dt>
                              <dd class="col-sm-10">{{ addresses(m.bcc) }}</dd>
                            </template>
                          </dl>

                          <div v-if="m.attachments?.length" class="mb-3">
                            <h3 class="h6">Attachments</h3>
                            <ul class="list-unstyled small mb-0">
                              <li v-for="(a, i) in m.attachments" :key="i">
                                <i class="bi bi-paperclip me-1"></i>{{ a.filename || 'unnamed' }}
                                <span class="text-muted"
                                  >({{ a.contentType || 'unknown type' }}, {{ formatBytes(a.sizeBytes) }})</span
                                >
                              </li>
                            </ul>
                          </div>

                          <div v-if="m.htmlBody">
                            <h3 class="h6">HTML preview</h3>
                            <iframe
                              :srcdoc="m.htmlBody"
                              class="email-html-frame"
                              sandbox=""
                              title="Email HTML preview"
                            ></iframe>
                          </div>

                          <div v-if="m.textBody" class="mt-3">
                            <h3 class="h6">Text body</h3>
                            <pre class="email-text-body small mb-0">{{ m.textBody }}</pre>
                          </div>

                          <p v-if="!m.htmlBody && !m.textBody" class="text-muted mb-0">This message has no body.</p>
                        </div>
                      </div>
                    </td>
                  </tr>
                </template>
                <tr v-if="!filteredMessages.length">
                  <td class="text-center text-muted py-4" colspan="7">No captured emails match your filter.</td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
      </template>
    </template>
  </div>
</template>

<style scoped>
.email-address-cell {
  max-width: 220px;
}

.email-subject-cell {
  max-width: 320px;
}

.email-drawer {
  border: 1px solid rgba(0, 0, 0, 0.08);
}

.email-html-frame {
  width: 100%;
  min-height: 320px;
  border: 1px solid var(--bs-border-color);
  border-radius: var(--bootui-radius-xs);
  background: #fff;
}

.email-text-body {
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--bs-tertiary-bg);
  border: 1px solid var(--bs-border-color);
  border-radius: var(--bootui-radius-xs);
  padding: 0.75rem;
}
</style>
