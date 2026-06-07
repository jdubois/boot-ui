const Overview = () => import('./views/Overview.vue')
const GitHub = () => import('./views/GitHub.vue')
const Beans = () => import('./views/Beans.vue')
const Conditions = () => import('./views/Conditions.vue')
const Config = () => import('./views/Config.vue')
const Mappings = () => import('./views/Mappings.vue')
const Health = () => import('./views/Health.vue')
const HttpSessions = () => import('./views/HttpSessions.vue')
const Loggers = () => import('./views/Loggers.vue')
const DatabaseConnectionsPools = () => import('./views/DatabaseConnectionsPools.vue')
const Data = () => import('./views/Data.vue')
const HibernateAdvisor = () => import('./views/HibernateAdvisor.vue')
const Flyway = () => import('./views/Flyway.vue')
const Liquibase = () => import('./views/Liquibase.vue')
const Startup = () => import('./views/Startup.vue')
const Scheduled = () => import('./views/Scheduled.vue')
const HttpProbe = () => import('./views/HttpProbe.vue')
const Pentesting = () => import('./views/Pentesting.vue')
const Architecture = () => import('./views/Architecture.vue')
const RestApiAdvisor = () => import('./views/RestApiAdvisor.vue')
const GraalVm = () => import('./views/GraalVm.vue')
const LogTail = () => import('./views/LogTail.vue')
const HttpExchanges = () => import('./views/HttpExchanges.vue')
const ProfileDiff = () => import('./views/ProfileDiff.vue')
const SpringCache = () => import('./views/SpringCache.vue')
const SpringSecurity = () => import('./views/SpringSecurity.vue')
const SpringAdvisor = () => import('./views/SpringAdvisor.vue')
const SecurityAdvisor = () => import('./views/SecurityAdvisor.vue')
const SecurityLogs = () => import('./views/SecurityLogs.vue')
const Memory = () => import('./views/Memory.vue')
const JvmTuning = () => import('./views/JvmTuning.vue')
const HeapDump = () => import('./views/HeapDump.vue')
const Threads = () => import('./views/Threads.vue')
const MemoryAdvisor = () => import('./views/MemoryAdvisor.vue')
const Metrics = () => import('./views/Metrics.vue')
const Vulnerabilities = () => import('./views/Dependencies.vue')
const DevServices = () => import('./views/DevServices.vue')
const DevTools = () => import('./views/DevTools.vue')
const Traces = () => import('./views/Traces.vue')
const Ai = () => import('./views/Ai.vue')
const Copilot = () => import('./views/Copilot.vue')

export const groups = {
  overview: 'overview',
  advisors: 'advisors',
  runtime: 'runtime',
  configuration: 'configuration',
  database: 'database',
  security: 'security',
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
    path: '/github',
    name: 'github',
    component: GitHub,
    meta: {group: groups.overview, icon: 'bi-github', title: 'GitHub'}
  },
  {
    path: '/architecture',
    name: 'architecture',
    component: Architecture,
    meta: {group: groups.advisors, icon: 'bi-diagram-2', title: 'Architecture'}
  },
  {
    path: '/rest-advisor',
    name: 'rest-advisor',
    component: RestApiAdvisor,
    meta: {group: groups.advisors, icon: 'bi-signpost-split', title: 'REST API'}
  },
  {
    path: '/spring-advisor',
    name: 'spring-advisor',
    component: SpringAdvisor,
    meta: {group: groups.advisors, icon: 'bi-lightbulb', title: 'Spring'}
  },
  {
    path: '/hibernate-advisor',
    name: 'hibernate-advisor',
    component: HibernateAdvisor,
    meta: {group: groups.advisors, icon: 'bi-database-gear', title: 'Hibernate'}
  },
  {
    path: '/memory-advisor',
    name: 'memory-advisor',
    component: MemoryAdvisor,
    meta: {group: groups.advisors, icon: 'bi-clipboard2-pulse', title: 'Memory'}
  },
  {
    path: '/security-advisor',
    name: 'security-advisor',
    component: SecurityAdvisor,
    meta: {group: groups.advisors, icon: 'bi-shield-check', title: 'Security'}
  },
  {
    path: '/pentest',
    name: 'pentest',
    component: Pentesting,
    meta: {group: groups.advisors, icon: 'bi-shield-exclamation', title: 'Pentesting'}
  },
  {
    path: '/vulnerabilities',
    name: 'vulnerabilities',
    component: Vulnerabilities,
    meta: {group: groups.advisors, icon: 'bi-bug', title: 'Vulnerabilities'}
  },
  {
    path: '/health',
    name: 'health',
    component: Health,
    meta: {group: groups.runtime, icon: 'bi-heart-pulse', title: 'Health'}
  },
  {
    path: '/http-sessions',
    name: 'http-sessions',
    component: HttpSessions,
    meta: {group: groups.runtime, icon: 'bi-cookie', title: 'HTTP Sessions'}
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
    path: '/jvm-tuning',
    name: 'jvm-tuning',
    component: JvmTuning,
    meta: {group: groups.runtime, icon: 'bi-sliders2-vertical', title: 'JVM Tuning'}
  },
  {
    path: '/heap-dump',
    name: 'heap-dump',
    component: HeapDump,
    meta: {group: groups.runtime, icon: 'bi-file-earmark-binary', title: 'Heap Dump'}
  },
  {
    path: '/threads',
    name: 'threads',
    component: Threads,
    meta: {group: groups.runtime, icon: 'bi-list-task', title: 'Threads'}
  },
  {
    path: '/startup',
    name: 'startup',
    component: Startup,
    meta: {group: groups.runtime, icon: 'bi-bar-chart-steps', title: 'Startup Timeline'}
  },
  {
    path: '/graalvm',
    name: 'graalvm',
    component: GraalVm,
    meta: {group: groups.runtime, icon: 'bi-rocket-takeoff', title: 'GraalVM'}
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
    path: '/database-connection-pools',
    name: 'database-connection-pools',
    component: DatabaseConnectionsPools,
    meta: {group: groups.database, icon: 'bi-hdd-network', title: 'Database Connection Pools'}
  },
  {
    path: '/data',
    name: 'data',
    component: Data,
    meta: {group: groups.database, icon: 'bi-database', title: 'Spring Data'}
  },
  {
    path: '/flyway',
    name: 'flyway',
    component: Flyway,
    meta: {group: groups.database, icon: 'bi-arrow-up-right-circle', title: 'Flyway'}
  },
  {
    path: '/liquibase',
    name: 'liquibase',
    component: Liquibase,
    meta: {group: groups.database, icon: 'bi-droplet', title: 'Liquibase'}
  },
  {
    path: '/spring-security',
    name: 'spring-security',
    component: SpringSecurity,
    meta: {group: groups.security, icon: 'bi-person-lock', title: 'Spring Security'}
  },
  {
    path: '/security-logs',
    name: 'security-logs',
    component: SecurityLogs,
    meta: {group: groups.security, icon: 'bi-shield-lock', title: 'Security Logs'}
  },
  {
    path: '/scheduled',
    name: 'scheduled',
    component: Scheduled,
    meta: {group: groups.services, icon: 'bi-clock-history', title: 'Scheduled Tasks'}
  },
  {
    path: '/spring-cache',
    name: 'spring-cache',
    component: SpringCache,
    meta: {group: groups.services, icon: 'bi-hdd-stack', title: 'Spring Cache'}
  },
  {
    path: '/ai',
    name: 'ai',
    component: Ai,
    meta: {group: groups.services, icon: 'bi-cpu', title: 'AI Usage'}
  },
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
    path: '/http-exchanges',
    name: 'http-exchanges',
    component: HttpExchanges,
    meta: {group: groups.diagnostics, icon: 'bi-arrow-left-right', title: 'HTTP Exchanges'}
  },
  {
    path: '/http-probe',
    name: 'http-probe',
    component: HttpProbe,
    meta: {group: groups.diagnostics, icon: 'bi-send', title: 'HTTP Probe'}
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
  {path: '/tuning-advisor', redirect: '/jvm-tuning'},
  {path: '/dependencies', redirect: '/vulnerabilities'}
]
