<script setup>
import {computed} from 'vue'
import {useFrontmatter} from 'vuepress/client'
import SiteCopyright from './SiteCopyright.vue'

const frontmatter = useFrontmatter()
const footer = computed(() => frontmatter.value.footer)
</script>

<template>
  <div class="vp-footer" vp-footer>
    <p v-if="footer" class="bootui-footer-note">{{ footer }}</p>
    <SiteCopyright />
  </div>
</template>
