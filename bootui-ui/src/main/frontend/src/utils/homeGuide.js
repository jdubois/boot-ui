export const homeGuide = [
  {
    id: 'understand',
    title: 'Understand this application',
    description: 'Explore how your application is configured, connected and put together.',
    icon: 'bi-sliders',
    topics: [
      {
        id: 'wiring',
        title: 'Configuration and wiring',
        description: 'Find effective settings and understand which components were created.',
        panels: ['config', 'profile-diff', 'beans', 'conditions']
      },
      {
        id: 'web',
        title: 'HTTP routes and access rules',
        description: 'Find an endpoint and see how access is configured.',
        panels: ['mappings', 'spring-security']
      },
      {
        id: 'data',
        title: 'Database setup and persistence',
        description: 'Explore connections, repositories, entity mappings and migrations.',
        panels: ['database-connection-pools', 'data', 'hibernate', 'flyway', 'liquibase']
      },
      {
        id: 'memory',
        title: 'Memory and JVM',
        description: 'Understand JVM sizing or choose a memory assessment.',
        panels: ['jvm-tuning', 'memory']
      },
      {
        id: 'services',
        title: 'Application services',
        description: 'Discover caching, scheduled work, resilience and AI integrations.',
        panels: ['cache', 'scheduled', 'fault-tolerance', 'ai']
      },
      {
        id: 'local',
        title: 'Local development environment',
        description: 'Inspect development services, instrumentation and repository context.',
        panels: ['dev-services', 'devtools', 'java-agent', 'github']
      },
      {
        id: 'tools',
        title: 'Explore without the browser',
        description: 'Use the same diagnostics from a terminal or a coding agent.',
        panels: ['cli', 'mcp-server']
      }
    ]
  },
  {
    id: 'behavior',
    title: 'Follow its behavior',
    description: 'Follow requests, investigate errors and understand what happens at runtime.',
    icon: 'bi-activity',
    topics: [
      {
        id: 'start',
        title: 'Where should I investigate first?',
        description: 'Start with recorded observations, recent activity or an explicit health check.',
        panels: ['runtime-insights', 'activity', 'health']
      },
      {
        id: 'http',
        title: 'A request or downstream call',
        description: 'Follow incoming traffic, outgoing calls and their timing.',
        panels: ['http-exchanges', 'traces', 'rest-client-trace', 'http-probe', 'http-sessions', 'websockets']
      },
      {
        id: 'errors',
        title: 'Errors or access failures',
        description: 'Find captured failures, logs and resilience behavior.',
        panels: ['exceptions', 'log-tail', 'loggers', 'security-logs', 'fault-tolerance']
      },
      {
        id: 'memory',
        title: 'Memory, CPU or concurrency',
        description: 'Inspect JVM resources or explicitly collect deeper evidence.',
        panels: ['live-memory', 'threads', 'heap-dump', 'metrics', 'jvm-tuning']
      },
      {
        id: 'database',
        title: 'Queries, transactions or database health',
        description: 'Follow recorded SQL and investigate database-specific behavior.',
        panels: [
          'sql-trace',
          'transactions',
          'hibernate-statistics',
          'postgresql',
          'mysql',
          'database-connection-pools'
        ]
      },
      {
        id: 'messages',
        title: 'Messages or background work',
        description: 'Explore messaging, scheduled tasks, caches and AI activity.',
        panels: ['email', 'kafka', 'rabbitmq', 'jms', 'scheduled', 'cache', 'ai']
      },
      {
        id: 'code',
        title: 'Startup or executed code',
        description: 'Find startup costs and, with the Java agent, the code that actually ran.',
        panels: ['startup', 'java-agent', 'code-paths', 'code-inventory', 'side-effects']
      }
    ]
  },
  {
    id: 'review',
    title: 'Review the code',
    description: 'Choose an assessment to find improvements in your code and configuration.',
    icon: 'bi-diagram-2',
    topics: [
      {
        id: 'start',
        title: 'Which assessment should I review?',
        description: 'Compare retained findings and see which assessments have run.',
        panels: ['overview']
      },
      {
        id: 'design',
        title: 'Architecture and API design',
        description: 'Review structure, HTTP contracts and framework practices.',
        panels: ['architecture', 'rest-api', 'spring']
      },
      {
        id: 'data',
        title: 'Persistence and memory practices',
        description: 'Review ORM mappings, database structure and memory findings.',
        panels: ['hibernate', 'database-advisor', 'memory']
      },
      {
        id: 'security',
        title: 'Security and dependency risks',
        description: 'Choose a security assessment and review its scope before running it.',
        panels: ['security', 'pentesting', 'vulnerabilities']
      },
      {
        id: 'deployment',
        title: 'Native images or checkpoint readiness',
        description: 'Review readiness for native compilation or checkpoint and restore.',
        panels: ['graalvm', 'crac']
      },
      {
        id: 'execution',
        title: 'Did my change run?',
        description: 'Use retained Java-agent evidence to explore execution and call paths.',
        panels: ['code-inventory', 'code-paths']
      },
      {
        id: 'tools',
        title: 'Use the CLI or a coding assistant',
        description: 'Bring the investigation into your existing development workflow.',
        panels: ['cli', 'mcp-server', 'copilot', 'claude-code', 'github']
      }
    ]
  }
]

export const homeAgentGuide = {
  id: 'agents',
  title: 'Let your agent help',
  icon: 'bi-robot',
  description: 'Offload an investigation to your coding agent, with BootUI skills and step-by-step guides.',
  panels: ['mcp-server', 'cli'],
  resources: [
    {
      id: 'agent-skill',
      title: 'Give your agent the BootUI skill',
      description: 'Teach your agent to inspect the running application, propose focused fixes and verify the result.',
      paragraphs: [
        'Review the skill instructions before installing. Choose one of these methods; the skill installer detects supported coding assistants. Cursor and Claude Code users can also use their BootUI plugin.'
      ],
      commands: [
        {
          label: 'Install with the skill installer',
          text: 'npx skills add https://github.com/jdubois/boot-ui/tree/main/skills/bootui'
        },
        {
          label: 'Or use GitHub CLI 2.90 or later',
          text: 'gh skill preview jdubois/boot-ui skills/bootui\ngh skill install jdubois/boot-ui skills/bootui'
        }
      ],
      items: [],
      note: 'Use the MCP Server or Command Line panel to connect your workflow, or use plain HTTP. Agree on the permitted work: installing a skill does not authorize scans, external lookups or code changes.',
      documentationLabel: 'Skill installation documentation',
      href: 'https://www.julien-dubois.com/boot-ui/ai-agents#install-the-bootui-agent-skill'
    },
    {
      id: 'agent-playbooks',
      title: 'Choose an investigation guide',
      description:
        'Six playbooks cover application checkups, databases, slow routes, errors, changed code and JVM performance.',
      paragraphs: [
        'Give your agent a concrete goal and ask it to start with existing evidence. The playbooks guide an investigation and propose a plan; agree on the scope before allowing new scans, reproductions or code changes.'
      ],
      commands: [],
      items: [
        {title: 'Application checkup', description: 'Find the most useful improvements.'},
        {title: 'Database efficiency', description: 'Investigate repeated SQL or transaction problems.'},
        {title: 'Slow endpoint', description: 'Explain a slow route.'},
        {
          title: 'Errors and hidden failures',
          description: 'Diagnose exceptions or errors behind successful responses.'
        },
        {title: 'Verify my changes', description: 'Check that changed code ran and had the intended effect.'},
        {title: 'JVM performance and memory', description: 'Investigate CPU, GC, retention, hangs or thread growth.'}
      ],
      note: null,
      documentationLabel: 'Investigation playbook documentation',
      href: 'https://www.julien-dubois.com/boot-ui/ai-agents#application-improvement-playbooks'
    }
  ]
}

export const homePanelDescriptions = {
  overview: 'Compare cached advisor scores, findings and assessment coverage.',
  activity: 'Browse recent recorded work across the application.',
  'runtime-insights': 'Find useful observations and open the recorded evidence behind them.',
  architecture: 'Assess code structure, dependencies and architecture rules.',
  'rest-api': 'Review API design, endpoint contracts and error handling.',
  spring: 'Review framework-specific application practices.',
  'database-advisor': 'Assess database structure and schema practices.',
  hibernate: 'Review entity mappings, fetching and persistence practices.',
  memory: 'Review memory findings and their supporting evidence.',
  security: 'Assess the application security configuration.',
  pentesting: 'Review the checks and scope before explicitly starting an assessment.',
  vulnerabilities: 'Inspect dependency advisories; external lookups require an explicit action.',
  health: 'Open health contributors and run their checks; some may contact dependencies.',
  'http-sessions': 'Inspect active server-side HTTP sessions.',
  metrics: 'Explore available measurements, counters and gauges.',
  'live-memory': 'Inspect current heap, non-heap and memory-pool usage.',
  'jvm-tuning': 'Understand JVM flags, memory sizing and container recommendations.',
  'heap-dump': 'Explicitly capture or inspect heap evidence when you need deeper analysis.',
  threads: 'Inspect thread states and explicitly acquire thread-stack evidence.',
  startup: 'Find which startup steps took time.',
  graalvm: 'Assess native-image readiness and review suggested artifacts.',
  crac: 'Assess checkpoint-and-restore readiness.',
  config: 'Inspect effective properties and their sources, with existing masking.',
  'profile-diff': 'Compare configuration across profiles.',
  loggers: 'Inspect loggers and, when permitted, adjust their levels.',
  beans: 'Explore registered components and their dependencies.',
  conditions: 'Understand why auto-configuration matched or did not match.',
  mappings: 'Find HTTP routes and their handlers.',
  'database-connection-pools': 'Inspect reported JDBC pools and their configuration.',
  postgresql: 'Explicitly inspect PostgreSQL operational evidence.',
  mysql: 'Explicitly inspect MySQL operational evidence.',
  'sql-trace': 'Inspect recorded JDBC statements and their timing.',
  transactions: 'Follow recorded transaction boundaries, commits and rollbacks.',
  'hibernate-statistics': 'Inspect ORM statistics when collection is enabled.',
  data: 'Explore Spring Data repositories and query methods.',
  flyway: 'Inspect migration history; migration actions remain explicit.',
  liquibase: 'Inspect changesets; update actions remain explicit.',
  'spring-security': 'Inspect filter chains and configured access rules.',
  'security-logs': 'Inspect recorded authentication and access events.',
  scheduled: 'Explore scheduled jobs and their recorded executions.',
  'rest-client-trace': 'Inspect recorded outgoing HTTP calls.',
  'fault-tolerance': 'Inspect retries, circuit breakers and resilience configuration.',
  websockets: 'Explore WebSocket connections and supported messaging activity.',
  ai: 'Inspect supported AI framework configuration and recorded activity.',
  cache: 'Inspect configured caches and available statistics.',
  email: 'Inspect captured development email and mail integration.',
  kafka: 'Explore Kafka integration and recorded messaging activity.',
  rabbitmq: 'Explore RabbitMQ integration and recorded messaging activity.',
  jms: 'Explore JMS integration and recorded messaging activity.',
  traces: 'Follow recorded spans and request timing.',
  'log-tail': 'Read captured application log output.',
  exceptions: 'Explore captured exception groups and occurrences.',
  'http-exchanges': 'Inspect retained incoming HTTP requests and responses.',
  'http-probe': 'Compose and explicitly send a bounded HTTP request.',
  'java-agent': 'Inspect agent setup and control permitted runtime sensors.',
  'code-paths': 'Explore recorded method paths and their timing; requires the Java agent.',
  'code-inventory': 'See observed execution and change evidence; requires the Java agent.',
  'side-effects': 'Inspect recorded side effects; requires the Java agent.',
  'mcp-server': 'Connect a coding agent to the local, opt-in diagnostic tools.',
  cli: 'Use BootUI diagnostics from the command line.',
  devtools: 'Inspect Spring development restart and reload support.',
  'dev-services': 'Inspect local development-service integrations.',
  copilot: 'Inspect locally recorded Copilot sessions and activity.',
  'claude-code': 'Inspect locally recorded Claude Code sessions and activity.',
  github: 'Read cached repository information; external refresh requires Connect.'
}
