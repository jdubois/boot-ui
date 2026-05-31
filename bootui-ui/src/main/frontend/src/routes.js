import Overview from './views/Overview.vue'
import Beans from './views/Beans.vue'
import Conditions from './views/Conditions.vue'
import Config from './views/Config.vue'
import Mappings from './views/Mappings.vue'
import Health from './views/Health.vue'
import Loggers from './views/Loggers.vue'
import Hikari from './views/Hikari.vue'
import Data from './views/Data.vue'
import Startup from './views/Startup.vue'
import Scheduled from './views/Scheduled.vue'
import HttpProbe from './views/HttpProbe.vue'
import Pentesting from './views/Pentesting.vue'
import Architecture from './views/Architecture.vue'
import LogTail from './views/LogTail.vue'
import ProfileDiff from './views/ProfileDiff.vue'
import Cache from './views/Cache.vue'
import Security from './views/Security.vue'
import Memory from './views/Memory.vue'
import HeapDump from './views/HeapDump.vue'
import Metrics from './views/Metrics.vue'
import Vulnerabilities from './views/Dependencies.vue'
import DevServices from './views/DevServices.vue'
import DevTools from './views/DevTools.vue'
import Traces from './views/Traces.vue'
import Ai from './views/Ai.vue'
import Copilot from './views/Copilot.vue'

export const groups = {
  overview: 'overview',
  runtime: 'runtime',
  configuration: 'configuration',
  services: 'services',
  diagnostics: 'diagnostics',
  developerTools: 'developer-tools'
}

export const routes = [
  {path: '/', redirect: '/overview'},
  {
    path: '/overview',
    name: 'overview',
    component: Overview,
    meta: {group: groups.overview, icon: 'bi-speedometer2', title: 'Overview'}
  },
  {
    path: '/health',
    name: 'health',
    component: Health,
    meta: {group: groups.runtime, icon: 'bi-heart-pulse', title: 'Health'}
  },
  {
    path: '/metrics',
    name: 'metrics',
    component: Metrics,
    meta: {group: groups.runtime, icon: 'bi-activity', title: 'Metrics'}
  },
  {
    path: '/memory',
    name: 'memory',
    component: Memory,
    meta: {group: groups.runtime, icon: 'bi-memory', title: 'Memory'}
  },
  {
    path: '/heap-dump',
    name: 'heap-dump',
    component: HeapDump,
    meta: {group: groups.runtime, icon: 'bi-file-earmark-binary', title: 'Heap Dump'}
  },
  {
    path: '/startup',
    name: 'startup',
    component: Startup,
    meta: {group: groups.runtime, icon: 'bi-bar-chart-steps', title: 'Startup Timeline'}
  },
  {
    path: '/config',
    name: 'config',
    component: Config,
    meta: {group: groups.configuration, icon: 'bi-sliders', title: 'Configuration'}
  },
  {
    path: '/profiles',
    name: 'profiles',
    component: ProfileDiff,
    meta: {group: groups.configuration, icon: 'bi-layers', title: 'Profile Diff'}
  },
  {
    path: '/loggers',
    name: 'loggers',
    component: Loggers,
    meta: {group: groups.configuration, icon: 'bi-journal-text', title: 'Loggers'}
  },
  {
    path: '/beans',
    name: 'beans',
    component: Beans,
    meta: {group: groups.configuration, icon: 'bi-diagram-3', title: 'Beans'}
  },
  {
    path: '/conditions',
    name: 'conditions',
    component: Conditions,
    meta: {group: groups.configuration, icon: 'bi-check2-circle', title: 'Conditions'}
  },
  {
    path: '/mappings',
    name: 'mappings',
    component: Mappings,
    meta: {group: groups.configuration, icon: 'bi-signpost-2', title: 'Mappings'}
  },
  {
    path: '/scheduled',
    name: 'scheduled',
    component: Scheduled,
    meta: {group: groups.services, icon: 'bi-clock-history', title: 'Scheduled Tasks'}
  },
  {
    path: '/hikari',
    name: 'hikari',
    component: Hikari,
    meta: {group: groups.services, icon: 'bi-hdd-network', title: 'Connection Pools'}
  },
  {path: '/data', name: 'data', component: Data, meta: {group: groups.services, icon: 'bi-database', title: 'Data'}},
  {
    path: '/cache',
    name: 'cache',
    component: Cache,
    meta: {group: groups.services, icon: 'bi-hdd-stack', title: 'Cache'}
  },
  {
    path: '/security',
    name: 'security',
    component: Security,
    meta: {group: groups.services, icon: 'bi-person-lock', title: 'Security'}
  },
  {path: '/ai', name: 'ai', component: Ai, meta: {group: groups.services, icon: 'bi-cpu', title: 'AI Usage'}},
  {
    path: '/traces',
    name: 'traces',
    component: Traces,
    meta: {group: groups.diagnostics, icon: 'bi-bezier2', title: 'Traces'}
  },
  {
    path: '/log-tail',
    name: 'log-tail',
    component: LogTail,
    meta: {group: groups.diagnostics, icon: 'bi-terminal', title: 'Log Tail'}
  },
  {
    path: '/http-probe',
    name: 'http-probe',
    component: HttpProbe,
    meta: {group: groups.diagnostics, icon: 'bi-send', title: 'HTTP Probe'}
  },
  {
    path: '/pentest',
    name: 'pentest',
    component: Pentesting,
    meta: {group: groups.diagnostics, icon: 'bi-shield-exclamation', title: 'Pentesting'}
  },
  {
    path: '/vulnerabilities',
    name: 'vulnerabilities',
    component: Vulnerabilities,
    meta: {group: groups.diagnostics, icon: 'bi-bug', title: 'Vulnerabilities'}
  },
  {
    path: '/architecture',
    name: 'architecture',
    component: Architecture,
    meta: {group: groups.diagnostics, icon: 'bi-diagram-2', title: 'Architecture'}
  },
  {
    path: '/devtools',
    name: 'devtools',
    component: DevTools,
    meta: {group: groups.developerTools, icon: 'bi-lightning-charge', title: 'DevTools'}
  },
  {
    path: '/dev-services',
    name: 'dev-services',
    component: DevServices,
    meta: {group: groups.developerTools, icon: 'bi-box-seam', title: 'Dev Services'}
  },
  {
    path: '/copilot',
    name: 'copilot',
    component: Copilot,
    meta: {group: groups.developerTools, icon: 'bi-robot', title: 'Copilot'}
  },
  {
    path: '/claude-code',
    name: 'claude-code',
    component: Copilot,
    meta: {group: groups.developerTools, icon: 'bi-stars', title: 'Claude Code'}
  },
  {path: '/dependencies', redirect: '/vulnerabilities'}
]
