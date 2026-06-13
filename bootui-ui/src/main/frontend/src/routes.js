const Overview = () => import('./views/Overview.vue')
const GitHub = () => import('./views/GitHub.vue')
const Beans = () => import('./views/Beans.vue')
const Conditions = () => import('./views/Conditions.vue')
const Config = () => import('./views/Config.vue')
const Mappings = () => import('./views/Mappings.vue')
const Health = () => import('./views/Health.vue')
const HttpSessions = () => import('./views/HttpSessions.vue')
const Loggers = () => import('./views/Loggers.vue')
const DatabaseConnectionPools = () => import('./views/DatabaseConnectionPools.vue')
const SqlTrace = () => import('./views/SqlTrace.vue')
const Data = () => import('./views/Data.vue')
const Hibernate = () => import('./views/Hibernate.vue')
const Flyway = () => import('./views/Flyway.vue')
const Liquibase = () => import('./views/Liquibase.vue')
const Startup = () => import('./views/Startup.vue')
const Scheduled = () => import('./views/Scheduled.vue')
const HttpProbe = () => import('./views/HttpProbe.vue')
const Pentesting = () => import('./views/Pentesting.vue')
const Architecture = () => import('./views/Architecture.vue')
const RestApi = () => import('./views/RestApi.vue')
const GraalVm = () => import('./views/GraalVm.vue')
const Crac = () => import('./views/Crac.vue')
const LogTail = () => import('./views/LogTail.vue')
const Exceptions = () => import('./views/Exceptions.vue')
const HttpExchanges = () => import('./views/HttpExchanges.vue')
const ProfileDiff = () => import('./views/ProfileDiff.vue')
const SpringCache = () => import('./views/SpringCache.vue')
const SpringSecurity = () => import('./views/SpringSecurity.vue')
const Spring = () => import('./views/Spring.vue')
const Security = () => import('./views/Security.vue')
const SecurityLogs = () => import('./views/SecurityLogs.vue')
const LiveMemory = () => import('./views/LiveMemory.vue')
const JvmTuning = () => import('./views/JvmTuning.vue')
const HeapDump = () => import('./views/HeapDump.vue')
const Threads = () => import('./views/Threads.vue')
const Memory = () => import('./views/Memory.vue')
const Metrics = () => import('./views/Metrics.vue')
const Vulnerabilities = () => import('./views/Vulnerabilities.vue')
const DevServices = () => import('./views/DevServices.vue')
const DevTools = () => import('./views/DevTools.vue')
const Traces = () => import('./views/Traces.vue')
const Ai = () => import('./views/Ai.vue')
const Copilot = () => import('./views/Copilot.vue')
const McpServer = () => import('./views/McpServer.vue')

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
    meta: {group: groups.overview, icon: 'bi-speedometer2', title: 'Overview', shortcut: 'ov'}
  },
  {
    path: '/github',
    name: 'github',
    component: GitHub,
    meta: {group: groups.overview, icon: 'bi-github', title: 'GitHub', shortcut: 'gh'}
  },
  {
    path: '/architecture',
    name: 'architecture',
    component: Architecture,
    meta: {group: groups.advisors, icon: 'bi-diagram-2', title: 'Architecture', shortcut: 'ar'}
  },
  {
    path: '/rest-api',
    name: 'rest-api',
    component: RestApi,
    meta: {group: groups.advisors, icon: 'bi-signpost-split', title: 'REST API', shortcut: 'ra'}
  },
  {
    path: '/spring',
    name: 'spring',
    component: Spring,
    meta: {group: groups.advisors, icon: 'bi-leaf', title: 'Spring', shortcut: 'sr'}
  },
  {
    path: '/hibernate',
    name: 'hibernate',
    component: Hibernate,
    meta: {group: groups.advisors, icon: 'bi-database-gear', title: 'Hibernate', shortcut: 'hb'}
  },
  {
    path: '/memory',
    name: 'memory',
    component: Memory,
    meta: {group: groups.advisors, icon: 'bi-clipboard2-pulse', title: 'Memory', shortcut: 'mm'}
  },
  {
    path: '/security',
    name: 'security',
    component: Security,
    meta: {group: groups.advisors, icon: 'bi-shield-check', title: 'Security', shortcut: 'sc'}
  },
  {
    path: '/pentesting',
    name: 'pentesting',
    component: Pentesting,
    meta: {group: groups.advisors, icon: 'bi-shield-exclamation', title: 'Pentesting', shortcut: 'pt'}
  },
  {
    path: '/vulnerabilities',
    name: 'vulnerabilities',
    component: Vulnerabilities,
    meta: {group: groups.advisors, icon: 'bi-bug', title: 'Vulnerabilities', shortcut: 'vu'}
  },
  {
    path: '/health',
    name: 'health',
    component: Health,
    meta: {group: groups.runtime, icon: 'bi-heart-pulse', title: 'Health', shortcut: 'hl'}
  },
  {
    path: '/http-sessions',
    name: 'http-sessions',
    component: HttpSessions,
    meta: {group: groups.runtime, icon: 'bi-cookie', title: 'HTTP Sessions', shortcut: 'hs'}
  },
  {
    path: '/metrics',
    name: 'metrics',
    component: Metrics,
    meta: {group: groups.runtime, icon: 'bi-activity', title: 'Metrics', shortcut: 'mt'}
  },
  {
    path: '/live-memory',
    name: 'live-memory',
    component: LiveMemory,
    meta: {group: groups.runtime, icon: 'bi-memory', title: 'Live Memory', shortcut: 'lm'}
  },
  {
    path: '/jvm-tuning',
    name: 'jvm-tuning',
    component: JvmTuning,
    meta: {group: groups.runtime, icon: 'bi-sliders2-vertical', title: 'JVM Tuning', shortcut: 'jv'}
  },
  {
    path: '/heap-dump',
    name: 'heap-dump',
    component: HeapDump,
    meta: {group: groups.runtime, icon: 'bi-file-earmark-binary', title: 'Heap Dump', shortcut: 'hd'}
  },
  {
    path: '/threads',
    name: 'threads',
    component: Threads,
    meta: {group: groups.runtime, icon: 'bi-list-task', title: 'Threads', shortcut: 'th'}
  },
  {
    path: '/startup',
    name: 'startup',
    component: Startup,
    meta: {group: groups.runtime, icon: 'bi-bar-chart-steps', title: 'Startup Timeline', shortcut: 'su'}
  },
  {
    path: '/graalvm',
    name: 'graalvm',
    component: GraalVm,
    meta: {group: groups.runtime, icon: 'bi-rocket-takeoff', title: 'GraalVM', shortcut: 'gv'}
  },
  {
    path: '/crac',
    name: 'crac',
    component: Crac,
    meta: {group: groups.runtime, icon: 'bi-camera', title: 'CRaC', shortcut: 'cr'}
  },
  {
    path: '/config',
    name: 'config',
    component: Config,
    meta: {group: groups.configuration, icon: 'bi-sliders', title: 'Configuration', shortcut: 'cf'}
  },
  {
    path: '/profile-diff',
    name: 'profile-diff',
    component: ProfileDiff,
    meta: {group: groups.configuration, icon: 'bi-layers', title: 'Profile Diff', shortcut: 'pd'}
  },
  {
    path: '/loggers',
    name: 'loggers',
    component: Loggers,
    meta: {group: groups.configuration, icon: 'bi-journal-text', title: 'Loggers', shortcut: 'lg'}
  },
  {
    path: '/beans',
    name: 'beans',
    component: Beans,
    meta: {group: groups.configuration, icon: 'bi-diagram-3', title: 'Beans', shortcut: 'bn'}
  },
  {
    path: '/conditions',
    name: 'conditions',
    component: Conditions,
    meta: {group: groups.configuration, icon: 'bi-check2-circle', title: 'Conditions', shortcut: 'cd'}
  },
  {
    path: '/mappings',
    name: 'mappings',
    component: Mappings,
    meta: {group: groups.configuration, icon: 'bi-signpost-2', title: 'Mappings', shortcut: 'mp'}
  },
  {
    path: '/database-connection-pools',
    name: 'database-connection-pools',
    component: DatabaseConnectionPools,
    meta: {group: groups.database, icon: 'bi-hdd-network', title: 'Database Connection Pools', shortcut: 'dc'}
  },
  {
    path: '/sql-trace',
    name: 'sql-trace',
    component: SqlTrace,
    meta: {group: groups.database, icon: 'bi-stopwatch', title: 'SQL Trace', shortcut: 'sq'}
  },
  {
    path: '/data',
    name: 'data',
    component: Data,
    meta: {group: groups.database, icon: 'bi-database', title: 'Spring Data', shortcut: 'sd'}
  },
  {
    path: '/flyway',
    name: 'flyway',
    component: Flyway,
    meta: {group: groups.database, icon: 'bi-arrow-up-right-circle', title: 'Flyway', shortcut: 'fw'}
  },
  {
    path: '/liquibase',
    name: 'liquibase',
    component: Liquibase,
    meta: {group: groups.database, icon: 'bi-droplet', title: 'Liquibase', shortcut: 'lb'}
  },
  {
    path: '/spring-security',
    name: 'spring-security',
    component: SpringSecurity,
    meta: {group: groups.security, icon: 'bi-person-lock', title: 'Spring Security', shortcut: 'ss'}
  },
  {
    path: '/security-logs',
    name: 'security-logs',
    component: SecurityLogs,
    meta: {group: groups.security, icon: 'bi-shield-lock', title: 'Security Logs', shortcut: 'sl'}
  },
  {
    path: '/scheduled',
    name: 'scheduled',
    component: Scheduled,
    meta: {group: groups.services, icon: 'bi-clock-history', title: 'Scheduled Tasks', shortcut: 'sk'}
  },
  {
    path: '/spring-cache',
    name: 'spring-cache',
    component: SpringCache,
    meta: {group: groups.services, icon: 'bi-hdd-stack', title: 'Spring Cache', shortcut: 'ca'}
  },
  {
    path: '/ai',
    name: 'ai',
    component: Ai,
    meta: {group: groups.services, icon: 'bi-cpu', title: 'AI Usage', shortcut: 'ai'}
  },
  {
    path: '/traces',
    name: 'traces',
    component: Traces,
    meta: {group: groups.diagnostics, icon: 'bi-bezier2', title: 'Traces', shortcut: 'tr'}
  },
  {
    path: '/log-tail',
    name: 'log-tail',
    component: LogTail,
    meta: {group: groups.diagnostics, icon: 'bi-terminal', title: 'Log Tail', shortcut: 'lt'}
  },
  {
    path: '/exceptions',
    name: 'exceptions',
    component: Exceptions,
    meta: {group: groups.diagnostics, icon: 'bi-exclamation-octagon', title: 'Exceptions', shortcut: 'ex'}
  },
  {
    path: '/http-exchanges',
    name: 'http-exchanges',
    component: HttpExchanges,
    meta: {group: groups.diagnostics, icon: 'bi-arrow-left-right', title: 'HTTP Exchanges', shortcut: 'hx'}
  },
  {
    path: '/http-probe',
    name: 'http-probe',
    component: HttpProbe,
    meta: {group: groups.diagnostics, icon: 'bi-send', title: 'HTTP Probe', shortcut: 'hp'}
  },
  {
    path: '/mcp-server',
    name: 'mcp-server',
    component: McpServer,
    meta: {group: groups.developerTools, icon: 'bi-plug', title: 'MCP Server', shortcut: 'mc'}
  },
  {
    path: '/devtools',
    name: 'devtools',
    component: DevTools,
    meta: {group: groups.developerTools, icon: 'bi-lightning-charge', title: 'DevTools', shortcut: 'dt'}
  },
  {
    path: '/dev-services',
    name: 'dev-services',
    component: DevServices,
    meta: {group: groups.developerTools, icon: 'bi-box-seam', title: 'Dev Services', shortcut: 'ds'}
  },
  {
    path: '/copilot',
    name: 'copilot',
    component: Copilot,
    meta: {group: groups.developerTools, icon: 'bi-robot', title: 'Copilot', shortcut: 'cp'}
  },
  {
    path: '/claude-code',
    name: 'claude-code',
    component: Copilot,
    meta: {group: groups.developerTools, icon: 'bi-claude', title: 'Claude Code', shortcut: 'cc'}
  },
  {path: '/tuning-advisor', redirect: '/jvm-tuning'},
  {path: '/pentest', redirect: '/pentesting'},
  {path: '/dependencies', redirect: '/vulnerabilities'},
  {path: '/rest-advisor', redirect: '/rest-api'},
  {path: '/spring-advisor', redirect: '/spring'},
  {path: '/hibernate-advisor', redirect: '/hibernate'},
  {path: '/memory-advisor', redirect: '/memory'},
  {path: '/security-advisor', redirect: '/security'},
  {path: '/profiles', redirect: '/profile-diff'}
]
