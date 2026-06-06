import {chromium} from '@playwright/test'
import {spawn} from 'node:child_process'
import fs from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const e2eDir = path.resolve(__dirname, '..')
const repoRoot = path.resolve(e2eDir, '..', '..')
const frontendDir = path.join(repoRoot, 'bootui-ui', 'src', 'main', 'frontend')
const imagesDir = path.join(repoRoot, 'docs', 'images')

const port = Number(process.env.BOOTUI_SCREENSHOT_PORT || 5173)
const baseUrl = process.env.BOOTUI_SCREENSHOT_BASE_URL || `http://127.0.0.1:${port}`
const viewport = {width: 1600, height: 900}
const nowMillis = Date.now()
const nowNanos = nowMillis * 1_000_000
const traceId = '0123456789abcdef0123456789abcdef'
const aiSpanId = '1111111111111111'

const panelOrder = [
  ['overview', 'Overview'],
  ['github', 'GitHub'],
  ['health', 'Health'],
  ['http-sessions', 'HTTP Sessions'],
  ['metrics', 'Metrics'],
  ['memory', 'Memory'],
  ['tuning-advisor', 'Tuning Advisor'],
  ['heap-dump', 'Heap Dump'],
  ['threads', 'Threads'],
  ['startup', 'Startup Timeline'],
  ['graalvm', 'GraalVM'],
  ['config', 'Configuration'],
  ['profiles', 'Profile Diff'],
  ['loggers', 'Loggers'],
  ['beans', 'Beans'],
  ['conditions', 'Conditions'],
  ['mappings', 'Mappings'],
  ['database-connection-pools', 'Database Connection Pools'],
  ['data', 'Spring Data'],
  ['hibernate-advisor', 'Hibernate Advisor'],
  ['flyway', 'Flyway'],
  ['liquibase', 'Liquibase'],
  ['spring-security', 'Spring Security'],
  ['security-logs', 'Security Logs'],
  ['security-advisor', 'Security Advisor'],
  ['pentest', 'Pentesting'],
  ['vulnerabilities', 'Vulnerabilities'],
  ['scheduled', 'Scheduled Tasks'],
  ['spring-cache', 'Spring Cache'],
  ['ai', 'AI Usage'],
  ['traces', 'Traces'],
  ['log-tail', 'Log Tail'],
  ['http-exchanges', 'HTTP Exchanges'],
  ['http-probe', 'HTTP Probe'],
  ['architecture', 'Architecture'],
  ['devtools', 'DevTools'],
  ['dev-services', 'Dev Services'],
  ['copilot', 'Copilot'],
  ['claude-code', 'Claude Code']
]

const overview = {
  bootUiVersion: '0.5.0',
  applicationName: 'bootui-sample',
  springBootVersion: '4.0.6',
  javaVersion: '17',
  javaVendor: 'Eclipse Temurin',
  activeProfiles: ['dev', 'local'],
  defaultProfiles: ['default'],
  webApplicationType: 'SERVLET',
  serverPort: 8080,
  managementPort: null,
  contextPath: '',
  startupTimeMillis: 1280,
  activation: {
    enabled: true,
    localhostOnly: true,
    reason: 'Active dev profile matched bootui.enabled-profiles',
    warnings: []
  },
  openApiUrl: '/swagger-ui/index.html'
}

const github = {
  available: true,
  unavailableReason: null,
  connected: true,
  status: 'CONNECTED',
  message: null,
  refreshedAt: nowMillis - 45_000,
  repository: {
    owner: 'jdubois',
    name: 'boot-ui',
    fullName: 'jdubois/boot-ui',
    host: 'github.com',
    apiBaseUrl: 'https://api.github.com/',
    htmlUrl: 'https://github.com/jdubois/boot-ui',
    defaultBranch: 'main',
    localBranch: 'jdubois/prepare-0-5-0',
    upstreamBranch: 'main',
    visibility: 'public',
    privateRepository: false,
    fork: false,
    archived: false,
    pushedAt: nowMillis - 18 * 60 * 1000,
    stars: 128,
    forks: 14,
    watchers: 11,
    openIssues: 9,
    latestRelease: 'v0.4.0'
  },
  credential: {source: 'GITHUB_TOKEN', authenticated: true, login: 'local-dev', scopes: 'repo, workflow'},
  metrics: [
    {label: 'Open pull requests', value: '3', detail: 'Bounded live queue', tone: 'primary'},
    {label: 'Open issues', value: '9', detail: 'Grouped by label and age', tone: 'info'},
    {label: 'Workflow failures', value: '1', detail: 'Latest run per workflow', tone: 'danger'},
    {label: 'Core quota remaining', value: '90%', detail: 'GitHub REST core resource', tone: 'success'},
    {label: 'Copilot usage', value: '2,523,456', detail: 'tokens in the latest 28-day report', tone: 'info'}
  ],
  quotas: [
    githubQuota('core', 'Core', 'Rate limit', 5000, 500, 4500, 10, 'OK'),
    githubQuota('search', 'Search', 'Rate limit', 30, 3, 27, 10, 'OK'),
    githubQuota(
      'actions_cache',
      'Actions cache',
      'Repository quota',
      10_737_418_240,
      1_073_741_824,
      9_663_676_416,
      10,
      'OK'
    )
  ],
  pullRequests: [
    {
      number: 211,
      title: 'Update implementation plan roadmap',
      author: 'julien',
      draft: false,
      htmlUrl: 'https://github.com/jdubois/boot-ui/pull/211',
      updatedAt: nowMillis - 20 * 60 * 1000,
      reviewDecision: 'APPROVED',
      checksConclusion: 'success',
      labels: ['docs']
    },
    {
      number: 210,
      title: 'Update GitHub Actions execution drawer',
      author: 'julien',
      draft: false,
      htmlUrl: 'https://github.com/jdubois/boot-ui/pull/210',
      updatedAt: nowMillis - 90 * 60 * 1000,
      reviewDecision: null,
      checksConclusion: 'success',
      labels: ['github']
    }
  ],
  workflowRuns: [
    githubWorkflowRun(
      501,
      100,
      'Build',
      'CI-equivalent Maven build',
      326,
      'push',
      'completed',
      'success',
      12 * 60 * 1000
    ),
    githubWorkflowRun(
      502,
      200,
      'Release',
      'Release v0.5.0 dry run',
      27,
      'workflow_dispatch',
      'completed',
      'failure',
      4 * 60 * 1000
    ),
    githubWorkflowRun(
      503,
      300,
      'Native image',
      'Native image smoke test',
      14,
      'schedule',
      'completed',
      'success',
      18 * 60 * 1000
    )
  ],
  workflows: [
    githubWorkflow(100, 'Build', '.github/workflows/build.yml', 501),
    githubWorkflow(200, 'Release', '.github/workflows/release.yml', 502),
    githubWorkflow(300, 'Native image', '.github/workflows/native.yml', 503)
  ],
  issueBuckets: [
    {label: 'Open issues', count: 9, tone: 'primary'},
    {label: 'Bug', count: 2, tone: 'danger'},
    {label: 'Enhancement', count: 5, tone: 'info'},
    {label: 'Documentation', count: 2, tone: 'success'}
  ],
  issues: [
    {
      number: 188,
      title: 'Flaky Playwright run on slow CI agents',
      author: 'julien',
      htmlUrl: 'https://github.com/jdubois/boot-ui/issues/188',
      createdAt: nowMillis - 3 * 24 * 60 * 60 * 1000,
      updatedAt: nowMillis - 35 * 60 * 1000,
      comments: 4,
      labels: ['bug', 'ci']
    },
    {
      number: 184,
      title: 'Document the GitHub issues drawer',
      author: 'octocat',
      htmlUrl: 'https://github.com/jdubois/boot-ui/issues/184',
      createdAt: nowMillis - 5 * 24 * 60 * 60 * 1000,
      updatedAt: nowMillis - 4 * 60 * 60 * 1000,
      comments: 1,
      labels: ['docs']
    },
    {
      number: 179,
      title: 'Add metric for stale issues older than 90 days',
      author: 'contributor',
      htmlUrl: 'https://github.com/jdubois/boot-ui/issues/179',
      createdAt: nowMillis - 12 * 24 * 60 * 60 * 1000,
      updatedAt: nowMillis - 2 * 24 * 60 * 60 * 1000,
      comments: 0,
      labels: ['enhancement']
    }
  ],
  securitySignals: [
    {label: 'Dependabot alerts', status: 'AVAILABLE', count: 0, unavailableReason: null},
    {label: 'Code scanning alerts', status: 'AVAILABLE', count: 1, unavailableReason: null},
    {label: 'Secret scanning alerts', status: 'AVAILABLE', count: 0, unavailableReason: null}
  ],
  copilotUsage: {
    status: 'AVAILABLE',
    scope: 'organization',
    summary: '2,523,456 tokens used in the latest 28-day report.',
    reportStartDay: '2026-05-07',
    reportEndDay: '2026-06-03',
    downloadLinkCount: 1,
    documentationUrl: 'https://docs.github.com/en/rest/copilot/copilot-usage-metrics',
    unavailableReason: null
  },
  warnings: ['Actions billing quota requires repository admin access and was skipped.']
}

const startup = {
  steps: [
    {
      id: 1,
      parentId: null,
      name: 'spring.boot.application.starting',
      durationMs: 24,
      tags: [{key: 'mainApplicationClass', value: 'BootUiSampleApplication'}]
    },
    {
      id: 2,
      parentId: null,
      name: 'spring.context.refresh',
      durationMs: 860,
      tags: [{key: 'context', value: 'bootui-sample'}]
    },
    {
      id: 3,
      parentId: 2,
      name: 'spring.beans.instantiate',
      durationMs: 380,
      tags: [{key: 'beanName', value: 'sampleCatalog'}]
    },
    {
      id: 4,
      parentId: 2,
      name: 'spring.data.repositories.bootstrap',
      durationMs: 145,
      tags: [{key: 'store', value: 'jpa'}]
    },
    {
      id: 5,
      parentId: 2,
      name: 'spring.cache.redis.initialize',
      durationMs: 210,
      tags: [{key: 'cacheManager', value: 'redisCacheManager'}]
    },
    {
      id: 6,
      parentId: null,
      name: 'spring.boot.application.ready',
      durationMs: 18,
      tags: [{key: 'profiles', value: 'dev,local'}]
    }
  ]
}

const MB = 1024 * 1024
const memory = {
  calculation: {
    totalMemoryBytes: 768 * MB,
    threadCount: 64,
    liveThreadCount: 39,
    headRoomPercent: 5,
    valid: true,
    error: null,
    heapBytes: 438 * MB,
    metaspaceBytes: 94 * MB,
    codeCacheBytes: 96 * MB,
    directMemoryBytes: 64 * MB,
    stackBytesTotal: 64 * MB,
    headRoomBytes: 38 * MB,
    liveLoadedClassCount: 14872,
    loadedClasses: 18590
  },
  suggestedJvmOptions:
    '-Xms438m -Xmx438m -XX:MaxMetaspaceSize=94m -XX:ReservedCodeCacheSize=96m -XX:MaxDirectMemorySize=64m -XX:+UseG1GC',
  heap: {usedBytes: 172 * MB, committedBytes: 256 * MB, maxBytes: 438 * MB, usedPercent: 39},
  nonHeap: {usedBytes: 116 * MB, committedBytes: 148 * MB, maxBytes: -1, usedPercent: 78},
  pools: [
    {name: 'G1 Eden Space', usedBytes: 56 * MB, committedBytes: 96 * MB, maxBytes: -1, usedPercent: 58},
    {name: 'G1 Old Gen', usedBytes: 108 * MB, committedBytes: 150 * MB, maxBytes: 438 * MB, usedPercent: 25},
    {name: 'Metaspace', usedBytes: 82 * MB, committedBytes: 94 * MB, maxBytes: 94 * MB, usedPercent: 87},
    {name: 'CodeCache', usedBytes: 34 * MB, committedBytes: 48 * MB, maxBytes: 96 * MB, usedPercent: 35}
  ],
  jvmInputArguments: ['-Dspring.profiles.active=dev,local', '-XX:+UseG1GC']
}

const health = {
  name: 'application',
  status: 'UP',
  details: {uptime: '23m', region: 'local'},
  components: [
    {name: 'db', status: 'UP', details: {database: 'PostgreSQL', validationQuery: 'isValid()'}, components: []},
    {name: 'redis', status: 'UP', details: {version: '8.0', mode: 'standalone'}, components: []},
    {name: 'diskSpace', status: 'UP', details: {total: '494 GB', free: '128 GB', threshold: '10 MB'}, components: []}
  ]
}

const httpSessions = {
  available: true,
  unavailableReason: null,
  totalSessions: 3,
  returnedSessions: 3,
  limit: 50,
  limited: false,
  actionEnabled: true,
  valueExposure: 'MASKED',
  sessions: [
    {
      sessionKey: 'session-key-alice',
      id: 'session-a1b2...',
      idMasked: true,
      current: true,
      creationTime: new Date(nowMillis - 42 * 60 * 1000).toISOString(),
      lastAccessedTime: new Date(nowMillis - 12_000).toISOString(),
      idleSeconds: 12,
      maxInactiveIntervalSeconds: 1800,
      attributeCount: 3,
      attributes: [
        httpSessionAttribute(
          'SPRING_SECURITY_CONTEXT',
          'org.springframework.security.core.context.SecurityContextImpl'
        ),
        httpSessionAttribute('csrfToken', 'org.springframework.security.web.csrf.DefaultCsrfToken'),
        httpSessionAttribute('cartSize', 'java.lang.Integer')
      ]
    },
    {
      sessionKey: 'session-key-bob',
      id: 'session-c3d4...',
      idMasked: true,
      current: false,
      creationTime: new Date(nowMillis - 3 * 3600 * 1000).toISOString(),
      lastAccessedTime: new Date(nowMillis - 8 * 60 * 1000).toISOString(),
      idleSeconds: 480,
      maxInactiveIntervalSeconds: 1800,
      attributeCount: 2,
      attributes: [
        httpSessionAttribute('apiToken', 'java.lang.String'),
        httpSessionAttribute('samplePreferences', 'io.github.jdubois.bootui.sample.SampleAppPreferences')
      ]
    },
    {
      sessionKey: 'session-key-guest',
      id: 'session-e5f6...',
      idMasked: true,
      current: false,
      creationTime: new Date(nowMillis - 11 * 60 * 1000).toISOString(),
      lastAccessedTime: new Date(nowMillis - 2 * 60 * 1000).toISOString(),
      idleSeconds: 120,
      maxInactiveIntervalSeconds: 1800,
      attributeCount: 1,
      attributes: [httpSessionAttribute('sampleGreeting', 'java.lang.String')]
    }
  ]
}

let metricSample = 0
const metrics = {
  metricsAvailable: true,
  total: 5,
  meters: [
    {name: 'jvm.memory.used', type: 'GAUGE', description: 'The amount of used memory'},
    {name: 'http.server.requests', type: 'TIMER', description: 'HTTP server request latency'},
    {name: 'cache.gets', type: 'FUNCTION_COUNTER', description: 'Cache lookup count'},
    {name: 'process.uptime', type: 'TIME_GAUGE', description: 'The uptime of the Java virtual machine'},
    {name: 'spring.ai.chat.client', type: 'TIMER', description: 'Spring AI chat client latency'}
  ]
}

function metricDetail(name) {
  metricSample += 1
  const value = name === 'process.uptime' ? 1420 + metricSample : 183_500_000 + metricSample * 2_000_000
  return {
    name,
    description: metrics.meters.find((meter) => meter.name === name)?.description || 'Sample metric',
    type: metrics.meters.find((meter) => meter.name === name)?.type || 'GAUGE',
    baseUnit: name.includes('memory') ? 'bytes' : null,
    measurements: [{statistic: 'VALUE', value}],
    availableTags: [
      {key: 'area', values: ['heap', 'nonheap'], truncated: false},
      {key: 'id', values: ['G1 Old Gen', 'Metaspace'], truncated: false}
    ],
    samples: [
      {
        tags: [
          {key: 'area', value: 'heap'},
          {key: 'id', value: 'G1 Old Gen'}
        ],
        measurements: [{statistic: 'VALUE', value}]
      },
      {
        tags: [
          {key: 'area', value: 'nonheap'},
          {key: 'id', value: 'Metaspace'}
        ],
        measurements: [{statistic: 'VALUE', value: 86_200_000}]
      }
    ]
  }
}

const conditions = {
  positiveMatches: [
    {
      autoConfigurationClass: 'BootUiAutoConfiguration',
      condition: 'BootUiActivationCondition',
      outcome: 'matched',
      message: 'Active profile dev enables BootUI in AUTO mode'
    },
    {
      autoConfigurationClass: 'CacheAutoConfiguration',
      condition: 'CacheCondition',
      outcome: 'matched',
      message: 'RedisCacheManager bean is available'
    },
    {
      autoConfigurationClass: 'DataSourceAutoConfiguration',
      condition: 'OnClassCondition',
      outcome: 'matched',
      message: 'DataSource and PostgreSQL driver classes were found'
    }
  ],
  negativeMatches: [
    {
      autoConfigurationClass: 'ReactiveWebServerFactoryAutoConfiguration',
      condition: 'OnWebApplicationCondition',
      outcome: 'did not match',
      message: 'Application is a servlet web application'
    }
  ]
}

const beans = {
  total: 6,
  beans: [
    {
      name: 'sampleController',
      type: 'io.github.jdubois.bootui.sample.BootUiSampleApplication$SampleController',
      scope: 'singleton',
      classification: 'APPLICATION',
      dependencies: ['sampleSettings', 'sampleCatalog']
    },
    {
      name: 'sampleCatalog',
      type: 'io.github.jdubois.bootui.sample.BootUiSampleApplication$SampleCatalog',
      scope: 'singleton',
      classification: 'APPLICATION',
      dependencies: ['productRepository']
    },
    {
      name: 'productRepository',
      type: 'io.github.jdubois.bootui.sample.ProductRepository',
      scope: 'singleton',
      classification: 'APPLICATION',
      dependencies: ['jpaSharedEM_entityManagerFactory']
    },
    {
      name: 'bootUiAutoConfiguration',
      type: 'io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration',
      scope: 'singleton',
      classification: 'BOOTUI',
      dependencies: []
    },
    {
      name: 'redisCacheManager',
      type: 'org.springframework.data.redis.cache.RedisCacheManager',
      scope: 'singleton',
      classification: 'FRAMEWORK',
      dependencies: ['redisConnectionFactory']
    },
    {
      name: 'dispatcherServlet',
      type: 'org.springframework.web.servlet.DispatcherServlet',
      scope: 'singleton',
      classification: 'FRAMEWORK',
      dependencies: []
    }
  ]
}

const mappings = {
  contexts: {
    'bootui-sample': {
      mappings: {
        dispatcherServlets: {
          dispatcherServlet: [
            mapping('GET', '/api/sample/hello', 'SampleController#hello()'),
            mapping('GET', '/api/sample/products', 'SampleController#products()'),
            mapping('POST', '/api/chat', 'ChatController#chat(ChatRequest)'),
            mapping('GET', '/admin', 'AdminController#admin()'),
            mapping('GET', '/bootui/api/spring-cache', 'SpringCacheController#springCache()')
          ]
        }
      }
    }
  }
}

const flatMappings = [
  {method: 'GET', pattern: '/api/sample/hello', handler: 'SampleController#hello()', produces: null, consumes: null},
  {
    method: 'GET',
    pattern: '/api/sample/products',
    handler: 'SampleController#products()',
    produces: null,
    consumes: null
  },
  {method: 'POST', pattern: '/api/chat', handler: 'ChatController#chat(ChatRequest)', produces: null, consumes: null},
  {method: 'GET', pattern: '/admin', handler: 'AdminController#admin()', produces: null, consumes: null},
  {
    method: 'GET',
    pattern: '/bootui/api/spring-cache',
    handler: 'SpringCacheController#springCache()',
    produces: null,
    consumes: null
  }
]

const configuration = {
  activeProfiles: ['dev', 'local'],
  sources: [
    'applicationConfig: [classpath:/application.properties]',
    '.bootui/application-bootui.properties',
    'systemProperties'
  ],
  propertySuggestions: [
    {name: 'server.port', type: 'java.lang.Integer', defaultValue: 8080, description: 'Server HTTP port.'},
    {
      name: 'bootui.expose-values',
      type: 'ExposeValues',
      defaultValue: 'MASKED',
      description: 'Controls browser-visible configuration values.'
    },
    {name: 'spring.cache.type', type: 'CacheType', defaultValue: null, description: 'Cache provider to use.'}
  ],
  properties: [
    {
      name: 'spring.application.name',
      value: 'bootui-sample',
      masked: false,
      source: 'applicationConfig: [classpath:/application.properties]',
      description: 'Application name.',
      defaultValue: null,
      override: false
    },
    {
      name: 'server.port',
      value: '8080',
      masked: false,
      source: 'applicationConfig: [classpath:/application.properties]',
      description: 'Server HTTP port.',
      defaultValue: '8080',
      override: false
    },
    {
      name: 'sample.greeting',
      value: 'Bonjour',
      masked: false,
      source: '.bootui/application-bootui.properties',
      description: 'Greeting prefix used by the sample app.',
      defaultValue: 'Hello',
      override: true
    },
    {
      name: 'spring.datasource.password',
      value: '******',
      masked: true,
      source: 'systemProperties',
      description: 'Database password.',
      defaultValue: null,
      override: false
    },
    {
      name: 'spring.cache.type',
      value: 'redis',
      masked: false,
      source: 'applicationConfig: [classpath:/application.properties]',
      description: 'Cache provider to use.',
      defaultValue: null,
      override: false
    }
  ]
}

const profileDiff = {
  activeProfiles: ['dev', 'local'],
  profileSources: [
    {
      profile: 'dev',
      sourceName: 'classpath:/application-dev.properties',
      properties: [
        {name: 'logging.level.io.github.jdubois.bootui', value: 'DEBUG', masked: false},
        {name: 'sample.retries', value: '3', masked: false},
        {name: 'spring.datasource.password', value: '******', masked: true}
      ]
    },
    {
      profile: 'local',
      sourceName: 'file:.bootui/application-bootui.properties',
      properties: [
        {name: 'sample.greeting', value: 'Bonjour', masked: false},
        {name: 'bootui.cache.clear-enabled', value: 'true', masked: false}
      ]
    }
  ]
}

const loggers = {
  availableLevels: ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'OFF'],
  loggers: [
    {name: 'ROOT', configuredLevel: 'INFO', effectiveLevel: 'INFO'},
    {name: 'io.github.jdubois.bootui', configuredLevel: 'DEBUG', effectiveLevel: 'DEBUG'},
    {name: 'io.github.jdubois.bootui.sample', configuredLevel: 'INFO', effectiveLevel: 'INFO'},
    {name: 'org.springframework.web', configuredLevel: null, effectiveLevel: 'INFO'},
    {name: 'org.hibernate.SQL', configuredLevel: 'DEBUG', effectiveLevel: 'DEBUG'}
  ]
}

const traceReport = {
  enabled: true,
  retained: 2,
  capacity: 500,
  traces: [
    {
      traceId,
      rootSpanName: 'POST /api/chat',
      services: ['bootui-sample', 'ollama', 'redis'],
      startEpochNanos: nowNanos - 45_000_000_000,
      endEpochNanos: nowNanos - 43_780_000_000,
      durationNanos: 1_220_000_000,
      spanCount: 5,
      hasError: false,
      hasAi: true
    },
    {
      traceId: 'fedcba9876543210fedcba9876543210',
      rootSpanName: 'GET /api/sample/products',
      services: ['bootui-sample', 'postgres'],
      startEpochNanos: nowNanos - 23_000_000_000,
      endEpochNanos: nowNanos - 22_870_000_000,
      durationNanos: 130_000_000,
      spanCount: 3,
      hasError: false,
      hasAi: false
    }
  ]
}

const traceDetail = {
  traceId,
  spans: [
    span(traceId, 'aaaaaaaaaaaaaaaa', null, 'POST /api/chat', 'SERVER', 'bootui-sample', 0, 1_220_000_000),
    span(
      traceId,
      'bbbbbbbbbbbbbbbb',
      'aaaaaaaaaaaaaaaa',
      'ChatClient call',
      'CLIENT',
      'bootui-sample',
      80_000_000,
      980_000_000
    ),
    span(
      traceId,
      'cccccccccccccccc',
      'bbbbbbbbbbbbbbbb',
      'POST /api/generate',
      'CLIENT',
      'ollama',
      130_000_000,
      760_000_000
    ),
    span(
      traceId,
      'dddddddddddddddd',
      'aaaaaaaaaaaaaaaa',
      'GET sample-greetings',
      'CLIENT',
      'redis',
      40_000_000,
      30_000_000
    ),
    span(
      traceId,
      'eeeeeeeeeeeeeeee',
      'aaaaaaaaaaaaaaaa',
      'INSERT chat_audit',
      'CLIENT',
      'postgres',
      1_060_000_000,
      90_000_000
    )
  ]
}

const aiOverview = {
  enabled: true,
  springAiDetected: true,
  langChain4jDetected: false,
  totalChats: 8,
  totalInputTokens: 2674,
  totalOutputTokens: 812,
  tokensByModel: {'qwen2.5:0.5b': 2386, 'llama3.2:1b': 1100},
  callsByModel: {'qwen2.5:0.5b': 6, 'llama3.2:1b': 2},
  toolCallCount: 5,
  vectorOperationCount: 3,
  embeddingCount: 2,
  recent: [
    aiChat(aiSpanId, 'ollama', 'qwen2.5:0.5b', 412, 96, 640_000_000, 'stop'),
    aiChat('2222222222222222', 'ollama', 'qwen2.5:0.5b', 305, 141, 820_000_000, 'stop'),
    aiChat('3333333333333333', 'ollama', 'llama3.2:1b', 288, 205, 1_120_000_000, 'length')
  ],
  contentBanner:
    'Prompt and completion text is hidden by default. Enable Spring AI content observations to inspect message snippets locally.'
}

const aiTokens = {
  minutes: 60,
  buckets: [
    {epochMinute: 100, inputTokens: 0, outputTokens: 0, callCount: 0},
    {epochMinute: 101, inputTokens: 220, outputTokens: 75, callCount: 1},
    {epochMinute: 102, inputTokens: 180, outputTokens: 64, callCount: 1},
    {epochMinute: 103, inputTokens: 420, outputTokens: 130, callCount: 2},
    {epochMinute: 104, inputTokens: 310, outputTokens: 91, callCount: 1},
    {epochMinute: 105, inputTokens: 560, outputTokens: 182, callCount: 2},
    {epochMinute: 106, inputTokens: 250, outputTokens: 88, callCount: 1}
  ]
}

const aiDetail = {
  summary: aiOverview.recent[0],
  toolCalls: [
    {
      spanId: '4444444444444444',
      name: 'lookupProductCatalog',
      startEpochNanos: nowNanos,
      durationNanos: 42_000_000,
      statusCode: 'OK'
    },
    {
      spanId: '5555555555555555',
      name: 'summarizeCacheStats',
      startEpochNanos: nowNanos,
      durationNanos: 28_000_000,
      statusCode: 'OK'
    }
  ],
  vectorOperations: [
    {
      spanId: '6666666666666666',
      operation: 'query',
      collectionName: 'bootui-docs',
      startEpochNanos: nowNanos,
      durationNanos: 35_000_000,
      statusCode: 'OK'
    }
  ],
  attributes: [
    {key: 'gen_ai.system', type: 'string', value: 'ollama'},
    {key: 'gen_ai.request.model', type: 'string', value: 'qwen2.5:0.5b'}
  ],
  events: [],
  contentCaptured: false,
  contentBanner: 'Message content is not captured on this span.'
}

const devTools = {
  liveReloadAvailable: true,
  liveReloadPort: 35729,
  liveReloadUnavailableReason: null,
  restartAvailable: true,
  restartPending: false,
  restartUnavailableReason: 'Spring Boot DevTools restart is initialized.'
}

const devServices = {
  dockerComposePresent: true,
  testcontainersPresent: true,
  snapshotTimestamp: nowMillis,
  total: 3,
  services: [
    {
      id: 'compose:postgres',
      name: 'postgres',
      type: 'PostgreSQL',
      source: 'Docker Compose',
      image: 'postgres:18-alpine',
      status: 'READY_AT_STARTUP',
      host: 'localhost',
      ports: [{containerPort: 5432, hostPort: 15432, protocol: 'tcp'}],
      connectionDetails: {database: 'bootui_sample', username: 'bootui', password: 'masked'},
      restartable: false,
      logsAvailable: false,
      note: 'Docker Compose status is a startup snapshot.'
    },
    {
      id: 'compose:redis',
      name: 'redis',
      type: 'Redis',
      source: 'Docker Compose',
      image: 'redis:8-alpine',
      status: 'READY_AT_STARTUP',
      host: 'localhost',
      ports: [{containerPort: 6379, hostPort: 16379, protocol: 'tcp'}],
      connectionDetails: {cacheNames: 'sample-products,sample-greetings'},
      restartable: false,
      logsAvailable: true,
      note: 'Bounded logs are available for supported local services.'
    },
    {
      id: 'compose:ollama',
      name: 'ollama',
      type: 'Ollama',
      source: 'Docker Compose',
      image: 'ollama/ollama:latest',
      status: 'READY_AT_STARTUP',
      host: 'localhost',
      ports: [{containerPort: 11434, hostPort: 11434, protocol: 'tcp'}],
      connectionDetails: {model: 'qwen2.5:0.5b'},
      restartable: false,
      logsAvailable: true,
      note: 'Spring AI uses this local model endpoint.'
    }
  ]
}

const scheduled = {
  schedulingPresent: true,
  total: 3,
  tasks: [
    {runnable: 'EchoScheduler.echo', triggerType: 'FIXED_RATE', expression: '30000', initialDelayMs: 0, timeUnit: 'ms'},
    {
      runnable: 'ProductWarmup.refreshCatalog',
      triggerType: 'CRON',
      expression: '0 */5 * * * *',
      initialDelayMs: null,
      timeUnit: 'ms'
    },
    {
      runnable: 'AiUsageRollup.aggregateMinute',
      triggerType: 'FIXED_DELAY',
      expression: '60000',
      initialDelayMs: 10000,
      timeUnit: 'ms'
    }
  ]
}

const dataReport = {
  total: 2,
  repositories: [
    {
      beanName: 'productRepository',
      repositoryInterface: 'io.github.jdubois.bootui.sample.ProductRepository',
      domainType: 'io.github.jdubois.bootui.sample.Product',
      idType: 'java.lang.Long',
      storeModule: 'JPA',
      queryMethodCount: 3,
      fragmentCount: 0
    },
    {
      beanName: 'chatAuditRepository',
      repositoryInterface: 'io.github.jdubois.bootui.sample.ChatAuditRepository',
      domainType: 'io.github.jdubois.bootui.sample.ChatAudit',
      idType: 'java.util.UUID',
      storeModule: 'JPA',
      queryMethodCount: 2,
      fragmentCount: 1
    }
  ]
}

const databaseConnectionPoolsReport = {
  available: true,
  unavailableReason: null,
  pools: [
    {
      beanName: 'dataSource',
      poolName: 'HikariPool-1',
      available: true,
      unavailableReason: null,
      jdbcUrl: 'jdbc:postgresql://localhost:5432/bootui_sample',
      username: 'bootui',
      driverClassName: 'org.postgresql.Driver',
      minimumIdle: 5,
      maximumPoolSize: 20,
      connectionTimeoutMs: 30000,
      validationTimeoutMs: 5000,
      idleTimeoutMs: 600000,
      maxLifetimeMs: 1800000,
      keepaliveTimeMs: 120000,
      readOnly: false,
      autoCommit: true
    },
    {
      beanName: 'readReplicaDataSource',
      poolName: 'HikariPool-read',
      available: true,
      unavailableReason: null,
      jdbcUrl: 'jdbc:postgresql://replica:5432/bootui_sample',
      username: 'bootui_ro',
      driverClassName: 'org.postgresql.Driver',
      minimumIdle: 2,
      maximumPoolSize: 8,
      connectionTimeoutMs: 30000,
      validationTimeoutMs: 5000,
      idleTimeoutMs: 600000,
      maxLifetimeMs: 1800000,
      keepaliveTimeMs: 0,
      readOnly: true,
      autoCommit: true
    }
  ]
}

const databaseConnectionPoolSnapshots = {
  'HikariPool-1': {active: 6, idle: 9, total: 15, pending: 1},
  'HikariPool-read': {active: 2, idle: 4, total: 6, pending: 0}
}

function databaseConnectionPoolSnapshot(poolName) {
  const base = databaseConnectionPoolSnapshots[poolName] || {active: 0, idle: 0, total: 0, pending: 0}
  return {poolName, ...base}
}

const flyway = {
  total: 4,
  databases: [
    {
      name: 'flyway',
      currentVersion: '2',
      applied: 2,
      pending: 2,
      migrateEnabled: true,
      migrateDisabledReason: null,
      cleanEnabled: false,
      cleanDisabledReason: 'Flyway clean is disabled by spring.flyway.clean-disabled=true.',
      migrations: [
        flywayMigration('1', 'create catalog', 'SQL', 'SUCCESS', 'V1__create_catalog.sql', 34),
        flywayMigration('2', 'seed catalog', 'SQL', 'SUCCESS', 'V2__seed_catalog.sql', 18),
        flywayMigration('3', 'add catalog tags', 'SQL', 'PENDING', 'V3__add_catalog_tags.sql', null),
        flywayMigration('4', 'classify catalog books', 'SQL', 'PENDING', 'V4__classify_catalog_books.sql', null)
      ]
    }
  ]
}

const liquibase = {
  total: 4,
  databases: [
    {
      name: 'liquibase',
      applied: 2,
      pending: 2,
      updateEnabled: true,
      updateDisabledReason: null,
      changeSets: [
        liquibaseChangeSet(
          '001-create-inventory',
          'bootui',
          'db/changelog/db.changelog-base.xml',
          'Create inventory table',
          'EXECUTED',
          1
        ),
        liquibaseChangeSet(
          '002-seed-inventory',
          'bootui',
          'db/changelog/db.changelog-base.xml',
          'Seed inventory rows',
          'EXECUTED',
          2
        ),
        liquibaseChangeSet(
          '003-add-location',
          'bootui',
          'db/changelog/db.changelog-master.xml',
          'Add warehouse location',
          'PENDING',
          null
        ),
        liquibaseChangeSet(
          '004-add-restock-threshold',
          'bootui',
          'db/changelog/db.changelog-master.xml',
          'Add restock threshold',
          'PENDING',
          null
        )
      ]
    }
  ]
}

const heapDump = {
  hotspotAvailable: true,
  captureEnabled: true,
  rawDownloadEnabled: false,
  outputDirectory: '/tmp/bootui-heap-dumps',
  liveHeapUsedBytes: 184 * MB,
  freeDiskBytes: 128 * 1024 * MB,
  dumpCount: 1,
  maxDumps: 3,
  histogramTotalInstances: 1_842_517,
  histogramTotalBytes: 172 * MB,
  capture: {
    status: 'ANALYZED',
    message: null,
    capturedAtEpochMs: nowMillis - 45 * 1000
  },
  topClasses: [
    {rank: 1, className: 'byte[]', instances: 412_904, bytes: 58 * MB},
    {rank: 2, className: 'java.lang.String', instances: 388_211, bytes: 24 * MB},
    {rank: 3, className: 'java.util.HashMap$Node', instances: 196_540, bytes: 18 * MB},
    {rank: 4, className: 'java.lang.Object[]', instances: 84_320, bytes: 14 * MB},
    {rank: 5, className: 'io.github.jdubois.bootui.sample.Product', instances: 62_104, bytes: 11 * MB},
    {rank: 6, className: 'java.util.concurrent.ConcurrentHashMap$Node', instances: 51_882, bytes: 9 * MB},
    {rank: 7, className: 'char[]', instances: 47_219, bytes: 7 * MB},
    {rank: 8, className: 'java.lang.Long', instances: 40_511, bytes: 5 * MB},
    {rank: 9, className: 'java.util.ArrayList', instances: 28_640, bytes: 4 * MB},
    {rank: 10, className: 'java.lang.Integer', instances: 21_004, bytes: 3 * MB}
  ],
  dumps: [
    {
      name: 'heapdump-20260531-100245.hprof',
      sizeBytes: 196 * MB,
      live: true,
      createdAtEpochMs: nowMillis - 45 * 1000
    }
  ]
}

const threads = {
  available: true,
  unavailableReason: null,
  capturedAt: nowMillis - 1500,
  total: 6,
  daemonThreads: 4,
  peakThreads: 42,
  deadlockDetected: false,
  deadlockedThreadIds: [],
  virtualThreadsSupported: true,
  stateCounts: [
    {state: 'RUNNABLE', count: 2},
    {state: 'WAITING', count: 2},
    {state: 'TIMED_WAITING', count: 1},
    {state: 'BLOCKED', count: 1}
  ],
  threads: [
    thread(42, 'http-nio-8080-exec-1', 'RUNNABLE', false, 180, [
      'org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1088)',
      'io.github.jdubois.bootui.sample.SampleController.products(SampleController.java:82)'
    ]),
    thread(43, 'http-nio-8080-exec-2', 'WAITING', false, 92, [
      'java.base/jdk.internal.misc.Unsafe.park(Native Method)',
      'java.base/java.util.concurrent.locks.LockSupport.park(LockSupport.java:371)'
    ]),
    thread(21, 'boundedElastic-1', 'TIMED_WAITING', true, 43, [
      'java.base/java.lang.Thread.sleep(Native Method)',
      'io.github.jdubois.bootui.sample.AiUsageRollup.aggregateMinute(AiUsageRollup.java:47)'
    ]),
    thread(
      55,
      'VirtualThread[#55]/runnable@ForkJoinPool-1-worker-1',
      'RUNNABLE',
      true,
      17,
      ['io.github.jdubois.bootui.sample.ChatController.chat(ChatController.java:61)'],
      true
    ),
    thread(14, 'DestroyJavaVM', 'WAITING', false, null, []),
    thread(16, 'HikariPool-1 housekeeper', 'BLOCKED', true, 8, [
      'com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:192)'
    ])
  ]
}

const dataDetail = {
  repositoryInterface: 'io.github.jdubois.bootui.sample.ProductRepository',
  domainType: 'io.github.jdubois.bootui.sample.Product',
  idType: 'java.lang.Long',
  beanName: 'productRepository',
  storeModule: 'JPA',
  customImplementation: 'org.springframework.data.jpa.repository.support.SimpleJpaRepository',
  methods: [
    {
      origin: 'DERIVED',
      signature: 'List<Product> findByActiveTrueOrderByNameAsc()',
      query: null,
      namedQuery: null,
      nativeQuery: false
    },
    {
      origin: 'QUERY',
      signature: 'List<Product> findFeaturedProducts()',
      query: 'select p from Product p where p.active = true and p.category = :category',
      namedQuery: null,
      nativeQuery: false
    },
    {
      origin: 'CRUD',
      signature: 'Optional<Product> findById(Long id)',
      query: null,
      namedQuery: null,
      nativeQuery: false
    }
  ]
}

const cache = {
  cacheAvailable: true,
  clearEnabled: true,
  managerCount: 1,
  cacheCount: 3,
  operationCount: 4,
  warnings: [],
  managers: [
    {
      name: 'redisCacheManager',
      type: 'org.springframework.data.redis.cache.RedisCacheManager',
      noOp: false,
      caches: [
        cacheEntry('redisCacheManager', 'sample-products', 24, 512, 48, 0.91),
        cacheEntry('redisCacheManager', 'sample-greetings', 8, 210, 19, 0.92),
        cacheEntry('redisCacheManager', 'ai-response-snippets', 12, 94, 37, 0.72)
      ]
    }
  ],
  operations: [
    {
      operation: '@Cacheable',
      beanName: 'sampleCatalog',
      targetType: 'io.github.jdubois.bootui.sample.SampleCatalog',
      method: 'activeProducts()',
      caches: ['sample-products'],
      key: "'active'",
      condition: null,
      unless: '#result.isEmpty()',
      allEntries: false,
      beforeInvocation: false
    },
    {
      operation: '@Cacheable',
      beanName: 'sampleCatalog',
      targetType: 'io.github.jdubois.bootui.sample.SampleCatalog',
      method: 'greeting(String,int)',
      caches: ['sample-greetings'],
      key: '#greeting + ":" + #retries',
      condition: null,
      unless: null,
      allEntries: false,
      beforeInvocation: false
    },
    {
      operation: '@CacheEvict',
      beanName: 'sampleCatalog',
      targetType: 'io.github.jdubois.bootui.sample.SampleCatalog',
      method: 'evictProducts()',
      caches: ['sample-products'],
      key: null,
      condition: null,
      unless: null,
      allEntries: true,
      beforeInvocation: false
    },
    {
      operation: '@CachePut',
      beanName: 'aiUsageService',
      targetType: 'io.github.jdubois.bootui.sample.AiUsageService',
      method: 'rememberSnippet(String)',
      caches: ['ai-response-snippets'],
      key: '#conversationId',
      condition: null,
      unless: null,
      allEntries: false,
      beforeInvocation: false
    }
  ]
}

const security = {
  springSecurityPresent: true,
  chains: [
    {
      order: 0,
      requestMatcher: '/bootui/**',
      requestMatcherType: 'MvcRequestMatcher',
      csrfEnabled: false,
      corsEnabled: true,
      sessionManagementPresent: false,
      filters: [
        'DisableEncodeUrlFilter',
        'WebAsyncManagerIntegrationFilter',
        'HeaderWriterFilter',
        'CorsFilter',
        'AuthorizationFilter'
      ]
    },
    {
      order: 1,
      requestMatcher: 'any request',
      requestMatcherType: 'AnyRequestMatcher',
      csrfEnabled: true,
      corsEnabled: true,
      sessionManagementPresent: true,
      filters: [
        'SecurityContextHolderFilter',
        'CsrfFilter',
        'LogoutFilter',
        'UsernamePasswordAuthenticationFilter',
        'BasicAuthenticationFilter',
        'AuthorizationFilter'
      ]
    }
  ],
  auth: {
    authenticationProviderTypes: ['org.springframework.security.authentication.dao.DaoAuthenticationProvider'],
    userDetailsServiceTypes: ['org.springframework.security.provisioning.InMemoryUserDetailsManager'],
    configuredUsername: 'user'
  }
}

const securityEndpoints = {
  handlerMappingAvailable: true,
  total: 5,
  endpoints: [
    {
      method: 'GET',
      pattern: '/bootui/**',
      handler: 'BootUiForwardingController',
      chainIndex: 0,
      rule: 'permitAll',
      roles: [],
      bestEffort: false,
      description: 'LocalhostOnlyFilter enforces loopback access before security.'
    },
    {
      method: 'GET',
      pattern: '/api/sample/hello',
      handler: 'SampleController#hello',
      chainIndex: 1,
      rule: 'permitAll',
      roles: [],
      bestEffort: false,
      description: 'Public sample endpoint.'
    },
    {
      method: 'GET',
      pattern: '/admin',
      handler: 'AdminController#admin',
      chainIndex: 1,
      rule: 'hasRole',
      roles: ['ADMIN'],
      bestEffort: false,
      description: 'Admin-only sample route.'
    },
    {
      method: 'POST',
      pattern: '/api/chat',
      handler: 'ChatController#chat',
      chainIndex: 1,
      rule: 'authenticated',
      roles: [],
      bestEffort: true,
      description: 'Requires authenticated local user.'
    },
    {
      method: 'GET',
      pattern: '/api/secure',
      handler: 'HelloController#secure',
      chainIndex: 1,
      rule: 'authenticated',
      roles: [],
      bestEffort: false,
      description: 'Protected sample endpoint.'
    }
  ]
}

const pentest = {
  checksRun: 41,
  findingsFound: 4,
  disclaimer: 'These local-only checks target the host application and exclude BootUI /bootui paths.',
  scan: {
    status: 'COMPLETED',
    scanner: 'BootUI local OWASP hygiene',
    message: 'Completed 41 local checks with 4 heuristic finding(s).',
    scannedAt: new Date(nowMillis - 45_000).toISOString()
  },
  severityCounts: [
    {severity: 'HIGH', count: 1},
    {severity: 'MEDIUM', count: 1},
    {severity: 'LOW', count: 1},
    {severity: 'INFO', count: 1}
  ],
  coverage: [
    {
      category: 'A01',
      title: 'Broken Access Control',
      description: 'Spring Security and handler mappings were inspected for authorization review prompts.',
      status: 'REVIEW'
    },
    {
      category: 'A02',
      title: 'Cryptographic Failures',
      description: 'Cookie flags were checked on synthetic localhost responses.',
      status: 'PASS'
    },
    {
      category: 'A03',
      title: 'Injection',
      description: 'No payload-based SQL, XSS, or command injection probing is performed by BootUI.',
      status: 'HANDOFF'
    },
    {
      category: 'A05',
      title: 'Security Misconfiguration',
      description:
        'Security headers, CORS behavior, verbose errors, and actuator mappings were reviewed outside BootUI paths.',
      status: 'REVIEW'
    },
    {
      category: 'A06',
      title: 'Vulnerable and Outdated Components',
      description: 'Use the Vulnerabilities panel for explicit OSV dependency scanning.',
      status: 'INFO'
    }
  ],
  findings: [
    {
      id: 'PT-SECURITY-MISSING',
      severity: 'HIGH',
      confidence: 'HIGH',
      title: 'Spring Security is not present',
      target: 'Application context',
      owaspCategory: 'A01 Broken Access Control',
      evidence: 'No SecurityFilterChain beans were detected in the application context.',
      recommendation: 'Add Spring Security and define explicit authorization rules for application endpoints.'
    },
    {
      id: 'PT-HEADERS-MISSING',
      severity: 'MEDIUM',
      confidence: 'MEDIUM',
      title: 'Missing hardening response headers',
      target: '/__bootui_pentest__/missing-resource',
      owaspCategory: 'A05 Security Misconfiguration',
      evidence: 'Missing X-Content-Type-Options and Content-Security-Policy headers on the synthetic 404 response.',
      recommendation: 'Configure security headers globally and verify they apply to error responses.'
    },
    {
      id: 'PT-ACTUATOR-MAPPINGS',
      severity: 'LOW',
      confidence: 'MEDIUM',
      title: 'Actuator mappings are available',
      target: 'Spring MVC handler mappings',
      owaspCategory: 'A05 Security Misconfiguration',
      evidence: 'Actuator request mappings were detected and should be reviewed for exposure.',
      recommendation: 'Keep actuator endpoints local-only, authenticated, or disabled outside development.'
    },
    {
      id: 'PT-HSTS-MISSING',
      severity: 'INFO',
      confidence: 'LOW',
      title: 'Strict-Transport-Security not observed',
      target: '/__bootui_pentest__/missing-resource',
      owaspCategory: 'A05 Security Misconfiguration',
      evidence:
        'No Strict-Transport-Security header was seen on the synthetic localhost response (expected over plain HTTP).',
      recommendation: 'Enable HSTS once the application is served over HTTPS in non-local environments.'
    }
  ]
}

const architecture = {
  localOnly: true,
  disclaimer:
    "Heuristic, project-agnostic architecture rules run against the host application's own classes only. " +
    'These checks complement, but do not replace, a project-specific ArchUnit test suite or an architecture review.',
  basePackages: ['io.github.jdubois.bootui.sample'],
  classesAnalyzed: 42,
  rulesEvaluated: 15,
  violationsFound: 4,
  severityCounts: [
    {severity: 'HIGH', count: 1},
    {severity: 'MEDIUM', count: 2},
    {severity: 'LOW', count: 1},
    {severity: 'INFO', count: 0}
  ],
  scan: {
    analyzer: 'BootUI ArchUnit hygiene',
    status: 'SCANNED',
    message: 'Architecture rules completed against 42 application class(es) under the detected base package(s).',
    scannedAt: nowMillis - 35_000,
    rulesEvaluated: 15,
    classesAnalyzed: 42,
    violationsFound: 4
  },
  results: [
    architectureResult(
      'ARCH-SPRING-004',
      'Beans should not self-invoke their own proxied methods',
      'Spring stereotypes',
      'HIGH',
      'Detects direct self-invocation of @Transactional, @Async, or @Cacheable methods, which bypasses the Spring proxy and silently disables the behaviour.',
      'VIOLATION',
      1,
      ['io.github.jdubois.bootui.sample.order.OrderService.placeOrder() self-invokes @Transactional method confirm()'],
      'Move the proxied method to a collaborating bean or inject a self-reference so the Spring proxy is applied.'
    ),
    architectureResult(
      'ARCH-SPRING-001',
      'Classes should not use field injection',
      'Spring stereotypes',
      'MEDIUM',
      'Detects @Autowired, @Inject, @Value, or @Resource on fields instead of constructor injection.',
      'VIOLATION',
      3,
      [
        'Field io.github.jdubois.bootui.sample.catalog.CatalogService.repository is annotated with @Autowired',
        'Field io.github.jdubois.bootui.sample.order.OrderController.orderService is annotated with @Autowired',
        'Field io.github.jdubois.bootui.sample.web.GreetingController.greeter is annotated with @Value'
      ],
      'Inject collaborators through the constructor instead of annotating fields.'
    ),
    architectureResult(
      'ARCH-PKG-001',
      'Packages should be free of cycles',
      'Package structure',
      'MEDIUM',
      'Detects cyclic dependencies between the top-level package slices under the application base package.',
      'VIOLATION',
      2,
      [
        'Cycle between slices: io.github.jdubois.bootui.sample.catalog -> io.github.jdubois.bootui.sample.order',
        'Cycle between slices: io.github.jdubois.bootui.sample.order -> io.github.jdubois.bootui.sample.catalog'
      ],
      'Break the dependency cycle by extracting shared types or inverting one of the dependencies so packages form a directed acyclic graph.'
    ),
    architectureResult(
      'ARCH-CODE-005',
      'Classes should not call Throwable.printStackTrace()',
      'Coding practices',
      'LOW',
      'Detects calls to Throwable.printStackTrace(), which write to System.err and bypass structured logging.',
      'VIOLATION',
      1,
      [
        'io.github.jdubois.bootui.sample.order.OrderService.process(OrderService.java:58) calls Throwable.printStackTrace()'
      ],
      'Log the exception through the project logging facade instead of calling printStackTrace().'
    )
  ]
}

const hibernateAdvisor = {
  localOnly: true,
  disclaimer:
    "Heuristic Hibernate/JPA mapping rules run against the host application's mapped entities only. " +
    "These checks are review prompts, not verdicts, and should be validated against the application's data access patterns.",
  entityPackages: ['io.github.jdubois.bootui.sample'],
  entitiesAnalyzed: 6,
  rulesEvaluated: 9,
  violationsFound: 4,
  severityCounts: [
    {severity: 'HIGH', count: 1},
    {severity: 'MEDIUM', count: 2},
    {severity: 'LOW', count: 0},
    {severity: 'INFO', count: 1}
  ],
  scan: {
    analyzer: 'BootUI Hibernate Advisor',
    status: 'SCANNED',
    message: 'Hibernate Advisor completed against 6 mapped entities.',
    scannedAt: nowMillis - 28_000,
    rulesEvaluated: 9,
    entitiesAnalyzed: 6,
    violationsFound: 4
  },
  results: [
    hibernateAdvisorResult(
      'HIB-FETCH-001',
      'Eager fetching should stay explicit and bounded',
      'Fetching',
      'HIGH',
      'Detects JPA associations and @ElementCollection attributes mapped with FetchType.EAGER, including default-eager to-one associations.',
      3,
      [
        'io.github.jdubois.bootui.sample.order.Order#customer is mapped as FetchType.EAGER.',
        'io.github.jdubois.bootui.sample.invoice.Invoice#order is mapped as FetchType.EAGER.',
        'io.github.jdubois.bootui.sample.preferences.Preferences#enabledFeatures is an @ElementCollection mapped as FetchType.EAGER.'
      ],
      'Prefer LAZY mappings and fetch required graphs or collection values explicitly with joins, entity graphs, or DTO queries.'
    ),
    hibernateAdvisorResult(
      'HIB-ID-001',
      'Generated identifiers should avoid GenerationType.IDENTITY',
      'Identifiers',
      'MEDIUM',
      'Detects identifiers using GenerationType.IDENTITY, which prevents JDBC batch inserts.',
      1,
      ['io.github.jdubois.bootui.sample.Product#id uses GenerationType.IDENTITY.'],
      "Prefer SEQUENCE with allocationSize and Hibernate's pooled optimizer when the database supports sequences."
    ),
    hibernateAdvisorResult(
      'HIB-CONFIG-001',
      'Open Session in View should be disabled',
      'Configuration',
      'MEDIUM',
      "Detects spring.jpa.open-in-view=true, including Spring Boot's default when the property is not set.",
      1,
      ['spring.jpa.open-in-view=true is enabled.'],
      'Set spring.jpa.open-in-view=false and fetch data inside transactional service boundaries.'
    ),
    hibernateAdvisorResult(
      'HIB-FETCH-002',
      'Batch fetching should cover lazy secondary-select associations',
      'Fetching',
      'INFO',
      'Detects lazy to-one and collection associations that can initialize through secondary selects without hibernate.default_batch_fetch_size or an applicable @BatchSize.',
      3,
      [
        'io.github.jdubois.bootui.sample.SampleOrder#tags can initialize through secondary selects without a global batch-fetch size or applicable @BatchSize.',
        'io.github.jdubois.bootui.sample.SampleOrder#invoices can initialize through secondary selects without a global batch-fetch size or applicable @BatchSize.',
        'io.github.jdubois.bootui.sample.SampleCustomer#invoices can initialize through secondary selects without a global batch-fetch size or applicable @BatchSize.'
      ],
      'Set a bounded hibernate.default_batch_fetch_size or targeted @BatchSize for associations traversed across multiple owner rows; use explicit fetch plans or paged queries for a single oversized collection.'
    )
  ]
}

const securityAdvisor = {
  localOnly: true,
  disclaimer:
    "Heuristic Spring Security rules run against the host application's registered filter chains and security beans only. " +
    "These checks are review prompts, not verdicts, and should be validated against the application's threat model.",
  filterChains: [
    'Or [PathPattern [/bootui], PathPattern [/bootui/**], PathPattern [/bootui/api], PathPattern [/bootui/api/**]]',
    'Or [PathPattern [/admin/**], PathPattern [/api/secure]]',
    'any request'
  ],
  filterChainsAnalyzed: 3,
  rulesEvaluated: 41,
  violationsFound: 5,
  severityCounts: [
    {severity: 'HIGH', count: 2},
    {severity: 'MEDIUM', count: 1},
    {severity: 'LOW', count: 1},
    {severity: 'INFO', count: 1}
  ],
  scan: {
    analyzer: 'BootUI Spring Security Advisor',
    status: 'SCANNED',
    message: 'Security Advisor completed against 3 filter chains.',
    scannedAt: nowMillis - 36_000,
    rulesEvaluated: 41,
    filterChainsAnalyzed: 3,
    violationsFound: 5
  },
  results: [
    securityAdvisorResult(
      'SEC-ACT-002',
      'Sensitive actuator endpoints should not be exposed',
      'Actuator exposure',
      'HIGH',
      'Detects high-value actuator endpoints (env, beans, configprops, heapdump, threaddump, shutdown) in the web exposure list.',
      3,
      [
        "Actuator endpoint 'env' is web-exposed.",
        "Actuator endpoint 'configprops' is web-exposed.",
        "Actuator endpoint 'mappings' is web-exposed."
      ],
      'Remove sensitive endpoints from management.endpoints.web.exposure.include or protect them with authentication.'
    ),
    securityAdvisorResult(
      'SEC-AUTHZ-002',
      'Avoid blanket permitAll authorization',
      'Authorization',
      'HIGH',
      'Detects a chain whose authorization grants every request to anonymous callers.',
      1,
      ['Chain #2 (any request) permits every request anonymously even though it configures authentication.'],
      'Restrict sensitive paths and finish with anyRequest().authenticated(); keep permitAll only for public endpoints.'
    ),
    securityAdvisorResult(
      'SEC-ACT-003',
      'Exposed actuator endpoints should be protected by a security chain',
      'Actuator exposure',
      'MEDIUM',
      'Detects web-exposed actuator endpoints when no filter chain references /actuator.',
      1,
      ['Actuator endpoints are exposed at /actuator but no security filter chain matches that path.'],
      'Add a SecurityFilterChain with a securityMatcher for the actuator base path that requires authentication.'
    ),
    securityAdvisorResult(
      'SEC-AUTH-005',
      'Avoid the auto-generated login page in production',
      'Authentication',
      'LOW',
      "Detects the framework's DefaultLoginPageGeneratingFilter while a production profile is active.",
      1,
      ['Chain #2 (any request) serves the auto-generated Spring Security login page in production.'],
      'Provide a custom login page via formLogin().loginPage(...) for production.'
    ),
    securityAdvisorResult(
      'SEC-ACT-006',
      'Sensitive actuator endpoints should use an isolated management port',
      'Actuator exposure',
      'INFO',
      'Notes that sensitive actuator endpoints are exposed on the main application port.',
      1,
      ['Sensitive actuator endpoints are exposed but management.server.port is unset.'],
      'Set management.server.port to a separate, network-restricted port.'
    )
  ]
}

const graalVm = {
  localOnly: true,
  disclaimer:
    'Heuristic native-image readiness checks run against the host application and generated metadata must be reviewed.',
  basePackages: ['io.github.jdubois.bootui.sample'],
  includeDependencies: true,
  classesAnalyzed: 42,
  checksRun: 9,
  findingsFound: 3,
  dependenciesAnalyzed: 17,
  dependenciesWithoutMetadata: 12,
  warnings: [],
  scan: {
    scanner: 'BootUI GraalVM readiness',
    status: 'SCANNED',
    message: 'Readiness checks completed against 42 application class(es).',
    scannedAt: new Date(nowMillis - 55_000).toISOString()
  },
  severityCounts: [
    {severity: 'HIGH', count: 0},
    {severity: 'MEDIUM', count: 2},
    {severity: 'LOW', count: 1},
    {severity: 'INFO', count: 0}
  ],
  metadata: {
    reflectionEntries: 4,
    serializationEntries: 1,
    resourceEntries: 3
  },
  dependencies: [
    {name: 'org.springframework.boot:spring-boot-autoconfigure', shipsMetadata: true, note: 'Ships native hints.'},
    {name: 'org.postgresql:postgresql', shipsMetadata: true, note: 'JDBC driver metadata detected.'},
    {name: 'com.example:local-sdk', shipsMetadata: false, note: 'No META-INF/native-image metadata found.'}
  ],
  findings: [
    graalVmFinding(
      'GRAAL-REFLECTION-001',
      'Reflective constructor access needs metadata',
      'Reflection',
      'MEDIUM',
      'SampleService reflectively creates a plugin class.',
      2,
      [
        'io.github.jdubois.bootui.sample.PluginLoader uses Class.forName("com.example.LocalPlugin")',
        'io.github.jdubois.bootui.sample.PluginLoader calls getDeclaredConstructor()'
      ],
      'Review whether the target type needs reflection metadata or can be registered through Spring AOT.'
    ),
    graalVmFinding(
      'GRAAL-SERIALIZATION-001',
      'Serializable domain type may need registration',
      'Serialization',
      'MEDIUM',
      'A Serializable type is visible in application code.',
      1,
      ['io.github.jdubois.bootui.sample.ChatAudit implements java.io.Serializable'],
      'Register serialization metadata only if the type is serialized at runtime.'
    ),
    graalVmFinding(
      'GRAAL-RESOURCE-001',
      'Runtime resource lookup detected',
      'Resources',
      'LOW',
      'Application code loads classpath resources dynamically.',
      1,
      ['io.github.jdubois.bootui.sample.SampleCatalog loads classpath:/sample-data/products.json'],
      'Confirm the resource pattern is included in reachability-metadata.json.'
    )
  ]
}

const dependencies = {
  total: 6,
  vulnerable: 2,
  scanningEnabled: true,
  scan: {
    status: 'COMPLETED',
    scanner: 'OSV.dev',
    message: 'Scan completed with 3 advisories across 2 dependencies.',
    scannedAt: new Date(nowMillis - 90_000).toISOString()
  },
  severityCounts: [
    {severity: 'CRITICAL', count: 0},
    {severity: 'HIGH', count: 1},
    {severity: 'MEDIUM', count: 2},
    {severity: 'LOW', count: 0},
    {severity: 'UNKNOWN', count: 0}
  ],
  dependencies: [
    dependency('pkg:maven/org.springframework.boot/spring-boot-starter-web', '4.0.6', 'NONE', []),
    dependency('pkg:maven/com.fasterxml.jackson.core/jackson-databind', '2.20.1', 'NONE', []),
    dependency('pkg:maven/org.postgresql/postgresql', '42.2.19', 'HIGH', [
      vulnerability(
        'GHSA-example-001',
        'HIGH',
        'Sample advisory showing why local dependency scans are useful.',
        ['CVE-2026-0001'],
        ['42.7.4']
      )
    ]),
    dependency('pkg:maven/io.netty/netty-handler', '4.1.95.Final', 'MEDIUM', [
      vulnerability(
        'GHSA-example-002',
        'MEDIUM',
        'Sample TLS handling advisory in a transitive dependency.',
        ['CVE-2026-0002'],
        ['4.1.118.Final']
      ),
      vulnerability('GHSA-example-003', 'MEDIUM', 'Sample denial-of-service advisory.', [], ['4.1.119.Final'])
    ]),
    dependency('pkg:maven/org.springframework.ai/spring-ai-core', '2.0.0-M7', 'NONE', []),
    dependency('pkg:maven/org.testcontainers/testcontainers', '2.0.3', 'NONE', [])
  ]
}

const securityLogs = {
  auditEventsPresent: true,
  unavailableReason: null,
  maxLogs: 500,
  typeSummaries: [
    {type: 'AUTHENTICATION_SUCCESS', count: 8},
    {type: 'AUTHENTICATION_FAILURE', count: 2},
    {type: 'AUTHORIZATION_DENIED', count: 1}
  ],
  events: [
    securityEvent('alice', 'AUTHENTICATION_SUCCESS', nowMillis - 12_000, [
      {name: 'remoteAddress', value: '127.0.0.1', masked: false, truncated: false},
      {name: 'sessionId', value: '******', masked: true, truncated: false}
    ]),
    securityEvent('bob', 'AUTHENTICATION_FAILURE', nowMillis - 10_000, [
      {name: 'reason', value: 'Bad credentials', masked: false, truncated: false},
      {name: 'remoteAddress', value: '******', masked: true, truncated: false}
    ]),
    securityEvent('alice', 'AUTHORIZATION_DENIED', nowMillis - 7_000, [
      {name: 'requestUrl', value: '/admin', masked: false, truncated: false},
      {name: 'requiredRole', value: 'ROLE_ADMIN', masked: false, truncated: false}
    ])
  ],
  page: {
    total: 11,
    matched: 3,
    offset: 0,
    limit: 50,
    returned: 3,
    hasMore: false
  }
}

const httpExchanges = [
  httpExchange('ex-1', 'GET', '/api/sample/products', 'category=tools', 200, 34, 1864, traceId, [
    {name: 'accept', values: ['application/json'], masked: false}
  ]),
  httpExchange('ex-2', 'POST', '/api/chat', null, 200, 812, 452, traceId, [
    {name: 'authorization', values: [], masked: true},
    {name: 'content-type', values: ['application/json'], masked: false}
  ]),
  httpExchange('ex-3', 'GET', '/admin', null, 403, 12, 0, null, [{name: 'cookie', values: [], masked: true}])
]

const copilotSessionId = 'session-bootui-2026-001'
const copilotSession2Id = 'session-bootui-2026-002'

const copilotDashboard = {
  available: true,
  unavailableReason: null,
  sessionStateDir: '~/.copilot/session-state',
  sessionCount: 85,
  eventCount: 69032,
  turnCount: 8764,
  totalInputTokens: 423543210,
  totalOutputTokens: 17156790,
  errorCount: 284,
  activeLast24Hours: 17,
  activeLast7Days: 42,
  sessionsWithSchemaDrift: 0,
  lastActivityEpochMillis: nowMillis - 26 * 1000,
  categoryCounts: [
    {label: 'FILE_EDIT', count: 18840},
    {label: 'FILE_READ', count: 14390},
    {label: 'SEARCH', count: 10580},
    {label: 'SHELL', count: 8840},
    {label: 'WEB', count: 4620},
    {label: 'MCP', count: 3162},
    {label: 'HOOK', count: 1875},
    {label: 'ASK', count: 925},
    {label: 'OTHER', count: 5800}
  ],
  modelCounts: [
    {label: 'claude-sonnet-4.6', count: 61},
    {label: 'claude-opus-4.7', count: 24}
  ],
  topTools: [
    {label: 'edit', count: 18840},
    {label: 'view', count: 14390},
    {label: 'grep', count: 10580},
    {label: 'bash', count: 8840},
    {label: 'glob', count: 4200}
  ],
  otherToolEventCount: 12182,
  activityBuckets: Array.from({length: 24}, (_, i) => {
    const hour = i
    const base = [0, 0, 0, 0, 0, 0, 0, 2, 18, 42, 67, 88, 54, 0, 0, 0, 0, 31, 76, 112, 98, 63, 34, 12][i]
    return {
      startEpochMillis: nowMillis - (23 - i) * 3600 * 1000,
      endEpochMillis: nowMillis - (22 - i) * 3600 * 1000,
      eventCount: base > 0 ? base * 83 + Math.round(hour * 7) : 0,
      errorCount: base > 0 ? Math.min(28, Math.floor(base * 0.3)) : 0,
      inputTokens: base > 0 ? base * 1240000 + i * 95000 : 0,
      outputTokens: base > 0 ? base * 58000 + i * 4200 : 0
    }
  }),
  dailyActivityBuckets: Array.from({length: 7}, (_, i) => ({
    startEpochMillis: nowMillis - (6 - i) * 86400 * 1000,
    endEpochMillis: nowMillis - (5 - i) * 86400 * 1000,
    eventCount: [5200, 8700, 11932, 10450, 9875, 11750, 11125][i],
    errorCount: [12, 26, 48, 41, 36, 57, 64][i],
    inputTokens: [31200000, 52800000, 73500000, 64900000, 58300000, 75400000, 67443210][i],
    outputTokens: [1100000, 2050000, 2960000, 2470000, 2210000, 3090000, 3276790][i]
  })),
  recentSessions: [
    {
      id: copilotSessionId,
      filename: 'laughing-succotash',
      startedAtEpochMillis: nowMillis - 45 * 60 * 1000,
      updatedAtEpochMillis: nowMillis - 26 * 1000,
      model: 'claude-sonnet-4.6',
      workingDirectory: '/workspace/BootUI/jdubois-laughing-succotash',
      status: 'active',
      eventCount: 4284,
      turnCount: 547,
      inputTokens: 47543210,
      outputTokens: 1945432,
      errorCount: 13,
      lastActivitySummary: 'Updated docs and screenshot script',
      schemaDrift: false
    },
    {
      id: copilotSession2Id,
      filename: 'crispy-broccoli',
      startedAtEpochMillis: nowMillis - 3 * 3600 * 1000,
      updatedAtEpochMillis: nowMillis - 2.5 * 3600 * 1000,
      model: 'claude-sonnet-4.6',
      workingDirectory: '/workspace/BootUI/jdubois-crispy-broccoli',
      status: 'complete',
      eventCount: 3198,
      turnCount: 431,
      inputTokens: 29111100,
      outputTokens: 955432,
      errorCount: 22,
      lastActivitySummary: 'Added Copilot panel backend and tests',
      schemaDrift: false
    }
  ],
  warnings: []
}

const copilotSessions = {
  available: true,
  unavailableReason: null,
  sessionStateDir: '~/.copilot/session-state',
  total: 85,
  returned: 85,
  maxSessions: 100,
  sessions: copilotDashboard.recentSessions,
  warnings: []
}

const copilotSessionDetail = {
  summary: copilotDashboard.recentSessions[0],
  counts: {
    total: 4284,
    byCategory: {
      FILE_EDIT: 72,
      FILE_READ: 68,
      SEARCH: 54,
      SHELL: 41,
      WEB: 18,
      MCP: 12,
      HOOK: 9,
      ASK: 7,
      OTHER: 3
    },
    errors: 13,
    lastActivityEpochMillis: nowMillis - 26 * 1000
  },
  turns: [
    {
      index: 0,
      startedAtEpochMillis: nowMillis - 45 * 60 * 1000,
      durationMillis: 12400,
      summary: 'Exploring repository structure and docs',
      eventCount: 14,
      outputTokens: 3210
    },
    {
      index: 1,
      startedAtEpochMillis: nowMillis - 40 * 60 * 1000,
      durationMillis: 28700,
      summary: 'Reading PLAN.md, FEATURES.md, README, and copilot instructions',
      eventCount: 22,
      outputTokens: 8420
    },
    {
      index: 2,
      startedAtEpochMillis: nowMillis - 30 * 60 * 1000,
      durationMillis: 54200,
      summary: 'Updating screenshot script with Copilot fixture data',
      eventCount: 38,
      outputTokens: 12640
    },
    {
      index: 3,
      startedAtEpochMillis: nowMillis - 10 * 60 * 1000,
      durationMillis: 18900,
      summary: 'Patching FEATURES.md, PLAN.md, and copilot instructions',
      eventCount: 21,
      outputTokens: 6850
    }
  ],
  recentEvents: [
    {
      id: 'ev-001',
      turnIndex: 3,
      timestampEpochMillis: nowMillis - 9 * 60 * 1000,
      type: 'tool.execution_complete',
      toolName: 'view',
      category: 'FILE_READ',
      summary: 'docs/FEATURES.md',
      success: true
    },
    {
      id: 'ev-002',
      turnIndex: 3,
      timestampEpochMillis: nowMillis - 8 * 60 * 1000,
      type: 'tool.execution_complete',
      toolName: 'grep',
      category: 'SEARCH',
      summary: 'Copilot — docs/FEATURES.md',
      success: true
    },
    {
      id: 'ev-003',
      turnIndex: 3,
      timestampEpochMillis: nowMillis - 7 * 60 * 1000,
      type: 'tool.execution_complete',
      toolName: 'edit',
      category: 'FILE_EDIT',
      summary: '.github/copilot-instructions.md',
      success: true
    },
    {
      id: 'ev-004',
      turnIndex: 3,
      timestampEpochMillis: nowMillis - 6 * 60 * 1000,
      type: 'tool.execution_complete',
      toolName: 'edit',
      category: 'FILE_EDIT',
      summary: 'docs/PLAN.md',
      success: true
    },
    {
      id: 'ev-005',
      turnIndex: 3,
      timestampEpochMillis: nowMillis - 5 * 60 * 1000,
      type: 'tool.execution_complete',
      toolName: 'bash',
      category: 'SHELL',
      summary: 'npm run screenshots',
      success: true
    }
  ],
  failureEvents: [],
  warnings: []
}

const screenshots = [
  [
    'overview',
    'Overview',
    'bootui-overview.png',
    async (page) => {
      await page.getByText('Understand your Spring Boot app').waitFor()
      await page.getByRole('button', {name: /Run all scanners/}).click()
      await page.getByText('6 of 6 scanners scored').waitFor()
      await page.getByText('1 security alert(s)').waitFor()
    }
  ],
  ['github', 'GitHub', 'bootui-github.png', waitForText('Open pull requests')],
  ['health', 'Health', 'bootui-health.png', waitForText('Component tree')],
  [
    'http-sessions',
    'HTTP Sessions',
    'bootui-http-sessions.png',
    async (page) => {
      await page.getByText('session-a1b2...').waitFor()
      await page
        .getByRole('button', {name: /Details/})
        .first()
        .click()
      await page.getByText('SPRING_SECURITY_CONTEXT').waitFor()
    }
  ],
  [
    'metrics',
    'Metrics',
    'bootui-metrics.png',
    async (page) => {
      await page.getByText('jvm.memory.used').first().waitFor()
      await page.waitForTimeout(2300)
    }
  ],
  ['memory', 'Memory', 'bootui-memory.png', waitForText('Memory Pools')],
  ['tuning-advisor', 'Tuning Advisor', 'bootui-tuning-advisor.png', waitForText('Bare metal JVM calculator')],
  [
    'heap-dump',
    'Heap Dump',
    'bootui-heap-dump.png',
    async (page) => {
      await page.getByText('Top classes by retained size').waitFor()
      await page.getByText('java.lang.String').first().waitFor()
    }
  ],
  ['threads', 'Threads', 'bootui-threads.png', waitForText('http-nio-8080-exec-1')],
  ['startup', 'Startup Timeline', 'bootui-startup-timeline.png', waitForText('spring.context.refresh')],
  ['graalvm', 'GraalVM', 'bootui-graalvm.png', waitForText('Reflective constructor access needs metadata')],
  ['config', 'Configuration', 'bootui-configuration.png', waitForText('sample.greeting')],
  ['profiles', 'Profile Diff', 'bootui-profile-diff.png', waitForText('classpath:/application-dev.properties')],
  ['loggers', 'Loggers', 'bootui-loggers.png', waitForText('io.github.jdubois.bootui')],
  ['beans', 'Beans', 'bootui-beans.png', waitForText('sampleController')],
  ['conditions', 'Conditions', 'bootui-conditions.png', waitForText('BootUiAutoConfiguration')],
  ['mappings', 'Mappings', 'bootui-mappings.png', waitForText('/api/sample/products')],
  [
    'database-connection-pools',
    'Database Connection Pools',
    'bootui-database-connection-pools.png',
    async (page) => {
      await page.getByText('HikariPool-1').first().waitFor()
      await page.getByText('jdbc:postgresql://localhost:5432/bootui_sample').waitFor()
      await page.waitForTimeout(4500)
    }
  ],
  [
    'data',
    'Spring Data',
    'bootui-data.png',
    async (page) => {
      await page.getByText('ProductRepository').waitFor()
      await page.getByRole('button', {name: /ProductRepository/}).click()
      await page.getByText('findByActiveTrueOrderByNameAsc').waitFor()
    }
  ],
  ['hibernate-advisor', 'Hibernate Advisor', 'bootui-hibernate-advisor.png', waitForText('FetchType.EAGER')],
  ['flyway', 'Flyway', 'bootui-flyway.png', waitForText('V3__add_catalog_tags.sql')],
  ['liquibase', 'Liquibase', 'bootui-liquibase.png', waitForText('003-add-location')],
  ['spring-security', 'Spring Security', 'bootui-security.png', waitForText('/api/sample/hello')],
  ['security-logs', 'Security Logs', 'bootui-security-logs.png', waitForText('AUTHENTICATION_SUCCESS')],
  ['security-advisor', 'Security Advisor', 'bootui-security-advisor.png', waitForText('SEC-ACT-002')],
  ['pentest', 'Pentesting', 'bootui-pentesting.png', waitForText('Missing hardening response headers')],
  ['vulnerabilities', 'Vulnerabilities', 'bootui-vulnerabilities.png', waitForText('GHSA-example-001')],
  ['scheduled', 'Scheduled Tasks', 'bootui-scheduled-tasks.png', waitForText('EchoScheduler.echo')],
  ['spring-cache', 'Spring Cache', 'bootui-spring-cache.png', waitForText('sample-products')],
  ['ai', 'AI Usage', 'bootui-ai.png', waitForText('Token usage')],
  ['traces', 'Traces', 'bootui-traces.png', waitForText('POST /api/chat')],
  ['log-tail', 'Log Tail', 'bootui-log-tail.png', waitForText('Started BootUI sample application')],
  [
    'http-exchanges',
    'HTTP Exchanges',
    'bootui-http-exchanges.png',
    async (page) => {
      await page.getByText('/api/sample/products').waitFor()
      await page
        .getByRole('button', {name: /View details/})
        .first()
        .click()
      await page.getByText('Request headers').waitFor()
    }
  ],
  [
    'http-probe',
    'HTTP Probe',
    'bootui-http-probe.png',
    async (page) => {
      await page.getByPlaceholder('/api/sample/hello').fill('/api/sample/products')
      await page.getByRole('button', {name: 'Send'}).click()
      await page.getByText('200 OK').waitFor()
    }
  ],
  ['architecture', 'Architecture', 'bootui-architecture.png', waitForText('Packages should be free of cycles')],
  ['devtools', 'DevTools', 'bootui-devtools.png', waitForText('Trigger LiveReload')],
  ['dev-services', 'Dev Services', 'bootui-dev-services.png', waitForText('postgres')],
  [
    'copilot',
    'Copilot',
    'bootui-copilot.png',
    async (page) => {
      await page.getByText('Copilot activity overview').waitFor()
      await page.getByText('session-bootui-2026-001').first().click()
      await page.getByText('Updated docs and screenshot script').waitFor()
    }
  ],
  [
    'claude-code',
    'Claude Code',
    'bootui-claude-code.png',
    async (page) => {
      await page.getByText('Claude Code activity overview').waitFor()
      await page.getByText(claudeCodeSessionId).first().click()
      await page.getByText('FILE_EDIT · MultiEdit refactor').first().waitFor()
    }
  ]
]

const screenshotFilter = (process.env.BOOTUI_SCREENSHOT_ONLY || '')
  .split(',')
  .map((value) => value.trim())
  .filter(Boolean)
const selectedScreenshots =
  screenshotFilter.length === 0
    ? screenshots
    : screenshots.filter(
        ([route, title, fileName]) =>
          screenshotFilter.includes(route) || screenshotFilter.includes(title) || screenshotFilter.includes(fileName)
      )

if (screenshotFilter.length > 0 && selectedScreenshots.length === 0) {
  throw new Error(`No screenshots matched BOOTUI_SCREENSHOT_ONLY=${process.env.BOOTUI_SCREENSHOT_ONLY}`)
}

const claudeCodeSessionId = '4f7c5b8a-9d3e-42a1-b07c-1e9d4af86c11'
const claudeCodeSession2Id = '2b3a16f0-77c8-4d9a-9e21-58aa3eb1d6c4'
const claudeCodeProjectSlug = '-workspace-BootUI-jdubois-laughing-succotash'

const claudeCodeDashboard = {
  available: true,
  unavailableReason: null,
  sessionStateDir: '~/.claude/projects',
  sessionCount: 85,
  eventCount: 69032,
  turnCount: 8764,
  totalInputTokens: 423543210,
  totalOutputTokens: 17156790,
  errorCount: 284,
  activeLast24Hours: 17,
  activeLast7Days: 42,
  sessionsWithSchemaDrift: 0,
  lastActivityEpochMillis: nowMillis - 26 * 1000,
  categoryCounts: [
    {label: 'FILE_EDIT', count: 18840},
    {label: 'FILE_READ', count: 14390},
    {label: 'SEARCH', count: 10580},
    {label: 'SHELL', count: 8840},
    {label: 'WEB', count: 4620},
    {label: 'MCP', count: 3162},
    {label: 'ASK', count: 1875},
    {label: 'SKILL', count: 925},
    {label: 'OTHER', count: 5800}
  ],
  modelCounts: [
    {label: 'claude-sonnet-4-20250514', count: 61},
    {label: 'claude-opus-4-1-20250915', count: 24}
  ],
  topTools: [
    {label: 'Edit', count: 18840},
    {label: 'Read', count: 14390},
    {label: 'Bash', count: 8840},
    {label: 'Grep', count: 10580},
    {label: 'Glob', count: 4200}
  ],
  otherToolEventCount: 12182,
  activityBuckets: Array.from({length: 24}, (_, i) => {
    const hour = i
    const base = [0, 0, 0, 0, 0, 0, 0, 0, 12, 36, 58, 74, 41, 0, 0, 0, 0, 22, 64, 96, 82, 51, 27, 8][i]
    return {
      startEpochMillis: nowMillis - (23 - i) * 3600 * 1000,
      endEpochMillis: nowMillis - (22 - i) * 3600 * 1000,
      eventCount: base > 0 ? base * 83 + Math.round(hour * 7) : 0,
      errorCount: base > 0 ? Math.min(28, Math.floor(base * 0.3)) : 0,
      inputTokens: base > 0 ? base * 1240000 + i * 95000 : 0,
      outputTokens: base > 0 ? base * 58000 + i * 4200 : 0
    }
  }),
  dailyActivityBuckets: Array.from({length: 7}, (_, i) => ({
    startEpochMillis: nowMillis - (6 - i) * 86400 * 1000,
    endEpochMillis: nowMillis - (5 - i) * 86400 * 1000,
    eventCount: [5200, 8700, 11932, 10450, 9875, 11750, 11125][i],
    errorCount: [12, 26, 48, 41, 36, 57, 64][i],
    inputTokens: [31200000, 52800000, 73500000, 64900000, 58300000, 75400000, 67443210][i],
    outputTokens: [1100000, 2050000, 2960000, 2470000, 2210000, 3090000, 3276790][i]
  })),
  recentSessions: [
    {
      id: claudeCodeSessionId,
      filename: `${claudeCodeProjectSlug}/${claudeCodeSessionId}.jsonl`,
      startedAtEpochMillis: nowMillis - 38 * 60 * 1000,
      updatedAtEpochMillis: nowMillis - 26 * 1000,
      model: 'claude-sonnet-4-20250514',
      workingDirectory: '/workspace/BootUI/jdubois-laughing-succotash',
      status: 'active',
      eventCount: 4214,
      turnCount: 536,
      inputTokens: 47543210,
      outputTokens: 1945432,
      errorCount: 13,
      lastActivitySummary: 'FILE_EDIT · MultiEdit refactor',
      schemaDrift: false
    },
    {
      id: claudeCodeSession2Id,
      filename: `-workspace-BootUI-jdubois-crispy-broccoli/${claudeCodeSession2Id}.jsonl`,
      startedAtEpochMillis: nowMillis - 2.7 * 3600 * 1000,
      updatedAtEpochMillis: nowMillis - 2.2 * 3600 * 1000,
      model: 'claude-opus-4-1-20250915',
      workingDirectory: '/workspace/BootUI/jdubois-crispy-broccoli',
      status: 'complete',
      eventCount: 3154,
      turnCount: 423,
      inputTokens: 29111100,
      outputTokens: 955432,
      errorCount: 22,
      lastActivitySummary: 'SHELL · Bash · failed',
      schemaDrift: false
    }
  ],
  warnings: []
}

const claudeCodeSessions = {
  available: true,
  unavailableReason: null,
  sessionStateDir: '~/.claude/projects',
  total: 85,
  returned: 85,
  maxSessions: 100,
  sessions: claudeCodeDashboard.recentSessions,
  warnings: []
}

const claudeCodeSessionDetail = {
  summary: claudeCodeDashboard.recentSessions[0],
  counts: {
    total: 4214,
    byCategory: {
      FILE_EDIT: 58,
      FILE_READ: 51,
      SEARCH: 36,
      SHELL: 29,
      WEB: 12,
      MCP: 8,
      ASK: 6,
      SKILL: 4,
      OTHER: 10
    },
    errors: 13,
    lastActivityEpochMillis: nowMillis - 26 * 1000
  },
  turns: [
    {
      index: 0,
      startedAtEpochMillis: nowMillis - 38 * 60 * 1000,
      durationMillis: 9800,
      summary: 'Exploring project structure with Glob',
      eventCount: 11,
      inputTokens: 18420,
      outputTokens: 920
    },
    {
      index: 1,
      startedAtEpochMillis: nowMillis - 32 * 60 * 1000,
      durationMillis: 21400,
      summary: 'Reading source files: CopilotSessionStore, BootUiProperties',
      eventCount: 19,
      inputTokens: 32600,
      outputTokens: 1840
    },
    {
      index: 2,
      startedAtEpochMillis: nowMillis - 22 * 60 * 1000,
      durationMillis: 48700,
      summary: 'Refactor to AgentSessionStore with Edit and MultiEdit',
      eventCount: 27,
      inputTokens: 41280,
      outputTokens: 3120
    },
    {
      index: 3,
      startedAtEpochMillis: nowMillis - 8 * 60 * 1000,
      durationMillis: 14600,
      summary: 'Running ./mvnw test to verify the refactor',
      eventCount: 16,
      inputTokens: 22450,
      outputTokens: 1160
    }
  ],
  recentEvents: [
    {
      id: 'toolu_01',
      turnIndex: 3,
      timestampEpochMillis: nowMillis - 11 * 60 * 1000,
      type: 'tool_use',
      toolName: 'Read',
      category: 'FILE_READ',
      summary: 'FILE_READ · Read',
      success: true
    },
    {
      id: 'toolu_02',
      turnIndex: 3,
      timestampEpochMillis: nowMillis - 10 * 60 * 1000,
      type: 'tool_use',
      toolName: 'Grep',
      category: 'SEARCH',
      summary: 'SEARCH · Grep',
      success: true
    },
    {
      id: 'toolu_03',
      turnIndex: 3,
      timestampEpochMillis: nowMillis - 9 * 60 * 1000,
      type: 'tool_use',
      toolName: 'Edit',
      category: 'FILE_EDIT',
      summary: 'FILE_EDIT · Edit',
      success: true
    },
    {
      id: 'toolu_04',
      turnIndex: 3,
      timestampEpochMillis: nowMillis - 8 * 60 * 1000,
      type: 'tool_use',
      toolName: 'MultiEdit',
      category: 'FILE_EDIT',
      summary: 'FILE_EDIT · MultiEdit',
      success: true
    },
    {
      id: 'toolu_05',
      turnIndex: 3,
      timestampEpochMillis: nowMillis - 7 * 60 * 1000,
      type: 'tool_use',
      toolName: 'Bash',
      category: 'SHELL',
      summary: 'SHELL · Bash',
      success: true
    },
    {
      id: 'toolu_06',
      turnIndex: 3,
      timestampEpochMillis: nowMillis - 6 * 60 * 1000,
      type: 'tool_result',
      toolName: 'Bash',
      category: 'SHELL',
      summary: 'SHELL · Bash',
      success: true
    }
  ],
  failureEvents: [],
  warnings: []
}

let viteProcess
let browser

try {
  await fs.mkdir(imagesDir, {recursive: true})
  if (process.env.BOOTUI_SCREENSHOT_BASE_URL) {
    await waitForServer(`${baseUrl}/bootui/`)
  } else if (await isServerReady(`${baseUrl}/bootui/`)) {
    console.log(`Reusing existing dev server at ${baseUrl}/bootui/`)
  } else {
    viteProcess = startVite()
    await waitForServer(`${baseUrl}/bootui/`)
  }

  browser = await chromium.launch()
  const page = await browser.newPage({
    viewport,
    deviceScaleFactor: 1
  })

  await page.addInitScript(
    ({logLines}) => {
      const styleText = `
      *, *::before, *::after {
        animation-duration: 0s !important;
        animation-delay: 0s !important;
        caret-color: transparent !important;
        transition-duration: 0s !important;
        transition-delay: 0s !important;
      }
    `
      document.addEventListener('DOMContentLoaded', () => {
        const style = document.createElement('style')
        style.textContent = styleText
        document.head.appendChild(style)
      })

      class FakeEventSource {
        constructor() {
          this.listeners = new Map()
          this.readyState = 0
          setTimeout(() => {
            this.readyState = 1
            this.emit('open', {type: 'open'})
            logLines.forEach((line, index) => {
              setTimeout(() => this.emit('log', {type: 'log', data: JSON.stringify(line)}), 40 + index * 50)
            })
          }, 20)
        }

        addEventListener(type, listener) {
          const listeners = this.listeners.get(type) || []
          listeners.push(listener)
          this.listeners.set(type, listeners)
        }

        removeEventListener(type, listener) {
          const listeners = this.listeners.get(type) || []
          this.listeners.set(
            type,
            listeners.filter((candidate) => candidate !== listener)
          )
        }

        close() {
          this.readyState = 2
        }

        emit(type, event) {
          for (const listener of this.listeners.get(type) || []) {
            listener(event)
          }
          const handler = this[`on${type}`]
          if (handler) handler(event)
        }
      }

      window.EventSource = FakeEventSource
    },
    {logLines: sampleLogLines()}
  )

  await page.route('**/bootui/api/**', handleApiRoute)

  for (const [route, title, fileName, prepare] of selectedScreenshots) {
    await page.goto(`${baseUrl}/bootui/#/${route}`)
    await page.locator('main .page-panel h2').first().waitFor()
    await prepare(page)
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.waitForTimeout(250)
    await page.screenshot({
      path: path.join(imagesDir, fileName),
      fullPage: false,
      animations: 'disabled'
    })
    console.log(`Captured ${fileName}`)
  }
} finally {
  if (browser) await browser.close()
  if (viteProcess) {
    viteProcess.kill('SIGTERM')
  }
}

function startVite() {
  const viteBin = path.join(frontendDir, 'node_modules', 'vite', 'bin', 'vite.js')
  const child = spawn(process.execPath, [viteBin, '--host', '127.0.0.1', '--port', String(port), '--strictPort'], {
    cwd: frontendDir,
    stdio: ['ignore', 'pipe', 'pipe'],
    env: {...process.env, BROWSER: 'none'}
  })
  child.stdout.on('data', (data) => process.stdout.write(data))
  child.stderr.on('data', (data) => process.stderr.write(data))
  child.on('exit', (code) => {
    if (code !== null && code !== 0 && code !== 143) {
      console.error(`Vite exited with status ${code}`)
    }
  })
  return child
}

async function waitForServer(url) {
  const deadline = Date.now() + 60_000
  while (Date.now() < deadline) {
    if (await isServerReady(url)) return
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`Timed out waiting for ${url}`)
}

async function isServerReady(url) {
  try {
    const response = await fetch(url)
    return response.ok
  } catch {
    return false
  }
}

async function handleApiRoute(route) {
  const request = route.request()
  const url = new URL(request.url())
  const endpoint = decodeURIComponent(url.pathname.replace(/^\/bootui\/api\/?/, ''))

  if (endpoint === 'overview') return fulfillJson(route, overview)
  if (endpoint === 'github' || endpoint === 'github/refresh') return fulfillJson(route, github)
  if (endpoint === 'panels')
    return fulfillJson(route, {
      panels: panelOrder.map(([id, title]) => ({id, title, available: true, unavailableReason: null}))
    })
  if (endpoint === 'startup') return fulfillJson(route, startup)
  if (endpoint.startsWith('memory') || endpoint.startsWith('tuning-advisor')) return fulfillJson(route, memory)
  if (endpoint === 'health') return fulfillJson(route, health)
  if (endpoint === 'http-sessions') return fulfillJson(route, httpSessions)
  if (endpoint.startsWith('http-sessions/') && endpoint.endsWith('/clear'))
    return fulfillJson(route, {status: 'success', message: 'Cleared HTTP session attributes.'})
  if (endpoint.startsWith('http-sessions/') && endpoint.endsWith('/invalidate'))
    return fulfillJson(route, {status: 'success', message: 'Destroyed HTTP session.'})
  if (endpoint === 'metrics') return fulfillJson(route, metrics)
  if (endpoint === 'metrics/detail')
    return fulfillJson(route, metricDetail(url.searchParams.get('name') || 'jvm.memory.used'))
  if (endpoint === 'conditions') return fulfillJson(route, conditions)
  if (endpoint === 'beans') return fulfillJson(route, beans)
  if (endpoint === 'mappings') return fulfillJson(route, mappings)
  if (endpoint === 'mappings/flat') return fulfillJson(route, pagedReport('mappings', flatMappings, url))
  if (endpoint === 'config') return fulfillJson(route, configuration)
  if (endpoint === 'profiles') return fulfillJson(route, profileDiff)
  if (endpoint === 'loggers') return fulfillJson(route, loggers)
  if (endpoint === 'threads') return fulfillJson(route, pagedReport('threads', threads.threads, url, threads))
  if (endpoint === 'threads/download') return fulfillJson(route, {status: 'OK'})
  if (endpoint === 'traces') return fulfillJson(route, traceReport)
  if (endpoint === `traces/${traceId}`) return fulfillJson(route, traceDetail)
  if (endpoint === 'ai/overview') return fulfillJson(route, aiOverview)
  if (endpoint === 'ai/tokens') return fulfillJson(route, aiTokens)
  if (endpoint === `ai/chats/${aiSpanId}`) return fulfillJson(route, aiDetail)
  if (endpoint === 'probe') return fulfillJson(route, probeResponse(postDataJson(request)))
  if (endpoint === 'copilot/dashboard') return fulfillJson(route, copilotDashboard)
  if (endpoint === 'copilot/sessions') return fulfillJson(route, copilotSessions)
  if (endpoint === `copilot/sessions/${copilotSessionId}`) return fulfillJson(route, copilotSessionDetail)
  if (endpoint === `copilot/sessions/${copilotSession2Id}`)
    return fulfillJson(route, {
      ...copilotSessionDetail,
      summary: copilotDashboard.recentSessions[1],
      counts: {...copilotSessionDetail.counts, total: 198, errors: 2},
      recentEvents: copilotSessionDetail.recentEvents.slice(0, 3),
      failureEvents: [
        {
          id: 'ev-fail-001',
          turnIndex: 2,
          timestampEpochMillis: nowMillis - 2.6 * 3600 * 1000,
          type: 'tool.execution_complete',
          toolName: 'bash',
          category: 'SHELL',
          summary: './mvnw test',
          success: false
        }
      ]
    })
  if (endpoint.startsWith('copilot/sessions/') && endpoint.endsWith('/events'))
    return fulfillJson(route, {
      sessionId: copilotSessionId,
      total: copilotSessionDetail.recentEvents.length,
      returned: copilotSessionDetail.recentEvents.length,
      events: copilotSessionDetail.recentEvents
    })
  if (endpoint === 'claude-code/dashboard') return fulfillJson(route, claudeCodeDashboard)
  if (endpoint === 'claude-code/sessions') return fulfillJson(route, claudeCodeSessions)
  if (endpoint === `claude-code/sessions/${claudeCodeSessionId}`) return fulfillJson(route, claudeCodeSessionDetail)
  if (endpoint === `claude-code/sessions/${claudeCodeSession2Id}`)
    return fulfillJson(route, {
      ...claudeCodeSessionDetail,
      summary: claudeCodeDashboard.recentSessions[1],
      counts: {...claudeCodeSessionDetail.counts, total: 154, errors: 1},
      recentEvents: claudeCodeSessionDetail.recentEvents.slice(0, 3),
      failureEvents: [
        {
          id: 'toolu_fail_01',
          turnIndex: 2,
          timestampEpochMillis: nowMillis - 2.3 * 3600 * 1000,
          type: 'tool_result',
          toolName: 'Bash',
          category: 'SHELL',
          summary: 'SHELL · Bash · failed',
          success: false
        }
      ]
    })
  if (endpoint.startsWith('claude-code/sessions/') && endpoint.endsWith('/events'))
    return fulfillJson(route, {
      sessionId: claudeCodeSessionId,
      total: claudeCodeSessionDetail.recentEvents.length,
      returned: claudeCodeSessionDetail.recentEvents.length,
      events: claudeCodeSessionDetail.recentEvents
    })
  if (endpoint === 'devtools') return fulfillJson(route, devTools)
  if (endpoint === 'dev-services') return fulfillJson(route, devServices)
  if (endpoint === 'dev-services/compose:redis/logs') {
    return fulfillJson(route, {
      id: 'compose:redis',
      logs: 'Redis ready to accept connections\nCache hit sample-products::active',
      truncated: false,
      maxBytes: 65536
    })
  }
  if (endpoint === 'scheduled') return fulfillJson(route, scheduled)
  if (endpoint === 'data/repositories') return fulfillJson(route, dataReport)
  if (endpoint.startsWith('data/repositories/')) return fulfillJson(route, dataDetail)
  if (endpoint === 'hibernate-advisor') return fulfillJson(route, hibernateAdvisor)
  if (endpoint === 'hibernate-advisor/scan') return fulfillJson(route, hibernateAdvisor)
  if (endpoint === 'flyway/migrations') return fulfillJson(route, flyway)
  if (endpoint === 'flyway/migrate')
    return fulfillJson(route, {status: 'success', message: 'Applied 2 pending Flyway migration(s).'})
  if (endpoint === 'flyway/clean') return fulfillJson(route, {status: 'blocked', message: 'Flyway clean is disabled.'})
  if (endpoint === 'liquibase/changesets') return fulfillJson(route, liquibase)
  if (endpoint === 'liquibase/update')
    return fulfillJson(route, {status: 'success', message: 'Applied 2 pending Liquibase change set(s).'})
  if (endpoint === 'database-connection-pools/pools') return fulfillJson(route, databaseConnectionPoolsReport)
  if (endpoint.startsWith('database-connection-pools/pools/') && endpoint.endsWith('/snapshot')) {
    const poolName = endpoint.slice('database-connection-pools/pools/'.length, -'/snapshot'.length)
    return fulfillJson(route, databaseConnectionPoolSnapshot(poolName))
  }
  if (endpoint.startsWith('heap-dump')) return fulfillJson(route, heapDump)
  if (endpoint === 'spring-cache') return fulfillJson(route, cache)
  if (endpoint === 'spring-security') return fulfillJson(route, security)
  if (endpoint === 'spring-security/endpoints') return fulfillJson(route, securityEndpoints)
  if (endpoint === 'spring-security/explain')
    return fulfillJson(route, {
      matched: true,
      bestEffort: false,
      chainIndex: 1,
      matcherDescription: 'any request',
      filters: security.chains[1].filters
    })
  if (endpoint === 'security-logs') return fulfillJson(route, securityLogs)
  if (endpoint === 'security-advisor') return fulfillJson(route, securityAdvisor)
  if (endpoint === 'security-advisor/scan') return fulfillJson(route, securityAdvisor)
  if (endpoint === 'http-exchanges')
    return fulfillJson(
      route,
      pagedReport('exchanges', httpExchanges, url, {
        recorded: httpExchanges.length,
        unavailableReason: null
      })
    )
  if (endpoint === 'dependencies') return fulfillJson(route, dependencies)
  if (endpoint === 'dependencies/scan') return fulfillJson(route, dependencies)
  if (endpoint === 'pentest') return fulfillJson(route, pentest)
  if (endpoint === 'pentest/scan') return fulfillJson(route, pentest)
  if (endpoint === 'architecture') return fulfillJson(route, architecture)
  if (endpoint === 'architecture/scan') return fulfillJson(route, architecture)
  if (endpoint === 'graalvm') return fulfillJson(route, graalVm)
  if (endpoint === 'graalvm/scan') return fulfillJson(route, graalVm)

  return fulfillJson(route, {error: `No screenshot fixture for ${endpoint}`}, 404)
}

function waitForText(text) {
  return (page) => page.getByText(text).first().waitFor()
}

function pagedReport(itemsKey, items, url, extra = {}) {
  const offset = Number(url.searchParams.get('offset') || 0)
  const limit = Number(url.searchParams.get('limit') || items.length)
  const returnedItems = items.slice(offset, offset + limit)
  return {
    ...extra,
    total: items.length,
    [itemsKey]: returnedItems,
    page: {
      total: items.length,
      matched: items.length,
      offset,
      limit,
      returned: returnedItems.length,
      hasMore: offset + returnedItems.length < items.length
    }
  }
}

function mapping(method, pattern, handler) {
  return {
    handler,
    predicate: `{${method} [${pattern}]}`,
    details: {
      requestMappingConditions: {
        patterns: [pattern],
        methods: [method],
        produces: [],
        consumes: []
      }
    }
  }
}

function span(trace, spanId, parentSpanId, name, kind, serviceName, offsetNanos, durationNanos) {
  return {
    traceId: trace,
    spanId,
    parentSpanId,
    name,
    kind,
    serviceName,
    scope: 'io.micrometer.tracing',
    startEpochNanos: nowNanos + offsetNanos,
    endEpochNanos: nowNanos + offsetNanos + durationNanos,
    durationNanos,
    statusCode: 'OK',
    statusMessage: null,
    attributes: [],
    events: []
  }
}

function aiChat(spanId, provider, model, inputTokens, outputTokens, durationNanos, finishReason) {
  return {
    traceId,
    spanId,
    startEpochNanos: nowNanos - Number.parseInt(spanId.slice(0, 2), 16) * 1_000_000_000,
    durationNanos,
    provider,
    requestModel: model,
    responseModel: model,
    inputTokens,
    outputTokens,
    totalTokens: inputTokens + outputTokens,
    finishReason,
    statusCode: 'OK',
    operation: 'chat',
    toolCallCount: spanId === aiSpanId ? 2 : 0,
    vectorOperationCount: spanId === aiSpanId ? 1 : 0
  }
}

function cacheEntry(managerName, name, size, hits, misses, hitRatio) {
  return {
    managerName,
    name,
    nativeType: 'org.springframework.data.redis.cache.RedisCache',
    size,
    metrics: {
      available: true,
      hits,
      misses,
      hitRatio,
      puts: size + 18,
      evictions: 3,
      removals: 1
    }
  }
}

function dependency(packageName, version, highestSeverity, vulnerabilities) {
  return {
    packageName,
    version,
    highestSeverity,
    vulnerabilityCount: vulnerabilities.length,
    vulnerabilities
  }
}

function vulnerability(id, severity, summary, aliases, fixedVersions) {
  return {
    id,
    severity,
    summary,
    details: summary,
    aliases,
    fixedVersions,
    references: ['https://osv.dev/vulnerability/' + id]
  }
}

function architectureResult(
  id,
  name,
  category,
  severity,
  description,
  status,
  violationCount,
  sampleViolations,
  recommendation
) {
  return {
    id,
    name,
    category,
    severity,
    description,
    status,
    violationCount,
    sampleViolations,
    recommendation
  }
}

function hibernateAdvisorResult(
  id,
  name,
  category,
  severity,
  description,
  violationCount,
  sampleViolations,
  recommendation
) {
  return {
    id,
    name,
    category,
    severity,
    description,
    status: 'VIOLATION',
    violationCount,
    sampleViolations,
    recommendation,
    learnMoreUrl: 'https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html'
  }
}

function securityAdvisorResult(
  id,
  name,
  category,
  severity,
  description,
  violationCount,
  sampleViolations,
  recommendation
) {
  return {
    id,
    name,
    category,
    severity,
    description,
    status: 'VIOLATION',
    violationCount,
    sampleViolations,
    recommendation,
    learnMoreUrl: 'https://docs.spring.io/spring-security/reference/index.html'
  }
}

function graalVmFinding(id, name, category, severity, description, occurrenceCount, sampleOccurrences, recommendation) {
  return {
    id,
    name,
    category,
    severity,
    description,
    status: 'REVIEW',
    occurrenceCount,
    sampleOccurrences,
    recommendation
  }
}

function githubQuota(key, label, category, limit, used, remaining, percentUsed, status) {
  return {
    key,
    label,
    category,
    scope: category === 'Repository quota' ? 'repository' : 'credential',
    limit,
    used,
    remaining,
    resetAt: nowMillis + 45 * 60 * 1000,
    percentUsed,
    status,
    unavailableReason: null
  }
}

function githubWorkflowRun(id, workflowId, name, displayTitle, runNumber, event, status, conclusion, durationMillis) {
  return {
    id,
    workflowId,
    name,
    displayTitle,
    runNumber,
    event,
    status,
    conclusion,
    branch: 'main',
    actor: 'github-actions[bot]',
    htmlUrl: `https://github.com/jdubois/boot-ui/actions/runs/${id}`,
    createdAt: nowMillis - durationMillis - runNumber * 10_000,
    updatedAt: nowMillis - runNumber * 10_000,
    durationMillis
  }
}

function githubWorkflow(id, name, workflowPath) {
  return {
    id,
    name,
    path: workflowPath,
    state: 'active',
    htmlUrl: `https://github.com/jdubois/boot-ui/actions/workflows/${workflowPath.split('/').at(-1)}`,
    latestRun: null
  }
}

function httpSessionAttribute(name, type) {
  return {
    name,
    type,
    value: '******',
    masked: true,
    truncated: false
  }
}

function flywayMigration(version, description, type, state, script, executionTime) {
  return {
    version,
    description,
    type,
    script,
    state,
    installedBy: executionTime == null ? null : 'bootui',
    installedOn: executionTime == null ? null : new Date(nowMillis - Number(version) * 60_000).toISOString(),
    installedRank: executionTime == null ? null : Number(version),
    executionTime,
    checksum: executionTime == null ? null : 1000 + Number(version)
  }
}

function liquibaseChangeSet(id, author, changeLog, description, execType, orderExecuted) {
  return {
    id,
    author,
    changeLog,
    description,
    comments: null,
    execType,
    dateExecuted: orderExecuted == null ? null : new Date(nowMillis - orderExecuted * 90_000).toISOString(),
    orderExecuted,
    checksum: orderExecuted == null ? null : `9:${orderExecuted}abcdef`,
    tag: id === '002-seed-inventory' ? 'demo-baseline' : null,
    deploymentId: orderExecuted == null ? null : `deploy-${orderExecuted}`,
    contexts: [],
    labels: []
  }
}

function thread(id, name, state, daemon, cpuTimeMillis, stackTrace, virtual = false) {
  return {
    id,
    name,
    state,
    priority: 5,
    daemon,
    virtual,
    cpuTimeMillis,
    userTimeMillis: cpuTimeMillis == null ? null : Math.round(cpuTimeMillis * 0.8),
    deadlocked: false,
    lockName: null,
    lockOwnerId: null,
    lockOwnerName: null,
    stackTrace
  }
}

function securityEvent(principal, type, timestampMillis, data) {
  return {
    principal,
    type,
    timestamp: new Date(timestampMillis).toISOString(),
    data
  }
}

function httpExchange(id, method, path, query, status, durationMs, responseSizeBytes, exchangeTraceId, requestHeaders) {
  return {
    id,
    timestamp: new Date(nowMillis - durationMs * 100).toISOString(),
    method,
    uri: query ? `http://localhost:8080${path}?${query}` : `http://localhost:8080${path}`,
    path,
    query,
    status,
    statusFamily: `${Math.floor(status / 100)}xx`,
    durationMs,
    responseSizeBytes,
    traceId: exchangeTraceId,
    remoteAddress: '127.0.0.1',
    principal: method === 'POST' ? 'alice' : null,
    sessionId: null,
    requestHeaders,
    responseHeaders: [
      {name: 'content-type', values: ['application/json'], masked: false},
      {name: 'x-content-type-options', values: ['nosniff'], masked: false}
    ]
  }
}

function sampleLogLines() {
  return [
    {
      timestamp: nowMillis - 12_000,
      level: 'INFO',
      logger: 'io.github.jdubois.bootui.sample.BootUiSampleApplication',
      message: 'Started BootUI sample application in 1.28 seconds'
    },
    {
      timestamp: nowMillis - 8_000,
      level: 'DEBUG',
      logger: 'io.github.jdubois.bootui.autoconfigure.web.SpringCacheController',
      message: 'Discovered 3 caches across 1 cache manager'
    },
    {
      timestamp: nowMillis - 4_000,
      level: 'INFO',
      logger: 'io.github.jdubois.bootui.sample.EchoScheduler',
      message: 'echo'
    },
    {
      timestamp: nowMillis - 2_000,
      level: 'WARN',
      logger: 'io.github.jdubois.bootui.sample.ChatController',
      message: 'Ollama latency above local threshold: 820 ms'
    }
  ]
}

function probeResponse(request) {
  return {
    status: 200,
    statusText: 'OK',
    headers: {
      'content-type': 'application/json',
      'x-bootui-probe': 'local'
    },
    body: JSON.stringify(
      {
        path: request.path || '/api/sample/products',
        products: [
          {id: 1, name: 'BootUI Starter', category: 'library'},
          {id: 2, name: 'Sample Console', category: 'demo'}
        ]
      },
      null,
      2
    ),
    durationMs: 18,
    error: null
  }
}

function postDataJson(request) {
  try {
    return request.postDataJSON() || {}
  } catch {
    return {}
  }
}

function fulfillJson(route, body, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body)
  })
}
