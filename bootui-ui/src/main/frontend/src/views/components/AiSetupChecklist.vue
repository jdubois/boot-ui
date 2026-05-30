<script setup>
import {useCopyToClipboard} from '../../utils/useCopyToClipboard'

defineProps({
  springAiDetected: Boolean,
  enabled: Boolean,
  hasData: Boolean
})

const {copiedKey, copyToClipboard} = useCopyToClipboard()

const otlpEndpoint = `/bootui/api/otlp/v1/traces`
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
          <div class="fw-semibold">Enable BootUI telemetry capture</div>
          <div v-if="!enabled" class="text-muted small">
            Add to your
            <code>application.properties</code>:
            <div class="d-flex align-items-center gap-1 mt-1">
              <code class="bg-light px-2 py-1 rounded">bootui.telemetry.enabled=true</code>
              <button
                :class="copiedKey === 'telemetry' ? 'btn-success' : 'btn-outline-secondary'"
                :title="copiedKey === 'telemetry' ? 'Copied!' : 'Copy'"
                class="btn btn-sm"
                @click="copyToClipboard('bootui.telemetry.enabled=true', 'telemetry')"
              >
                <i :class="copiedKey === 'telemetry' ? 'bi-check-lg' : 'bi-clipboard'" class="bi"></i>
              </button>
            </div>
          </div>
          <div v-else class="text-muted small">Telemetry capture is active.</div>
        </div>
      </li>
      <li class="list-group-item d-flex align-items-start gap-3">
        <span :class="hasData ? 'text-success' : 'text-secondary'" class="fs-5 mt-1">
          <i :class="hasData ? 'bi-check-circle-fill' : 'bi-circle'" class="bi"></i>
        </span>
        <div class="w-100">
          <div class="fw-semibold">Trace capture ready</div>
          <div class="text-muted small">
            The BootUI starter captures local application spans automatically. Exercise a Spring AI chat flow to
            populate this panel.
            <div class="d-flex align-items-center gap-1 mt-1">
              <code class="bg-light px-2 py-1 rounded text-break">{{ otlpEndpoint }}</code>
              <button
                :class="copiedKey === 'otlp' ? 'btn-success' : 'btn-outline-secondary'"
                :title="copiedKey === 'otlp' ? 'Copied!' : 'Copy'"
                class="btn btn-sm"
                @click="copyToClipboard(otlpEndpoint, 'otlp')"
              >
                <i :class="copiedKey === 'otlp' ? 'bi-check-lg' : 'bi-clipboard'" class="bi"></i>
              </button>
            </div>
            <div class="mt-2">
              Cooperating local services can still export OTLP traces to this endpoint when they are not using the
              BootUI starter.
            </div>
          </div>
        </div>
      </li>
    </ul>
  </div>
</template>
