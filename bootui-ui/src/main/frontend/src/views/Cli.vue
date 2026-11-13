<script setup>
import {getJson} from '../api.js'
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
import UnavailableState from './components/UnavailableState.vue'

const DEFAULT_API_PATH = '/bootui/api'

const props = defineProps(panelProps)
const {manifestAvailable, manifestUnavailableReason} = usePanelState(props)
const status = ref(null)
const lastFetched = ref(null)
const {message: banner, flash, clear} = useFlashMessage(8000)
const {copiedKey, copyToClipboard} = useCopyToClipboard(2000)

const enabled = computed(() => status.value?.enabled === true)
const actionTools = computed(() => (status.value?.tools ?? []).filter((tool) => tool.action))
const readTools = computed(() => (status.value?.tools ?? []).filter((tool) => !tool.action))

const apiPath = computed(() => getBootUiApiPath())

const origin = computed(() =>
  typeof window !== 'undefined' && window.location ? window.location.origin : 'http://localhost:8080'
)

const endpointUrl = computed(() => origin.value + apiPath.value + '/cli')

// The CLI defaults to /bootui/api, so only spell --api-path out when this app moved it.
const commandOptions = computed(() => {
  const options = ['--url ' + origin.value]
  if (apiPath.value !== DEFAULT_API_PATH) options.push('--api-path ' + apiPath.value)
  return options.join(' ')
})

const installCommand = computed(() => `jbang app install bootui@jdubois/boot-ui`)

const exampleCommand = computed(() => `bootui ${commandOptions.value} tools`)

const jbangSnippet = computed(() => `${installCommand.value}\n${exampleCommand.value}`)

// The documentation site publishes the installers, so the version they fetch follows
// releases without this panel having to know anything about it.
const INSTALL_SCRIPT_URL = 'https://www.julien-dubois.com/boot-ui/install.sh'
const INSTALL_POWERSHELL_URL = 'https://www.julien-dubois.com/boot-ui/install.ps1'

const scriptSnippet = computed(() => `curl -fsSL ${INSTALL_SCRIPT_URL} | sh\n${exampleCommand.value}`)

// Only a released version resolves on Maven Central; a development build has no published jar to name.
const releaseVersion = computed(() => {
  const version = status.value?.serverVersion ?? ''
  return /^\d+\.\d+\.\d+([.-][0-9A-Za-z.-]+)?$/.test(version) && !version.endsWith('-SNAPSHOT') ? version : null
})

const jarVersion = computed(() => releaseVersion.value ?? '<version>')

const javaSnippet = computed(
  () =>
    `VERSION=${jarVersion.value}\n` +
    'BASE=https://repo1.maven.org/maven2/com/julien-dubois/bootui/bootui-cli\n' +
    'curl -fLO "${BASE}/${VERSION}/bootui-cli-${VERSION}-all.jar"\n' +
    `java -jar "bootui-cli-\${VERSION}-all.jar" ${commandOptions.value} tools`
)

const callCount = computed(() => status.value?.callCount ?? 0)

// The CLI's own projection rule: an `id` is a required positional, everything else is a flag.
function argumentHint(tool) {
  return (tool.arguments ?? [])
    .map((name) => {
      if (name === 'id') return '<id>'
      if (name === 'scanId') return '--scan-id <scan-id>'
      return tool.schema === 'RULE_VIOLATIONS' ? `[--${name} <${name}>]` : `--${name}`
    })
    .join(' ')
}

const meanLatency = computed(() => {
  const calls = callCount.value
  if (!calls) return null
  return Math.round((status.value?.totalLatencyMillis ?? 0) / calls)
})

async function fetchStatus() {
  try {
    status.value = await getJson('api/cli')
    lastFetched.value = Date.now()
  } catch (e) {
    flash(formatLoadError(e, 'Could not load command-line endpoint status'), 'danger')
  }
}

const {autoRefresh, loading, load} = useAutoRefresh(fetchStatus, {enabled: manifestAvailable})
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-terminal-fill"
      title="Command Line"
      subtitle="Ask this running application one diagnostic question from a terminal or a CI job, with no browser and no MCP client."
      :loading="loading"
      :last-fetched="lastFetched"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <FlashBanner :message="banner" with-icon @dismiss="clear" />

    <UnavailableState v-if="!manifestAvailable" icon="bi-terminal-fill" :message="manifestUnavailableReason" />

    <PanelSkeleton v-else-if="loading && !status" />

    <template v-else-if="status">
      <!-- Status card -->
      <div class="card mb-4" :class="enabled ? 'border-success-subtle' : 'border-secondary-subtle'">
        <div class="card-body p-4 d-flex flex-wrap align-items-center gap-3">
          <div
            class="action-icon"
            :class="enabled ? 'bg-success-subtle text-success' : 'bg-secondary-subtle text-secondary'"
          >
            <i class="bi bi-terminal-fill"></i>
          </div>
          <div>
            <h3 class="h5 fw-bold mb-1">
              Command-line access is
              <span :class="enabled ? 'text-success' : 'text-secondary'">{{ enabled ? 'enabled' : 'disabled' }}</span>
            </h3>
            <p class="text-muted small mb-0">
              <template v-if="enabled">
                Set <code>bootui.cli.enabled=false</code> to refuse command-line access. This panel reports the
                endpoint; it does not switch it, so restarting a CI job never depends on a browser.
              </template>
              <template v-else>
                <code>bootui.cli.enabled=false</code> is set, so <code>{{ endpointUrl }}</code> answers
                <code>503</code> and advertises no tools.
              </template>
            </p>
          </div>
        </div>
      </div>

      <div class="row g-4 mb-4">
        <div class="col-lg-7">
          <div class="card h-100">
            <div class="card-body p-4">
              <h3 class="h6 fw-bold mb-2"><i class="bi bi-info-circle me-2"></i>What this endpoint does</h3>
              <p class="text-muted small mb-2">
                The <code>bootui</code> CLI is the same tool registry the
                <RouterLink :to="{name: 'mcp-server'}">MCP server</RouterLink> exposes to AI agents, projected onto
                subcommands. The command table is generated from that registry at build time, so the CLI cannot offer a
                diagnostic the MCP server does not, nor lack one it does.
              </p>
              <ul class="text-muted small mb-0 ps-3">
                <li>
                  <strong>Same policy, different transport.</strong> Secret masking and the per-panel enable and
                  read-only toggles apply exactly as they do in this UI.
                </li>
                <li>
                  <strong>Local only.</strong> The endpoint sits behind the same loopback, Host allow-list, and
                  cross-site write defenses as the rest of BootUI.
                </li>
                <li>
                  <strong>Independent of MCP.</strong> <code>bootui.mcp.enabled</code> is not required; these are two
                  separate switches.
                </li>
              </ul>
            </div>
          </div>
        </div>
        <div class="col-lg-5">
          <div class="card h-100">
            <div class="card-body p-4">
              <h3 class="h6 fw-bold mb-3"><i class="bi bi-hdd-network me-2"></i>Endpoint</h3>
              <dl class="row small mb-0">
                <dt class="col-5 text-muted fw-normal">URL</dt>
                <dd class="col-7 text-break">
                  <code>{{ endpointUrl }}</code>
                </dd>
                <dt class="col-5 text-muted fw-normal">Server</dt>
                <dd class="col-7">
                  {{ status.serverName }} <span class="text-muted">{{ status.serverVersion }}</span>
                </dd>
                <dt class="col-5 text-muted fw-normal">Commands</dt>
                <dd class="col-7">{{ status.toolCount }}</dd>
                <dt class="col-5 text-muted fw-normal">Max results</dt>
                <dd class="col-7">{{ status.maxResults }}</dd>
              </dl>
            </div>
          </div>
        </div>
      </div>

      <!-- Getting started -->
      <div class="card mb-4">
        <div class="card-body p-4">
          <h3 class="h6 fw-bold mb-2"><i class="bi bi-download me-2"></i>Talk to this application</h3>
          <p class="text-muted small mb-3">
            The CLI is one runnable jar that needs a JDK 17 or later. Piped output is this application's JSON verbatim,
            so it parses with <code>jq</code>. Use <code>bootui instances</code> and
            <code>bootui --app &lt;name&gt;</code> to discover and target local dev instances by application name, or
            <code>bootui init --vscode</code> to configure your IDE's MCP client.
          </p>

          <div class="d-flex align-items-center justify-content-between gap-2 mb-2">
            <h4 class="small fw-semibold mb-0">Install it in one command, on Linux and macOS</h4>
            <button
              type="button"
              class="btn btn-sm"
              :class="copiedKey === 'cli-script' ? 'btn-success' : 'btn-outline-secondary'"
              :title="copiedKey === 'cli-script' ? 'Copied!' : 'Copy command'"
              @click="copyToClipboard(scriptSnippet, 'cli-script')"
            >
              <i :class="['bi', copiedKey === 'cli-script' ? 'bi-check-lg' : 'bi-clipboard', 'me-1']"></i>
              {{ copiedKey === 'cli-script' ? 'Copied!' : 'Copy' }}
            </button>
          </div>
          <pre class="config-block bg-light border rounded p-3 mb-2 small"><code>{{ scriptSnippet }}</code></pre>
          <p class="text-muted small mb-4">
            It checks the download against the checksum published beside it and needs no administrator rights. On
            Windows, run <code>irm {{ INSTALL_POWERSHELL_URL }} | iex</code> in PowerShell.
          </p>

          <div class="d-flex align-items-center justify-content-between gap-2 mb-2">
            <h4 class="small fw-semibold mb-0">
              With <a href="https://www.jbang.dev" target="_blank" rel="noopener">JBang</a>, which downloads it for you
            </h4>
            <button
              type="button"
              class="btn btn-sm"
              :class="copiedKey === 'cli-command' ? 'btn-success' : 'btn-outline-secondary'"
              :title="copiedKey === 'cli-command' ? 'Copied!' : 'Copy command'"
              @click="copyToClipboard(jbangSnippet, 'cli-command')"
            >
              <i :class="['bi', copiedKey === 'cli-command' ? 'bi-check-lg' : 'bi-clipboard', 'me-1']"></i>
              {{ copiedKey === 'cli-command' ? 'Copied!' : 'Copy' }}
            </button>
          </div>
          <pre class="config-block bg-light border rounded p-3 mb-4 small"><code>{{ installCommand }}
{{ exampleCommand }}</code></pre>

          <div class="d-flex align-items-center justify-content-between gap-2 mb-2">
            <h4 class="small fw-semibold mb-0">Or with plain Java, no JBang</h4>
            <button
              type="button"
              class="btn btn-sm"
              :class="copiedKey === 'cli-java' ? 'btn-success' : 'btn-outline-secondary'"
              :title="copiedKey === 'cli-java' ? 'Copied!' : 'Copy command'"
              @click="copyToClipboard(javaSnippet, 'cli-java')"
            >
              <i :class="['bi', copiedKey === 'cli-java' ? 'bi-check-lg' : 'bi-clipboard', 'me-1']"></i>
              {{ copiedKey === 'cli-java' ? 'Copied!' : 'Copy' }}
            </button>
          </div>
          <pre class="config-block bg-light border rounded p-3 mb-2 small"><code>{{ javaSnippet }}</code></pre>
          <p v-if="!releaseVersion" class="text-muted small mb-0">
            This application reports a development build, so replace <code>&lt;version&gt;</code> with the released
            BootUI version you want.
          </p>
          <p v-else class="text-muted small mb-0">
            That is the {{ releaseVersion }} jar, matching this application. An alias keeps it short:
            <code>alias bootui='java -jar ~/tools/bootui-cli-all.jar'</code>.
          </p>
        </div>
      </div>

      <!-- Counters -->
      <div class="card mb-4">
        <div class="card-body p-4">
          <h3 class="h6 fw-bold mb-3"><i class="bi bi-speedometer2 me-2"></i>Command-line activity</h3>
          <p class="text-muted small">
            Counted separately from the MCP server's, so this panel reports what terminals and CI jobs did rather than
            what agents did.
          </p>
          <div class="row g-3 text-center">
            <div class="col-6 col-lg-3">
              <div class="stat-tile border rounded p-3 h-100">
                <div class="fs-4 fw-bold">{{ callCount }}</div>
                <div class="text-muted small">Calls</div>
              </div>
            </div>
            <div class="col-6 col-lg-3">
              <div class="stat-tile border rounded p-3 h-100">
                <div class="fs-4 fw-bold">
                  <template v-if="meanLatency !== null">{{ meanLatency }}<span class="fs-6"> ms</span></template>
                  <template v-else>—</template>
                </div>
                <div class="text-muted small">Mean latency</div>
              </div>
            </div>
            <div class="col-6 col-lg-3">
              <div class="stat-tile border rounded p-3 h-100">
                <div class="fs-4 fw-bold" :class="status.capacityRefusals ? 'text-warning' : ''">
                  {{ status.capacityRefusals }}
                </div>
                <div class="text-muted small">Capacity refusals</div>
              </div>
            </div>
            <div class="col-6 col-lg-3">
              <div class="stat-tile border rounded p-3 h-100">
                <div class="fs-4 fw-bold" :class="status.timeouts ? 'text-warning' : ''">{{ status.timeouts }}</div>
                <div class="text-muted small">Timeouts</div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- Commands -->
      <div class="card">
        <div class="card-body p-4">
          <div class="d-flex align-items-center justify-content-between mb-3">
            <h3 class="h6 fw-bold mb-0"><i class="bi bi-tools me-2"></i>Commands available ({{ status.toolCount }})</h3>
            <span v-if="!enabled" class="badge text-bg-secondary">Endpoint disabled — commands are not reachable</span>
          </div>

          <p class="text-muted small">
            <strong>Action commands</strong> run explicit scans or bounded runtime controls and exit <code>2</code> when
            the backing panel is read-only. <strong>Read commands</strong> return sanitized runtime data. A command
            whose panel is disabled stays listed and exits <code>2</code> when called, so a CI job can tell a policy
            refusal apart from a failure.
          </p>

          <div v-if="actionTools.length" class="mb-3">
            <h4 class="fs-6 text-muted fw-semibold mb-2">Action commands</h4>
            <ul class="list-group list-group-flush">
              <li v-for="tool in actionTools" :key="tool.name" class="list-group-item px-0">
                <div class="d-flex align-items-center justify-content-between gap-2 flex-wrap">
                  <span class="d-flex align-items-baseline gap-2 flex-wrap">
                    <code class="text-primary">bootui {{ tool.command }}</code>
                    <code v-if="argumentHint(tool)" class="text-muted small">{{ argumentHint(tool) }}</code>
                  </span>
                  <span class="d-flex gap-1">
                    <span class="badge text-bg-light border">{{ tool.panel }}</span>
                    <span v-if="!tool.panelEnabled" class="badge text-bg-secondary">panel disabled</span>
                    <span v-else-if="tool.panelReadOnly" class="badge text-bg-warning">read-only</span>
                  </span>
                </div>
                <div class="text-muted small mt-1">{{ tool.description }}</div>
                <div class="text-muted small">
                  MCP tool <code class="text-muted">{{ tool.name }}</code>
                </div>
              </li>
            </ul>
          </div>

          <div v-if="readTools.length">
            <h4 class="fs-6 text-muted fw-semibold mb-2">Read commands</h4>
            <ul class="list-group list-group-flush">
              <li v-for="tool in readTools" :key="tool.name" class="list-group-item px-0">
                <div class="d-flex align-items-center justify-content-between gap-2 flex-wrap">
                  <span class="d-flex align-items-baseline gap-2 flex-wrap">
                    <code class="text-primary">bootui {{ tool.command }}</code>
                    <code v-if="argumentHint(tool)" class="text-muted small">{{ argumentHint(tool) }}</code>
                  </span>
                  <span class="d-flex gap-1">
                    <span class="badge text-bg-light border">{{ tool.panel }}</span>
                    <span v-if="!tool.panelEnabled" class="badge text-bg-secondary">panel disabled</span>
                  </span>
                </div>
                <div class="text-muted small mt-1">{{ tool.description }}</div>
                <div class="text-muted small">
                  MCP tool <code class="text-muted">{{ tool.name }}</code>
                </div>
              </li>
            </ul>
          </div>

          <p v-if="!status.toolCount" class="text-muted small mb-0">No commands are currently available.</p>
        </div>
      </div>
    </template>

    <UnavailableState
      v-else
      message="Command-line endpoint status is unavailable. The app may be unreachable — retry or refresh this panel."
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

.config-block {
  overflow-x: auto;
  white-space: pre;
}
</style>
