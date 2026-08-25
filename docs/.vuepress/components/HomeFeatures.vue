<script setup>
import {computed} from 'vue'
import {usePageFrontmatter} from 'vuepress/client'

const frontmatter = usePageFrontmatter()

const features = computed(() => frontmatter.value.features ?? [])
</script>

<template>
  <div v-if="features.length" class="vp-features">
    <component
      :is="feature.link ? 'RouteLink' : 'div'"
      v-for="feature in features"
      :key="feature.title"
      v-bind="feature.link ? {to: feature.link} : {}"
      class="vp-feature"
      :class="{'vp-feature-link': feature.link}"
    >
      <h2>{{ feature.title }}</h2>
      <p>{{ feature.details }}</p>
      <span v-if="feature.linkText" class="vp-feature-cta" aria-hidden="true">{{ feature.linkText }}</span>
      <span v-if="feature.links" class="vp-feature-links">
        <RouteLink
          v-for="secondary in feature.links"
          :key="secondary.link"
          :to="secondary.link"
          class="vp-feature-cta vp-feature-cta-link"
        >
          {{ secondary.text }}
        </RouteLink>
      </span>
    </component>
  </div>
</template>
