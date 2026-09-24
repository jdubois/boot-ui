<script setup>
import {onBeforeUnmount, onMounted, ref} from 'vue'
import {withBase} from 'vuepress/client'

// The hero's "Watch the video" action links to this anchor. The click is a user gesture, so the
// browser allows playback with sound once the player is scrolled into view.
const ANCHOR = 'showcase-video'

const videoSrc = withBase('/videos/bootui-showcase.mp4')
const posterSrc = withBase('/videos/bootui-showcase-poster.jpg')
const captionsSrc = withBase('/videos/bootui-showcase.en.vtt')

const section = ref(null)
const player = ref(null)

function watchVideo(event) {
  if (!(event.target instanceof Element)) return
  const link = event.target.closest(`a[href$="#${ANCHOR}"]`)
  if (!link || !section.value || !player.value) return
  event.preventDefault()
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  section.value.scrollIntoView({behavior: reduceMotion ? 'auto' : 'smooth', block: 'center'})
  player.value.focus({preventScroll: true})
  player.value.play().catch(() => {})
}

onMounted(() => document.addEventListener('click', watchVideo))
onBeforeUnmount(() => document.removeEventListener('click', watchVideo))
</script>

<template>
  <section :id="ANCHOR" ref="section" class="bootui-showcase-video" aria-labelledby="bootui-showcase-video-title">
    <h2 id="bootui-showcase-video-title" class="bootui-showcase-video-title">See BootUI in four minutes</h2>
    <div class="bootui-showcase-video-frame">
      <video
        ref="player"
        class="bootui-showcase-video-player"
        controls
        playsinline
        preload="none"
        width="1920"
        height="1080"
        :poster="posterSrc"
        aria-describedby="bootui-showcase-video-caption"
      >
        <source :src="videoSrc" type="video/mp4" />
        <track kind="captions" srclang="en" label="English" :src="captionsSrc" default />
        <a :href="videoSrc">Download the BootUI showcase video (MP4)</a>
      </video>
    </div>
    <p id="bootui-showcase-video-caption" class="bootui-showcase-video-caption">
      The Spring Boot sample app from start-up to fix: advisors, Live Activity, runtime panels, and an AI agent
      verifying its change over MCP.
    </p>
  </section>
</template>

<style scoped>
.bootui-showcase-video {
  width: 100%;
  margin: 2.5rem 0;
}

.bootui-showcase-video-title {
  margin: 0 0 1rem;
  padding: 0;
  border: 0;
  text-align: center;
}

.bootui-showcase-video-frame {
  overflow: hidden;
  border: 1px solid var(--bootui-border);
  border-radius: 1.1rem;
  box-shadow: var(--bootui-shadow-md);
  background: var(--bootui-surface);
}

.bootui-showcase-video-player {
  display: block;
  width: 100%;
  height: auto;
  aspect-ratio: 16 / 9;
  background: #0a1120;
}

.bootui-showcase-video-caption {
  margin: 0.75rem 0 0;
  text-align: center;
  color: var(--bootui-text-muted);
  font-size: 0.95rem;
}
</style>
