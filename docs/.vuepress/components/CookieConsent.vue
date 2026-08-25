<script setup>
import {onMounted, onUnmounted, ref} from 'vue'
import {applyConsent, onConsentChange, readConsent, setConsent} from '../analytics.js'

const visible = ref(false)

// The banner is client-only: rendering it during the static build would ship a prompt that is
// already answered for returning readers, and would flash before hydration for everyone else.
onMounted(() => {
  const consent = readConsent()
  applyConsent(consent)
  visible.value = consent === null
})

onUnmounted(
  onConsentChange((consent) => {
    visible.value = consent === null
  })
)

function accept() {
  setConsent('granted')
  visible.value = false
}

function decline() {
  setConsent('denied')
  visible.value = false
}
</script>

<template>
  <Transition name="bootui-consent">
    <aside
      v-if="visible"
      class="bootui-consent"
      role="region"
      aria-labelledby="bootui-consent-title"
      aria-describedby="bootui-consent-description"
    >
      <div class="bootui-consent__text">
        <p id="bootui-consent-title" class="bootui-consent__title">Analytics cookies</p>
        <p id="bootui-consent-description" class="bootui-consent__description">
          BootUI would like to use Google Analytics to understand which documentation pages are useful. Nothing is set
          unless you accept, and declining changes nothing about how the site works. See the
          <RouteLink to="/privacy">privacy notice</RouteLink> for details or to change your mind later.
        </p>
      </div>
      <div class="bootui-consent__actions">
        <button type="button" class="bootui-consent__button" @click="decline">Decline</button>
        <button type="button" class="bootui-consent__button bootui-consent__button--primary" @click="accept">
          Accept
        </button>
      </div>
    </aside>
  </Transition>
</template>

<style scoped>
.bootui-consent {
  position: fixed;
  z-index: 100;
  right: 1rem;
  bottom: 1rem;
  left: 1rem;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 1rem 1.5rem;
  max-width: 62rem;
  margin: 0 auto;
  padding: 1rem 1.25rem;
  border: 1px solid var(--bootui-border);
  border-radius: 1.1rem;
  background: var(--bootui-surface-solid);
  box-shadow: var(--bootui-shadow-md);
  color: var(--bootui-text);
}

.bootui-consent__text {
  flex: 1 1 22rem;
}

.bootui-consent__title {
  margin: 0 0 0.25rem;
  font-size: 1rem;
  font-weight: 700;
}

.bootui-consent__description {
  margin: 0;
  color: var(--bootui-text-muted);
  font-size: 0.85rem;
  line-height: 1.5;
}

.bootui-consent__actions {
  display: flex;
  flex: 0 0 auto;
  gap: 0.6rem;
}

.bootui-consent__button {
  padding: 0.5rem 1.1rem;
  border: 1px solid var(--bootui-border-alt);
  border-radius: 0.75rem;
  background: transparent;
  color: var(--bootui-text);
  font: inherit;
  font-size: 0.85rem;
  font-weight: 700;
  cursor: pointer;
  transition:
    background 0.15s ease,
    border-color 0.15s ease;
}

.bootui-consent__button:hover {
  border-color: var(--bootui-green);
  background: var(--bootui-nav-hover-bg);
}

.bootui-consent__button--primary {
  border-color: transparent;
  background: var(--bootui-btn-primary-bg);
  color: #ffffff;
}

.bootui-consent__button--primary:hover {
  border-color: transparent;
  background: var(--bootui-btn-primary-bg-hover);
}

.bootui-consent-enter-active,
.bootui-consent-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
}

.bootui-consent-enter-from,
.bootui-consent-leave-to {
  opacity: 0;
  transform: translateY(0.75rem);
}

@media (prefers-reduced-motion: reduce) {
  .bootui-consent-enter-active,
  .bootui-consent-leave-active {
    transition: none;
  }
}

@media (max-width: 40rem) {
  .bootui-consent__actions {
    width: 100%;
  }

  .bootui-consent__button {
    flex: 1 1 0;
  }
}
</style>
