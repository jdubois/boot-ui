<script setup>
import {useCopyToClipboard} from '../../utils/useCopyToClipboard'

const {copiedKey, copyToClipboard} = useCopyToClipboard()

const dependency = `<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>`

const contentProperties = `spring.ai.chat.client.observations.log-prompt=true
spring.ai.chat.observations.log-prompt=true
spring.ai.chat.observations.log-completion=true`
</script>

<template>
  <div class="card mb-3 h-100">
    <div class="card-header d-flex align-items-center gap-2">
      <i class="bi bi-leaf-fill text-success"></i>
      <span class="fw-semibold">Set up Spring AI</span>
    </div>
    <div class="card-body">
      <p class="text-muted small mb-3">
        Spring AI publishes Micrometer observations for every chat-client call. The BootUI starter already ships the
        OpenTelemetry tracing bridge and an embedded OTLP receiver, so those observations are turned into GenAI spans
        automatically — no extra tracing dependency or exporter configuration is required.
      </p>

      <ol class="ps-3 mb-0">
        <li class="mb-3">
          <div class="fw-semibold small mb-1">1. Add a Spring AI model starter</div>
          <div class="text-muted small mb-1">
            Pick the starter for your provider (OpenAI, Ollama, Anthropic, …). Any of them brings the chat-client
            instrumentation that BootUI reads.
          </div>
          <div class="d-flex align-items-start gap-1">
            <pre
              class="bg-light px-2 py-1 rounded small mb-0 flex-grow-1 text-break"
            ><code>{{ dependency }}</code></pre>
            <button
              :class="copiedKey === 'springai-dep' ? 'btn-success' : 'btn-outline-secondary'"
              :title="copiedKey === 'springai-dep' ? 'Copied!' : 'Copy'"
              class="btn btn-sm"
              @click="copyToClipboard(dependency, 'springai-dep')"
            >
              <i :class="copiedKey === 'springai-dep' ? 'bi-check-lg' : 'bi-clipboard'" class="bi"></i>
            </button>
          </div>
        </li>
        <li class="mb-3">
          <div class="fw-semibold small mb-1">2. Enable BootUI telemetry capture</div>
          <div class="text-muted small">
            Add <code>bootui.telemetry.enabled=true</code> to your <code>application.properties</code> (see the
            checklist above). Token counts, latency, and model breakdowns will then appear with no further setup.
          </div>
        </li>
        <li class="mb-0">
          <div class="fw-semibold small mb-1">3. (Optional) Capture prompt &amp; completion text</div>
          <div class="text-muted small mb-1">
            Message content is off by default for privacy. Opt in to see prompts and responses in the conversation
            drawer:
          </div>
          <div class="d-flex align-items-start gap-1">
            <pre
              class="bg-light px-2 py-1 rounded small mb-0 flex-grow-1 text-break"
            ><code>{{ contentProperties }}</code></pre>
            <button
              :class="copiedKey === 'springai-content' ? 'btn-success' : 'btn-outline-secondary'"
              :title="copiedKey === 'springai-content' ? 'Copied!' : 'Copy'"
              class="btn btn-sm"
              @click="copyToClipboard(contentProperties, 'springai-content')"
            >
              <i :class="copiedKey === 'springai-content' ? 'bi-check-lg' : 'bi-clipboard'" class="bi"></i>
            </button>
          </div>
        </li>
      </ol>

      <div class="mt-3 small">
        <a href="https://docs.spring.io/spring-ai/reference/observability/index.html" rel="noopener" target="_blank">
          Spring AI observability reference <i class="bi bi-box-arrow-up-right"></i>
        </a>
      </div>
    </div>
  </div>
</template>
