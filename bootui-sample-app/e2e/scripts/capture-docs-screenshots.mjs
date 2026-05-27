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
  ['startup', 'Startup Timeline'],
  ['memory', 'Memory'],
  ['health', 'Health'],
  ['metrics', 'Metrics'],
  ['conditions', 'Conditions'],
  ['beans', 'Beans'],
  ['mappings', 'Mappings'],
  ['config', 'Configuration'],
  ['profiles', 'Profile Diff'],
  ['loggers', 'Loggers'],
  ['log-tail', 'Log Tail'],
  ['traces', 'Traces'],
  ['http-probe', 'HTTP Probe'],
  ['devtools', 'DevTools'],
  ['dev-services', 'Dev Services'],
  ['scheduled', 'Scheduled Tasks'],
  ['data', 'Data'],
  ['cache', 'Cache'],
  ['ai', 'AI Usage'],
  ['security', 'Security'],
  ['vulnerabilities', 'Vulnerabilities']
]

const overview = {
  bootUiVersion: '0.1.0-alpha.4',
  applicationName: 'bootui-sample',
  springBootVersion: '4.0.6',
  javaVersion: '25',
  javaVendor: 'Eclipse Adoptium',
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
            mapping('GET', '/bootui/api/cache', 'CacheController#report()')
          ]
        }
      }
    }
  }
}

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

const screenshots = [
  ['overview', 'Overview', 'bootui-overview.png', waitForText('Understand your Spring Boot app')],
  ['startup', 'Startup Timeline', 'bootui-startup-timeline.png', waitForText('spring.context.refresh')],
  ['memory', 'Memory', 'bootui-memory.png', waitForText('JVM memory calculator')],
  ['health', 'Health', 'bootui-health.png', waitForText('Component tree')],
  [
    'metrics',
    'Metrics',
    'bootui-metrics.png',
    async (page) => {
      await page.getByText('Micrometer metrics').waitFor()
      await page.waitForTimeout(2300)
    }
  ],
  ['conditions', 'Conditions', 'bootui-conditions.png', waitForText('BootUiAutoConfiguration')],
  ['beans', 'Beans', 'bootui-beans.png', waitForText('sampleController')],
  ['mappings', 'Mappings', 'bootui-mappings.png', waitForText('/api/sample/products')],
  ['config', 'Configuration', 'bootui-configuration.png', waitForText('sample.greeting')],
  ['profiles', 'Profile Diff', 'bootui-profile-diff.png', waitForText('classpath:/application-dev.properties')],
  ['loggers', 'Loggers', 'bootui-loggers.png', waitForText('io.github.jdubois.bootui')],
  ['log-tail', 'Log Tail', 'bootui-log-tail.png', waitForText('Started BootUI sample application')],
  ['traces', 'Traces', 'bootui-traces.png', waitForText('POST /api/chat')],
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
  ['devtools', 'DevTools', 'bootui-devtools.png', waitForText('Trigger LiveReload')],
  ['dev-services', 'Dev Services', 'bootui-dev-services.png', waitForText('postgres')],
  ['scheduled', 'Scheduled Tasks', 'bootui-scheduled-tasks.png', waitForText('EchoScheduler.echo')],
  [
    'data',
    'Data',
    'bootui-data.png',
    async (page) => {
      await page.getByText('ProductRepository').waitFor()
      await page.getByRole('button', {name: /ProductRepository/}).click()
      await page.getByText('findByActiveTrueOrderByNameAsc').waitFor()
    }
  ],
  ['cache', 'Cache', 'bootui-cache.png', waitForText('sample-products')],
  ['ai', 'AI Usage', 'bootui-ai.png', waitForText('Token usage')],
  ['security', 'Security', 'bootui-security.png', waitForText('/api/sample/hello')],
  ['vulnerabilities', 'Vulnerabilities', 'bootui-vulnerabilities.png', waitForText('GHSA-example-001')]
]

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

  for (const [route, title, fileName, prepare] of screenshots) {
    await page.goto(`${baseUrl}/bootui/#/${route}`)
    await page.locator('.page-heading h2').filter({hasText: title}).first().waitFor()
    await prepare(page)
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
  if (endpoint.startsWith('memory')) return fulfillJson(route, memory)
  if (endpoint === 'health') return fulfillJson(route, health)
  if (endpoint === 'metrics') return fulfillJson(route, metrics)
  if (endpoint === 'metrics/detail')
    return fulfillJson(route, metricDetail(url.searchParams.get('name') || 'jvm.memory.used'))
  if (endpoint === 'conditions') return fulfillJson(route, conditions)
  if (endpoint === 'beans') return fulfillJson(route, beans)
  if (endpoint === 'mappings') return fulfillJson(route, mappings)
  if (endpoint === 'config') return fulfillJson(route, configuration)
  if (endpoint === 'profiles') return fulfillJson(route, profileDiff)
  if (endpoint === 'loggers') return fulfillJson(route, loggers)
  if (endpoint === 'traces') return fulfillJson(route, traceReport)
  if (endpoint === `traces/${traceId}`) return fulfillJson(route, traceDetail)
  if (endpoint === 'ai/overview') return fulfillJson(route, aiOverview)
  if (endpoint === 'ai/tokens') return fulfillJson(route, aiTokens)
  if (endpoint === `ai/chats/${aiSpanId}`) return fulfillJson(route, aiDetail)
  if (endpoint === 'probe') return fulfillJson(route, probeResponse(postDataJson(request)))
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
  if (endpoint === 'cache') return fulfillJson(route, cache)
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

  return fulfillJson(route, {error: `No screenshot fixture for ${endpoint}`}, 404)
}

function waitForText(text) {
  return (page) => page.getByText(text).first().waitFor()
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
      logger: 'io.github.jdubois.bootui.autoconfigure.web.CacheController',
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
