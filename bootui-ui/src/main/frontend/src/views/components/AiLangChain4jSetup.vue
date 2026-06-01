<script setup>
import {useCopyToClipboard} from '../../utils/useCopyToClipboard'

const {copiedKey, copyToClipboard} = useCopyToClipboard()

const dependency = `<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j-open-ai-official</artifactId>
</dependency>`

const instrumentation = `<dependency>
  <groupId>dev.langchain4j</groupId>
  <artifactId>langchain4j-observability-opentelemetry</artifactId>
</dependency>`

const otlpEndpoint = `/bootui/api/otlp/v1/traces`
</script>

<template>
  <div class="card mb-3 h-100">
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
          <div class="text-muted small mb-1">Use the Spring Boot starter for your provider (OpenAI Official shown here).</div>
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
