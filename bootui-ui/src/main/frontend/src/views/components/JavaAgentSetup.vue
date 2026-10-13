<script setup>
import {computed, ref, watch} from 'vue'
import {useCopyToClipboard} from '../../utils/useCopyToClipboard.js'
import PanelTabs from './PanelTabs.vue'

// The Java Agent panel's setup card: where the agent jar is, and one copyable snippet per way of starting the
// application. Without an attached agent it opens the panel and walks through attaching it; otherwise it closes the
// panel as reference.
const props = defineProps({
  // The report's JavaAgentSetupDto: jarPath, jarFound, buildTool, snippets.
  setup: {type: Object, default: null},
  attached: {type: Boolean, default: true}
})

const activeIds = ref({})
const copyBlocked = ref(false)
const {copiedKey, copyToClipboard} = useCopyToClipboard(2000)

// The download snippets get short tab names: the group heading already says what they download.
const DOWNLOAD_LABELS = {
  'curl-download': 'curl',
  'maven-download': 'Maven',
  'gradle-kotlin-download': 'Gradle (Kotlin)',
  'gradle-groovy-download': 'Gradle (Groovy)'
}

const snippets = computed(() => props.setup?.snippets ?? [])
// Two choices in sequence, so two short tab strips instead of one long one: get the jar, then start with it.
const groups = computed(() => {
  const download = snippets.value
    .filter((snippet) => snippet.id.endsWith('-download'))
    .map((snippet) => ({...snippet, label: DOWNLOAD_LABELS[snippet.id] ?? snippet.label}))
  const attach = snippets.value.filter((snippet) => !snippet.id.endsWith('-download'))
  return [
    {
      key: 'download',
      title: 'Get the jar',
      hint: 'Maven and Gradle use your own mirror and proxy settings, which helps behind a corporate proxy.',
      label: 'Java agent download snippets',
      snippets: download
    },
    {
      key: 'attach',
      title: 'Start the application with it',
      hint: 'Pick the tab for your build tool or IDE.',
      label: 'Java agent setup snippets',
      snippets: attach
    }
  ].filter((group) => group.snippets.length)
})

function activeSnippet(group) {
  return group.snippets.find((snippet) => snippet.id === activeIds.value[group.key]) ?? group.snippets[0]
}

function copyKey(snippet) {
  return snippet ? `java-agent-${snippet.id}` : null
}

async function copySnippet(snippet) {
  copyBlocked.value = false
  const copied = await copyToClipboard(snippet.text, copyKey(snippet))
  copyBlocked.value = !copied
}

function selectSnippet(group, id) {
  activeIds.value = {...activeIds.value, [group.key]: id}
  copyBlocked.value = false
}

function languageLabel(language) {
  return {xml: 'XML', kotlin: 'Kotlin', groovy: 'Groovy', shell: 'Shell', text: 'Text'}[language] ?? language ?? 'Text'
}
</script>

<template>
  <section class="card java-agent-setup" aria-labelledby="java-agent-setup-title">
    <div class="card-body p-4">
      <h3 id="java-agent-setup-title" class="h6 fw-bold mb-3">
        <i class="bi bi-wrench-adjustable-circle me-2" aria-hidden="true"></i
        >{{ attached ? 'Setup snippets' : 'Attach the agent' }}
      </h3>
      <dl class="row small mb-4">
        <dt class="col-sm-3 text-muted fw-normal">Agent jar</dt>
        <dd class="col-sm-9">
          <code class="bootui-break-anywhere">{{ setup?.jarPath ?? '—' }}</code>
          <span :class="['badge ms-2', setup?.jarFound ? 'text-bg-success' : 'text-bg-secondary']">
            {{ setup?.jarFound ? 'Found' : 'Not found' }}
          </span>
        </dd>
        <dt class="col-sm-3 text-muted fw-normal">Build tool</dt>
        <dd class="col-sm-9 mb-0">{{ setup?.buildTool ?? 'UNKNOWN' }}</dd>
      </dl>

      <ol v-if="groups.length" class="java-agent-setup__groups list-unstyled mb-0">
        <li v-for="(group, index) in groups" :key="group.key" class="java-agent-setup__group">
          <div class="java-agent-setup__heading">
            <span v-if="groups.length > 1" class="java-agent-setup__step" aria-hidden="true">{{ index + 1 }}</span>
            <div class="min-width-0">
              <h4 class="h6 fw-bold mb-0">{{ group.title }}</h4>
              <p class="text-muted small mb-0">{{ group.hint }}</p>
            </div>
          </div>
          <PanelTabs
            class="mb-3"
            :tabs="group.snippets"
            :selected="activeSnippet(group).id"
            id-prefix="java-agent"
            :label="group.label"
            @select="(id) => selectSnippet(group, id)"
          />
          <div
            v-for="snippet in group.snippets"
            v-show="snippet.id === activeSnippet(group).id"
            :id="`java-agent-panel-${snippet.id}`"
            :key="`${snippet.id}-panel`"
            role="tabpanel"
            class="java-agent-snippet-panel"
            :aria-labelledby="`java-agent-tab-${snippet.id}`"
            tabindex="0"
          >
            <div class="d-flex justify-content-between align-items-center gap-2 mb-2">
              <span class="badge text-bg-secondary">{{ languageLabel(snippet.language) }}</span>
              <button
                type="button"
                class="btn btn-sm"
                :class="copiedKey === copyKey(snippet) ? 'btn-success' : 'btn-outline-secondary'"
                :title="copiedKey === copyKey(snippet) ? 'Copied!' : `Copy ${snippet.label} snippet`"
                @click="copySnippet(snippet)"
              >
                <i
                  :class="['bi', copiedKey === copyKey(snippet) ? 'bi-check-lg' : 'bi-clipboard', 'me-1']"
                  aria-hidden="true"
                ></i>
                {{ copiedKey === copyKey(snippet) ? 'Copied!' : 'Copy' }}
              </button>
              <span class="visually-hidden" aria-live="polite">
                {{ copiedKey === copyKey(snippet) ? `${snippet.label} snippet copied` : '' }}
              </span>
            </div>
            <pre class="java-agent-code rounded p-3 mb-0"><code>{{ snippet.text }}</code></pre>
          </div>
        </li>
      </ol>
      <p v-else class="text-muted small mb-0">No setup snippets were returned for this runtime.</p>
      <p v-if="!attached && groups.length" class="small text-muted mt-4 mb-0" data-testid="java-agent-setup-restart">
        Then restart the application. BootUI claims the agent and this panel shows <strong>Armed</strong>; if it
        doesn’t, the status above says why.
      </p>
      <p v-if="copyBlocked" class="alert alert-warning small mt-3 mb-0">
        The browser blocked clipboard access, so nothing was copied. Select the snippet text and copy it manually.
      </p>
    </div>
  </section>
</template>

<style scoped>
.java-agent-setup__groups {
  display: grid;
  gap: 1.75rem;
}

.java-agent-setup__group {
  min-width: 0;
}

.java-agent-setup__heading {
  display: flex;
  align-items: flex-start;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
}

.java-agent-setup__step {
  flex: none;
  display: inline-grid;
  place-items: center;
  width: 1.6rem;
  height: 1.6rem;
  border-radius: 50%;
  border: 1px solid var(--bs-border-color);
  font-size: 0.8rem;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.java-agent-snippet-panel:focus-visible {
  outline: 0.18rem solid color-mix(in srgb, var(--bootui-blue) 35%, transparent);
  outline-offset: 0.2rem;
}

.java-agent-code {
  background: var(--bootui-code-pane-bg);
  color: var(--bootui-code-pane-text);
  font-size: 0.85rem;
  max-height: 26rem;
  overflow: auto;
  white-space: pre;
}

.min-width-0 {
  min-width: 0;
}
</style>
