<script setup>
import {computed, onBeforeUnmount, onMounted, ref} from 'vue'
import overviewImage from '../../images/bootui-overview.webp'
import activityImage from '../../images/bootui-activity.webp'
import githubImage from '../../images/bootui-github.webp'
import jvmTuningImage from '../../images/bootui-jvm-tuning.webp'
import heapDumpImage from '../../images/bootui-heap-dump.webp'
import graalvmImage from '../../images/bootui-graalvm.webp'
import copilotImage from '../../images/bootui-copilot.webp'

const slides = [
  {
    title: 'Overview',
    src: overviewImage,
    caption: 'Score your app on demand and jump straight to the advisors that need attention.'
  },
  {
    title: 'Live Activity',
    src: activityImage,
    caption: 'Watch requests, queries, and cache activity stream from the running app in real time.'
  },
  {
    title: 'GitHub',
    src: githubImage,
    caption: 'Review pull requests, workflow runs, and API quota for the connected repository.'
  },
  {
    title: 'JVM Tuning',
    src: jvmTuningImage,
    caption: 'Plan bare-metal JVM options and calculate Kubernetes memory sizing.'
  },
  {
    title: 'Heap Dump',
    src: heapDumpImage,
    caption: 'Capture and analyse heap dumps to find what is retaining memory.'
  },
  {
    title: 'GraalVM',
    src: graalvmImage,
    caption: 'Check native-image readiness before you compile an ahead-of-time binary.'
  },
  {
    title: 'Copilot',
    src: copilotImage,
    caption: 'Explore local GitHub Copilot CLI activity, sessions, and tool usage.'
  }
]

const AUTOPLAY_INTERVAL = 6000

const current = ref(0)
const paused = ref(false)
let timer = null

const currentSlide = computed(() => slides[current.value])

function goTo(index) {
  current.value = (index + slides.length) % slides.length
}

function next() {
  goTo(current.value + 1)
}

function previous() {
  goTo(current.value - 1)
}

function prefersReducedMotion() {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  )
}

function startAutoplay() {
  stopAutoplay()
  if (prefersReducedMotion()) {
    return
  }
  timer = window.setInterval(() => {
    if (!paused.value) {
      next()
    }
  }, AUTOPLAY_INTERVAL)
}

function stopAutoplay() {
  if (timer !== null) {
    window.clearInterval(timer)
    timer = null
  }
}

function pause() {
  paused.value = true
}

function resume() {
  paused.value = false
}

onMounted(startAutoplay)
onBeforeUnmount(stopAutoplay)
</script>

<template>
  <section
    class="bootui-home-carousel"
    aria-roledescription="carousel"
    aria-label="BootUI panel screenshots"
    @mouseenter="pause"
    @mouseleave="resume"
    @focusin="pause"
    @focusout="resume"
  >
    <div class="bootui-carousel-frame">
      <div class="bootui-carousel-viewport">
        <div class="bootui-carousel-track" :style="{transform: `translateX(-${current * 100}%)`}">
          <div
            v-for="(slide, index) in slides"
            :key="slide.title"
            class="bootui-carousel-slide"
            role="group"
            aria-roledescription="slide"
            :aria-label="`${index + 1} of ${slides.length}: ${slide.title}`"
            :aria-hidden="index !== current"
          >
            <img
              class="bootui-carousel-img"
              :src="slide.src"
              :alt="`BootUI ${slide.title} panel`"
              loading="lazy"
              decoding="async"
            />
          </div>
        </div>
      </div>

      <button
        type="button"
        class="bootui-carousel-control bootui-carousel-control--prev"
        aria-label="Previous panel"
        @click="previous"
      >
        <svg
          class="bootui-carousel-icon"
          xmlns="http://www.w3.org/2000/svg"
          viewBox="0 0 16 16"
          fill="currentColor"
          aria-hidden="true"
        >
          <path
            fill-rule="evenodd"
            d="M11.354 1.646a.5.5 0 0 1 0 .708L5.707 8l5.647 5.646a.5.5 0 0 1-.708.708l-6-6a.5.5 0 0 1 0-.708l6-6a.5.5 0 0 1 .708 0"
          />
        </svg>
      </button>
      <button
        type="button"
        class="bootui-carousel-control bootui-carousel-control--next"
        aria-label="Next panel"
        @click="next"
      >
        <svg
          class="bootui-carousel-icon"
          xmlns="http://www.w3.org/2000/svg"
          viewBox="0 0 16 16"
          fill="currentColor"
          aria-hidden="true"
        >
          <path
            fill-rule="evenodd"
            d="M4.646 1.646a.5.5 0 0 1 .708 0l6 6a.5.5 0 0 1 0 .708l-6 6a.5.5 0 0 1-.708-.708L10.293 8 4.646 2.354a.5.5 0 0 1 0-.708"
          />
        </svg>
      </button>

      <div class="bootui-carousel-caption" aria-live="polite">
        <span class="bootui-carousel-caption-title">{{ currentSlide.title }}</span>
        <span class="bootui-carousel-caption-text">{{ currentSlide.caption }}</span>
      </div>
    </div>

    <div class="bootui-carousel-indicators" role="group" aria-label="Choose a panel screenshot">
      <button
        v-for="(slide, index) in slides"
        :key="slide.title"
        type="button"
        class="bootui-carousel-dot"
        :class="{'bootui-carousel-dot--active': index === current}"
        :aria-current="index === current ? 'true' : undefined"
        :aria-label="`Show ${slide.title}`"
        @click="goTo(index)"
      ></button>
    </div>
  </section>
</template>

<style scoped>
.bootui-home-carousel {
  width: 100%;
}

.bootui-carousel-frame {
  position: relative;
  overflow: hidden;
  border: 1px solid var(--bootui-border);
  border-radius: 1.1rem;
  box-shadow: var(--bootui-shadow-md);
  background: var(--bootui-surface);
}

.bootui-carousel-viewport {
  overflow: hidden;
}

.bootui-carousel-track {
  display: flex;
  transition: transform 520ms cubic-bezier(0.4, 0, 0.2, 1);
}

.bootui-carousel-slide {
  flex: 0 0 100%;
  min-width: 100%;
}

.bootui-carousel-img {
  display: block;
  width: 100%;
  height: auto;
  aspect-ratio: 16 / 9;
  margin: 0;
  border: 0;
  border-radius: 0;
  box-shadow: none;
}

.bootui-carousel-control {
  position: absolute;
  top: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2.75rem;
  height: 2.75rem;
  transform: translateY(-50%);
  border: 1px solid var(--bootui-border);
  border-radius: 999px;
  background: var(--bootui-surface);
  color: var(--bootui-text);
  box-shadow: var(--bootui-shadow-sm);
  cursor: pointer;
  opacity: 0.92;
  transition:
    background 160ms ease,
    color 160ms ease,
    opacity 160ms ease;
}

.bootui-carousel-icon {
  width: 1.1rem;
  height: 1.1rem;
}

.bootui-carousel-control:hover {
  opacity: 1;
  color: #ffffff;
  background: linear-gradient(135deg, var(--bootui-green), var(--bootui-blue));
}

.bootui-carousel-control--prev {
  left: 1rem;
}

.bootui-carousel-control--next {
  right: 1rem;
}

.bootui-carousel-caption {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  padding: 2.5rem 1.5rem 1.25rem;
  color: #ffffff;
  text-align: left;
  background: linear-gradient(to top, rgba(8, 22, 16, 0.82), rgba(8, 22, 16, 0));
}

.bootui-carousel-caption-title {
  font-size: 1.15rem;
  font-weight: 800;
}

.bootui-carousel-caption-text {
  max-width: 48rem;
  font-size: 0.95rem;
  opacity: 0.92;
}

.bootui-carousel-indicators {
  display: flex;
  flex-wrap: wrap;
  gap: 0.55rem;
  justify-content: center;
  margin-top: 1rem;
}

.bootui-carousel-dot {
  width: 0.7rem;
  height: 0.7rem;
  padding: 0;
  border: 1px solid var(--bootui-border-alt);
  border-radius: 999px;
  background: var(--bootui-surface-alt);
  cursor: pointer;
  transition:
    background 160ms ease,
    transform 160ms ease;
}

.bootui-carousel-dot:hover {
  transform: scale(1.15);
}

.bootui-carousel-dot--active {
  border-color: transparent;
  background: linear-gradient(135deg, var(--bootui-green), var(--bootui-blue));
}

@media (max-width: 48rem) {
  .bootui-carousel-caption {
    padding: 2rem 1rem 1rem;
  }

  .bootui-carousel-caption-text {
    display: none;
  }

  .bootui-carousel-control {
    width: 2.25rem;
    height: 2.25rem;
  }

  .bootui-carousel-icon {
    width: 0.95rem;
    height: 0.95rem;
  }
}

@media (prefers-reduced-motion: reduce) {
  .bootui-carousel-track {
    transition: none;
  }
}
</style>
