<script setup>
import {computed, h, onBeforeUnmount, ref} from 'vue'
import {ClientOnly, withBase} from 'vuepress/client'
import {useDarkMode} from '@theme/useDarkMode'
import {useData} from '@theme/useData'
import VPAutoLink from '@theme/VPAutoLink.vue'

const {frontmatter, siteLocale} = useData()
const isDarkMode = useDarkMode()

const heroText = computed(() => {
  if (frontmatter.value.heroText === null) return null
  return frontmatter.value.heroText || siteLocale.value.title || 'Hello'
})

const tagline = computed(() => {
  if (frontmatter.value.tagline === null) return null
  return frontmatter.value.tagline || siteLocale.value.description || 'Welcome to your VuePress site'
})

const heroImage = computed(() => {
  if (isDarkMode.value && frontmatter.value.heroImageDark !== undefined) return frontmatter.value.heroImageDark
  return frontmatter.value.heroImage
})

const heroAlt = computed(() => frontmatter.value.heroAlt || heroText.value || 'hero')
const heroHeight = computed(() => frontmatter.value.heroHeight ?? 280)

const actions = computed(() => {
  if (!Array.isArray(frontmatter.value.actions)) return []
  return frontmatter.value.actions.map(({type = 'primary', ...rest}) => ({type, ...rest}))
})

const HomeHeroImage = () => {
  if (!heroImage.value) return null

  const img = h('img', {
    class: 'vp-hero-image',
    src: withBase(heroImage.value),
    alt: heroAlt.value,
    height: heroHeight.value
  })

  if (frontmatter.value.heroImageDark === undefined) return img

  return h(ClientOnly, () => img)
}

const consoleAddress = computed(() => frontmatter.value.consoleAddress ?? null)

const copyState = ref('idle')
let resetTimer = null

const statusText = computed(() => {
  if (copyState.value === 'copied') return 'Copied'
  if (copyState.value === 'failed') return 'Copy failed — select the address instead'
  return ''
})

const settle = (state) => {
  copyState.value = state
  if (resetTimer) clearTimeout(resetTimer)
  resetTimer = setTimeout(() => {
    copyState.value = 'idle'
    resetTimer = null
  }, 2400)
}

const copyAddress = async () => {
  try {
    await navigator.clipboard.writeText(consoleAddress.value)
    settle('copied')
  } catch {
    settle('failed')
  }
}

onBeforeUnmount(() => {
  if (resetTimer) clearTimeout(resetTimer)
})
</script>

<template>
  <header class="vp-hero">
    <HomeHeroImage />

    <h1 v-if="heroText" id="main-title">
      {{ heroText }}
    </h1>

    <p v-if="tagline" class="vp-hero-description">
      {{ tagline }}
    </p>

    <p v-if="actions.length" class="vp-hero-actions">
      <VPAutoLink
        v-for="action in actions"
        :key="action.text"
        class="vp-hero-action-button"
        :class="[action.type]"
        :config="action"
      />
    </p>

    <p v-if="consoleAddress" class="vp-console">
      <button
        type="button"
        class="vp-console-copy"
        :class="`is-${copyState}`"
        :aria-label="`Copy the console address ${consoleAddress}`"
        @click="copyAddress"
      >
        <code class="vp-console-address">{{ consoleAddress }}</code>
        <svg
          class="vp-console-icon"
          viewBox="0 0 16 16"
          width="16"
          height="16"
          fill="currentColor"
          aria-hidden="true"
          focusable="false"
        >
          <path
            v-if="copyState === 'copied'"
            d="M13.854 3.646a.5.5 0 0 1 0 .708l-7 7a.5.5 0 0 1-.708 0l-3.5-3.5a.5.5 0 1 1 .708-.708L6.5 10.293l6.646-6.647a.5.5 0 0 1 .708 0z"
          />
          <template v-else>
            <path
              d="M4 1.5H3a2 2 0 0 0-2 2V14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V3.5a2 2 0 0 0-2-2h-1v1h1a1 1 0 0 1 1 1V14a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V3.5a1 1 0 0 1 1-1h1v-1z"
            />
            <path
              d="M9.5 1a.5.5 0 0 1 .5.5v1a.5.5 0 0 1-.5.5h-3a.5.5 0 0 1-.5-.5v-1a.5.5 0 0 1 .5-.5h3zm-3-1A1.5 1.5 0 0 0 5 1.5v1A1.5 1.5 0 0 0 6.5 4h3A1.5 1.5 0 0 0 11 2.5v-1A1.5 1.5 0 0 0 9.5 0h-3z"
            />
          </template>
        </svg>
      </button>
      <span class="vp-console-status" role="status">{{ statusText }}</span>
    </p>
  </header>
</template>
