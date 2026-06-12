<script setup>
import {apiFetch} from '../api.js'
import {computed, ref} from 'vue'
import {formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import {useFlashMessage} from '../utils/useFlashMessage.js'
import FlashBanner from './components/FlashBanner.vue'
import PanelHeader from './components/PanelHeader.vue'
import ReadOnlyNotice from './components/ReadOnlyNotice.vue'
import SpinnerButton from './components/SpinnerButton.vue'
import UnavailableState from './components/UnavailableState.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason} = usePanelState(props)
const status = ref(null)
const toggling = ref(false)
const lastFetched = ref(null)
const {message: banner, flash, clear} = useFlashMessage(8000)

const enabled = computed(() => status.value?.enabled === true)
const actionTools = computed(() => (status.value?.tools ?? []).filter((tool) => tool.action))
const readTools = computed(() => (status.value?.tools ?? []).filter((tool) => !tool.action))

async function fetchStatus() {
  try {
    const res = await apiFetch('api/mcp-server')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    status.value = await res.json()
    lastFetched.value = Date.now()
  } catch (e) {
    flash(formatLoadError(e, 'Could not load MCP server status'), 'danger')
  }
}

async function toggle() {
  if (readOnly.value) {
    flash(readOnlyReason.value, 'warning')
    return
  }
  const target = !enabled.value
  toggling.value = true
  try {
    const res = await apiFetch('api/mcp-server/toggle', {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({enabled: target})
    })
    const result = await res.json().catch(() => ({}))
    if (!res.ok) {
      flash(result.message || result.error || 'HTTP ' + res.status, 'warning')
      await load()
      return
    }
    status.value = result
    lastFetched.value = Date.now()
    flash(result.enabled ? 'MCP server enabled.' : 'MCP server disabled.', result.enabled ? 'success' : 'secondary')
  } catch (e) {
    flash(formatLoadError(e, 'Could not toggle the MCP server'), 'danger')
  } finally {
    toggling.value = false
  }
}

const {autoRefresh, loading, load} = useAutoRefresh(fetchStatus)
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-plug"
      title="MCP Server"
      subtitle="Expose BootUI advisors and read-only diagnostics to local AI agents over the Model Context Protocol."
      :loading="loading"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <FlashBanner :message="banner" with-icon @dismiss="clear" />

    <ReadOnlyNotice v-if="readOnly" :reason="readOnlyReason">The MCP server toggle is read-only.</ReadOnlyNotice>

    <div v-if="loading && !status" class="card">
      <div class="card-body text-muted">Loading MCP server status…</div>
    </div>

    <template v-else-if="status">
      <!-- Toggle card -->
      <div class="card mb-4 toggle-card" :class="enabled ? 'border-success-subtle' : 'border-secondary-subtle'">
        <div class="card-body p-4 d-flex flex-wrap align-items-center justify-content-between gap-3">
          <div class="d-flex align-items-center gap-3">
            <div
              class="action-icon"
              :class="enabled ? 'bg-success-subtle text-success' : 'bg-secondary-subtle text-secondary'"
            >
              <i class="bi" :class="enabled ? 'bi-plug-fill' : 'bi-plug'"></i>
            </div>
            <div>
              <h2 class="h5 fw-bold mb-1">
                MCP server is
                <span :class="enabled ? 'text-success' : 'text-secondary'">{{ enabled ? 'enabled' : 'disabled' }}</span>
              </h2>
              <p class="text-muted small mb-0">
                Toggling here overrides the
                <code>bootui.mcp.enabled</code> property at runtime (configured: <code>{{ status.configuredMode }}</code
                ><span v-if="status.overridden"> · currently overridden</span>).
              </p>
            </div>
          </div>

          <div class="form-check form-switch mcp-switch m-0">
            <input
              id="mcp-enabled-toggle"
              class="form-check-input"
              type="checkbox"
              role="switch"
              :checked="enabled"
              :disabled="readOnly || toggling"
              @change="toggle"
            />
            <label class="form-check-label visually-hidden" for="mcp-enabled-toggle">Enable MCP server</label>
          </div>
        </div>
      </div>

      <!-- What it does / exposes -->
      <div class="row g-4 mb-4">
        <div class="col-lg-7">
          <div class="card h-100">
            <div class="card-body p-4">
              <h3 class="h6 fw-bold mb-2"><i class="bi bi-info-circle me-2"></i>What this server does</h3>
              <p class="text-muted small mb-2">
                The BootUI MCP server lets a local AI coding agent (such as GitHub Copilot or Claude Code) consult
                BootUI's advisors before proposing a fix and pull runtime diagnostics while diagnosing an issue. It is a
                headless integration over the
                <a href="https://modelcontextprotocol.io" target="_blank" rel="noopener">Model Context Protocol</a>,
                served as JSON-RPC 2.0 at the loopback endpoint below.
              </p>
              <ul class="text-muted small mb-0 ps-3">
                <li>
                  <strong>Local only.</strong> The endpoint sits behind the same loopback, Host allow-list, and
                  cross-site write defenses as the rest of BootUI.
                </li>
                <li>
                  <strong>Reuses the panels.</strong> Each tool delegates to the same controllers the UI uses, so secret
                  masking and per-panel enable/read-only toggles apply identically.
                </li>
                <li>
                  <strong>Opt-in &amp; fail-closed.</strong> Disabled by default; this toggle (or
                  <code>bootui.mcp.enabled=ON</code>) turns it on.
                </li>
              </ul>
            </div>
          </div>
        </div>
        <div class="col-lg-5">
          <div class="card h-100">
            <div class="card-body p-4">
              <h3 class="h6 fw-bold mb-3"><i class="bi bi-hdd-network me-2"></i>Connection</h3>
              <dl class="row small mb-0">
                <dt class="col-5 text-muted fw-normal">Endpoint</dt>
                <dd class="col-7">
                  <code>{{ status.endpoint }}</code>
                </dd>
                <dt class="col-5 text-muted fw-normal">Transport</dt>
                <dd class="col-7">{{ status.transport }}</dd>
                <dt class="col-5 text-muted fw-normal">Protocol</dt>
                <dd class="col-7">
                  <code>{{ status.protocolVersion }}</code>
                </dd>
                <dt class="col-5 text-muted fw-normal">Server</dt>
                <dd class="col-7">
                  {{ status.serverName }} <span class="text-muted">{{ status.serverVersion }}</span>
                </dd>
                <dt class="col-5 text-muted fw-normal">Tools</dt>
                <dd class="col-7">{{ status.toolCount }}</dd>
                <dt class="col-5 text-muted fw-normal">Max results</dt>
                <dd class="col-7">{{ status.maxResults }}</dd>
              </dl>
            </div>
          </div>
        </div>
      </div>

      <!-- Tools exposed -->
      <div class="card">
        <div class="card-body p-4">
          <div class="d-flex align-items-center justify-content-between mb-3">
            <h3 class="h6 fw-bold mb-0"><i class="bi bi-tools me-2"></i>Tools exposed ({{ status.toolCount }})</h3>
            <span v-if="!enabled" class="badge text-bg-secondary">Server disabled — tools are not reachable</span>
          </div>

          <p class="text-muted small">
            <strong>Action tools</strong> run an advisor scan (refused when the backing panel is read-only).
            <strong>Read tools</strong> return sanitized runtime data. A tool is advertised only when its backing panel
            is enabled.
          </p>

          <div v-if="actionTools.length" class="mb-3">
            <div class="text-uppercase text-muted small fw-semibold mb-2">Action tools</div>
            <ul class="list-group list-group-flush">
              <li v-for="tool in actionTools" :key="tool.name" class="list-group-item px-0">
                <div class="d-flex align-items-center justify-content-between gap-2">
                  <code class="text-primary">{{ tool.name }}</code>
                  <span class="d-flex gap-1">
                    <span class="badge text-bg-light border">{{ tool.panel }}</span>
                    <span v-if="!tool.panelEnabled" class="badge text-bg-secondary">panel disabled</span>
                    <span v-else-if="tool.panelReadOnly" class="badge text-bg-warning">read-only</span>
                  </span>
                </div>
                <div class="text-muted small mt-1">{{ tool.description }}</div>
              </li>
            </ul>
          </div>

          <div v-if="readTools.length">
            <div class="text-uppercase text-muted small fw-semibold mb-2">Read tools</div>
            <ul class="list-group list-group-flush">
              <li v-for="tool in readTools" :key="tool.name" class="list-group-item px-0">
                <div class="d-flex align-items-center justify-content-between gap-2">
                  <code class="text-primary">{{ tool.name }}</code>
                  <span class="d-flex gap-1">
                    <span class="badge text-bg-light border">{{ tool.panel }}</span>
                    <span v-if="!tool.panelEnabled" class="badge text-bg-secondary">panel disabled</span>
                  </span>
                </div>
                <div class="text-muted small mt-1">{{ tool.description }}</div>
              </li>
            </ul>
          </div>

          <p v-if="!status.toolCount" class="text-muted small mb-0">No tools are currently available.</p>
        </div>
      </div>
    </template>

    <UnavailableState
      v-else
      message="MCP server status is unavailable. The app may be unreachable — retry or refresh this panel."
    />
  </div>
</template>

<style scoped>
.action-icon {
  align-items: center;
  border-radius: 1rem;
  display: inline-flex;
  font-size: 1.5rem;
  height: 3rem;
  justify-content: center;
  width: 3rem;
}

.mcp-switch .form-check-input {
  cursor: pointer;
  height: 1.75rem;
  width: 3.25rem;
}
</style>
