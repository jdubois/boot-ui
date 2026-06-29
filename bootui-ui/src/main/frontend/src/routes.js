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
const LiveActivity = () => import('./views/LiveActivity.vue')

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

/** @type {import('vue-router').RouteRecordRaw[]} */
export const routes = [
  {path: '/', redirect: '/overview'},
  {
    path: '/overview',
    name: 'overview',
    component: Overview,
    meta: {
      group: groups.overview,
      icon: 'bi-speedometer2',
      title: 'Overview',
      shortcut: 'ov',
      keywords: ['home', 'dashboard', 'summary', 'score', 'advisors', 'start']
    }
  },
  {
    path: '/activity',
    name: 'activity',
    component: LiveActivity,
    meta: {
      group: groups.overview,
      icon: 'bi-broadcast',
      title: 'Live Activity',
      shortcut: 'la',
      keywords: ['realtime', 'live', 'stream', 'feed', 'events', 'recent']
    }
  },
  {
    path: '/github',
    name: 'github',
    component: GitHub,
    meta: {
      group: groups.overview,
      icon: 'bi-github',
      title: 'GitHub',
      shortcut: 'gh',
      keywords: ['repo', 'repository', 'git', 'source', 'remote']
    }
  },
  {
    path: '/architecture',
    name: 'architecture',
    component: Architecture,
    meta: {
      group: groups.advisors,
      icon: 'bi-diagram-2',
      title: 'Architecture',
      shortcut: 'ar',
      keywords: [
        'archunit',
        'layers',
        'layering',
        'packages',
        'cycles',
        'coupling',
        'dependencies',
        'structure',
        'design',
        'hygiene'
      ]
    }
  },
  {
    path: '/rest-api',
    name: 'rest-api',
    component: RestApi,
    meta: {
      group: groups.advisors,
      icon: 'bi-signpost-split',
      title: 'REST API',
      shortcut: 'ra',
      keywords: [
        'rest',
        'controllers',
        'endpoints',
        'routes',
        'openapi',
        'swagger',
        'springdoc',
        'dto',
        'pagination',
        'versioning',
        'status codes',
        'api design'
      ]
    }
  },
  {
    path: '/spring',
    name: 'spring',
    component: Spring,
    meta: {
      group: groups.advisors,
      icon: 'bi-leaf',
      title: 'Spring',
      titleByPlatform: {quarkus: 'Quarkus'},
      shortcut: 'sr',
      keywords: [
        'context',
        'beans',
        'wiring',
        'environment',
        'profiles',
        'virtual threads',
        'actuator',
        'framework',
        'runtime'
      ]
    }
  },
  {
    path: '/hibernate',
    name: 'hibernate',
    component: Hibernate,
    meta: {
      group: groups.advisors,
      icon: 'bi-database-gear',
      title: 'Hibernate',
      shortcut: 'hb',
      keywords: [
        'jpa',
        'orm',
        'entities',
        'persistence',
        'n+1',
        'fetch',
        'lazy',
        'eager',
        'ddl-auto',
        'cascade',
        'repositories',
        'mapping'
      ]
    }
  },
  {
    path: '/memory',
    name: 'memory',
    component: Memory,
    meta: {
      group: groups.advisors,
      icon: 'bi-clipboard2-pulse',
      title: 'Memory',
      shortcut: 'mm',
      keywords: [
        'heap',
        'gc',
        'garbage collection',
        'metaspace',
        'oom',
        'out of memory',
        'leak',
        'deadlock',
        'pools',
        'footprint'
      ]
    }
  },
  {
    path: '/security',
    name: 'security',
    component: Security,
    meta: {
      group: groups.advisors,
      icon: 'bi-shield-check',
      title: 'Security',
      shortcut: 'sc',
      keywords: [
        'auth',
        'authentication',
        'authorization',
        'csrf',
        'cors',
        'jwt',
        'oauth',
        'oauth2',
        'headers',
        'hardening',
        'password',
        'filter chain'
      ]
    }
  },
  {
    path: '/pentesting',
    name: 'pentesting',
    component: Pentesting,
    meta: {
      group: groups.advisors,
      icon: 'bi-shield-exclamation',
      title: 'Pentesting',
      shortcut: 'pt',
      keywords: [
        'owasp',
        'top 10',
        'attack',
        'exploit',
        'penetration',
        'security headers',
        'cookies',
        'h2 console',
        'hardening'
      ]
    }
  },
  {
    path: '/vulnerabilities',
    name: 'vulnerabilities',
    component: Vulnerabilities,
    meta: {
      group: groups.advisors,
      icon: 'bi-bug',
      title: 'Vulnerabilities',
      shortcut: 'vu',
      keywords: ['cve', 'osv', 'dependencies', 'deps', 'supply chain', 'advisories', 'sca', 'known vulnerabilities']
    }
  },
  {
    path: '/health',
    name: 'health',
    component: Health,
    meta: {
      group: groups.runtime,
      icon: 'bi-heart-pulse',
      title: 'Health',
      shortcut: 'hl',
      keywords: ['actuator', 'status', 'liveness', 'readiness', 'up', 'down', 'indicators', 'checks']
    }
  },
  {
    path: '/http-sessions',
    name: 'http-sessions',
    component: HttpSessions,
    meta: {
      group: groups.runtime,
      icon: 'bi-cookie',
      title: 'HTTP Sessions',
      shortcut: 'hs',
      keywords: ['sessions', 'tomcat', 'cookies', 'jsessionid', 'attributes']
    }
  },
  {
    path: '/metrics',
    name: 'metrics',
    component: Metrics,
    meta: {
      group: groups.runtime,
      icon: 'bi-activity',
      title: 'Metrics',
      shortcut: 'mt',
      keywords: ['micrometer', 'meters', 'gauges', 'counters', 'measurements', 'charts', 'telemetry']
    }
  },
  {
    path: '/live-memory',
    name: 'live-memory',
    component: LiveMemory,
    meta: {
      group: groups.runtime,
      icon: 'bi-memory',
      title: 'Live Memory',
      shortcut: 'lm',
      keywords: ['heap', 'non-heap', 'pools', 'usage', 'ram', 'memory usage']
    }
  },
  {
    path: '/jvm-tuning',
    name: 'jvm-tuning',
    component: JvmTuning,
    meta: {
      group: groups.runtime,
      icon: 'bi-sliders2-vertical',
      title: 'JVM Tuning',
      shortcut: 'jv',
      keywords: [
        'jvm args',
        'flags',
        'xmx',
        'xms',
        'sizing',
        'kubernetes',
        'container',
        'virtual threads',
        'ram percentage',
        'java options',
        'tuning'
      ]
    }
  },
  {
    path: '/heap-dump',
    name: 'heap-dump',
    component: HeapDump,
    meta: {
      group: groups.runtime,
      icon: 'bi-file-earmark-binary',
      title: 'Heap Dump',
      shortcut: 'hd',
      keywords: ['hprof', 'dump', 'memory leak', 'retention', 'histogram']
    }
  },
  {
    path: '/threads',
    name: 'threads',
    component: Threads,
    meta: {
      group: groups.runtime,
      icon: 'bi-list-task',
      title: 'Threads',
      shortcut: 'th',
      keywords: ['thread dump', 'stack trace', 'deadlock', 'blocked', 'runnable', 'virtual threads']
    }
  },
  {
    path: '/startup',
    name: 'startup',
    component: Startup,
    meta: {
      group: groups.runtime,
      icon: 'bi-bar-chart-steps',
      title: 'Startup Timeline',
      shortcut: 'su',
      keywords: ['startup', 'boot time', 'slow startup', 'bean init', 'timeline', 'cold start']
    }
  },
  {
    path: '/graalvm',
    name: 'graalvm',
    component: GraalVm,
    meta: {
      group: groups.runtime,
      icon: 'bi-rocket-takeoff',
      title: 'GraalVM',
      shortcut: 'gv',
      keywords: ['native image', 'aot', 'reachability', 'reflection', 'native', 'distroless', 'dockerfile', 'graal']
    }
  },
  {
    path: '/crac',
    name: 'crac',
    component: Crac,
    meta: {
      group: groups.runtime,
      icon: 'bi-camera',
      title: 'CRaC',
      shortcut: 'cr',
      keywords: ['checkpoint', 'restore', 'coordinated restore', 'fast startup', 'snapshot']
    }
  },
  {
    path: '/config',
    name: 'config',
    component: Config,
    meta: {
      group: groups.configuration,
      icon: 'bi-sliders',
      title: 'Configuration',
      shortcut: 'cf',
      keywords: [
        'config',
        'properties',
        'env',
        'environment',
        'yaml',
        'application.properties',
        'overrides',
        'settings',
        'values'
      ]
    }
  },
  {
    path: '/profile-diff',
    name: 'profile-diff',
    component: ProfileDiff,
    meta: {
      group: groups.configuration,
      icon: 'bi-layers',
      title: 'Profile Diff',
      shortcut: 'pd',
      keywords: ['profiles', 'compare', 'diff', 'environment diff']
    }
  },
  {
    path: '/loggers',
    name: 'loggers',
    component: Loggers,
    meta: {
      group: groups.configuration,
      icon: 'bi-journal-text',
      title: 'Loggers',
      shortcut: 'lg',
      keywords: ['log level', 'logging', 'levels', 'debug', 'logback', 'slf4j']
    }
  },
  {
    path: '/beans',
    name: 'beans',
    component: Beans,
    meta: {
      group: groups.configuration,
      icon: 'bi-diagram-3',
      title: 'Beans',
      shortcut: 'bn',
      keywords: ['spring beans', 'components', 'context', 'dependency injection', 'wiring']
    }
  },
  {
    path: '/conditions',
    name: 'conditions',
    component: Conditions,
    meta: {
      group: groups.configuration,
      icon: 'bi-check2-circle',
      title: 'Conditions',
      shortcut: 'cd',
      keywords: ['auto-configuration', 'autoconfig', 'conditional', 'matches', 'condition evaluation', 'why']
    }
  },
  {
    path: '/mappings',
    name: 'mappings',
    component: Mappings,
    meta: {
      group: groups.configuration,
      icon: 'bi-signpost-2',
      title: 'Mappings',
      shortcut: 'mp',
      keywords: ['routes', 'endpoints', 'url mappings', 'request mappings', 'handlers', 'web surface']
    }
  },
  {
    path: '/database-connection-pools',
    name: 'database-connection-pools',
    component: DatabaseConnectionPools,
    meta: {
      group: groups.database,
      icon: 'bi-hdd-network',
      title: 'Database Connection Pools',
      shortcut: 'dc',
      keywords: ['hikari', 'hikaricp', 'datasource', 'connection pool', 'jdbc', 'connections']
    }
  },
  {
    path: '/sql-trace',
    name: 'sql-trace',
    component: SqlTrace,
    meta: {
      group: groups.database,
      icon: 'bi-stopwatch',
      title: 'SQL Trace',
      shortcut: 'sq',
      keywords: ['sql', 'queries', 'slow queries', 'slow query', 'n+1', 'jdbc', 'statements', 'select', 'query log']
    }
  },
  {
    path: '/data',
    name: 'data',
    component: Data,
    meta: {
      group: groups.database,
      icon: 'bi-database',
      title: 'Spring Data',
      shortcut: 'sd',
      keywords: ['repositories', 'jpa repositories', 'crud', 'query methods', 'domain']
    }
  },
  {
    path: '/flyway',
    name: 'flyway',
    component: Flyway,
    meta: {
      group: groups.database,
      icon: 'bi-database-up',
      title: 'Flyway',
      shortcut: 'fw',
      keywords: ['migrations', 'schema', 'migrate', 'db migration', 'versioned']
    }
  },
  {
    path: '/liquibase',
    name: 'liquibase',
    component: Liquibase,
    meta: {
      group: groups.database,
      icon: 'bi-droplet',
      title: 'Liquibase',
      shortcut: 'lb',
      keywords: ['migrations', 'changelog', 'changesets', 'schema', 'db migration']
    }
  },
  {
    path: '/spring-security',
    name: 'spring-security',
    component: SpringSecurity,
    meta: {
      group: groups.security,
      icon: 'bi-person-lock',
      title: 'Spring Security',
      shortcut: 'ss',
      keywords: ['filter chain', 'filterchain', 'securityfilterchain', 'endpoint rules', 'auth', 'authorization']
    }
  },
  {
    path: '/security-logs',
    name: 'security-logs',
    component: SecurityLogs,
    meta: {
      group: groups.security,
      icon: 'bi-shield-lock',
      title: 'Security Logs',
      shortcut: 'sl',
      keywords: [
        'audit',
        'audit events',
        'login',
        'logins',
        'failed login',
        'sign in',
        'authentication failures',
        'access denied',
        'denials'
      ]
    }
  },
  {
    path: '/scheduled',
    name: 'scheduled',
    component: Scheduled,
    meta: {
      group: groups.services,
      icon: 'bi-clock-history',
      title: 'Scheduled Tasks',
      shortcut: 'sk',
      keywords: ['cron', 'jobs', 'scheduling', 'background tasks', 'fixed rate', 'triggers']
    }
  },
  {
    path: '/cache',
    name: 'cache',
    component: SpringCache,
    meta: {
      group: groups.services,
      icon: 'bi-hdd-stack',
      title: 'Cache',
      shortcut: 'ca',
      keywords: ['caching', 'cacheable', 'cache manager', 'cacheput', 'cacheevict', 'caches']
    }
  },
  {
    path: '/ai',
    name: 'ai',
    component: Ai,
    meta: {
      group: groups.services,
      icon: 'bi-cpu',
      title: 'AI Usage',
      shortcut: 'ai',
      keywords: [
        'llm',
        'spring ai',
        'langchain4j',
        'tokens',
        'gpt',
        'openai',
        'chat model',
        'genai',
        'embeddings',
        'prompts'
      ]
    }
  },
  {
    path: '/traces',
    name: 'traces',
    component: Traces,
    meta: {
      group: groups.diagnostics,
      icon: 'bi-bezier2',
      title: 'Traces',
      shortcut: 'tr',
      keywords: ['tracing', 'distributed tracing', 'spans', 'otlp', 'opentelemetry', 'waterfall', 'latency']
    }
  },
  {
    path: '/log-tail',
    name: 'log-tail',
    component: LogTail,
    meta: {
      group: groups.diagnostics,
      icon: 'bi-terminal',
      title: 'Log Tail',
      shortcut: 'lt',
      keywords: ['logs', 'log stream', 'console', 'tail', 'application logs']
    }
  },
  {
    path: '/exceptions',
    name: 'exceptions',
    component: Exceptions,
    meta: {
      group: groups.diagnostics,
      icon: 'bi-exclamation-octagon',
      title: 'Exceptions',
      shortcut: 'ex',
      keywords: ['errors', 'stack traces', 'failures', 'throwables', 'crashes', 'error grouping']
    }
  },
  {
    path: '/http-exchanges',
    name: 'http-exchanges',
    component: HttpExchanges,
    meta: {
      group: groups.diagnostics,
      icon: 'bi-arrow-left-right',
      title: 'HTTP Exchanges',
      shortcut: 'hx',
      keywords: ['requests', 'http traffic', 'request log', 'inbound requests', 'response']
    }
  },
  {
    path: '/http-probe',
    name: 'http-probe',
    component: HttpProbe,
    meta: {
      group: groups.diagnostics,
      icon: 'bi-send',
      title: 'HTTP Probe',
      shortcut: 'hp',
      keywords: ['curl', 'request', 'api test', 'send request', 'http client', 'ping']
    }
  },
  {
    path: '/mcp-server',
    name: 'mcp-server',
    component: McpServer,
    meta: {
      group: groups.developerTools,
      icon: 'bi-plug',
      title: 'MCP Server',
      shortcut: 'mc',
      keywords: ['model context protocol', 'mcp', 'ai agent', 'copilot', 'claude', 'tools', 'json-rpc', 'agent']
    }
  },
  {
    path: '/devtools',
    name: 'devtools',
    component: DevTools,
    meta: {
      group: groups.developerTools,
      icon: 'bi-lightning-charge',
      title: 'DevTools',
      shortcut: 'dt',
      keywords: ['devtools', 'livereload', 'restart', 'hot reload', 'auto restart']
    }
  },
  {
    path: '/dev-services',
    name: 'dev-services',
    component: DevServices,
    meta: {
      group: groups.developerTools,
      icon: 'bi-box-seam',
      title: 'Dev Services',
      shortcut: 'ds',
      keywords: ['docker compose', 'testcontainers', 'containers', 'service connections', 'compose']
    }
  },
  {
    path: '/copilot',
    name: 'copilot',
    component: Copilot,
    meta: {
      group: groups.developerTools,
      icon: 'bi-robot',
      title: 'Copilot',
      shortcut: 'cp',
      keywords: ['copilot cli', 'github copilot', 'ai sessions', 'agent activity', 'token usage']
    }
  },
  {
    path: '/claude-code',
    name: 'claude-code',
    component: Copilot,
    meta: {
      group: groups.developerTools,
      icon: 'bi-claude',
      title: 'Claude Code',
      shortcut: 'cc',
      keywords: ['claude', 'anthropic', 'ai sessions', 'agent activity', 'claude code']
    }
  },
  {path: '/tuning-advisor', redirect: '/jvm-tuning'},
  {path: '/pentest', redirect: '/pentesting'},
  {path: '/dependencies', redirect: '/vulnerabilities'},
  {path: '/rest-advisor', redirect: '/rest-api'},
  {path: '/spring-advisor', redirect: '/spring'},
  {path: '/hibernate-advisor', redirect: '/hibernate'},
  {path: '/memory-advisor', redirect: '/memory'},
  {path: '/security-advisor', redirect: '/security'},
  {path: '/profiles', redirect: '/profile-diff'},
  {path: '/spring-cache', redirect: '/cache'}
]
