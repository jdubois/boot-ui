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

const activeSnippetId = ref(null)
const copyBlocked = ref(false)
const {copiedKey, copyToClipboard} = useCopyToClipboard(2000)

const snippets = computed(() => props.setup?.snippets ?? [])
const activeSnippet = computed(() => snippets.value.find((snippet) => snippet.id === activeSnippetId.value) ?? null)
const copiedActiveSnippet = computed(() => copiedKey.value === copyKey(activeSnippet.value))
const hasDownload = computed(() => snippets.value.some((snippet) => snippet.id === 'maven-download'))

watch(
  snippets,
  (items) => {
    if (!items.length) {
      activeSnippetId.value = null
      return
    }
    if (!items.some((snippet) => snippet.id === activeSnippetId.value)) {
      activeSnippetId.value = items[0].id
    }
  },
  {immediate: true}
)

function copyKey(snippet) {
  return snippet ? `java-agent-${snippet.id}` : null
}

async function copyActiveSnippet() {
  copyBlocked.value = false
  if (!activeSnippet.value) return
  const copied = await copyToClipboard(activeSnippet.value.text, copyKey(activeSnippet.value))
  copyBlocked.value = !copied
}

function selectSnippet(id) {
  activeSnippetId.value = id
  copyBlocked.value = false
}

function languageLabel(language) {
  return {xml: 'XML', kotlin: 'Kotlin', groovy: 'Groovy', shell: 'Shell', text: 'Text'}[language] ?? language ?? 'Text'
}
</script>

<template>
  <section class="card java-agent-setup" aria-labelledby="java-agent-setup-title">
    <div class="card-body p-4">
      <div class="d-flex flex-wrap justify-content-between align-items-start gap-3 mb-3">
        <div class="min-width-0">
          <h3 id="java-agent-setup-title" class="h6 fw-bold mb-2">
            <i class="bi bi-wrench-adjustable-circle me-2" aria-hidden="true"></i
            >{{ attached ? 'Setup snippets' : 'Attach the agent' }}
          </h3>
          <ol v-if="!attached" class="java-agent-setup__steps small mb-3" data-testid="java-agent-setup-steps">
            <li v-if="hasDownload">
              The jar is not at the path below yet: download it with the <strong>Download the agent</strong> snippet.
            </li>
            <li>
              Add the <code>-javaagent</code> option where you start the application: pick the tab for your build tool
              or IDE, and copy it.
            </li>
            <li>
              Restart the application. BootUI claims the agent and this panel shows <strong>Armed</strong>; if it
              doesn’t, the status above says why.
            </li>
          </ol>
          <dl class="row small mb-0">
            <dt class="col-sm-4 text-muted fw-normal">Agent jar</dt>
            <dd class="col-sm-8">
              <code class="bootui-break-anywhere">{{ setup?.jarPath ?? '—' }}</code>
            </dd>
            <dt class="col-sm-4 text-muted fw-normal">Jar found</dt>
            <dd class="col-sm-8">
              <span :class="['badge', setup?.jarFound ? 'text-bg-success' : 'text-bg-secondary']">
                {{ setup?.jarFound ? 'Found' : 'Not found' }}
              </span>
            </dd>
            <dt class="col-sm-4 text-muted fw-normal">Build tool</dt>
            <dd class="col-sm-8">{{ setup?.buildTool ?? 'UNKNOWN' }}</dd>
          </dl>
        </div>
        <button
          type="button"
          class="btn btn-sm"
          :class="copiedActiveSnippet ? 'btn-success' : 'btn-outline-secondary'"
          :disabled="!activeSnippet"
          :title="copiedActiveSnippet ? 'Copied!' : 'Copy snippet'"
          @click="copyActiveSnippet"
        >
          <i :class="['bi', copiedActiveSnippet ? 'bi-check-lg' : 'bi-clipboard', 'me-1']" aria-hidden="true"></i>
          {{ copiedActiveSnippet ? 'Copied!' : 'Copy' }}
        </button>
        <span class="visually-hidden" aria-live="polite">
          {{ copiedActiveSnippet && activeSnippet ? `${activeSnippet.label} snippet copied` : '' }}
        </span>
      </div>

      <div v-if="snippets.length" class="java-agent-snippets">
        <PanelTabs
          class="mb-3"
          :tabs="snippets"
          :selected="activeSnippetId"
          id-prefix="java-agent"
          label="Java agent setup snippets"
          @select="selectSnippet"
        />
        <div
          v-for="snippet in snippets"
          v-show="snippet.id === activeSnippetId"
          :id="`java-agent-panel-${snippet.id}`"
          :key="`${snippet.id}-panel`"
          role="tabpanel"
          class="java-agent-snippet-panel"
          :aria-labelledby="`java-agent-tab-${snippet.id}`"
          tabindex="0"
        >
          <div class="d-flex flex-wrap justify-content-between align-items-center gap-2 mb-2">
            <h4 class="h6 mb-0">{{ snippet.label }}</h4>
            <span class="badge text-bg-secondary">{{ languageLabel(snippet.language) }}</span>
          </div>
          <pre class="java-agent-code rounded p-3 mb-0"><code>{{ snippet.text }}</code></pre>
        </div>
      </div>
      <p v-else class="text-muted small mb-0">No setup snippets were returned for this runtime.</p>
      <p v-if="copyBlocked" class="alert alert-warning small mt-3 mb-0">
        The browser blocked clipboard access, so nothing was copied. Select the snippet text and copy it manually.
      </p>
    </div>
  </section>
</template>

<style scoped>
.java-agent-setup__steps {
  max-width: 72ch;
  padding-inline-start: 1.25rem;
}

.java-agent-setup__steps li + li {
  margin-top: 0.25rem;
}

.java-agent-snippet-panel:focus-visible {
  outline: 0.18rem solid color-mix(in srgb, var(--bootui-blue) 35%, transparent);
  outline-offset: 0.2rem;
}

.java-agent-code {
  background: var(--bs-dark);
  color: var(--bs-light);
  font-size: 0.85rem;
  max-height: 26rem;
  overflow: auto;
  white-space: pre;
}

.min-width-0 {
  min-width: 0;
}
</style>
