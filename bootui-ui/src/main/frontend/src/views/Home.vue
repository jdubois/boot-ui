<script setup>
import {computed, inject, onActivated, onDeactivated, ref, useId, watch} from 'vue'
import {useRouter} from 'vue-router'
import {homeAgentGuide, homeGuide, homePanelDescriptions} from '../utils/homeGuide.js'
import {createPanelLookup, resolveRouteTitle, routePanelState} from '../utils/panelNavigation.js'
import {useCopyToClipboard} from '../utils/useCopyToClipboard.js'
import PanelHeader from './components/PanelHeader.vue'

const router = useRouter()
const manifest = inject('panels', ref(null))
const active = ref(true)
const guide = ref(null)
const step = ref(null)
const id = useId()
const selectedGuide = ref(null)
const selectedTopic = ref(null)
const direction = ref('forward')
const moving = ref(false)
const copyError = ref(null)
const {copiedKey, copyToClipboard} = useCopyToClipboard()
let returnFocus = null
const lookup = computed(() => createPanelLookup(manifest.value))
const routeLookup = new Map(router.getRoutes().map((route) => [route.name, route]))

onActivated(() => {
  active.value = true
})
onDeactivated(() => {
  active.value = false
  resetCopyFeedback()
})

function destination(name) {
  const route = routeLookup.get(name)
  if (!route) throw new Error(`Unknown Home destination: ${name}`)
  const panel = lookup.value.get(name)
  const status = !manifest.value
    ? {
        icon: 'bi-hourglass-split',
        kind: 'unknown',
        label: 'Checking availability',
        reason: 'Waiting for application capabilities. If this persists, reload BootUI.'
      }
    : !panel
      ? {
          icon: 'bi-slash-circle',
          kind: 'unavailable',
          label: 'Unavailable',
          reason: 'This panel is not reported by the running application.'
        }
      : typeof panel.available !== 'boolean'
        ? {
            icon: 'bi-question-circle',
            kind: 'unknown',
            label: 'Availability unknown',
            reason: 'Availability was not reported for this panel.'
          }
        : routePanelState(route, lookup.value)
  return {
    name,
    title: resolveRouteTitle(route, manifest.value?.platform),
    description: homePanelDescriptions[name],
    path: route.path,
    status,
    navigable: panel?.available === true && panel.enabled !== false
  }
}

const sections = computed(() => [
  ...homeGuide.map((section) => ({
    ...section,
    topics: section.topics.map((topic) => ({...topic, icon: null, destinations: topic.panels.map(destination)})),
    destinations: [],
    resources: []
  })),
  {...homeAgentGuide, topics: null, destinations: homeAgentGuide.panels.map(destination)}
])
const currentGuide = computed(() => sections.value.find((section) => section.id === selectedGuide.value))
const currentTopic = computed(() => currentGuide.value?.topics?.find((topic) => topic.id === selectedTopic.value))
const currentResource = computed(() =>
  currentGuide.value?.resources.find((resource) => resource.id === selectedTopic.value)
)
const choices = computed(() => (currentGuide.value ? currentGuide.value.topics : sections.value))
const destinations = computed(() => currentTopic.value?.destinations ?? currentGuide.value?.destinations ?? [])
const stepKey = computed(() => `${selectedGuide.value ?? 'root'}/${selectedTopic.value ?? ''}`)

function resetCopyFeedback() {
  copyError.value = null
  copiedKey.value = null
}

watch(stepKey, resetCopyFeedback)

async function copyCommand(command) {
  resetCopyFeedback()
  if (!(await copyToClipboard(command.text, command.label))) copyError.value = command.label
}

function choose(choice) {
  if (moving.value) return
  direction.value = 'forward'
  returnFocus = null
  if (currentGuide.value) selectedTopic.value = choice.id
  else selectedGuide.value = choice.id
}

function goBack(toRoot = false) {
  if (moving.value) return
  direction.value = 'back'
  returnFocus = toRoot ? selectedGuide.value : selectedTopic.value
  selectedTopic.value = null
  if (toRoot) selectedGuide.value = null
}

function beginStep(element) {
  moving.value = true
  element.inert = true
}

function cancelStep(element) {
  moving.value = false
  element.inert = false
}

function finishStep(element) {
  cancelStep(element)
  if (!active.value) return
  const previous = returnFocus && element.querySelector(`[data-home-choice="${returnFocus}"]`)
  const target = previous || element.querySelector('h2')
  target.focus()
  returnFocus = null
}

watch(sections, () => {
  const focused = document.activeElement
  if (!active.value || !(focused instanceof HTMLElement) || !guide.value?.contains(focused)) return
  const item = focused.closest('[data-home-panel]')
  if (item && !destination(item.getAttribute('data-home-panel')).navigable) {
    step.value.querySelector('h2').focus()
  }
})
</script>

<template>
  <div ref="guide" class="home-guide">
    <PanelHeader icon="bi-house" title="Home" :loading="moving" @title-action="goBack(true)" />

    <div class="home-guide-stage" :aria-busy="moving">
      <Transition
        :name="`home-${direction}`"
        @before-leave="beginStep"
        @before-enter="beginStep"
        @after-enter="finishStep"
        @enter-cancelled="cancelStep"
        @leave-cancelled="cancelStep"
      >
        <section
          :key="stepKey"
          ref="step"
          class="home-guide-step"
          :aria-labelledby="currentGuide ? `${id}-${stepKey}-heading` : `${id}-root-heading`"
        >
          <nav v-if="currentGuide" class="home-guide-path" aria-label="Home guide path">
            <ol>
              <li>
                <button type="button" class="btn btn-outline-secondary" data-home-back="root" @click="goBack(true)">
                  <i class="bi bi-arrow-left" aria-hidden="true"></i>All guides
                </button>
              </li>
              <li v-if="currentTopic || currentResource">
                <i class="bi bi-chevron-right" aria-hidden="true"></i>
                <button type="button" class="btn btn-outline-secondary" data-home-back="topics" @click="goBack()">
                  {{ currentGuide.title }}
                </button>
              </li>
              <li>
                <i class="bi bi-chevron-right" aria-hidden="true"></i>
                <h2 :id="`${id}-${stepKey}-heading`" class="home-guide-step-heading" tabindex="-1" aria-current="page">
                  {{ currentTopic?.title ?? currentResource?.title ?? currentGuide.title }}
                </h2>
              </li>
            </ol>
          </nav>

          <h2
            v-if="!currentGuide"
            :id="`${id}-root-heading`"
            class="home-guide-step-heading visually-hidden"
            tabindex="-1"
          >
            Choose a guide
          </h2>
          <p v-if="currentTopic || currentResource" class="home-guide-context">
            {{ currentTopic?.description ?? currentResource?.description }}
          </p>
          <p v-else-if="currentGuide?.id === 'agents'" class="home-guide-context">
            Give your agent a goal and let it follow a BootUI guide. You decide what it may run or change; the guides
            start with existing evidence and keep work within your agreed scope.
          </p>

          <div v-if="currentResource" class="home-guide-resource">
            <p v-for="paragraph in currentResource.paragraphs" :key="paragraph">{{ paragraph }}</p>
            <div v-for="command in currentResource.commands" :key="command.label">
              <h3 class="h6">{{ command.label }}</h3>
              <div class="bootui-code-pane home-guide-command p-3 mb-4">
                <div class="d-flex align-items-center justify-content-end gap-2 mb-2">
                  <span class="small" role="status">{{ copiedKey === command.label ? 'Copied!' : '' }}</span>
                  <button
                    type="button"
                    class="btn btn-sm btn-outline-secondary home-guide-copy"
                    :aria-label="`Copy commands: ${command.label}`"
                    :title="copiedKey === command.label ? 'Copied!' : 'Copy commands'"
                    @click="copyCommand(command)"
                  >
                    <i
                      :class="['bi', copiedKey === command.label ? 'bi-check-lg' : 'bi-clipboard']"
                      aria-hidden="true"
                    ></i>
                  </button>
                </div>
                <pre
                  class="mb-0 small"
                  tabindex="0"
                  role="region"
                  :aria-label="command.label"
                ><code>{{ command.text }}</code></pre>
              </div>
              <p v-if="copyError === command.label" class="alert alert-warning small" role="alert">
                The browser blocked clipboard access, so nothing was copied. Select the commands and copy them manually.
              </p>
            </div>
            <ul v-if="currentResource.items.length">
              <li v-for="item in currentResource.items" :key="item.title">
                <strong>{{ item.title }}.</strong> {{ item.description }}
              </li>
            </ul>
            <p v-if="currentResource.note">{{ currentResource.note }}</p>
            <p class="mb-2">Find the full documentation at:</p>
            <a
              :href="currentResource.href"
              target="_blank"
              rel="noopener noreferrer"
              class="btn btn-outline-secondary home-guide-documentation"
              :aria-label="`${currentResource.documentationLabel} (opens in a new tab)`"
            >
              <span>
                {{ currentResource.documentationLabel }} <i class="bi bi-box-arrow-up-right" aria-hidden="true"></i>
              </span>
            </a>
          </div>

          <ul
            v-else-if="choices && !currentTopic"
            class="home-guide-choices"
            :class="{'home-guide-choices--root': !currentGuide}"
          >
            <li v-for="choice in choices" :key="choice.id">
              <button
                type="button"
                class="home-guide-choice"
                :data-home-choice="choice.id"
                :aria-labelledby="`${id}-${stepKey}-choice-${choice.id}`"
                :aria-describedby="`${id}-${stepKey}-description-${choice.id}`"
                @click="choose(choice)"
              >
                <i v-if="choice.icon" :class="['bi', choice.icon, 'home-guide-choice-icon']" aria-hidden="true"></i>
                <span class="home-guide-choice-copy">
                  <span :id="`${id}-${stepKey}-choice-${choice.id}`" class="home-guide-choice-title">{{
                    choice.title
                  }}</span>
                  <span :id="`${id}-${stepKey}-description-${choice.id}`" class="home-guide-explanation">{{
                    choice.description
                  }}</span>
                </span>
                <i class="bi bi-arrow-right home-guide-arrow" aria-hidden="true"></i>
              </button>
            </li>
          </ul>

          <ul v-else class="home-guide-destinations">
            <li v-for="resource in currentGuide?.resources ?? []" :key="resource.id">
              <button
                type="button"
                class="home-guide-choice"
                :data-home-choice="resource.id"
                :aria-labelledby="`${id}-${stepKey}-resource-${resource.id}-title`"
                :aria-describedby="`${id}-${stepKey}-resource-${resource.id}-description`"
                @click="choose(resource)"
              >
                <span class="home-guide-choice-copy">
                  <span :id="`${id}-${stepKey}-resource-${resource.id}-title`" class="home-guide-choice-title">{{
                    resource.title
                  }}</span>
                  <span :id="`${id}-${stepKey}-resource-${resource.id}-description`" class="home-guide-explanation">{{
                    resource.description
                  }}</span>
                </span>
                <i class="bi bi-arrow-right home-guide-arrow" aria-hidden="true"></i>
              </button>
            </li>
            <li v-for="panel in destinations" :key="panel.name" :data-home-panel="panel.name">
              <router-link
                v-if="panel.navigable"
                :to="panel.path"
                class="btn home-guide-choice home-guide-panel-link"
                :aria-labelledby="`${id}-${stepKey}-${panel.name}-title`"
                :aria-describedby="`${id}-${stepKey}-${panel.name}-description${panel.status ? ` ${id}-${stepKey}-${panel.name}-reason` : ''}`"
              >
                <span class="home-guide-choice-copy">
                  <span :id="`${id}-${stepKey}-${panel.name}-title`" class="home-guide-choice-title">{{
                    panel.title
                  }}</span>
                  <span :id="`${id}-${stepKey}-${panel.name}-description`" class="home-guide-explanation">{{
                    panel.description
                  }}</span>
                  <span
                    v-if="panel.status"
                    :id="`${id}-${stepKey}-${panel.name}-reason`"
                    class="home-guide-panel-reason"
                  >
                    <span class="badge rounded-pill home-guide-panel-status">
                      <i :class="['bi', panel.status.icon]" aria-hidden="true"></i>{{ panel.status.label }}
                    </span>
                    {{ panel.status.reason }}
                  </span>
                </span>
                <i class="bi bi-arrow-right home-guide-arrow" aria-hidden="true"></i>
              </router-link>
              <div
                v-else
                class="home-guide-choice"
                role="group"
                aria-disabled="true"
                :aria-labelledby="`${id}-${stepKey}-${panel.name}-unavailable-title`"
                :aria-describedby="`${id}-${stepKey}-${panel.name}-unavailable-reason`"
              >
                <span class="home-guide-choice-copy">
                  <span :id="`${id}-${stepKey}-${panel.name}-unavailable-title`" class="home-guide-choice-title">{{
                    panel.title
                  }}</span>
                  <span class="home-guide-explanation">{{ panel.description }}</span>
                  <span :id="`${id}-${stepKey}-${panel.name}-unavailable-reason`" class="home-guide-panel-reason">{{
                    panel.status.reason
                  }}</span>
                </span>
                <span v-if="panel.status" class="badge rounded-pill home-guide-panel-status">
                  <i :class="['bi', panel.status.icon ?? 'bi-info-circle']" aria-hidden="true"></i>
                  {{ panel.status.label }}
                </span>
              </div>
            </li>
          </ul>
        </section>
      </Transition>
    </div>
  </div>
</template>

<style scoped>
.home-guide-stage {
  display: grid;
  margin: 0 auto;
  max-width: 56rem;
  min-height: 28rem;
  overflow: clip;
  padding: 1rem 0.5rem 2rem;
}
.home-guide-step {
  align-self: start;
  grid-area: 1 / 1;
  min-width: 0;
}
.home-guide-path {
  background: var(--bootui-surface);
  border: 1px solid var(--bootui-border);
  border-radius: var(--bootui-radius-md);
  margin-bottom: 1.5rem;
  padding: 0.65rem 0.85rem;
}
.home-guide-path ol {
  align-items: center;
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 0.75rem;
  list-style: none;
  margin: 0;
  padding: 0;
}
.home-guide-path li,
.home-guide-path button {
  align-items: center;
  display: inline-flex;
  gap: 0.65rem;
}
.home-guide-path li {
  color: var(--bootui-text-muted);
  font-size: 1rem;
  min-width: 0;
}
.home-guide-path button {
  min-height: 48px;
  padding: 0.5rem 0.75rem;
  text-align: left;
}
.home-guide-path button:hover {
  text-decoration: none;
}
.home-guide-step-heading {
  font-size: 1.15rem;
  font-weight: 700;
  line-height: 1.3;
  margin: 0;
}
.home-guide-context {
  color: var(--bootui-text);
  line-height: 1.6;
  margin: 0 0 1.5rem;
  max-width: 65ch;
}
.home-guide-choices,
.home-guide-destinations {
  list-style: none;
  margin: 0;
  padding: 0;
}
.home-guide-choices > li + li,
.home-guide-destinations > li + li {
  border-top: 1px solid var(--bootui-border);
}
.home-guide-choice {
  align-items: center;
  background: none;
  border: 0;
  border-radius: var(--bootui-radius-sm);
  color: var(--bootui-text);
  display: flex;
  gap: 1.25rem;
  min-height: 44px;
  padding: 1.15rem 1rem;
  text-align: left;
  transition: background-color 140ms ease-out;
  width: 100%;
}
.home-guide-choice:not([aria-disabled='true']):hover {
  background: var(--bootui-surface);
  color: var(--bootui-text);
}
.home-guide-choice-icon {
  color: var(--bootui-text-muted);
  flex-shrink: 0;
  font-size: var(--bootui-icon-size);
}
.home-guide-choice-copy {
  display: grid;
  gap: 0.4rem;
  min-width: 0;
}
.home-guide-choice-title {
  font-size: 1rem;
  font-weight: 650;
  line-height: 1.4;
}
.home-guide-choices--root .home-guide-choice {
  padding-block: 1.65rem;
}
.home-guide-choices--root .home-guide-choice-title {
  font-size: 1.15rem;
}
.home-guide-explanation {
  color: var(--bootui-text);
  font-size: 0.875rem;
  font-weight: 400;
  line-height: 1.6;
  max-width: 65ch;
}
.home-guide-choices--root .home-guide-explanation {
  font-size: 1rem;
}
.home-guide-arrow {
  color: var(--bootui-text-muted);
  flex-shrink: 0;
  margin-left: auto;
  transition: transform 140ms ease-out;
}
.home-guide-choice:hover .home-guide-arrow {
  transform: translateX(3px);
}
.home-guide-destinations > li {
  min-width: 0;
}
.home-guide-panel-link {
  text-decoration: none;
}
.home-guide-panel-link:hover {
  text-decoration: none;
}
.home-guide-resource {
  color: var(--bootui-text);
  line-height: 1.6;
  max-width: 65ch;
}
.home-guide-resource p,
.home-guide-resource ul {
  margin: 0 0 1.5rem;
}
.home-guide-resource ul {
  display: grid;
  gap: 1rem;
  padding-left: 1.25rem;
}
.home-guide-documentation.btn {
  --bs-btn-color: var(--bootui-text);
  max-width: 100%;
  text-align: left;
  white-space: normal;
}
.home-guide-copy.btn {
  --bs-btn-color: var(--bootui-code-pane-text);
  --bs-btn-border-color: var(--bootui-code-pane-text);
  --bs-btn-hover-color: var(--bootui-code-pane-text);
  --bs-btn-hover-bg: color-mix(in srgb, var(--bootui-code-pane-text) 10%, var(--bootui-code-pane-bg));
  --bs-btn-hover-border-color: var(--bootui-code-pane-text);
  --bs-btn-active-color: var(--bootui-code-pane-text);
  --bs-btn-active-bg: color-mix(in srgb, var(--bootui-code-pane-text) 18%, var(--bootui-code-pane-bg));
  --bs-btn-active-border-color: var(--bootui-code-pane-text);
  min-height: 36px;
  min-width: 36px;
}
/* Guide rows use a trailing arrow instead of the shared button-link prefix. */
.home-guide-panel-link.btn::before {
  display: none;
}
.home-guide-panel-link.btn > .home-guide-arrow {
  display: inline-block;
}
.home-guide-panel-status {
  align-items: center;
  background: var(--bootui-surface-alt);
  border: 1px solid var(--bootui-border-alt);
  color: var(--bootui-text-muted);
  display: inline-flex;
  flex-shrink: 0;
  font-weight: 500;
  gap: 0.4rem;
  justify-self: start;
  padding: 0.4rem 0.65rem;
}
.home-guide-panel-status,
.home-guide-panel-reason {
  font-size: 0.875rem;
  line-height: 1.6;
}
.home-guide-choice:focus-visible,
.home-guide-path button:focus-visible,
.home-guide-step-heading:focus-visible,
.home-guide-panel-link:focus-visible,
.home-guide-resource pre:focus-visible,
.home-guide-copy:focus-visible,
.home-guide-documentation:focus-visible {
  border-radius: var(--bootui-radius-sm);
  outline: 2px solid var(--bootui-blue);
  outline-offset: 3px;
}
.home-guide-panel-reason {
  color: var(--bootui-text);
  margin-top: 0.4rem;
}
.home-forward-enter-active,
.home-forward-leave-active,
.home-back-enter-active,
.home-back-leave-active {
  transition: transform 340ms cubic-bezier(0.22, 1, 0.36, 1);
}
.home-forward-enter-from,
.home-back-leave-to {
  transform: translateX(100%);
}
.home-forward-leave-to,
.home-back-enter-from {
  transform: translateX(-100%);
}
@media (max-width: 575.98px) {
  .home-guide-path {
    padding: 0.5rem;
  }
  .home-guide-path li:last-child {
    flex-basis: 100%;
    padding: 0.5rem 0.25rem;
  }
  .home-guide-choice {
    gap: 1rem;
    padding-inline: 0.5rem;
  }
}
@media (prefers-reduced-motion: reduce) {
  .home-guide-choice,
  .home-guide-arrow,
  .home-forward-enter-active,
  .home-forward-leave-active,
  .home-back-enter-active,
  .home-back-leave-active {
    transition: none;
  }
  .home-guide-choice:hover .home-guide-arrow,
  .home-forward-enter-from,
  .home-forward-leave-to,
  .home-back-enter-from,
  .home-back-leave-to {
    transform: none;
  }
}
</style>
