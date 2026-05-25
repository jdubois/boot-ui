<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const router = useRouter()
const route = useRoute()
const overview = ref(null)
const error = ref(null)

const routes = router.options.routes.filter(r => r.name)

async function loadOverview() {
  try {
    const res = await fetch('api/overview')
    if (!res.ok) throw new Error('HTTP ' + res.status)
    overview.value = await res.json()
  } catch (e) {
    error.value = e.message
  }
}

onMounted(loadOverview)
</script>

<template>
  <div class="d-flex flex-column min-vh-100">
    <nav class="navbar navbar-dark bg-success">
      <div class="container-fluid">
        <span class="navbar-brand mb-0 h1">
          <i class="bi bi-cup-hot-fill me-2"></i>BootUI
        </span>
        <span class="text-light small" v-if="overview">
          {{ overview.applicationName }} ·
          Spring Boot {{ overview.springBootVersion }} ·
          Java {{ overview.javaVersion }}
        </span>
      </div>
    </nav>

    <div class="container-fluid flex-grow-1">
      <div class="row h-100">
        <aside class="col-md-2 bg-light border-end py-3">
          <ul class="nav nav-pills flex-column">
            <li v-for="r in routes" :key="r.name" class="nav-item">
              <router-link
                :to="r.path"
                class="nav-link d-flex align-items-center"
                :class="{ active: route.name === r.name }">
                <i :class="['bi', r.meta.icon, 'me-2']"></i>
                <span>{{ r.meta.title }}</span>
                <span
                  v-if="r.meta.experimental"
                  class="badge bg-warning text-dark ms-auto"
                  title="Experimental panel: behavior and APIs may change before the first stable release.">
                  Experimental
                </span>
              </router-link>
            </li>
          </ul>
          <div v-if="overview && overview.activation && !overview.activation.enabled"
               class="alert alert-warning mt-3 small">
            BootUI is disabled: {{ overview.activation.reason }}
          </div>
        </aside>

        <main class="col-md-10 py-3">
          <div v-if="error" class="alert alert-danger">{{ error }}</div>
          <div v-if="route.meta && route.meta.experimental" class="alert alert-warning d-flex align-items-start">
            <i class="bi bi-exclamation-triangle-fill me-2 mt-1"></i>
            <div>
              <strong>Experimental panel.</strong>
              This panel is not yet part of the supported BootUI surface.
              Its behavior, data shape, and HTTP API may change or be removed before the first stable release.
            </div>
          </div>
          <router-view />
        </main>
      </div>
    </div>

    <footer class="border-top py-2 text-center text-muted small">
      BootUI · local developer console · loopback only by default
    </footer>
  </div>
</template>
