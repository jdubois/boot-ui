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

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', redirect: '/overview' },
    { path: '/overview', name: 'overview', component: Overview, meta: { icon: 'bi-speedometer2', title: 'Overview' } },
    { path: '/beans', name: 'beans', component: Beans, meta: { icon: 'bi-diagram-3', title: 'Beans' } },
    { path: '/conditions', name: 'conditions', component: Conditions, meta: { icon: 'bi-check2-circle', title: 'Conditions' } },
    { path: '/config', name: 'config', component: Config, meta: { icon: 'bi-sliders', title: 'Configuration' } },
    { path: '/mappings', name: 'mappings', component: Mappings, meta: { icon: 'bi-signpost-2', title: 'Mappings' } },
    { path: '/health', name: 'health', component: Health, meta: { icon: 'bi-heart-pulse', title: 'Health' } },
    { path: '/loggers', name: 'loggers', component: Loggers, meta: { icon: 'bi-journal-text', title: 'Loggers' } },
    { path: '/data', name: 'data', component: Data, meta: { icon: 'bi-database', title: 'Data' } }
  ]
})

createApp(App).use(router).mount('#app')
