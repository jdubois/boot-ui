const Overview = () => import('./views/Overview.vue')
const Beans = () => import('./views/Beans.vue')
const Conditions = () => import('./views/Conditions.vue')
const Config = () => import('./views/Config.vue')
const Mappings = () => import('./views/Mappings.vue')
const Health = () => import('./views/Health.vue')
const Loggers = () => import('./views/Loggers.vue')
const DatabaseConnectionsPools = () => import('./views/DatabaseConnectionsPools.vue')
const Data = () => import('./views/Data.vue')
const Startup = () => import('./views/Startup.vue')
const Scheduled = () => import('./views/Scheduled.vue')
const HttpProbe = () => import('./views/HttpProbe.vue')
const Pentesting = () => import('./views/Pentesting.vue')
const Architecture = () => import('./views/Architecture.vue')
const LogTail = () => import('./views/LogTail.vue')
const ProfileDiff = () => import('./views/ProfileDiff.vue')
const Cache = () => import('./views/Cache.vue')
const Security = () => import('./views/Security.vue')
const Memory = () => import('./views/Memory.vue')
const TuningAdvisor = () => import('./views/TuningAdvisor.vue')
const HeapDump = () => import('./views/HeapDump.vue')
const Metrics = () => import('./views/Metrics.vue')
const Vulnerabilities = () => import('./views/Dependencies.vue')
const DevServices = () => import('./views/DevServices.vue')
const DevTools = () => import('./views/DevTools.vue')
const Traces = () => import('./views/Traces.vue')
const Ai = () => import('./views/Ai.vue')
const Copilot = () => import('./views/Copilot.vue')

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
    path: '/tuning-advisor',
    name: 'tuning-advisor',
    component: TuningAdvisor,
    meta: {group: groups.runtime, icon: 'bi-calculator', title: 'Tuning advisor'}
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
    path: '/database-connection-pools',
    name: 'database-connection-pools',
    component: DatabaseConnectionsPools,
    meta: {group: groups.services, icon: 'bi-hdd-network', title: 'Database Connection Pools'}
  },
  {
    path: '/data',
    name: 'data',
    component: Data,
    meta: {group: groups.services, icon: 'bi-database', title: 'Spring Data'}
  },
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
    path: '/architecture',
    name: 'architecture',
    component: Architecture,
    meta: {group: groups.diagnostics, icon: 'bi-diagram-2', title: 'Architecture'}
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
    meta: {group: groups.developerTools, icon: 'bi-claude', title: 'Claude Code'}
  },
  {path: '/dependencies', redirect: '/vulnerabilities'}
]
