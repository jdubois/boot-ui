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
  ['health', 'Health'],
  ['metrics', 'Metrics'],
  ['memory', 'Memory'],
  ['tuning-advisor', 'Tuning Advisor'],
  ['heap-dump', 'Heap Dump'],
  ['startup', 'Startup Timeline'],
  ['config', 'Configuration'],
  ['profiles', 'Profile Diff'],
  ['loggers', 'Loggers'],
  ['beans', 'Beans'],
  ['conditions', 'Conditions'],
  ['mappings', 'Mappings'],
  ['scheduled', 'Scheduled Tasks'],
  ['database-connection-pools', 'Database Connection Pools'],
  ['data', 'Spring Data'],
  ['spring-cache', 'Spring Cache'],
  ['security', 'Security'],
  ['ai', 'AI Usage'],
  ['traces', 'Traces'],
  ['log-tail', 'Log Tail'],
  ['http-probe', 'HTTP Probe'],
  ['architecture', 'Architecture'],
  ['pentest', 'Pentesting'],
  ['vulnerabilities', 'Vulnerabilities'],
  ['devtools', 'DevTools'],
  ['dev-services', 'Dev Services'],
  ['copilot', 'Copilot'],
  ['claude-code', 'Claude Code']
]

const overview = {
  bootUiVersion: '0.3.0',
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

const copilotSessionId = 'session-bootui-2026-001'
const copilotSession2Id = 'session-bootui-2026-002'

const copilotDashboard = {
  available: true,
  unavailableReason: null,
  sessionStateDir: '~/.copilot/session-state',
  sessionCount: 24,
  eventCount: 1847,
  turnCount: 312,
  errorCount: 5,
  activeLast24Hours: 3,
  activeLast7Days: 11,
  sessionsWithSchemaDrift: 0,
  lastActivityEpochMillis: nowMillis - 4 * 60 * 1000,
  categoryCounts: [
    {label: 'FILE_EDIT', count: 412},
    {label: 'FILE_READ', count: 389},
    {label: 'SEARCH', count: 276},
    {label: 'SHELL', count: 198},
    {label: 'WEB', count: 87},
    {label: 'MCP', count: 64},
    {label: 'HOOK', count: 42},
    {label: 'ASK', count: 31},
    {label: 'OTHER', count: 348}
  ],
  modelCounts: [
    {label: 'claude-sonnet-4.6', count: 18},
    {label: 'claude-opus-4.7', count: 6}
  ],
  topTools: [
    {label: 'edit', count: 412},
    {label: 'view', count: 389},
    {label: 'grep', count: 218},
    {label: 'bash', count: 198},
    {label: 'glob', count: 58}
  ],
  otherToolEventCount: 572,
  activityBuckets: Array.from({length: 24}, (_, i) => {
    const hour = i
    const base = [0, 0, 0, 0, 0, 0, 0, 2, 18, 42, 67, 88, 54, 0, 0, 0, 0, 31, 76, 112, 98, 63, 34, 12][i]
    return {
      startEpochMillis: nowMillis - (23 - i) * 3600 * 1000,
      endEpochMillis: nowMillis - (22 - i) * 3600 * 1000,
      eventCount: base + Math.round(hour * 0.4),
      errorCount: base > 0 ? Math.min(2, Math.floor(base * 0.02)) : 0
    }
  }),
  dailyActivityBuckets: Array.from({length: 7}, (_, i) => ({
    startEpochMillis: nowMillis - (6 - i) * 86400 * 1000,
    endEpochMillis: nowMillis - (5 - i) * 86400 * 1000,
    eventCount: [0, 42, 218, 312, 189, 401, 685][i],
    errorCount: [0, 0, 1, 2, 0, 1, 1][i]
  })),
  recentSessions: [
    {
      id: copilotSessionId,
      filename: 'laughing-succotash',
      startedAtEpochMillis: nowMillis - 45 * 60 * 1000,
      updatedAtEpochMillis: nowMillis - 4 * 60 * 1000,
      model: 'claude-sonnet-4.6',
      workingDirectory: '/workspace/BootUI/jdubois-laughing-succotash',
      status: 'active',
      eventCount: 284,
      turnCount: 47,
      errorCount: 0,
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
      eventCount: 198,
      turnCount: 31,
      errorCount: 2,
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
  total: 24,
  returned: 24,
  maxSessions: 100,
  sessions: copilotDashboard.recentSessions,
  warnings: []
}

const copilotSessionDetail = {
  summary: copilotDashboard.recentSessions[0],
  counts: {
    total: 284,
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
    errors: 0,
    lastActivityEpochMillis: nowMillis - 4 * 60 * 1000
  },
  turns: [
    {
      index: 0,
      startedAtEpochMillis: nowMillis - 45 * 60 * 1000,
      durationMillis: 12400,
      summary: 'Exploring repository structure and docs',
      eventCount: 14
    },
    {
      index: 1,
      startedAtEpochMillis: nowMillis - 40 * 60 * 1000,
      durationMillis: 28700,
      summary: 'Reading PLAN.md, FEATURES.md, README, and copilot instructions',
      eventCount: 22
    },
    {
      index: 2,
      startedAtEpochMillis: nowMillis - 30 * 60 * 1000,
      durationMillis: 54200,
      summary: 'Updating screenshot script with Copilot fixture data',
      eventCount: 38
    },
    {
      index: 3,
      startedAtEpochMillis: nowMillis - 10 * 60 * 1000,
      durationMillis: 18900,
      summary: 'Patching FEATURES.md, PLAN.md, and copilot instructions',
      eventCount: 21
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
  ['overview', 'Overview', 'bootui-overview.png', waitForText('Understand your Spring Boot app')],
  ['health', 'Health', 'bootui-health.png', waitForText('Component tree')],
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
  ['startup', 'Startup Timeline', 'bootui-startup-timeline.png', waitForText('spring.context.refresh')],
  ['config', 'Configuration', 'bootui-configuration.png', waitForText('sample.greeting')],
  ['profiles', 'Profile Diff', 'bootui-profile-diff.png', waitForText('classpath:/application-dev.properties')],
  ['loggers', 'Loggers', 'bootui-loggers.png', waitForText('io.github.jdubois.bootui')],
  ['beans', 'Beans', 'bootui-beans.png', waitForText('sampleController')],
  ['conditions', 'Conditions', 'bootui-conditions.png', waitForText('BootUiAutoConfiguration')],
  ['mappings', 'Mappings', 'bootui-mappings.png', waitForText('/api/sample/products')],
  ['scheduled', 'Scheduled Tasks', 'bootui-scheduled-tasks.png', waitForText('EchoScheduler.echo')],
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
  ['spring-cache', 'Spring Cache', 'bootui-spring-cache.png', waitForText('sample-products')],
  ['security', 'Security', 'bootui-security.png', waitForText('/api/sample/hello')],
  ['ai', 'AI Usage', 'bootui-ai.png', waitForText('Token usage')],
  ['traces', 'Traces', 'bootui-traces.png', waitForText('POST /api/chat')],
  ['log-tail', 'Log Tail', 'bootui-log-tail.png', waitForText('Started BootUI sample application')],
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
  ['pentest', 'Pentesting', 'bootui-pentesting.png', waitForText('Missing hardening response headers')],
  ['vulnerabilities', 'Vulnerabilities', 'bootui-vulnerabilities.png', waitForText('GHSA-example-001')],
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
  sessionCount: 18,
  eventCount: 1264,
  turnCount: 247,
  errorCount: 3,
  activeLast24Hours: 2,
  activeLast7Days: 9,
  sessionsWithSchemaDrift: 0,
  lastActivityEpochMillis: nowMillis - 6 * 60 * 1000,
  categoryCounts: [
    {label: 'FILE_EDIT', count: 318},
    {label: 'FILE_READ', count: 281},
    {label: 'SEARCH', count: 196},
    {label: 'SHELL', count: 152},
    {label: 'WEB', count: 64},
    {label: 'MCP', count: 38},
    {label: 'ASK', count: 27},
    {label: 'SKILL', count: 14},
    {label: 'OTHER', count: 174}
  ],
  modelCounts: [
    {label: 'claude-sonnet-4-20250514', count: 14},
    {label: 'claude-opus-4-1-20250915', count: 4}
  ],
  topTools: [
    {label: 'Edit', count: 248},
    {label: 'Read', count: 281},
    {label: 'Bash', count: 152},
    {label: 'Grep', count: 124},
    {label: 'Glob', count: 72}
  ],
  otherToolEventCount: 387,
  activityBuckets: Array.from({length: 24}, (_, i) => {
    const hour = i
    const base = [0, 0, 0, 0, 0, 0, 0, 0, 12, 36, 58, 74, 41, 0, 0, 0, 0, 22, 64, 96, 82, 51, 27, 8][i]
    return {
      startEpochMillis: nowMillis - (23 - i) * 3600 * 1000,
      endEpochMillis: nowMillis - (22 - i) * 3600 * 1000,
      eventCount: base + Math.round(hour * 0.3),
      errorCount: base > 0 ? Math.min(1, Math.floor(base * 0.015)) : 0
    }
  }),
  dailyActivityBuckets: Array.from({length: 7}, (_, i) => ({
    startEpochMillis: nowMillis - (6 - i) * 86400 * 1000,
    endEpochMillis: nowMillis - (5 - i) * 86400 * 1000,
    eventCount: [0, 28, 167, 241, 142, 312, 482][i],
    errorCount: [0, 0, 0, 1, 0, 1, 1][i]
  })),
  recentSessions: [
    {
      id: claudeCodeSessionId,
      filename: `${claudeCodeProjectSlug}/${claudeCodeSessionId}.jsonl`,
      startedAtEpochMillis: nowMillis - 38 * 60 * 1000,
      updatedAtEpochMillis: nowMillis - 6 * 60 * 1000,
      model: 'claude-sonnet-4-20250514',
      workingDirectory: '/workspace/BootUI/jdubois-laughing-succotash',
      status: 'active',
      eventCount: 214,
      turnCount: 36,
      errorCount: 0,
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
      eventCount: 154,
      turnCount: 23,
      errorCount: 1,
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
  total: 18,
  returned: 18,
  maxSessions: 100,
  sessions: claudeCodeDashboard.recentSessions,
  warnings: []
}

const claudeCodeSessionDetail = {
  summary: claudeCodeDashboard.recentSessions[0],
  counts: {
    total: 214,
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
    errors: 0,
    lastActivityEpochMillis: nowMillis - 6 * 60 * 1000
  },
  turns: [
    {
      index: 0,
      startedAtEpochMillis: nowMillis - 38 * 60 * 1000,
      durationMillis: 9800,
      summary: 'Exploring project structure with Glob',
      eventCount: 11
    },
    {
      index: 1,
      startedAtEpochMillis: nowMillis - 32 * 60 * 1000,
      durationMillis: 21400,
      summary: 'Reading source files: CopilotSessionStore, BootUiProperties',
      eventCount: 19
    },
    {
      index: 2,
      startedAtEpochMillis: nowMillis - 22 * 60 * 1000,
      durationMillis: 48700,
      summary: 'Refactor to AgentSessionStore with Edit and MultiEdit',
      eventCount: 27
    },
    {
      index: 3,
      startedAtEpochMillis: nowMillis - 8 * 60 * 1000,
      durationMillis: 14600,
      summary: 'Running ./mvnw test to verify the refactor',
      eventCount: 16
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
  if (endpoint === 'panels')
    return fulfillJson(route, {
      panels: panelOrder.map(([id, title]) => ({id, title, available: true, unavailableReason: null}))
    })
  if (endpoint === 'startup') return fulfillJson(route, startup)
  if (endpoint.startsWith('memory') || endpoint.startsWith('tuning-advisor')) return fulfillJson(route, memory)
  if (endpoint === 'health') return fulfillJson(route, health)
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
  if (endpoint === 'database-connection-pools/pools') return fulfillJson(route, databaseConnectionPoolsReport)
  if (endpoint.startsWith('database-connection-pools/pools/') && endpoint.endsWith('/snapshot')) {
    const poolName = endpoint.slice('database-connection-pools/pools/'.length, -'/snapshot'.length)
    return fulfillJson(route, databaseConnectionPoolSnapshot(poolName))
  }
  if (endpoint.startsWith('heap-dump')) return fulfillJson(route, heapDump)
  if (endpoint === 'spring-cache') return fulfillJson(route, cache)
  if (endpoint === 'security') return fulfillJson(route, security)
  if (endpoint === 'security/endpoints') return fulfillJson(route, securityEndpoints)
  if (endpoint === 'security/explain')
    return fulfillJson(route, {
      matched: true,
      bestEffort: false,
      chainIndex: 1,
      matcherDescription: 'any request',
      filters: security.chains[1].filters
    })
  if (endpoint === 'dependencies') return fulfillJson(route, dependencies)
  if (endpoint === 'dependencies/scan') return fulfillJson(route, dependencies)
  if (endpoint === 'pentest') return fulfillJson(route, pentest)
  if (endpoint === 'pentest/scan') return fulfillJson(route, pentest)
  if (endpoint === 'architecture') return fulfillJson(route, architecture)
  if (endpoint === 'architecture/scan') return fulfillJson(route, architecture)

  return fulfillJson(route, {error: `No screenshot fixture for ${endpoint}`}, 404)
}

function waitForText(text) {
  return (page) => page.getByText(text).first().waitFor()
}

function pagedReport(itemsKey, items, url) {
  const offset = Number(url.searchParams.get('offset') || 0)
  const limit = Number(url.searchParams.get('limit') || items.length)
  const returnedItems = items.slice(offset, offset + limit)
  return {
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
