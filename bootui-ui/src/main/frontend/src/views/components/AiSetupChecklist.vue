<script setup>
import {computed} from 'vue'
import {useCopyToClipboard} from '../../utils/useCopyToClipboard'
import AiSpringAiSetup from './AiSpringAiSetup.vue'
import AiLangChain4jSetup from './AiLangChain4jSetup.vue'

const props = defineProps({
  springAiDetected: Boolean,
  langChain4jDetected: Boolean,
  enabled: Boolean,
  hasData: Boolean,
  platform: {type: String, default: 'spring-boot'}
})

const frameworkDetected = computed(() => props.springAiDetected || props.langChain4jDetected)
const isQuarkus = computed(() => props.platform === 'quarkus')

const {copiedKey, copyToClipboard} = useCopyToClipboard()

const otlpEndpoint = `/bootui/api/otlp/v1/traces`
</script>

<template>
  <div class="card mb-3">
    <div class="card-header fw-semibold">Setup checklist</div>
    <ul class="list-group list-group-flush">
      <li class="list-group-item d-flex align-items-start gap-3">
        <span :class="frameworkDetected ? 'text-success' : 'text-danger'" class="fs-5 mt-1">
          <i :class="frameworkDetected ? 'bi-check-circle-fill' : 'bi-x-circle-fill'" class="bi"></i>
        </span>
        <div>
          <div class="fw-semibold">
            {{ isQuarkus ? 'LangChain4j on classpath' : 'Spring AI or LangChain4j on classpath' }}
          </div>
          <div v-if="!frameworkDetected" class="text-muted small">
            <template v-if="isQuarkus">
              No AI framework is detected. Add
              <a href="https://docs.quarkiverse.io/quarkus-langchain4j/dev/" rel="noopener" target="_blank"
                >Quarkus LangChain4j</a
              >
              to your project dependencies.
            </template>
            <template v-else>
              No AI framework is detected. Add
              <a href="https://spring.io/projects/spring-ai" rel="noopener" target="_blank">Spring AI</a>
              or
              <a href="https://docs.langchain4j.dev" rel="noopener" target="_blank">LangChain4j</a>
              to your project dependencies.
            </template>
          </div>
          <div v-else-if="springAiDetected && langChain4jDetected" class="text-muted small">
            Spring AI and LangChain4j detected — ready to instrument chats.
          </div>
          <div v-else-if="springAiDetected" class="text-muted small">
            Spring AI detected — ready to instrument chats.
          </div>
          <div v-else class="text-muted small">LangChain4j detected — ready to instrument chats.</div>
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
            <template v-if="isQuarkus">
              The BootUI Quarkus extension captures local application spans automatically, in-process. Exercise a
              LangChain4j chat flow to populate this panel.
            </template>
            <template v-else>
              The BootUI starter captures local application spans automatically. Exercise a Spring AI or LangChain4j
              chat flow to populate this panel.
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
            </template>
          </div>
        </div>
      </li>
    </ul>
  </div>

  <div v-if="!frameworkDetected">
    <div class="d-flex align-items-center gap-2 mb-2">
      <i class="bi bi-stars text-warning"></i>
      <h6 class="mb-0">{{ isQuarkus ? 'Set up AI instrumentation' : 'Choose a framework to instrument' }}</h6>
    </div>
    <template v-if="isQuarkus">
      <p class="text-muted small">
        Add LangChain4j to start collecting AI usage. Quarkus LangChain4j emits OpenTelemetry GenAI spans that this
        panel reads; the BootUI extension captures them in-process.
      </p>
      <AiLangChain4jSetup :platform="platform" />
    </template>
    <template v-else>
      <p class="text-muted small">
        BootUI works with either framework — add one of them to start collecting AI usage. Both emit OpenTelemetry GenAI
        spans that this panel reads; the BootUI starter already provides the tracing pipeline that receives them.
      </p>
      <div class="row row-cols-1 row-cols-lg-2 g-3">
        <div class="col">
          <AiSpringAiSetup />
        </div>
        <div class="col">
          <AiLangChain4jSetup />
        </div>
      </div>
    </template>
  </div>
</template>
