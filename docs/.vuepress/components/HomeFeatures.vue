<script setup>
import {computed} from 'vue'
import {usePageFrontmatter} from 'vuepress/client'
import ShowcaseVideo from './ShowcaseVideo.vue'

const frontmatter = usePageFrontmatter()

const features = computed(() => frontmatter.value.features ?? [])
// The home page's own frontmatter opts in, so the video renders with the feature cards; index.css orders it after them.
const showcaseVideo = computed(() => frontmatter.value.showcaseVideo === true)
</script>

<template>
  <ShowcaseVideo v-if="showcaseVideo" class="bootui-home-showcase-video" />
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
    </component>
  </div>
</template>
