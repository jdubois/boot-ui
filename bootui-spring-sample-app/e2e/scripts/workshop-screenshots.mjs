/**
 * Deterministic synthetic fixture scenarios for BootUI workshop reference screenshots
 * (`docs/images/workshop-*.webp`).
 *
 * IMPORTANT: These screenshots are rendered by the real BootUI Vue frontend against
 * deterministic synthetic HTTP API fixtures for the `EagerDemoOrderController` / `EagerDemoOrderService` /
 * `EagerDemoOrderRepository` workshop story, using the selected release's version metadata.
 *
 * They are illustrative reference screenshots for the workshop narrative, NOT captured
 * live participant execution proof.
 *
 * Scenario states modeled:
 * - `pre-agent`: Chapters 00–04. The released BootUI running on `bootui-spring-sample-app` without
 *   `-javaagent`. `GET /api/insights/eager-orders` returns 200 OK for 16 orders while issuing
 *   17 SQL statements (`1` order query + `16` secondary `insight_eager_customers` SELECTs,
 *   flagged `Potential N+1`), compared with `GET /api/insights/eager-orders/joined` (1 SQL
 *   statement for the same 16 records, without spoiling the `join fetch` query text before
 *   Chapter 06). Over 6 requests, Runtime Insights promotes `Repeated SELECTs`.
 * - `agent-baseline`: Chapters 05–06 & Appendices A/C. Same JVM with the matching released Java agent
 *   attached (`ARMED`, Run 1) and 6 requests recorded on `GET /api/insights/eager-orders`
 *   (1 first + 5 warm requests) plus 1 request on `GET /api/insights/eager-orders/joined`.
 *   Code Paths attributes the 17 SQL statements to `EagerDemoOrderService.listWithSecondarySelects`,
 *   and Change Impact shows bounded blast radius (`1` route ran it in this run, `0` unexercised
 *   routes reach it, `1` route shares `insight_eager_orders` and `insight_eager_customers`).
 * - `agent-after-fix`: Chapters 07–08 & Appendix B. Same JVM after one DevTools live reload
 *   (Run 2 compared against Run 1) and 6 verification requests to `GET /api/insights/eager-orders`.
 *   Code Inventory confirms `1 changed, 0 added, 0 removed` and `Executed` on
 *   `GET /api/insights/eager-orders`; Request Profile confirms `1` joined SQL statement and no
 *   `Potential N+1`; Run Comparison shows `PARTIAL` (`Partly compared`) with `codeChanges`
 *   confirming `EagerDemoOrderService#listWithSecondarySelects` ran on `GET /api/insights/eager-orders`
 *   while explaining the `69` startup SQL events missed before aggregate registration.
 * - Appendix D uses `Runtime Insights -> JFR profile` (`runtime-insights?tab=profile`) in its
 *   `IDLE` ready-to-record state (`Profile resources`, no fabricated recording).
 */

const APP_NAME = 'bootui-spring-sample-app'

const ORDER_SELECT_SQL =
  'select e1_0.id,e1_0.customer_id,e1_0.description from insight_eager_orders e1_0 order by e1_0.id'
const CUSTOMER_SELECT_SQL = 'select e1_0.id,e1_0.name from insight_eager_customers e1_0 where e1_0.id=?'
const JOINED_ORDER_SQL =
  'select e1_0.id,c1_0.id,c1_0.name,e1_0.description from insight_eager_orders e1_0 join insight_eager_customers c1_0 on c1_0.id=e1_0.customer_id order by e1_0.id'

const CONTROLLER_CLASS = 'io.github.jdubois.bootui.sample.insights.EagerDemoOrderController'
const SERVICE_CLASS = 'io.github.jdubois.bootui.sample.insights.EagerDemoOrderService'
const REPO_CLASS = 'io.github.jdubois.bootui.sample.insights.EagerDemoOrderRepository'

const CONTROLLER_METHOD_KEY = `${CONTROLLER_CLASS}#listWithSecondarySelects()Ljava/util/List;`
const SERVICE_METHOD_KEY = `${SERVICE_CLASS}#listWithSecondarySelects()Ljava/util/List;`
const JOINED_CONTROLLER_METHOD_KEY = `${CONTROLLER_CLASS}#listWithCustomerJoin()Ljava/util/List;`
const JOINED_SERVICE_METHOD_KEY = `${SERVICE_CLASS}#listWithCustomerJoin()Ljava/util/List;`

const SERVICE_CALL_SITE = `${SERVICE_CLASS}.listWithSecondarySelects(EagerDemoOrderService.java:18)`

export function selectScreenshotServer({workshop, frontendOverride, baseUrlOverride, serverReady}) {
  if (workshop && !frontendOverride) {
    throw new Error('Workshop captures require BOOTUI_SCREENSHOT_FRONTEND_DIR pointing to the released frontend.')
  }
  if ((workshop || frontendOverride) && (baseUrlOverride || serverReady)) {
    throw new Error(
      'Workshop or explicit-frontend captures must start their own frontend. Unset BOOTUI_SCREENSHOT_BASE_URL and choose an unused BOOTUI_SCREENSHOT_PORT.'
    )
  }
  return baseUrlOverride ? 'external' : serverReady ? 'reuse' : 'start'
}

export function createWorkshopFixtures({
  nowMillis,
  bootUiVersion,
  springBootVersion,
  panelManifest,
  cliManifest,
  pagedReport
}) {
  const WORKSHOP_VERSION = bootUiVersion
  const agentRequirements = {
    'code-paths': "Requires the BootUI agent's code-paths sensor",
    'code-inventory': "Requires the BootUI agent's inventory sensor",
    'side-effects': 'Requires the BootUI agent'
  }

  function buildPanels(agentAttached) {
    return panelManifest.panels.map((panel) => {
      const agentRequired = !agentAttached && agentRequirements[panel.id]
      return {
        ...panel,
        enabled: true,
        available: !agentRequired,
        readOnly: false,
        readOnlyReason: null,
        unavailableReason: agentRequired ? `${agentRequired}: this JVM runs without the BootUI agent.` : null
      }
    })
  }

  const workshopOverview = {
    bootUiVersion: WORKSHOP_VERSION,
    applicationName: APP_NAME,
    frameworkName: 'Spring Boot',
    frameworkVersion: springBootVersion,
    javaVersion: '21',
    javaVendor: 'Eclipse Temurin',
    activeProfiles: ['dev', 'local'],
    defaultProfiles: ['default'],
    webApplicationType: 'SERVLET',
    serverPort: 8080,
    managementPort: null,
    contextPath: '',
    startupTimeMillis: 1180,
    activation: {
      enabled: true,
      localhostOnly: true,
      reason: 'Active dev profile matched bootui.enabled-profiles',
      warnings: []
    },
    openApiUrl: '/swagger-ui/index.html'
  }

  const workshopFlatMappings = [
    {
      method: 'GET',
      pattern: '/api/insights/eager-orders',
      handler: `${CONTROLLER_CLASS}#listWithSecondarySelects()`,
      produces: 'application/json',
      consumes: null
    },
    {
      method: 'GET',
      pattern: '/api/insights/eager-orders/joined',
      handler: `${CONTROLLER_CLASS}#listWithCustomerJoin()`,
      produces: 'application/json',
      consumes: null
    },
    {
      method: 'POST',
      pattern: '/api/insights/orders/{id}/import',
      handler: 'io.github.jdubois.bootui.sample.insights.InsightSeedController#importOrder(long)',
      produces: 'application/json',
      consumes: null
    }
  ]

  const workshopMappings = {
    contexts: {
      [APP_NAME]: {
        mappings: {
          dispatcherServlets: {
            dispatcherServlet: workshopFlatMappings.map((item) => ({
              handler: item.handler,
              predicate: `{${item.method} [${item.pattern}]}`,
              details: {
                requestMappingConditions: {
                  patterns: [item.pattern],
                  methods: [item.method],
                  produces: item.produces ? [item.produces] : [],
                  consumes: item.consumes ? [item.consumes] : []
                }
              }
            }))
          }
        }
      }
    }
  }

  const jarPath = `/Users/dev/.m2/repository/com/julien-dubois/bootui/bootui-agent/${WORKSHOP_VERSION}/bootui-agent-${WORKSHOP_VERSION}.jar`

  const javaAgentSetup = {
    jarPath,
    jarFound: true,
    buildTool: 'MAVEN',
    snippets: [
      {
        id: 'maven-download',
        label: 'Maven',
        language: 'shell',
        text: `./mvnw dependency:get -Dartifact=com.julien-dubois.bootui:bootui-agent:${WORKSHOP_VERSION}`
      },
      {
        id: 'curl-download',
        label: 'curl',
        language: 'shell',
        text: `curl -fLo bootui-agent-${WORKSHOP_VERSION}.jar https://repo1.maven.org/maven2/com/julien-dubois/bootui/bootui-agent/${WORKSHOP_VERSION}/bootui-agent-${WORKSHOP_VERSION}.jar`
      },
      {
        id: 'maven-spring-boot',
        label: 'Maven (Spring Boot)',
        language: 'shell',
        text: `./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-javaagent:${jarPath}"`
      },
      {
        id: 'maven-quarkus',
        label: 'Maven (Quarkus)',
        language: 'shell',
        text: `./mvnw quarkus:dev -Djvm.args="-javaagent:${jarPath}"`
      },
      {
        id: 'java-tool-options',
        label: 'JAVA_TOOL_OPTIONS',
        language: 'shell',
        text: `export JAVA_TOOL_OPTIONS="-javaagent:${jarPath}"`
      }
    ]
  }

  const javaAgentNotAttached = {
    state: 'NOT_ATTACHED',
    reason:
      'This JVM runs without the BootUI agent. BootUI works without it; attach it with one of the setup snippets below.',
    agentVersion: null,
    bootUiVersion: WORKSHOP_VERSION,
    protocol: null,
    expectedProtocol: 1,
    jdk: '21.0.5 (Eclipse Temurin)',
    loadMode: null,
    jarPath: null,
    startupMicros: null,
    heldBy: null,
    claim: null,
    toggles: [],
    sensors: [],
    retransformation: null,
    counters: null,
    setup: javaAgentSetup
  }

  const javaAgentArmed = {
    state: 'ARMED',
    reason: 'This application holds the agent’s claim.',
    agentVersion: WORKSHOP_VERSION,
    bootUiVersion: WORKSHOP_VERSION,
    protocol: 1,
    expectedProtocol: 1,
    jdk: '21.0.5 (Eclipse Temurin)',
    loadMode: 'PREMAIN (-javaagent)',
    jarPath,
    startupMicros: 14200,
    heldBy: null,
    claim: {
      armed: true,
      abandoned: false,
      generation: 1,
      owner: `${APP_NAME}@5f2a8c10`,
      application: APP_NAME,
      mode: 'dev',
      armedAt: nowMillis - 180_000,
      packages: ['io.github.jdubois.bootui.sample']
    },
    toggles: [
      {
        id: 'caught-exceptions',
        configured: false,
        enabled: false,
        overridden: false,
        state: 'off',
        optInReason: 'Opt-in because high-volume try/catch blocks in tight loops can add overhead.',
        available: true,
        unavailableReason: null,
        failure: null
      },
      {
        id: 'side-effects',
        configured: false,
        enabled: false,
        overridden: false,
        state: 'off',
        optInReason: 'Opt-in because it instruments outbound socket, file, process, and environment reads.',
        available: true,
        unavailableReason: null,
        failure: null
      }
    ],
    sensors: [
      {
        id: 'executors',
        state: 'installed',
        durationMillis: 4.2,
        installMillis: 3.1,
        selfTestMillis: 1.1,
        active: true,
        selfTestPassed: true,
        selfTestError: null,
        instrumentedTypes: 6,
        retransformedTypes: 4,
        retransformMillis: 2.8,
        failures: [],
        executors: {
          pending: 0,
          neverApplied: 0,
          ambiguous: 0,
          stale: 0,
          refused: 0,
          overflow: 0,
          virtualSkipped: 0,
          periodicSkipped: 1,
          skippedTasks: 0,
          skippedThreads: 0,
          failures: 0
        },
        hooks: []
      },
      {
        id: 'threads',
        state: 'installed',
        durationMillis: 1.8,
        installMillis: 1.2,
        selfTestMillis: 0.6,
        active: true,
        selfTestPassed: true,
        selfTestError: null,
        instrumentedTypes: 1,
        retransformedTypes: 1,
        retransformMillis: 0.9,
        failures: [],
        executors: {
          pending: 0,
          neverApplied: 0,
          ambiguous: 0,
          stale: 0,
          refused: 0,
          overflow: 0,
          libraryThreadsSkipped: 2,
          poolWorkersSkipped: 4,
          skippedTasks: 0,
          skippedThreads: 0,
          failures: 0
        },
        hooks: []
      },
      {
        id: 'inventory',
        state: 'installed',
        durationMillis: 6.4,
        installMillis: 5.0,
        selfTestMillis: 1.4,
        active: true,
        selfTestPassed: true,
        selfTestError: null,
        instrumentedTypes: 28,
        retransformedTypes: 28,
        retransformMillis: 5.1,
        failures: [],
        inventory: {
          methodsTracked: 42,
          executedThisRun: 19,
          methodOverflow: 0,
          transformFailures: 0,
          codeSources: 1,
          ringDropped: 0,
          ringLost: 0,
          internOverflow: 0
        },
        hooks: []
      },
      {
        id: 'code-paths',
        state: 'installed',
        durationMillis: 5.1,
        installMillis: 3.9,
        selfTestMillis: 1.2,
        active: true,
        selfTestPassed: true,
        selfTestError: null,
        instrumentedTypes: 14,
        retransformedTypes: 14,
        retransformMillis: 3.6,
        failures: [],
        codePaths: {
          fragmentsFlushed: 7,
          fragmentsDropped: 0,
          queueDropped: 0,
          callsDropped: 0,
          queueBytes: 0,
          excludedMethods: 0,
          errors: 0
        },
        hooks: []
      }
    ],
    retransformation: {
      state: 'IDLE',
      transformed: 54,
      retransformed: 52,
      failed: 0,
      skipped: 0,
      durationMillis: 16.2,
      running: false
    },
    counters: {
      claims: 1,
      takeovers: 0,
      holds: 0,
      staleTokens: 0,
      errors: 0
    },
    setup: javaAgentSetup
  }

  const preAgentActivityEntries = [
    {
      id: 'req-eager-01',
      timestamp: nowMillis - 8_000,
      type: 'REQUEST',
      severity: 'OK',
      summary: 'GET /api/insights/eager-orders → 200',
      detail: '16 orders · 17 SQL statements (13.8 ms)',
      durationMs: 18,
      correlationId: '4b8e9c10d2a14f00b712e9a40c881101',
      method: 'GET',
      path: '/api/insights/eager-orders',
      status: 200,
      thread: 'http-nio-8080-exec-1',
      profileable: true
    },
    {
      id: 'sql-eager-orders-01',
      timestamp: nowMillis - 7_996,
      type: 'SQL',
      severity: 'OK',
      summary: ORDER_SELECT_SQL,
      detail: 'PREPARED · 1.4 ms · conn #1 · EagerDemoOrderService.listWithSecondarySelects',
      durationMs: 1.4,
      correlationId: '4b8e9c10d2a14f00b712e9a40c881101',
      method: 'GET',
      path: '/api/insights/eager-orders',
      status: null,
      thread: 'http-nio-8080-exec-1',
      profileable: false,
      parentId: 'req-eager-01'
    },
    ...Array.from({length: 16}, (_, idx) => ({
      id: `sql-eager-cust-${String(idx + 1).padStart(2, '0')}`,
      timestamp: nowMillis - 7_994 + idx,
      type: 'SQL',
      severity: 'OK',
      summary: CUSTOMER_SELECT_SQL,
      detail: `PREPARED · 0.8 ms · conn #1 · params [${idx + 1}] · EagerDemoOrderService.listWithSecondarySelects`,
      durationMs: 0.8,
      correlationId: '4b8e9c10d2a14f00b712e9a40c881101',
      method: 'GET',
      path: '/api/insights/eager-orders',
      status: null,
      thread: 'http-nio-8080-exec-1',
      profileable: false,
      parentId: 'req-eager-01'
    })),
    {
      id: 'req-joined-01',
      timestamp: nowMillis - 14_000,
      type: 'REQUEST',
      severity: 'OK',
      summary: 'GET /api/insights/eager-orders/joined → 200',
      detail: '16 orders · 1 SQL statement (1.6 ms)',
      durationMs: 4,
      correlationId: '7a11c409e8b24d109911a0b2c3d4e502',
      method: 'GET',
      path: '/api/insights/eager-orders/joined',
      status: 200,
      thread: 'http-nio-8080-exec-2',
      profileable: true
    }
  ]

  const postFixActivityEntries = [
    {
      id: 'req-after-01',
      timestamp: nowMillis - 5_000,
      type: 'REQUEST',
      severity: 'OK',
      summary: 'GET /api/insights/eager-orders → 200',
      detail: '16 orders · 1 SQL statement (1.6 ms)',
      durationMs: 4,
      correlationId: '9c33e812a4f04b228810d1e2f3a4b506',
      method: 'GET',
      path: '/api/insights/eager-orders',
      status: 200,
      thread: 'http-nio-8080-exec-3',
      profileable: true
    },
    {
      id: 'sql-after-01',
      timestamp: nowMillis - 4_998,
      type: 'SQL',
      severity: 'OK',
      summary: JOINED_ORDER_SQL,
      detail: 'PREPARED · 1.6 ms · conn #1 · EagerDemoOrderService.listWithSecondarySelects',
      durationMs: 1.6,
      correlationId: '9c33e812a4f04b228810d1e2f3a4b506',
      method: 'GET',
      path: '/api/insights/eager-orders',
      status: null,
      thread: 'http-nio-8080-exec-3',
      profileable: false,
      parentId: 'req-after-01'
    },
    {
      id: 'req-after-02',
      timestamp: nowMillis - 9_000,
      type: 'REQUEST',
      severity: 'OK',
      summary: 'GET /api/insights/eager-orders → 200',
      detail: '16 orders · 1 SQL statement (1.5 ms)',
      durationMs: 4,
      correlationId: '8b22d70193e04a117700c0d1e2f3a405',
      method: 'GET',
      path: '/api/insights/eager-orders',
      status: 200,
      thread: 'http-nio-8080-exec-2',
      profileable: true
    }
  ]

  function buildActivityReport(afterFix) {
    const entries = afterFix ? postFixActivityEntries : preAgentActivityEntries
    return {
      available: true,
      unavailableReason: null,
      entries,
      kpis: {
        requestsPerMinute: afterFix ? 12.0 : 4.0,
        errorRatePercent: 0.0,
        p50LatencyMs: afterFix ? 4 : 18,
        p95LatencyMs: afterFix ? 5 : 21,
        slowestEndpoint: '/api/insights/eager-orders',
        slowestEndpointMs: afterFix ? 4 : 18,
        latencySampleCount: afterFix ? 6 : 2,
        slowestEndpointRoute: '/api/insights/eager-orders',
        slowestEndpointRouteId: 'GET /api/insights/eager-orders',
        slowestEndpointRouteSource: 'FRAMEWORK_TEMPLATE',
        activeExceptionCount: 0,
        sqlPerMinute: afterFix ? 12.0 : 36.0,
        slowestQueryMs: 2,
        healthStatus: 'UP',
        heapUsedBytes: 184_549_376,
        heapMaxBytes: 1_073_741_824,
        cacheHitRatioPercent: null,
        scheduledTaskFailureCount: 0,
        restCallErrorRatePercent: null,
        restCallP95LatencyMs: null
      },
      typeCounts: {
        REQUEST: afterFix ? 6 : 2,
        SQL: afterFix ? 6 : 18
      },
      sources: ['Runtime journal'],
      warnings: [],
      pageInfo: {
        persistent: false,
        limit: 200,
        returned: entries.length,
        hasMore: false,
        nextCursor: null
      },
      persistenceOption: {
        persistent: false,
        mode: 'MEMORY',
        dataSourceAvailable: true,
        switchAvailable: true,
        unavailableReason: null,
        tableName: 'bootui_activity_event',
        retentionMaxRows: 10000,
        retentionMaxAge: 'PT24H'
      }
    }
  }

  const activityServiceMap = {
    available: true,
    unavailableReason: null,
    windowStart: nowMillis - 60_000,
    windowEnd: nowMillis,
    totalInteractions: 18,
    totalFailures: 0,
    nodes: [
      {
        id: 'client:http',
        label: 'Local HTTP clients',
        kind: 'INBOUND',
        protocol: 'HTTP',
        direction: 'INBOUND',
        configured: false,
        observed: true,
        interactions: 2,
        failures: 0,
        distinctOperations: 2,
        lastSeen: nowMillis - 8_000,
        outcome: 'OBSERVED_OK',
        note: '2 recent HTTP requests'
      },
      {
        id: 'app',
        label: APP_NAME,
        kind: 'APPLICATION',
        protocol: 'JVM',
        direction: 'SELF',
        configured: true,
        observed: true,
        interactions: 18,
        failures: 0,
        distinctOperations: 2,
        lastSeen: nowMillis - 8_000,
        outcome: 'OBSERVED_OK',
        note: 'Spring Boot application'
      },
      {
        id: 'db:primary',
        label: 'H2 (insight_eager_orders, insight_eager_customers)',
        kind: 'DEPENDENCY',
        protocol: 'JDBC',
        direction: 'OUTBOUND',
        configured: true,
        observed: true,
        interactions: 18,
        failures: 0,
        distinctOperations: 3,
        lastSeen: nowMillis - 8_000,
        outcome: 'OBSERVED_OK',
        note: '18 SQL statements'
      }
    ],
    edges: [
      {
        id: 'edge:http',
        fromId: 'client:http',
        toId: 'app',
        protocol: 'HTTP',
        direction: 'INBOUND',
        interactions: 2,
        failures: 0,
        lastSeen: nowMillis - 8_000,
        outcome: 'OBSERVED_OK',
        recentInteractions: [
          {
            id: 'edge:http:latest',
            timestamp: nowMillis - 8_000,
            operation: 'GET /api/insights/eager-orders -> 200',
            outcome: 'OK',
            durationMs: 18
          }
        ]
      },
      {
        id: 'edge:db',
        fromId: 'app',
        toId: 'db:primary',
        protocol: 'JDBC',
        direction: 'OUTBOUND',
        interactions: 18,
        failures: 0,
        lastSeen: nowMillis - 8_000,
        outcome: 'OBSERVED_OK',
        recentInteractions: [
          {
            id: 'edge:db:latest',
            timestamp: nowMillis - 8_000,
            operation: CUSTOMER_SELECT_SQL,
            outcome: 'OK',
            durationMs: 1
          }
        ]
      }
    ],
    warnings: []
  }

  const preAgentRequestProfile = {
    available: true,
    unavailableReason: null,
    request: {
      id: 'req-eager-01',
      timestamp: new Date(nowMillis - 8_000).toISOString(),
      method: 'GET',
      uri: 'http://localhost:8080/api/insights/eager-orders',
      path: '/api/insights/eager-orders',
      query: null,
      status: 200,
      statusFamily: '2xx',
      durationMs: 18,
      responseSizeBytes: 1420,
      traceId: '4b8e9c10d2a14f00b712e9a40c881101',
      remoteAddress: '127.0.0.1',
      principal: null,
      sessionId: null,
      requestHeaders: [{name: 'accept', values: ['application/json'], masked: false}],
      responseHeaders: [{name: 'content-type', values: ['application/json'], masked: false}],
      route: '/api/insights/eager-orders',
      routeSource: 'FRAMEWORK_TEMPLATE'
    },
    sql: [
      {
        id: 1,
        timestamp: nowMillis - 7_996,
        sql: ORDER_SELECT_SQL,
        statementType: 'PREPARED',
        category: 'SELECT',
        durationMicros: 1_400,
        durationMillis: 1.4,
        success: true,
        errorMessage: null,
        affectedRows: 16,
        batchSize: 0,
        connectionId: 'conn-1',
        thread: 'http-nio-8080-exec-1',
        slow: false,
        parameters: [],
        callSite: SERVICE_CALL_SITE
      },
      ...Array.from({length: 16}, (_, idx) => ({
        id: idx + 2,
        timestamp: nowMillis - 7_994 + idx,
        sql: CUSTOMER_SELECT_SQL,
        statementType: 'PREPARED',
        category: 'SELECT',
        durationMicros: 775,
        durationMillis: 0.8,
        success: true,
        errorMessage: null,
        affectedRows: 1,
        batchSize: 0,
        connectionId: 'conn-1',
        thread: 'http-nio-8080-exec-1',
        slow: false,
        parameters: [`${idx + 1}:INTEGER(${idx + 1})`],
        callSite: SERVICE_CALL_SITE
      }))
    ],
    sqlGroups: [
      {
        sql: CUSTOMER_SELECT_SQL,
        category: 'SELECT',
        executions: 16,
        totalDurationMillis: 12.4,
        maxDurationMillis: 1.1,
        potentialNPlusOne: true,
        callSites: [SERVICE_CALL_SITE]
      },
      {
        sql: ORDER_SELECT_SQL,
        category: 'SELECT',
        executions: 1,
        totalDurationMillis: 1.4,
        maxDurationMillis: 1.4,
        potentialNPlusOne: false,
        callSites: [SERVICE_CALL_SITE]
      }
    ],
    sqlCorrelationApproximate: false,
    exceptions: [],
    trace: null,
    timing: {
      totalMs: 18,
      sqlCount: 17,
      sqlMs: 13.8,
      sqlPercent: 76.67
    },
    notes: ['17 SQL statements correlated by request id on GET /api/insights/eager-orders.']
  }

  const preAgentJournalProfile = {
    available: true,
    unavailableReason: null,
    requestId: 'req-eager-01',
    route: 'GET /api/insights/eager-orders',
    startedAt: nowMillis - 8_000,
    durationMicros: 18_000,
    resources: {
      availability: 'AVAILABLE',
      unmeasuredReason: null,
      cpuNanos: 4_200_000,
      allocatedBytes: 152_064,
      segments: 1,
      unmeasuredSegments: 0,
      gcPauses: 0,
      gcPauseMillis: 0,
      gcPausesTruncated: false
    },
    routeComparison: {
      requests: 6,
      minimumRequests: 5,
      p50Micros: 18_000,
      p95Micros: 21_000,
      standing: 'AT_OR_BELOW_P50'
    },
    orm: {
      sessions: 1,
      statements: 17,
      statementMicros: 13_800,
      flushes: 0,
      autoFlushes: 0,
      flushMicros: 0,
      autoFlushMicros: 0,
      entitiesInContext: 32,
      l2Hits: 0,
      l2Misses: 0,
      l2Puts: 0
    },
    timeline: [
      {
        source: 'HTTP',
        type: 'http.server.request',
        severity: 'OK',
        offsetMillis: 0,
        durationMicros: 18_000,
        label: 'GET /api/insights/eager-orders -> 200',
        detail: 'http-nio-8080-exec-1'
      },
      {
        source: 'TX',
        type: 'transaction.Completed',
        severity: 'OK',
        offsetMillis: 1,
        durationMicros: 16_800,
        label: 'EagerDemoOrderService.listWithSecondarySelects',
        detail: 'COMMITTED · read-only'
      },
      {
        source: 'SQL',
        type: 'sql.StatementExecuted',
        severity: 'OK',
        offsetMillis: 2,
        durationMicros: 1_400,
        label: ORDER_SELECT_SQL,
        detail: '16 rows · dataSource'
      },
      {
        source: 'SQL',
        type: 'sql.StatementExecuted',
        severity: 'WARN',
        offsetMillis: 4,
        durationMicros: 12_400,
        label: CUSTOMER_SELECT_SQL,
        detail: '16 statements (0.8 ms avg) · Potential N+1'
      },
      {
        source: 'ORM',
        type: 'orm.SessionClosed',
        severity: 'OK',
        offsetMillis: 17,
        durationMicros: 16_200,
        label: '17 statements, 0 flushes, 32 entities in context',
        detail: '1 session'
      }
    ],
    handoffs: [],
    lateHandoffs: 0,
    gcPauses: [],
    touched: {
      tables: ['insight_eager_orders', 'insight_eager_customers'],
      dataSources: ['dataSource'],
      transactions: ['EagerDemoOrderService.listWithSecondarySelects'],
      caches: [],
      messages: [],
      restCalls: [],
      logTemplates: [],
      models: []
    },
    notes: []
  }

  const preAgentCompactRequestProfile = {
    ...preAgentRequestProfile,
    request: {
      ...preAgentRequestProfile.request,
      traceId: null
    },
    notes: []
  }

  const preAgentCompactJournalProfile = {
    ...preAgentJournalProfile,
    resources: null,
    routeComparison: null,
    orm: null,
    timeline: [
      {
        source: 'HTTP',
        type: 'http.server.request',
        severity: 'OK',
        offsetMillis: 0,
        durationMicros: 18_000,
        label: 'GET /api/insights/eager-orders -> 200',
        detail: 'http-nio-8080-exec-1'
      },
      {
        source: 'SQL',
        type: 'sql.StatementExecuted',
        severity: 'WARN',
        offsetMillis: 4,
        durationMicros: 12_400,
        label: CUSTOMER_SELECT_SQL,
        detail: '16 statements (0.8 ms avg) · Potential N+1'
      }
    ],
    touched: {}
  }

  const postFixRequestProfile = {
    ...preAgentRequestProfile,
    request: {
      ...preAgentRequestProfile.request,
      id: 'req-after-01',
      timestamp: new Date(nowMillis - 5_000).toISOString(),
      durationMs: 4,
      traceId: null
    },
    sql: [
      {
        id: 21,
        timestamp: nowMillis - 4_998,
        sql: JOINED_ORDER_SQL,
        statementType: 'PREPARED',
        category: 'SELECT',
        durationMicros: 1_600,
        durationMillis: 1.6,
        success: true,
        errorMessage: null,
        affectedRows: 16,
        batchSize: 0,
        connectionId: 'conn-1',
        thread: 'http-nio-8080-exec-3',
        slow: false,
        parameters: [],
        callSite: SERVICE_CALL_SITE
      }
    ],
    sqlGroups: [
      {
        sql: JOINED_ORDER_SQL,
        category: 'SELECT',
        executions: 1,
        totalDurationMillis: 1.6,
        maxDurationMillis: 1.6,
        potentialNPlusOne: false,
        callSites: [SERVICE_CALL_SITE]
      }
    ],
    timing: {
      totalMs: 4,
      sqlCount: 1,
      sqlMs: 1.6,
      sqlPercent: 40.0
    },
    notes: []
  }

  const postFixJournalProfile = {
    ...preAgentJournalProfile,
    requestId: 'req-after-01',
    startedAt: nowMillis - 5_000,
    durationMicros: 4_000,
    resources: null,
    routeComparison: null,
    orm: null,
    timeline: [
      {
        source: 'HTTP',
        type: 'http.server.request',
        severity: 'OK',
        offsetMillis: 0,
        durationMicros: 4_000,
        label: 'GET /api/insights/eager-orders -> 200',
        detail: 'http-nio-8080-exec-3'
      },
      {
        source: 'SQL',
        type: 'sql.StatementExecuted',
        severity: 'OK',
        offsetMillis: 0.8,
        durationMicros: 1_600,
        label: JOINED_ORDER_SQL,
        detail: '16 rows · single joined SELECT'
      }
    ],
    touched: {}
  }

  const repeatedSelectsObservation = {
    id: 'obs-repeated-selects-eager-orders',
    kind: 'repeated-selects',
    status: 'OBSERVED',
    subject: 'GET /api/insights/eager-orders',
    sentence: `\`GET /api/insights/eager-orders\` ran \`${CUSTOMER_SELECT_SQL}\` 5 or more times after another statement in 6 of 6 requests, up to 16 times in one.`,
    affected: 6,
    eligible: 6,
    minimumTier: 'REQUEST_ID',
    exemplarRequestIds: ['req-eager-01', 'req-eager-02', 'req-eager-06'],
    whatToCheck: [
      'Open an exemplar request in Live Activity and check whether each repeat loads the children of one row the first statement returned.',
      "If it does, load them with one statement, such as a join or an IN list, in SQL Trace's call site."
    ],
    limitations: [
      'Counts statements the request ran; a statement Hibernate batches counts once.',
      'Total repeat time is 74.4 ms, summed across affected requests.'
    ]
  }

  const repeatedSelectsDetail = {
    available: true,
    unavailableReason: null,
    observation: repeatedSelectsObservation,
    columns: ['Request', 'Executions', 'Time (ms)', 'Call site', 'Phase', 'In transaction'],
    rows: Array.from({length: 6}, (_, index) => ({
      cells: [`req-eager-0${index + 1}`, '16', '12.4', SERVICE_CALL_SITE, 'handler', 'yes']
    })),
    truncated: 0
  }

  function buildRuntimeInsightsReport(scenarioId) {
    const isAfterFix = scenarioId === 'agent-after-fix'
    const requestCount = isAfterFix ? 6 : 7
    const observations = isAfterFix ? [] : [repeatedSelectsObservation]

    return {
      available: true,
      unavailableReason: null,
      window: {
        runId: isAfterFix ? 'run-2' : 'run-1',
        requests: requestCount,
        retainedEvents: isAfterFix ? 24 : 138,
        evictedEvents: 0,
        droppedEvents: 0,
        firstEventAt: nowMillis - 180_000,
        lastEventAt: nowMillis - 4_000
      },
      coverage: [
        {
          source: 'http',
          events: requestCount,
          byRequestId: requestCount,
          byTraceId: 0,
          byExecutionId: 0,
          unlinked: 0,
          dropped: 0
        },
        {
          source: 'sql',
          events: isAfterFix ? 6 : 103,
          byRequestId: isAfterFix ? 6 : 103,
          byTraceId: 0,
          byExecutionId: 0,
          unlinked: 0,
          dropped: 0
        },
        {
          source: 'transaction',
          events: requestCount,
          byRequestId: requestCount,
          byTraceId: 0,
          byExecutionId: 0,
          unlinked: 0,
          dropped: 0
        },
        {
          source: 'orm',
          events: requestCount,
          byRequestId: requestCount,
          byTraceId: 0,
          byExecutionId: 0,
          unlinked: 0,
          dropped: 0
        }
      ],
      checks: [
        {
          kind: 'repeated-selects',
          title: 'Repeated SELECTs',
          status: 'EVALUATED',
          evaluated: requestCount,
          minimumRequests: 5,
          minimumTier: 'REQUEST_ID',
          reason: null
        },
        {
          kind: 'route-time-breakdown',
          title: 'Where route time went',
          status: 'EVALUATED',
          evaluated: requestCount,
          minimumRequests: 5,
          minimumTier: 'REQUEST_ID',
          reason: null
        },
        {
          kind: 'errors-behind-2xx',
          title: 'Errors behind 2xx responses',
          status: 'EVALUATED',
          evaluated: requestCount,
          minimumRequests: 1,
          minimumTier: 'REQUEST_ID',
          reason: null
        },
        {
          kind: 'transaction-across-remote-call',
          title: 'Transactions open across remote calls',
          status: 'EVALUATED',
          evaluated: requestCount,
          minimumRequests: 1,
          minimumTier: 'REQUEST_ID',
          reason: null
        },
        {
          kind: 'proxy-bypass',
          title: 'Proxy bypass',
          status: 'EVALUATED',
          evaluated: requestCount,
          minimumRequests: 1,
          minimumTier: 'REQUEST_ID',
          reason: null
        },
        {
          kind: 'work-after-response',
          title: 'Work after the response',
          status: scenarioId === 'pre-agent' ? 'UNAVAILABLE' : 'EVALUATED',
          evaluated: scenarioId === 'pre-agent' ? 0 : requestCount,
          minimumRequests: 1,
          minimumTier: 'PROPAGATED',
          reason:
            scenarioId === 'pre-agent'
              ? 'Requires the BootUI agent executors sensor to attribute post-response tasks.'
              : null
        },
        {
          kind: 'event-loop-blocking',
          title: 'Event-loop blocking',
          status: 'NOT_APPLICABLE',
          evaluated: 0,
          minimumRequests: 1,
          minimumTier: 'REQUEST_ID',
          reason: 'This application uses a servlet web stack (Spring MVC), not a reactive event loop.'
        }
      ],
      observations,
      notExercised: [
        'POST /api/insights/orders/{id}/import',
        'POST /api/insights/orders/{id}/price-check',
        'POST /api/insights/orders/{id}/recalculate',
        'GET /api/insights/orders/after-response'
      ],
      notExercisedOmitted: 0
    }
  }

  const preFixComparison = {
    status: 'NO_PREVIOUS_RUN',
    reason: 'No previous run of this application was kept to compare with.',
    notComparableReasons: [],
    current: {
      runId: 'run-1',
      ordinal: 1,
      startedAt: nowMillis - 180_000,
      endedAt: null,
      requests: 7,
      source: 'MEMORY'
    },
    previous: null,
    runs: [],
    codeChanges: null,
    sideEffects: null,
    behavior: [],
    edges: [],
    latency: [],
    restartCost: null,
    limitations: []
  }

  const postFixComparison = {
    status: 'PARTIAL',
    reason:
      'Some journal evidence could not be compared; reliable dimensions remain available. Read the limits before treating an empty list as no change.',
    notComparableReasons: [],
    current: {
      runId: 'run-2',
      ordinal: 2,
      startedAt: nowMillis - 60_000,
      endedAt: null,
      requests: 6,
      source: 'MEMORY'
    },
    previous: {
      runId: 'run-1',
      ordinal: 1,
      startedAt: nowMillis - 240_000,
      endedAt: nowMillis - 62_000,
      requests: 7,
      source: 'MEMORY'
    },
    runs: [
      {
        runId: 'run-1',
        ordinal: 1,
        startedAt: nowMillis - 240_000,
        endedAt: nowMillis - 62_000,
        requests: 7,
        source: 'MEMORY'
      }
    ],
    codeChanges: {
      available: true,
      unavailableReason: null,
      counts: {
        changed: 1,
        added: 0,
        removed: 0,
        executed: 1,
        notExecuted: 0
      },
      methods: [
        {
          key: SERVICE_METHOD_KEY,
          className: SERVICE_CLASS,
          name: 'listWithSecondarySelects',
          descriptor: '()Ljava/util/List;',
          change: 'CHANGED',
          status: 'EXECUTED',
          notTrackedReason: null,
          routes: ['GET /api/insights/eager-orders'],
          routesTotal: 1,
          routesNote: null
        }
      ],
      methodsTotal: 1,
      limitations: []
    },
    sideEffects: null,
    behavior: [],
    edges: [],
    latency: [],
    restartCost: {
      status: 'COMPARED',
      reason: null,
      readyMsBefore: 1180,
      readyMsAfter: 410,
      beans: []
    },
    limitations: [
      "Current run's sql source missed 69 events before aggregate registration; affected dimensions cannot prove complete capture.",
      "Run 1's sql source missed 69 events before aggregate registration; affected dimensions cannot prove complete capture."
    ]
  }

  const resourceProfileIdle = {
    state: 'IDLE',
    reason: null,
    sampler: 'jdk.ExecutionSample',
    startedAt: null,
    endsAt: null,
    finishedAt: null,
    maxDurationSeconds: 30,
    cpuSamples: 0,
    outsideSamples: 0,
    requests: 0,
    routes: [],
    routesOmitted: 0,
    limitations: []
  }

  const changeImpactServiceMethod = {
    status: 'RESOLVED',
    reason: null,
    node: `METHOD ${SERVICE_CLASS}#listWithSecondarySelects()`,
    methodStatus: 'EXECUTED',
    methods: [SERVICE_METHOD_KEY],
    observedFrom: 'ROUTE_TREES',
    structuralReach: 2,
    observed: [
      {
        route: 'GET /api/insights/eager-orders',
        requests: 6,
        executedRequests: 6,
        anonymous: 6,
        errors: 0,
        partial: false,
        check: null,
        shared: [],
        reads: ['insight_eager_orders', 'insight_eager_customers'],
        writes: [],
        exemplarRequestIds: ['req-eager-01', 'req-eager-02']
      }
    ],
    observedTotal: 1,
    notObserved: [],
    notObservedTotal: 0,
    notExercised: [],
    notExercisedTotal: 0,
    notExercisedUndetermined: false,
    sharedResources: [
      {
        route: 'GET /api/insights/eager-orders/joined',
        requests: 1,
        executedRequests: 0,
        anonymous: 1,
        errors: 0,
        partial: false,
        check: null,
        shared: ['insight_eager_orders', 'insight_eager_customers'],
        reads: ['insight_eager_orders', 'insight_eager_customers'],
        writes: [],
        exemplarRequestIds: ['req-joined-01']
      }
    ],
    sharedResourcesTotal: 1,
    candidates: [],
    limitations: []
  }

  const changeImpactSymbols = {
    available: true,
    unavailableReason: null,
    query: 'EagerDemoOrderService',
    total: 2,
    symbols: [
      {
        kind: 'METHOD',
        name: 'EagerDemoOrderService#listWithSecondarySelects',
        type: SERVICE_CLASS
      },
      {
        kind: 'METHOD',
        name: 'EagerDemoOrderService#listWithCustomerJoin',
        type: SERVICE_CLASS
      }
    ]
  }

  const codePathsSummary = {
    available: true,
    unavailableReason: null,
    status: {
      generation: 1,
      settledTrees: 7,
      routeNodes: 8,
      routeNodeBudget: 10000,
      unroutedTrees: 0
    },
    routes: [
      {
        route: 'GET /api/insights/eager-orders',
        requests: 6,
        warmRequests: 5,
        firstRequestMillis: 24.2,
        p50Millis: 18.0,
        p95Millis: 21.0,
        maxMillis: 24.2,
        asyncRequests: 0,
        topMethods: [
          {
            method: SERVICE_METHOD_KEY,
            selfMillis: 17.2,
            share: 96
          },
          {
            method: CONTROLLER_METHOD_KEY,
            selfMillis: 0.4,
            share: 2
          }
        ]
      },
      {
        route: 'GET /api/insights/eager-orders/joined',
        requests: 1,
        warmRequests: 0,
        firstRequestMillis: 4.1,
        p50Millis: 4.1,
        p95Millis: 4.1,
        maxMillis: 4.1,
        asyncRequests: 0,
        topMethods: [
          {
            method: JOINED_SERVICE_METHOD_KEY,
            selfMillis: 3.7,
            share: 90
          }
        ]
      }
    ],
    probes: [],
    limitations: []
  }

  const codePathsRouteTree = {
    available: true,
    unavailableReason: null,
    route: 'GET /api/insights/eager-orders',
    found: true,
    assemblyOnly: false,
    warmRequests: 5,
    firstRequestMillis: 24.2,
    firstRequestId: 'req-eager-01',
    ownMillis: 18.0,
    handlerMillis: 17.6,
    shareOf: 'request',
    depth: 2,
    exemplarRequestIds: ['req-eager-01'],
    limitations: [],
    nodes: [
      {
        id: 0,
        parent: null,
        depth: 0,
        kind: 'REQUEST',
        method: null,
        phase: 'HANDLER',
        async: false,
        callsPerRequest: 1,
        totalMillis: 18.0,
        selfMillis: 0.4,
        p50Millis: 18.0,
        p95Millis: 21.0,
        share: 100,
        calls: []
      },
      {
        id: 1,
        parent: 0,
        depth: 1,
        kind: 'METHOD',
        method: CONTROLLER_METHOD_KEY,
        phase: 'HANDLER',
        async: false,
        callsPerRequest: 1,
        totalMillis: 17.6,
        selfMillis: 0.4,
        p50Millis: 17.5,
        p95Millis: 20.4,
        share: 98,
        calls: []
      },
      {
        id: 2,
        parent: 1,
        depth: 2,
        kind: 'METHOD',
        method: SERVICE_METHOD_KEY,
        phase: 'HANDLER',
        async: false,
        callsPerRequest: 1,
        totalMillis: 17.2,
        selfMillis: 17.2,
        p50Millis: 17.1,
        p95Millis: 19.9,
        share: 96,
        calls: [
          {
            kind: 'SQL',
            callsPerRequest: 17,
            totalMillis: 13.8
          }
        ]
      }
    ],
    methods: [
      {
        method: CONTROLLER_METHOD_KEY,
        callers: ['REQUEST'],
        routes: ['GET /api/insights/eager-orders']
      },
      {
        method: SERVICE_METHOD_KEY,
        callers: [CONTROLLER_METHOD_KEY],
        routes: ['GET /api/insights/eager-orders']
      }
    ],
    page: {
      total: 3,
      matched: 3,
      offset: 0,
      limit: 500,
      returned: 3,
      hasMore: false
    }
  }

  const codePathsRequestTree = {
    available: true,
    unavailableReason: null,
    found: true,
    requestId: 'req-after-01',
    route: 'GET /api/insights/eager-orders',
    assemblyOnly: false,
    topMethods: [
      {
        method: SERVICE_METHOD_KEY,
        selfMillis: 3.4,
        share: 85
      },
      {
        method: CONTROLLER_METHOD_KEY,
        selfMillis: 0.4,
        share: 10
      }
    ]
  }

  const codeInventorySummaryAfterFix = {
    available: true,
    unavailableReason: null,
    run: {
      generation: 2,
      application: APP_NAME,
      claimedAtEpochMillis: nowMillis - 60_000
    },
    scan: {
      status: 'COMPLETE',
      classes: 28,
      durationMillis: 14
    },
    methods: {
      tracked: 42,
      executed: 19,
      neverExecuted: 23,
      notTracked: 0,
      generated: 2,
      classes: 28,
      packages: 4
    },
    changes: {
      previousRun: true,
      scanStatus: 'COMPLETE',
      changed: 1,
      added: 0,
      removed: 0,
      executed: 1,
      notExecuted: 0
    },
    limitations: []
  }

  const codeInventoryChangesAfterFix = {
    counts: {
      previousRun: true,
      scanStatus: 'COMPLETE',
      changed: 1,
      added: 0,
      removed: 0,
      executed: 1,
      notExecuted: 0,
      partial: false,
      note: 'Compared against Run 1 before the DevTools restart.'
    },
    changes: [
      {
        key: SERVICE_METHOD_KEY,
        packageName: 'io.github.jdubois.bootui.sample.insights',
        className: SERVICE_CLASS,
        name: 'listWithSecondarySelects',
        descriptor: '()Ljava/util/List;',
        change: 'CHANGED',
        status: 'EXECUTED',
        notTrackedReason: null,
        firstRequestId: 'req-after-01',
        firstRoute: 'GET /api/insights/eager-orders'
      }
    ],
    page: {
      total: 1,
      matched: 1,
      offset: 0,
      limit: 500,
      returned: 1,
      hasMore: false
    }
  }

  const codeInventoryMethodsAfterFix = {
    packages: [
      {
        name: 'io.github.jdubois.bootui.sample.insights',
        classes: 10,
        executed: 8,
        neverExecuted: 12,
        notTracked: 0
      },
      {
        name: 'io.github.jdubois.bootui.sample',
        classes: 18,
        executed: 11,
        neverExecuted: 11,
        notTracked: 0
      }
    ],
    classes: [
      {
        packageName: 'io.github.jdubois.bootui.sample.insights',
        className: SERVICE_CLASS,
        executed: 3,
        neverExecuted: 0,
        notTracked: 0,
        changed: 1
      },
      {
        packageName: 'io.github.jdubois.bootui.sample.insights',
        className: CONTROLLER_CLASS,
        executed: 3,
        neverExecuted: 0,
        notTracked: 0,
        changed: 0
      },
      {
        packageName: 'io.github.jdubois.bootui.sample.insights',
        className: REPO_CLASS,
        executed: 2,
        neverExecuted: 0,
        notTracked: 0,
        changed: 0
      }
    ],
    methods: [
      {
        key: SERVICE_METHOD_KEY,
        packageName: 'io.github.jdubois.bootui.sample.insights',
        className: SERVICE_CLASS,
        name: 'listWithSecondarySelects',
        descriptor: '()Ljava/util/List;',
        status: 'EXECUTED',
        change: 'CHANGED'
      },
      {
        key: JOINED_SERVICE_METHOD_KEY,
        packageName: 'io.github.jdubois.bootui.sample.insights',
        className: SERVICE_CLASS,
        name: 'listWithCustomerJoin',
        descriptor: '()Ljava/util/List;',
        status: 'EXECUTED',
        change: null
      }
    ],
    page: {
      total: 2,
      matched: 2,
      offset: 0,
      limit: 500,
      returned: 2,
      hasMore: false
    }
  }

  function buildMcpServer(agentAttached) {
    const activePanels = buildPanels(agentAttached)
    const tools = cliManifest.tools
      .filter(
        (tool) =>
          tool.stacks.includes('SPRING_MVC') && activePanels.some((panel) => panel.id === tool.panel && panel.available)
      )
      .map((tool) => ({
        name: tool.name,
        description: tool.summary,
        panel: tool.panel,
        action: tool.action,
        panelEnabled: true,
        panelReadOnly: false
      }))
    return {
      enabled: true,
      configuredMode: 'ON',
      overridden: false,
      serverName: 'bootui',
      serverVersion: WORKSHOP_VERSION,
      transport: 'http',
      endpoint: '/bootui/api/mcp',
      protocolVersion: '2026-07-28',
      supportedProtocolVersions: ['2026-07-28', '2025-06-18'],
      maxResults: 200,
      callCount: 14,
      totalLatencyMillis: 184,
      capacityRefusals: 0,
      timeouts: 0,
      responseLimitRefusals: 0,
      cancellations: 0,
      toolCount: tools.length,
      tools
    }
  }

  function resolveEndpoint(scenarioId, endpoint, url) {
    const agentAttached = scenarioId !== 'pre-agent' && scenarioId !== 'pre-agent-compact'
    const afterFix = scenarioId === 'agent-after-fix'

    if (endpoint === 'overview') return workshopOverview
    if (endpoint === 'panels') {
      return {
        platform: 'spring-boot',
        panels: buildPanels(agentAttached)
      }
    }
    if (endpoint === 'java-agent') {
      return agentAttached ? javaAgentArmed : javaAgentNotAttached
    }
    if (endpoint === 'mappings') return workshopMappings
    if (endpoint === 'mappings/flat') {
      const q = (url.searchParams.get('q') || '').trim().toLowerCase()
      const filtered = q
        ? workshopFlatMappings.filter(
            (m) =>
              m.pattern.toLowerCase().includes(q) ||
              m.handler.toLowerCase().includes(q) ||
              m.method.toLowerCase().includes(q)
          )
        : workshopFlatMappings
      const report = pagedReport('mappings', filtered, url)
      return {
        ...report,
        total: workshopFlatMappings.length,
        page: {
          ...report.page,
          total: workshopFlatMappings.length,
          matched: filtered.length
        }
      }
    }
    if (endpoint === 'activity') return buildActivityReport(afterFix)
    if (endpoint === 'activity/service-map') return activityServiceMap
    if (endpoint === 'activity/journal') {
      return {
        enabled: true,
        retainedEvents: afterFix ? 24 : 138,
        evictedEvents: 0,
        droppedEvents: 0
      }
    }
    if (/^activity\/request\/[^/]+\/journal$/.test(endpoint)) {
      if (afterFix) return postFixJournalProfile
      if (scenarioId === 'pre-agent-compact') return preAgentCompactJournalProfile
      return preAgentJournalProfile
    }
    if (endpoint.startsWith('activity/request/')) {
      if (afterFix) return postFixRequestProfile
      if (scenarioId === 'pre-agent-compact') return preAgentCompactRequestProfile
      return preAgentRequestProfile
    }
    if (endpoint === 'runtime-insights') {
      return buildRuntimeInsightsReport(scenarioId)
    }
    if (endpoint.startsWith('runtime-insights/insights/')) {
      const id = decodeURIComponent(endpoint.slice('runtime-insights/insights/'.length))
      if (id === 'obs-repeated-selects-eager-orders') return repeatedSelectsDetail
      return repeatedSelectsDetail
    }
    if (endpoint === 'runtime-insights/comparison') {
      return afterFix ? postFixComparison : preFixComparison
    }
    if (endpoint === 'runtime-insights/resource-profile') {
      return resourceProfileIdle
    }
    if (endpoint === 'runtime-insights/impact') {
      return changeImpactServiceMethod
    }
    if (endpoint === 'runtime-insights/impact/symbols') {
      return changeImpactSymbols
    }
    if (endpoint === 'code-paths') {
      return codePathsSummary
    }
    if (endpoint === 'code-paths/route') {
      return codePathsRouteTree
    }
    if (endpoint.startsWith('code-paths/requests/')) {
      return codePathsRequestTree
    }
    if (endpoint === 'code-paths/beans') {
      return {available: true, unavailableReason: null, edges: []}
    }
    if (endpoint === 'code-inventory') {
      return codeInventorySummaryAfterFix
    }
    if (endpoint === 'code-inventory/changes') {
      return codeInventoryChangesAfterFix
    }
    if (endpoint === 'code-inventory/methods') {
      return codeInventoryMethodsAfterFix
    }
    if (endpoint === 'code-inventory/dependencies') {
      return {dependencies: [], page: {total: 0, matched: 0, offset: 0, limit: 500, returned: 0, hasMore: false}}
    }
    if (endpoint === 'mcp-server' || endpoint === 'mcp-server/toggle') {
      return buildMcpServer(agentAttached)
    }
    return undefined
  }

  const workshopScreenshots = [
    [
      'activity',
      'Live Activity',
      'workshop-00-request-profile-n-plus-one.webp',
      async (page) => {
        const minimizeBtn = page.getByRole('button', {name: /Minimize map/i})
        if (await minimizeBtn.count()) {
          await minimizeBtn.click()
        }
        await page.getByText('GET /api/insights/eager-orders').first().waitFor()
        await page
          .locator('tr', {hasText: 'GET /api/insights/eager-orders'})
          .first()
          .getByRole('button', {name: /Profile/})
          .click()
        const dialog = page.getByRole('dialog', {name: 'Request profile'})
        await dialog.waitFor()
        await dialog.getByText('N+1 · 16 identical').waitFor()
      },
      'pre-agent-compact'
    ],
    [
      'java-agent',
      'Java Agent',
      'workshop-01-java-agent-not-attached.webp',
      async (page) => {
        await page.getByRole('heading', {name: 'Not attached'}).waitFor()
        await page.getByRole('heading', {name: 'Attach the agent'}).waitFor()
      },
      'pre-agent'
    ],
    [
      'mappings',
      'Mappings',
      'workshop-02-mappings-eager-orders.webp',
      async (page) => {
        const filterInput = page.getByRole('textbox', {name: 'Filter HTTP mappings'})
        await filterInput.fill('eager-orders')
        await page.getByText('/api/insights/eager-orders', {exact: true}).waitFor()
        await page.getByText('/api/insights/eager-orders/joined', {exact: true}).waitFor()
        await page.getByText('/api/insights/orders/{id}/import').waitFor({state: 'hidden'})
      },
      'pre-agent'
    ],
    [
      'activity',
      'Live Activity',
      'workshop-03-journal-request-profile.webp',
      async (page) => {
        const minimizeBtn = page.getByRole('button', {name: /Minimize map/i})
        if (await minimizeBtn.count()) {
          await minimizeBtn.click()
        }
        await page.getByText('GET /api/insights/eager-orders').first().waitFor()
        await page
          .locator('tr', {hasText: 'GET /api/insights/eager-orders'})
          .first()
          .getByRole('button', {name: /Profile/})
          .click()
        const dialog = page.getByRole('dialog', {name: 'Request profile'})
        await dialog.waitFor()
        await dialog.getByRole('heading', {name: 'Recorded by the runtime journal'}).waitFor()
        await dialog.getByText('32 entities in context').first().waitFor()
      },
      'pre-agent'
    ],
    [
      'runtime-insights',
      'Runtime Insights',
      'workshop-04-runtime-insights-repeated-selects.webp',
      async (page) => {
        await page.getByPlaceholder(/Search checks, routes/).fill('eager-orders')
        const flatRowBtn = page.locator('#insight-row-obs-repeated-selects-eager-orders .insight-row-toggle')
        if (await flatRowBtn.count()) {
          await flatRowBtn.click()
        } else {
          const groupBtn = page.locator('#insight-group-repeated-selects')
          await groupBtn.waitFor()
          await groupBtn.click()
        }
        await page.locator('#insight-sentence', {hasText: 'up to 16 times in one'}).waitFor()
        await page.locator('.insight-evidence').getByText(SERVICE_CALL_SITE).first().waitFor()
        await page.evaluate(() => {
          window.__bootuiKeepWorkspaceScroll = true
          document.querySelector('.bootui-workspace')?.scrollTo(0, 200)
        })
      },
      'pre-agent'
    ],
    [
      'code-paths?route=GET%20%2Fapi%2Finsights%2Feager-orders&method=io.github.jdubois.bootui.sample.insights.EagerDemoOrderService%23listWithSecondarySelects()Ljava%2Futil%2FList%3B',
      'Code Paths',
      'workshop-05-code-paths-route-tree.webp',
      async (page) => {
        await page.getByText('EagerDemoOrderService.listWithSecondarySelects').first().waitFor()
        await page.getByText('SQL statements').first().waitFor()
        await page.getByRole('heading', {name: 'Called by, in this route'}).waitFor()
        await page.evaluate(() => {
          window.__bootuiKeepWorkspaceScroll = true
          document.querySelector('.bootui-workspace')?.scrollTo(0, 190)
        })
      },
      'agent-baseline'
    ],
    [
      'runtime-insights?impact=EagerDemoOrderService%23listWithSecondarySelects',
      'Runtime Insights',
      'workshop-06-change-impact-service-method.webp',
      async (page) => {
        await page.getByRole('heading', {name: 'Change impact'}).waitFor()
        await page.getByText('Code Inventory: ran in this run').waitFor()
        await page.getByText('6 ran it of 6 requests').waitFor()
        await page.getByText('GET /api/insights/eager-orders/joined').waitFor()
      },
      'agent-baseline'
    ],
    [
      'code-inventory',
      'Code Inventory',
      'workshop-07a-code-inventory-changed-executed.webp',
      async (page) => {
        await page.getByRole('heading', {name: /19 of 42 application methods executed/}).waitFor()
        await page.getByText('EagerDemoOrderService.listWithSecondarySelects()Ljava/util/List;').waitFor()
        await page.getByText('Probe in Code Paths').waitFor()
      },
      'agent-after-fix'
    ],
    [
      'activity',
      'Live Activity',
      'workshop-07b-request-profile-after-fix.webp',
      async (page) => {
        const minimizeBtn = page.getByRole('button', {name: /Minimize map/i})
        if (await minimizeBtn.count()) {
          await minimizeBtn.click()
        }
        await page.getByText('GET /api/insights/eager-orders').first().waitFor()
        await page
          .locator('tr', {hasText: 'GET /api/insights/eager-orders'})
          .first()
          .getByRole('button', {name: /Profile/})
          .click()
        const dialog = page.getByRole('dialog', {name: 'Request profile'})
        await dialog.waitFor()
        await dialog.getByText('×1').first().waitFor()
      },
      'agent-after-fix'
    ],
    [
      'runtime-insights?tab=changes',
      'Runtime Insights',
      'workshop-08-run-comparison-partial.webp',
      async (page) => {
        await page.getByRole('heading', {name: 'Compared with the previous run'}).waitFor()
        await page.locator('[data-testid="comparison-status"]').getByText('Partly compared', {exact: true}).waitFor()
        await page.getByText('EagerDemoOrderService#listWithSecondarySelects').first().waitFor()
        const limitsSummary = page.locator('.insight-comparison-limits summary')
        if (await limitsSummary.count()) {
          await limitsSummary.click()
        }
        await page
          .getByText(/missed 69 events before aggregate registration/)
          .first()
          .waitFor()
      },
      'agent-after-fix'
    ],
    [
      'mcp-server',
      'MCP Server',
      'workshop-appendix-a-mcp-and-agent-setup.webp',
      async (page) => {
        await page.getByText('Client configuration').waitFor()
        await page.getByText('/bootui/api/mcp').first().waitFor()
      },
      'agent-baseline'
    ],
    [
      'runtime-insights?tab=coverage',
      'Runtime Insights',
      'workshop-appendix-b-coverage-and-journal.webp',
      async (page) => {
        await page.getByRole('heading', {name: 'How this run was linked'}).waitFor()
        await page.getByRole('heading', {name: 'Checks and their limits'}).waitFor()
        const bySourceSummary = page.locator('.insight-coverage-sources summary')
        if (await bySourceSummary.count()) {
          await bySourceSummary.click()
        }
        await page.locator('.insight-coverage-sources').getByText('transaction').waitFor()
      },
      'agent-after-fix'
    ],
    [
      'java-agent',
      'Java Agent',
      'workshop-appendix-c-platform-availability.webp',
      async (page) => {
        await page.getByRole('heading', {name: 'Armed'}).waitFor()
        await page.getByRole('heading', {name: 'Sensors'}).waitFor()
        await page.getByText('code-paths').first().waitFor()
      },
      'agent-baseline'
    ],
    [
      'runtime-insights?tab=profile&q=GET%20%2Fapi%2Finsights%2Feager-orders',
      'Runtime Insights',
      'workshop-appendix-d-jfr-profile-ready.webp',
      async (page) => {
        await page.getByRole('heading', {name: 'Profile resources'}).waitFor()
        await page.getByRole('button', {name: 'Profile resources'}).waitFor()
      },
      'agent-baseline'
    ]
  ]

  return {
    workshopScreenshots,
    resolveEndpoint
  }
}
