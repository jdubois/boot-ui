<script setup>
import {computed} from 'vue'
import {useCopyToClipboard} from '../../utils/useCopyToClipboard'

const props = defineProps({
  platform: {type: String, default: 'spring-boot'}
})

const {copiedKey, copyToClipboard} = useCopyToClipboard()

const isQuarkus = computed(() => props.platform === 'quarkus')

const dependency = `<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j-open-ai-official</artifactId>
</dependency>`

const instrumentation = `<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j-observability-opentelemetry</artifactId>
</dependency>`

const otlpEndpoint = `/bootui/api/otlp/v1/traces`

const quarkusDependency = `<dependency>
  <groupId>io.quarkiverse.langchain4j</groupId>
  <artifactId>quarkus-langchain4j-openai</artifactId>
</dependency>`

const quarkusOpenTelemetry = `<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-opentelemetry</artifactId>
</dependency>`
</script>

<template>
  <div v-if="isQuarkus" class="card mb-3 h-100">
    <div class="card-header d-flex align-items-center gap-2">
      <i class="bi bi-link-45deg text-primary"></i>
      <span class="fw-semibold">Set up LangChain4j</span>
    </div>
    <div class="card-body">
      <p class="text-muted small mb-3">
        Quarkus LangChain4j integrates with OpenTelemetry. Add <code>quarkus-opentelemetry</code> and the BootUI
        extension captures the <code>gen_ai.*</code> spans it emits in-process — no exporter or collector to configure.
      </p>

      <ol class="ps-3 mb-0">
        <li class="mb-3">
          <div class="fw-semibold small mb-1">1. Add a Quarkus LangChain4j model extension</div>
          <div class="text-muted small mb-1">
            Pick the extension for your provider (OpenAI shown here; Ollama, Anthropic, … are also available). The
            version is managed by the Quarkus LangChain4j BOM.
          </div>
          <div class="d-flex align-items-start gap-1">
            <pre
              class="bg-light px-2 py-1 rounded small mb-0 flex-grow-1 text-break"
            ><code>{{ quarkusDependency }}</code></pre>
            <button
              :class="copiedKey === 'lc4j-q-dep' ? 'btn-success' : 'btn-outline-secondary'"
              :title="copiedKey === 'lc4j-q-dep' ? 'Copied!' : 'Copy'"
              class="btn btn-sm"
              @click="copyToClipboard(quarkusDependency, 'lc4j-q-dep')"
            >
              <i :class="copiedKey === 'lc4j-q-dep' ? 'bi-check-lg' : 'bi-clipboard'" class="bi"></i>
            </button>
          </div>
        </li>
        <li class="mb-3">
          <div class="fw-semibold small mb-1">2. Add OpenTelemetry</div>
          <div class="text-muted small mb-1">
            <code>quarkus-langchain4j</code> emits <code>gen_ai.*</code> spans through
            <code>quarkus-opentelemetry</code>, following the OpenTelemetry GenAI semantic conventions — the same spans
            BootUI reads.
          </div>
          <div class="d-flex align-items-start gap-1">
            <pre
              class="bg-light px-2 py-1 rounded small mb-0 flex-grow-1 text-break"
            ><code>{{ quarkusOpenTelemetry }}</code></pre>
            <button
              :class="copiedKey === 'lc4j-q-otel' ? 'btn-success' : 'btn-outline-secondary'"
              :title="copiedKey === 'lc4j-q-otel' ? 'Copied!' : 'Copy'"
              class="btn btn-sm"
              @click="copyToClipboard(quarkusOpenTelemetry, 'lc4j-q-otel')"
            >
              <i :class="copiedKey === 'lc4j-q-otel' ? 'bi-check-lg' : 'bi-clipboard'" class="bi"></i>
            </button>
          </div>
        </li>
        <li class="mb-0">
          <div class="fw-semibold small mb-1">3. Enable BootUI telemetry capture</div>
          <div class="text-muted small mb-1">
            Spans are captured in-process — there is no OTLP endpoint to configure. Add
            <code>bootui.telemetry.enabled=true</code> to your <code>application.properties</code>. Enable the
            extension's message-content option to see prompts and responses in the conversation drawer.
          </div>
          <div class="d-flex align-items-center gap-1">
            <code class="bg-light px-2 py-1 rounded text-break flex-grow-1">bootui.telemetry.enabled=true</code>
            <button
              :class="copiedKey === 'lc4j-q-enable' ? 'btn-success' : 'btn-outline-secondary'"
              :title="copiedKey === 'lc4j-q-enable' ? 'Copied!' : 'Copy'"
              class="btn btn-sm"
              @click="copyToClipboard('bootui.telemetry.enabled=true', 'lc4j-q-enable')"
            >
              <i :class="copiedKey === 'lc4j-q-enable' ? 'bi-check-lg' : 'bi-clipboard'" class="bi"></i>
            </button>
          </div>
        </li>
      </ol>

      <div class="mt-3 small">
        <a href="https://docs.quarkiverse.io/quarkus-langchain4j/dev/" rel="noopener" target="_blank">
          Quarkus LangChain4j guide <i class="bi bi-box-arrow-up-right"></i>
        </a>
      </div>
    </div>
  </div>

  <div v-else class="card mb-3 h-100">
    <div class="card-header d-flex align-items-center gap-2">
      <i class="bi bi-link-45deg text-primary"></i>
      <span class="fw-semibold">Set up LangChain4j</span>
    </div>
    <div class="card-body">
      <p class="text-muted small mb-3">
        LangChain4j does not use Micrometer, so it needs its own OpenTelemetry instrumentation to emit GenAI spans. Wire
        that instrumentation to export OTLP to the receiver that the BootUI starter already exposes — no separate
        collector is needed.
      </p>

      <ol class="ps-3 mb-0">
        <li class="mb-3">
          <div class="fw-semibold small mb-1">1. Add LangChain4j</div>
          <div class="text-muted small mb-1">
            Use the Spring Boot starter for your provider (OpenAI Official shown here).
          </div>
          <div class="d-flex align-items-start gap-1">
            <pre
              class="bg-light px-2 py-1 rounded small mb-0 flex-grow-1 text-break"
            ><code>{{ dependency }}</code></pre>
            <button
              :class="copiedKey === 'lc4j-dep' ? 'btn-success' : 'btn-outline-secondary'"
              :title="copiedKey === 'lc4j-dep' ? 'Copied!' : 'Copy'"
              class="btn btn-sm"
              @click="copyToClipboard(dependency, 'lc4j-dep')"
            >
              <i :class="copiedKey === 'lc4j-dep' ? 'bi-check-lg' : 'bi-clipboard'" class="bi"></i>
            </button>
          </div>
        </li>
        <li class="mb-3">
          <div class="fw-semibold small mb-1">2. Add the OpenTelemetry GenAI instrumentation</div>
          <div class="text-muted small mb-1">
            This registers a <code>ChatModelListener</code> that produces <code>gen_ai.*</code> spans following the
            OpenTelemetry GenAI semantic conventions — the same spans BootUI reads.
          </div>
          <div class="d-flex align-items-start gap-1">
            <pre
              class="bg-light px-2 py-1 rounded small mb-0 flex-grow-1 text-break"
            ><code>{{ instrumentation }}</code></pre>
            <button
              :class="copiedKey === 'lc4j-otel' ? 'btn-success' : 'btn-outline-secondary'"
              :title="copiedKey === 'lc4j-otel' ? 'Copied!' : 'Copy'"
              class="btn btn-sm"
              @click="copyToClipboard(instrumentation, 'lc4j-otel')"
            >
              <i :class="copiedKey === 'lc4j-otel' ? 'bi-check-lg' : 'bi-clipboard'" class="bi"></i>
            </button>
          </div>
        </li>
        <li class="mb-0">
          <div class="fw-semibold small mb-1">3. Export spans to BootUI &amp; enable telemetry</div>
          <div class="text-muted small mb-1">
            Point the instrumentation's OTLP exporter at the embedded receiver and set
            <code>bootui.telemetry.enabled=true</code>. Enable the instrumentation's message-content option to see
            prompts and responses in the conversation drawer.
          </div>
          <div class="d-flex align-items-center gap-1">
            <code class="bg-light px-2 py-1 rounded text-break flex-grow-1">{{ otlpEndpoint }}</code>
            <button
              :class="copiedKey === 'lc4j-otlp' ? 'btn-success' : 'btn-outline-secondary'"
              :title="copiedKey === 'lc4j-otlp' ? 'Copied!' : 'Copy'"
              class="btn btn-sm"
              @click="copyToClipboard(otlpEndpoint, 'lc4j-otlp')"
            >
              <i :class="copiedKey === 'lc4j-otlp' ? 'bi-check-lg' : 'bi-clipboard'" class="bi"></i>
            </button>
          </div>
        </li>
      </ol>

      <div class="mt-3 small">
        <a href="https://docs.langchain4j.dev/tutorials/observability" rel="noopener" target="_blank">
          LangChain4j observability tutorial <i class="bi bi-box-arrow-up-right"></i>
        </a>
      </div>
    </div>
  </div>
</template>
