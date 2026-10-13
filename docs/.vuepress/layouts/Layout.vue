<script setup>
import ParentLayout from '@vuepress/theme-default/layouts/Layout.vue'
import SiteCopyright from '../components/SiteCopyright.vue'
</script>

<template>
  <ParentLayout>
    <template #page-bottom>
      <footer class="bootui-page-footer">
        <SiteCopyright />
      </footer>
    </template>
  </ParentLayout>
</template>
