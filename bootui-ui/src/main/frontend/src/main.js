import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import 'bootstrap/dist/css/bootstrap.min.css'
import 'bootstrap-icons/font/bootstrap-icons.css'
import App from './App.vue'

import Overview from './views/Overview.vue'
import Beans from './views/Beans.vue'
import Conditions from './views/Conditions.vue'
import Config from './views/Config.vue'
import Mappings from './views/Mappings.vue'
import Health from './views/Health.vue'
import Loggers from './views/Loggers.vue'
import Data from './views/Data.vue'
import Startup from './views/Startup.vue'
import Scheduled from './views/Scheduled.vue'
import HttpProbe from './views/HttpProbe.vue'
import LogTail from './views/LogTail.vue'
import ProfileDiff from './views/ProfileDiff.vue'
import Security from './views/Security.vue'
import Memory from './views/Memory.vue'
import DevServices from './views/DevServices.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/overview' },
    { path: '/overview', name: 'overview', component: Overview, meta: { icon: 'bi-speedometer2', title: 'Overview' } },
    { path: '/health', name: 'health', component: Health, meta: { icon: 'bi-heart-pulse', title: 'Health' } },
    { path: '/startup', name: 'startup', component: Startup, meta: { icon: 'bi-bar-chart-steps', title: 'Startup Timeline' } },
    { path: '/memory', name: 'memory', component: Memory, meta: { icon: 'bi-memory', title: 'Memory' } },
    { path: '/conditions', name: 'conditions', component: Conditions, meta: { icon: 'bi-check2-circle', title: 'Conditions' } },
    { path: '/beans', name: 'beans', component: Beans, meta: { icon: 'bi-diagram-3', title: 'Beans' } },
    { path: '/mappings', name: 'mappings', component: Mappings, meta: { icon: 'bi-signpost-2', title: 'Mappings' } },
    { path: '/config', name: 'config', component: Config, meta: { icon: 'bi-sliders', title: 'Configuration' } },
    { path: '/profiles', name: 'profiles', component: ProfileDiff, meta: { icon: 'bi-layers', title: 'Profile Diff' } },
    { path: '/dev-services', name: 'dev-services', component: DevServices, meta: { icon: 'bi-box-seam', title: 'Dev Services' } },
    { path: '/data', name: 'data', component: Data, meta: { icon: 'bi-database', title: 'Data' } },
    { path: '/security', name: 'security', component: Security, meta: { icon: 'bi-shield-lock', title: 'Security' } },
    { path: '/scheduled', name: 'scheduled', component: Scheduled, meta: { icon: 'bi-clock-history', title: 'Scheduled Tasks' } },
    { path: '/loggers', name: 'loggers', component: Loggers, meta: { icon: 'bi-journal-text', title: 'Loggers' } },
    { path: '/log-tail', name: 'log-tail', component: LogTail, meta: { icon: 'bi-terminal', title: 'Log Tail' } },
    { path: '/http-probe', name: 'http-probe', component: HttpProbe, meta: { icon: 'bi-send', title: 'HTTP Probe' } }
  ]
})

createApp(App).use(router).mount('#app')
