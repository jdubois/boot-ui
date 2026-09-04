<script setup>
import {apiFetch, getJson} from '../api.js'
import {computed, ref} from 'vue'
import {formatLoadError} from '../utils/loadError.js'
import {panelProps, usePanelState} from '../utils/panelState.js'
import {useAutoRefresh} from '../utils/useAutoRefresh.js'
import {useCopyToClipboard} from '../utils/useCopyToClipboard.js'
import {useFlashMessage} from '../utils/useFlashMessage.js'
import {getBootUiApiPath} from '../utils/bootUiPath.js'
import FlashBanner from './components/FlashBanner.vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import ReadOnlyNotice from './components/ReadOnlyNotice.vue'
import UnavailableState from './components/UnavailableState.vue'

const props = defineProps(panelProps)
const {readOnly, readOnlyReason, manifestAvailable, manifestUnavailableReason} = usePanelState(props)
const status = ref(null)
const toggling = ref(false)
const lastFetched = ref(null)
const {message: banner, flash, clear} = useFlashMessage(8000)
const {copiedKey, copyToClipboard} = useCopyToClipboard(2000)

const enabled = computed(() => status.value?.enabled === true)
const actionTools = computed(() => (status.value?.tools ?? []).filter((tool) => tool.action))
const readTools = computed(() => (status.value?.tools ?? []).filter((tool) => !tool.action))

const endpointUrl = computed(() => {
  const path = getBootUiApiPath() + '/mcp'
  const origin = typeof window !== 'undefined' && window.location ? window.location.origin : ''
  return origin + path
})

function browserIsRemote() {
  if (typeof window === 'undefined' || !window.location) return false
  return !['localhost', '127.0.0.1', '::1', '[::1]'].includes(window.location.hostname)
}

const AUTHORIZATION_VALUE = 'Bearer <BootUI authentication token>'

const clients = [
  {id: 'vscode', label: 'VS Code', file: '.vscode/mcp.json'},
  {id: 'claude', label: 'Claude Code', file: 'a terminal in your project'},
  {id: 'cursor', label: 'Cursor', file: '~/.cursor/mcp.json'},
  {id: 'json', label: 'Other clients', file: '.mcp.json'}
]

const activeClient = ref('vscode')
// The browser's own hostname only proves how *this page* was reached: a published container port is
// reached on localhost while the agent still arrives from a non-loopback source. Seed the switch from
// that guess, then let the user correct it.
const remoteAgent = ref(browserIsRemote())

const serverName = computed(() => status.value?.serverName || 'bootui')

function serverEntry(withType) {
  const server = {}
  if (withType) server.type = status.value?.transport || 'http'
  server.url = endpointUrl.value
  if (remoteAgent.value) server.headers = {Authorization: AUTHORIZATION_VALUE}
  return server
}

const vsCodeConfig = computed(() => JSON.stringify({servers: {[serverName.value]: serverEntry(true)}}, null, 2))

// Cursor keys a remote server on `url` and does not use `type`.
const cursorConfig = computed(() => JSON.stringify({mcpServers: {[serverName.value]: serverEntry(false)}}, null, 2))

const genericConfig = computed(() => JSON.stringify({mcpServers: {[serverName.value]: serverEntry(true)}}, null, 2))

const claudeCommand = computed(() => {
  const transport = status.value?.transport || 'http'
  const command = `claude mcp add --transport ${transport} ${serverName.value} ${endpointUrl.value}`
  if (!remoteAgent.value) return command
  return `${command} \\\n  --header "Authorization: ${AUTHORIZATION_VALUE}"`
})

const snippets = computed(() => ({
  vscode: vsCodeConfig.value,
  claude: claudeCommand.value,
  cursor: cursorConfig.value,
  json: genericConfig.value
}))

const activeSnippet = computed(() => snippets.value[activeClient.value])

function clientTabId(id) {
  return `mcp-client-${id}-tab`
}

function clientPanelId(id) {
  return `mcp-client-${id}-panel`
}

function onClientTabKeydown(event, currentId) {
  const currentIndex = clients.findIndex((client) => client.id === currentId)
  let targetIndex = null
  if (event.key === 'ArrowRight') targetIndex = (currentIndex + 1) % clients.length
  else if (event.key === 'ArrowLeft') targetIndex = (currentIndex - 1 + clients.length) % clients.length
  else if (event.key === 'Home') targetIndex = 0
  else if (event.key === 'End') targetIndex = clients.length - 1
  if (targetIndex === null) return
  event.preventDefault()
  const target = clients[targetIndex].id
  activeClient.value = target
  document.getElementById(clientTabId(target))?.focus()
}

async function fetchStatus() {
  try {
    status.value = await getJson('api/mcp-server')
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
      flash(result.message || result.error || `HTTP ${res.status}`, 'warning')
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

const {autoRefresh, loading, load} = useAutoRefresh(fetchStatus, {enabled: manifestAvailable})
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

    <UnavailableState v-if="!manifestAvailable" icon="bi-plug" :message="manifestUnavailableReason" />

    <PanelSkeleton v-else-if="loading && !status" />

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
              <h3 class="h5 fw-bold mb-1">
                MCP server is
                <span :class="enabled ? 'text-success' : 'text-secondary'">{{ enabled ? 'enabled' : 'disabled' }}</span>
              </h3>
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
                <dt class="col-5 text-muted fw-normal">URL</dt>
                <dd class="col-7 text-break">
                  <code>{{ endpointUrl }}</code>
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

      <!-- Client configuration -->
      <div class="card mb-4">
        <div class="card-body p-4">
          <div class="d-flex align-items-center justify-content-between gap-2 mb-3">
            <h3 class="h6 fw-bold mb-0"><i class="bi bi-filetype-json me-2"></i>Client configuration</h3>
            <button
              type="button"
              class="btn btn-sm"
              :class="copiedKey === 'mcp-config' ? 'btn-success' : 'btn-outline-secondary'"
              :title="copiedKey === 'mcp-config' ? 'Copied!' : 'Copy configuration'"
              @click="copyToClipboard(activeSnippet, 'mcp-config')"
            >
              <i :class="['bi', copiedKey === 'mcp-config' ? 'bi-check-lg' : 'bi-clipboard', 'me-1']"></i>
              {{ copiedKey === 'mcp-config' ? 'Copied!' : 'Copy' }}
            </button>
          </div>

          <ul class="nav nav-tabs mb-3" role="tablist" aria-label="MCP client">
            <li v-for="client in clients" :key="client.id" class="nav-item">
              <button
                :id="clientTabId(client.id)"
                :aria-controls="clientPanelId(client.id)"
                :aria-selected="activeClient === client.id"
                :class="{active: activeClient === client.id}"
                :tabindex="activeClient === client.id ? 0 : -1"
                class="nav-link"
                role="tab"
                type="button"
                @click="activeClient = client.id"
                @keydown="onClientTabKeydown($event, client.id)"
              >
                {{ client.label }}
              </button>
            </li>
          </ul>

          <div
            v-for="client in clients"
            v-show="activeClient === client.id"
            :id="clientPanelId(client.id)"
            :key="client.id"
            :aria-labelledby="clientTabId(client.id)"
            role="tabpanel"
            tabindex="0"
          >
            <p class="text-muted small mb-2">
              <template v-if="client.id === 'claude'"
                >Run this in your project directory to register this running app with Claude Code.</template
              >
              <template v-else-if="client.id === 'json'"
                >The <code>mcpServers</code> shape used by Claude Code's <code>.mcp.json</code> and most other MCP
                clients.</template
              >
              <template v-else
                >Paste this into <code>{{ client.file }}</code
                >.</template
              >
            </p>
            <pre
              class="config-block bg-light border rounded p-3 mb-0 small"
            ><code>{{ snippets[client.id] }}</code></pre>
          </div>

          <div class="form-check remote-agent-check mt-3">
            <input id="mcp-remote-agent" v-model="remoteAgent" class="form-check-input" type="checkbox" />
            <label class="form-check-label small" for="mcp-remote-agent">
              Agent connects from another host or container
            </label>
          </div>
          <p class="text-muted small mb-0 mt-2">
            A loopback agent needs no credentials. An agent that reaches this app from anywhere else — a published
            container port, or any host allowed by <code>bootui.allow-non-localhost</code>,
            <code>bootui.trusted-proxies</code>, or <code>bootui.trust-container-gateway</code> — must send BootUI's
            token in the <code>Authorization</code> header, or every call answers <code>401</code>. BootUI generates
            that token at each start and logs it once; set <code>bootui.authentication.token</code> for a value that
            survives a restart.
          </p>
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
            <strong>Action tools</strong> run explicit scans or bounded runtime controls and are refused when the
            backing panel is read-only. <strong>Read tools</strong> return sanitized runtime data. Tools are advertised
            when their backing capability is available; disabled-panel tools stay listed but are refused when called.
          </p>

          <div v-if="actionTools.length" class="mb-3">
            <h4 class="fs-6 text-muted fw-semibold mb-2">Action tools</h4>
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
            <h4 class="fs-6 text-muted fw-semibold mb-2">Read tools</h4>
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
  border-radius: var(--bootui-radius-lg);
  display: inline-flex;
  font-size: var(--bootui-icon-size);
  height: 3rem;
  justify-content: center;
  width: 3rem;
}

.mcp-switch .form-check-input {
  cursor: pointer;
  height: 1.75rem;
  width: 3.25rem;
}

.config-block {
  overflow-x: auto;
  white-space: pre;
}

/* The narrow-viewport rules grow every checkbox to a 44px touch target, which pushes a label this long
   onto its own line. Lay the row out as flex so the label keeps its place beside the box and wraps. */
.remote-agent-check {
  align-items: center;
  display: flex;
  gap: 0.5rem;
  padding-left: 0;
}

.remote-agent-check .form-check-input {
  flex: 0 0 auto;
  float: none;
  margin-left: 0;
}
</style>
