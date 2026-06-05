<script setup>
import {computed} from 'vue'
import PanelHeader from './components/PanelHeader.vue'
import PanelSkeleton from './components/PanelSkeleton.vue'
import {useCopyToClipboard} from '../utils/useCopyToClipboard.js'
import {confidenceBadgeClass, formatBytes, useMemoryReport} from '../utils/memoryReport.js'

const {
  data,
  error,
  lastUpdated,
  totalMemoryMb,
  threadCount,
  headRoomPercent,
  kubernetesBurstableEnabled,
  kubernetesActuatorEnabled,
  autoRefresh,
  loading,
  initialLoading,
  load
} = useMemoryReport({endpoint: 'api/tuning-advisor', tuningInputs: true})
const {copiedKey, copyToClipboard} = useCopyToClipboard(2000)

const springVirtualThreadsEnabled = computed(() => data.value?.calculation?.virtualThreadsEnabled === true)

const breakdown = computed(() => {
  const c = data.value?.calculation
  if (!c) return []
  const segments = [
    {key: 'heap', label: 'Heap', bytes: c.heapBytes, color: '#198754'},
    {key: 'metaspace', label: 'Metaspace', bytes: c.metaspaceBytes, color: '#0d6efd'},
    {key: 'codeCache', label: 'Code cache', bytes: c.codeCacheBytes, color: '#6610f2'},
    {key: 'directMemory', label: 'Direct', bytes: c.directMemoryBytes, color: '#fd7e14'},
    {key: 'stacks', label: 'Thread stacks', bytes: c.stackBytesTotal, color: '#ffc107'},
    {key: 'headroom', label: 'Headroom', bytes: c.headRoomBytes, color: '#6c757d'}
  ]
  const total = segments.reduce((sum, s) => sum + Math.max(0, s.bytes || 0), 0)
  return segments.map((s) => ({
    ...s,
    bytes: Math.max(0, s.bytes || 0),
    percent: total > 0 ? (Math.max(0, s.bytes || 0) / total) * 100 : 0
  }))
})

function stepTotal(delta) {
  const next = Math.max(128, Math.min(65536, (totalMemoryMb.value || 0) + delta))
  totalMemoryMb.value = next
}

async function copyOptions() {
  if (data.value?.suggestedJvmOptions) {
    await copyToClipboard(data.value.suggestedJvmOptions, 'jvm-options')
  }
}

async function copyKubernetesYaml() {
  if (data.value?.kubernetes?.yaml) {
    await copyToClipboard(data.value.kubernetes.yaml, 'kubernetes-yaml')
  }
}
</script>

<template>
  <div>
    <PanelHeader
      icon="bi-sliders2-vertical"
      title="Tuning Advisor"
      subtitle="Review current JVM arguments, plan bare-metal JVM options, and calculate Kubernetes memory sizing."
      :loading="loading"
      :error="error"
      :last-fetched="lastUpdated ? lastUpdated.getTime() : null"
      v-model:auto-refresh="autoRefresh"
      @refresh="load"
    />

    <PanelSkeleton v-if="initialLoading" />

    <template v-else-if="data">
      <div v-if="data.jvmInputArguments && data.jvmInputArguments.length" class="card mb-4">
        <div class="card-header"><i class="bi bi-terminal me-2"></i>Current JVM Arguments</div>
        <div class="card-body">
          <ul class="list-unstyled mb-0">
            <li v-for="arg in data.jvmInputArguments" :key="arg" class="mb-1">
              <code class="text-secondary">{{ arg }}</code>
            </li>
          </ul>
        </div>
      </div>
      <div v-else class="card mb-4">
        <div class="card-header"><i class="bi bi-terminal me-2"></i>Current JVM Arguments</div>
        <div class="card-body text-muted small">No explicit JVM arguments were passed at startup.</div>
      </div>

      <div
        v-if="data.calculation"
        :class="['alert mb-4 virtual-threads-status', springVirtualThreadsEnabled ? 'alert-info' : 'alert-warning']"
      >
        <div class="d-flex gap-2">
          <i
            :class="['bi', springVirtualThreadsEnabled ? 'bi-info-circle' : 'bi-exclamation-triangle', 'flex-shrink-0']"
          ></i>
          <div>
            <div class="fw-semibold mb-1">
              Spring virtual threads {{ springVirtualThreadsEnabled ? 'enabled' : 'not enabled' }}
            </div>
            <template v-if="springVirtualThreadsEnabled">
              <p class="small mb-2">
                This application is running with <code>spring.threads.virtual.enabled=true</code>. That is positive for
                performance because Spring Boot can use virtual threads for web requests and supported task executors,
                reducing platform-thread pressure during blocking work.
              </p>
            </template>
            <template v-else>
              <p class="small mb-2">
                <code>spring.threads.virtual.enabled=true</code> is not active for this application. On Java 21+,
                enabling it in application configuration is recommended for services that handle blocking web requests
                or supported task-executor work because it can improve throughput and latency under concurrent blocking
                workloads.
              </p>
              <p class="small mb-0">
                BootUI keeps the JVM and Kubernetes snippets in platform-thread mode until the running application
                enables Spring virtual threads.
              </p>
            </template>
          </div>
        </div>
      </div>

      <div v-if="data.calculation" class="card mb-4 border-primary">
        <div class="card-header bg-primary text-white d-flex justify-content-between align-items-center">
          <span><i class="bi bi-calculator me-2"></i>Bare metal JVM calculator</span>
          <small class="text-white-50">Plan a fixed JVM process budget</small>
        </div>
        <div class="card-body">
          <p class="text-muted small mb-3">
            For a dedicated server or VM, heap is fixed after subtracting metaspace (sized from currently loaded classes
            × 1.25 safety factor), code cache, direct memory, platform-thread stacks, and headroom from your target JVM
            process memory.
          </p>

          <div class="row g-3 mb-3">
            <div class="col-md-5">
              <label class="form-label small fw-semibold">Target JVM process memory (MB)</label>
              <div class="input-group input-group-sm">
                <button aria-label="Decrease" class="btn btn-outline-secondary" type="button" @click="stepTotal(-64)">
                  −
                </button>
                <input
                  v-model.number="totalMemoryMb"
                  class="form-control text-center"
                  max="65536"
                  min="128"
                  step="64"
                  type="number"
                />
                <button aria-label="Increase" class="btn btn-outline-secondary" type="button" @click="stepTotal(64)">
                  +
                </button>
                <span class="input-group-text">MB</span>
              </div>
            </div>
            <div class="col-md-4">
              <label class="form-label small fw-semibold">
                Platform thread budget
                <span class="text-muted fw-normal"> (currently {{ data.calculation.liveThreadCount }}) </span>
              </label>
              <input
                v-model.number="threadCount"
                class="form-control form-control-sm"
                max="10000"
                min="1"
                step="10"
                type="number"
              />
            </div>
            <div class="col-md-3">
              <label class="form-label small fw-semibold">Headroom (%)</label>
              <input
                v-model.number="headRoomPercent"
                class="form-control form-control-sm"
                max="30"
                min="0"
                step="1"
                type="number"
              />
            </div>
          </div>

          <div v-if="!data.calculation.valid" class="alert alert-warning small mb-3">
            <i class="bi bi-exclamation-triangle me-1"></i>{{ data.calculation.error }}
          </div>

          <template v-else>
            <div aria-label="Memory breakdown" class="progress breakdown-bar mb-2" role="img" style="height: 24px">
              <div
                v-for="seg in breakdown"
                :key="seg.key"
                :style="{width: seg.percent + '%', backgroundColor: seg.color}"
                :title="seg.label + ': ' + formatBytes(seg.bytes)"
                class="progress-bar"
              >
                <span v-if="seg.percent >= 8" class="small">{{ seg.label }}</span>
              </div>
            </div>
            <div class="d-flex flex-wrap gap-3 small mb-2">
              <div v-for="seg in breakdown" :key="'leg-' + seg.key" class="d-flex align-items-center">
                <span :style="{backgroundColor: seg.color}" class="legend-swatch me-1"></span>
                <span class="text-muted me-1">{{ seg.label }}:</span>
                <span class="fw-semibold">{{ formatBytes(seg.bytes) }}</span>
              </div>
            </div>
            <div class="small text-muted">
              Currently {{ data.calculation.liveLoadedClassCount.toLocaleString() }} classes loaded · metaspace sized
              for {{ data.calculation.loadedClasses.toLocaleString() }} classes × 1.25 safety factor
              <span v-if="data.calculation.virtualThreadsEnabled">
                · virtual-thread mode uses {{ data.calculation.stackBytesPerThread / 1024 }} KB platform-thread stacks
              </span>
            </div>
          </template>
        </div>
      </div>

      <div class="card mb-4 border-primary">
        <div class="card-header bg-primary text-white d-flex justify-content-between align-items-center">
          <span><i class="bi bi-rocket-takeoff me-2"></i>Bare metal JVM options</span>
          <button
            :class="{'btn-success': copiedKey === 'jvm-options'}"
            :disabled="!data.calculation || !data.calculation.valid"
            class="btn btn-sm btn-light"
            @click="copyOptions"
          >
            <i :class="['bi', copiedKey === 'jvm-options' ? 'bi-check-lg' : 'bi-clipboard', 'me-1']"></i>
            {{ copiedKey === 'jvm-options' ? 'Copied!' : 'Copy' }}
          </button>
        </div>
        <div class="card-body">
          <p class="text-muted small mb-2">
            Generated from your calculator inputs for a dedicated host. <code>-Xms == -Xmx</code> fixes heap size for
            predictable startup and runtime latency; GC is picked automatically (G1 below 4 GB, ZGC above).
          </p>
          <pre
            :class="{'opacity-50': data.calculation && !data.calculation.valid}"
            class="bg-dark text-light rounded p-3 mb-0 options-box"
          ><code>{{ data.suggestedJvmOptions || '—' }}</code></pre>
          <div class="mt-2">
            <span class="badge text-bg-secondary me-1"><i class="bi bi-shield-check me-1"></i>OOM protection</span>
            <span class="badge text-bg-secondary me-1"><i class="bi bi-gear me-1"></i>GC tuned</span>
            <span class="badge text-bg-secondary me-1"><i class="bi bi-memory me-1"></i>Fixed heap</span>
          </div>
        </div>
      </div>

      <div v-if="data.kubernetes" class="card mb-4 border-success">
        <div class="card-header bg-success text-white d-flex justify-content-between align-items-center">
          <span><i class="bi bi-box-seam me-2"></i>Kubernetes calculator</span>
          <span :class="['badge', confidenceBadgeClass(data.kubernetes.confidence)]">
            {{ data.kubernetes.confidence }} confidence
          </span>
        </div>
        <div class="card-body">
          <p class="text-muted small mb-3">
            Uses the calculator total as the hard Kubernetes memory limit. By default, the generated manifest sets
            <code>requests.memory == limits.memory</code> for Guaranteed QoS; the Burstable toggle lowers only the
            request. The JVM uses <code>MaxRAMPercentage</code>/<code>InitialRAMPercentage</code> instead of fixed
            <code>-Xmx</code>/<code>-Xms</code>, so heap tracks the container memory limit when an operator resizes the
            pod.
          </p>

          <div class="row g-3 mb-3">
            <div class="col-lg-6">
              <div class="border rounded p-3 h-100">
                <div class="d-flex justify-content-between gap-3">
                  <div>
                    <div class="fw-semibold">Burstable resources</div>
                    <div class="text-muted small">
                      Off by default. When enabled, the snippet lowers <code>requests.memory</code> to the current
                      snapshot-based request while keeping the same limit. Use only when your cluster intentionally
                      overcommits memory.
                    </div>
                  </div>
                  <div class="form-check form-switch mb-0 flex-shrink-0">
                    <input
                      id="kubernetesBurstableEnabled"
                      v-model="kubernetesBurstableEnabled"
                      class="form-check-input"
                      type="checkbox"
                    />
                    <label class="form-check-label small fw-semibold" for="kubernetesBurstableEnabled">
                      {{ kubernetesBurstableEnabled ? 'On' : 'Off' }}
                    </label>
                  </div>
                </div>
              </div>
            </div>
            <div class="col-lg-6">
              <div class="border rounded p-3 h-100">
                <div class="d-flex justify-content-between gap-3">
                  <div>
                    <div class="fw-semibold">Spring Boot Actuator probes</div>
                    <div class="text-muted small">
                      Initialized from the current health/probes configuration. Recommended so Kubernetes can use
                      startup, readiness, and liveness checks from <code>/actuator/health</code>.
                    </div>
                  </div>
                  <div class="form-check form-switch mb-0 flex-shrink-0">
                    <input
                      id="kubernetesActuatorEnabled"
                      v-model="kubernetesActuatorEnabled"
                      class="form-check-input"
                      type="checkbox"
                    />
                    <label class="form-check-label small fw-semibold" for="kubernetesActuatorEnabled">
                      {{ kubernetesActuatorEnabled ? 'On' : 'Off' }}
                    </label>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div v-if="data.kubernetes.detectedContainerLimitMemory" class="alert alert-light border small mb-3">
            <i class="bi bi-hdd-network me-1"></i>
            Detected cgroup memory limit:
            <strong>{{ data.kubernetes.detectedContainerLimitMemory }}</strong>
          </div>

          <div class="row g-3 mb-3">
            <div class="col-md">
              <div class="border rounded p-3 h-100">
                <div class="text-muted small">Request memory</div>
                <div class="fs-5 fw-semibold">{{ data.kubernetes.requestMemory || '—' }}</div>
                <div class="text-muted small">
                  {{ kubernetesBurstableEnabled ? 'Burstable scheduling request' : 'Guaranteed scheduling request' }}
                </div>
              </div>
            </div>
            <div class="col-md">
              <div class="border rounded p-3 h-100">
                <div class="text-muted small">Limit memory</div>
                <div class="fs-5 fw-semibold">{{ data.kubernetes.limitMemory }}</div>
                <div class="text-muted small">Container OOM boundary</div>
              </div>
            </div>
            <div class="col-md">
              <div class="border rounded p-3 h-100">
                <div class="text-muted small">Heap percentage</div>
                <div class="fs-5 fw-semibold">
                  {{
                    data.kubernetes.maxRamPercentage != null ? data.kubernetes.maxRamPercentage.toFixed(1) + '%' : '—'
                  }}
                </div>
                <div class="text-muted small">MaxRAMPercentage</div>
              </div>
            </div>
            <div class="col-md">
              <div class="border rounded p-3 h-100">
                <div class="text-muted small">QoS class</div>
                <div class="fs-5 fw-semibold">{{ data.kubernetes.qosClass }}</div>
                <div class="text-muted small">
                  {{ kubernetesBurstableEnabled ? 'Opt-in mode' : 'Recommended default' }}
                </div>
              </div>
            </div>
            <div class="col-md">
              <div class="border rounded p-3 h-100">
                <div class="text-muted small">Current snapshot</div>
                <div class="fs-5 fw-semibold">{{ data.kubernetes.currentSnapshotMemory }}</div>
                <div class="text-muted small">Committed JVM memory + live platform stacks</div>
              </div>
            </div>
          </div>

          <div class="d-flex justify-content-between align-items-center mb-2">
            <div class="small fw-semibold">Deployment snippet</div>
            <button
              :class="{'btn-success': copiedKey === 'kubernetes-yaml'}"
              :disabled="!data.kubernetes.yaml"
              class="btn btn-sm btn-outline-success"
              @click="copyKubernetesYaml"
            >
              <i :class="['bi', copiedKey === 'kubernetes-yaml' ? 'bi-check-lg' : 'bi-clipboard', 'me-1']"></i>
              {{ copiedKey === 'kubernetes-yaml' ? 'Copied!' : 'Copy YAML' }}
            </button>
          </div>
          <pre
            :class="{'opacity-50': !data.kubernetes.yaml}"
            class="bg-dark text-light rounded p-3 mb-3 options-box"
          ><code>{{ data.kubernetes.yaml || 'Adjust calculator inputs until a valid heap is available.' }}</code></pre>

          <div v-if="data.kubernetes.warnings?.length" class="alert alert-info small mb-0">
            <div class="fw-semibold mb-1"><i class="bi bi-info-circle me-1"></i>Sizing notes</div>
            <ul class="mb-0 ps-3">
              <li v-for="warning in data.kubernetes.warnings" :key="warning">{{ warning }}</li>
            </ul>
          </div>
        </div>
      </div>
    </template>

    <div v-else-if="!error" class="text-muted">Loading…</div>
  </div>
</template>

<style scoped>
.options-box {
  font-size: 0.85rem;
  white-space: pre-wrap;
  word-break: break-all;
}

.breakdown-bar .progress-bar {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #fff;
  font-weight: 500;
}

.legend-swatch {
  display: inline-block;
  width: 12px;
  height: 12px;
  border-radius: 2px;
}
</style>
