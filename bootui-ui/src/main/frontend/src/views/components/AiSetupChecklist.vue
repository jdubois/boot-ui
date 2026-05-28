<script setup>
defineProps({
  springAiDetected: Boolean,
  enabled: Boolean,
  hasData: Boolean
})

async function copyToClipboard(text) {
  try {
    await navigator.clipboard.writeText(text)
  } catch (_) {
    // ignore
  }
}

const otlpProps = `management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:8080/bootui/api/otlp/v1/traces`
const mavSnippet = `<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>`
</script>

<template>
  <div class="card mb-3">
    <div class="card-header fw-semibold">Setup checklist</div>
    <ul class="list-group list-group-flush">
      <li class="list-group-item d-flex align-items-start gap-3">
        <span :class="springAiDetected ? 'text-success' : 'text-danger'" class="fs-5 mt-1">
          <i :class="springAiDetected ? 'bi-check-circle-fill' : 'bi-x-circle-fill'" class="bi"></i>
        </span>
        <div>
          <div class="fw-semibold">Spring AI on classpath</div>
          <div v-if="!springAiDetected" class="text-muted small">
            Spring AI is not detected. Add
            <a href="https://spring.io/projects/spring-ai" rel="noopener" target="_blank">Spring AI</a>
            to your project dependencies.
          </div>
          <div v-else class="text-muted small">Spring AI detected — ready to instrument chats.</div>
        </div>
      </li>
      <li class="list-group-item d-flex align-items-start gap-3">
        <span :class="enabled ? 'text-success' : 'text-danger'" class="fs-5 mt-1">
          <i :class="enabled ? 'bi-check-circle-fill' : 'bi-x-circle-fill'" class="bi"></i>
        </span>
        <div>
          <div class="fw-semibold">Enable the BootUI telemetry receiver</div>
          <div v-if="!enabled" class="text-muted small">
            Add to your
            <code>application.properties</code>:
            <div class="d-flex align-items-center gap-1 mt-1">
              <code class="bg-light px-2 py-1 rounded">bootui.telemetry.enabled=true</code>
              <button
                class="btn btn-sm btn-outline-secondary"
                @click="copyToClipboard('bootui.telemetry.enabled=true')"
              >
                <i class="bi bi-clipboard"></i>
              </button>
            </div>
          </div>
          <div v-else class="text-muted small">Telemetry receiver is active.</div>
        </div>
      </li>
      <li class="list-group-item d-flex align-items-start gap-3">
        <span :class="hasData ? 'text-success' : 'text-secondary'" class="fs-5 mt-1">
          <i :class="hasData ? 'bi-check-circle-fill' : 'bi-circle'" class="bi"></i>
        </span>
        <div class="w-100">
          <div class="fw-semibold">OTLP exporter configured</div>
          <div class="text-muted small">
            Configure your application to export OTLP traces to BootUI:
            <div class="d-flex align-items-center gap-1 mt-1">
              <code class="bg-light px-2 py-1 rounded text-break">{{ otlpProps }}</code>
              <button class="btn btn-sm btn-outline-secondary" @click="copyToClipboard(otlpProps)">
                <i class="bi bi-clipboard"></i>
              </button>
            </div>
            <div class="mt-2">Also add the tracing bridge dependency:</div>
            <div class="d-flex align-items-start gap-1 mt-1">
              <pre class="bg-light p-2 rounded small mb-0 flex-grow-1">{{ mavSnippet }}</pre>
              <button class="btn btn-sm btn-outline-secondary" @click="copyToClipboard(mavSnippet)">
                <i class="bi bi-clipboard"></i>
              </button>
            </div>
          </div>
        </div>
      </li>
    </ul>
  </div>
</template>
