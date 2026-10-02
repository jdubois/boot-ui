import {describe, expect, it, vi} from 'vitest'
import {ApiError} from '../api.js'
import {
  MAX_EXCEPTION_DETAILS,
  MAX_FRAMES,
  MAX_OCCURRENCES,
  codeBlock,
  escapeMarkdown,
  exceptionMarkdown,
  formatDuration,
  inlineCode,
  loadExceptionCorrelation,
  loadProfileExceptionDetails,
  profileMarkdown
} from './markdownExport.js'

function profile(overrides = {}) {
  return {
    available: true,
    unavailableReason: null,
    request: {
      id: 'req-1',
      method: 'GET',
      path: '/api/todos',
      status: 500,
      durationMs: 120,
      principal: null,
      traceId: 'trace-1'
    },
    sql: [],
    sqlGroups: [
      {
        sql: 'select * from todo where id = ?',
        category: 'SELECT',
        executions: 6,
        totalDurationMillis: 60.25,
        maxDurationMillis: 20,
        potentialNPlusOne: true,
        callSites: ['com.example.TodoRepository.findById(TodoRepository.java:42)']
      }
    ],
    sqlCorrelationApproximate: false,
    exceptions: [
      {
        exceptionClassName: 'java.lang.IllegalStateException',
        message: 'boom',
        location: 'com.example.TodoService.load(TodoService.java:10)',
        timestamp: 1700000000010,
        thread: 'http-nio-1',
        handler: 'TodoController#get',
        source: 'web',
        exceptionGroupId: 'g-1'
      }
    ],
    security: [],
    trace: null,
    timing: {totalMs: 120, sqlMs: 60.25, sqlCount: 6, sqlPercent: 50.2, restCallCount: 0, restCallMs: 0},
    notes: ['Exceptions are correlated exactly by trace id trace-1.'],
    restCalls: [],
    cacheAccesses: [],
    sections: [
      {type: 'SQL', available: true, unavailableReason: null, tier: 'TRACE_ID', childTiers: [], total: 6, truncated: 0},
      {
        type: 'EXCEPTION',
        available: true,
        unavailableReason: null,
        tier: 'TRACE_ID',
        childTiers: ['TRACE_ID'],
        total: 1,
        truncated: 0
      }
    ],
    correlationTiers: [],
    approximate: false,
    ...overrides
  }
}

function detail(overrides = {}) {
  return {
    group: {
      id: 'g-1',
      exceptionClassName: 'java.lang.IllegalStateException',
      message: 'boom',
      count: 3,
      firstSeen: 1700000000000,
      lastSeen: 1700000000010,
      location: 'com.example.TodoService.load(TodoService.java:10)',
      applicationException: true,
      lastThread: 'http-nio-1',
      lastRequestMethod: 'GET',
      lastRequestPath: '/api/todos',
      lastHandler: 'TodoController#get',
      lastSource: 'web',
      lastTraceId: 'trace-1',
      status: 'OPEN',
      regressionCount: 0,
      errorContract: null
    },
    frames: [
      {
        declaringClass: 'com.example.TodoService',
        methodName: 'load',
        fileName: 'TodoService.java',
        lineNumber: 10,
        applicationFrame: true
      },
      {
        declaringClass: 'org.springframework.Dispatcher',
        methodName: 'handle',
        fileName: null,
        lineNumber: null,
        applicationFrame: false
      }
    ],
    causes: [
      {
        exceptionClassName: 'java.io.IOException',
        message: 'disk full',
        frames: [
          {
            declaringClass: 'com.example.Disk',
            methodName: 'write',
            fileName: 'Disk.java',
            lineNumber: 7,
            applicationFrame: true
          }
        ],
        commonFrames: 2
      }
    ],
    occurrences: [
      {
        timestamp: 1700000000010,
        thread: 'http-nio-1',
        requestMethod: 'GET',
        requestPath: '/api/todos',
        handler: 'TodoController#get',
        source: 'web',
        traceId: null
      }
    ],
    ...overrides
  }
}

// Every top-level line that is not inside a fenced block. Structure is broken when one of these lines
// is a heading, list item, or fence that the export did not write itself.
function structuralLines(markdown) {
  const lines = []
  let fence = null
  for (const line of markdown.split('\n')) {
    const match = line.match(/^(`{3,})/)
    if (fence) {
      if (match && match[1].length >= fence.length && line.trim() === match[1]) fence = null
      continue
    }
    if (match) {
      fence = match[1]
      continue
    }
    lines.push(line)
  }
  return {lines, closed: fence === null}
}

describe('Markdown primitives', () => {
  it('escapes inline syntax and leading block markers in captured text', () => {
    expect(escapeMarkdown('# not a heading')).toBe('\\# not a heading')
    expect(escapeMarkdown('- not a list')).toBe('\\- not a list')
    expect(escapeMarkdown('1. not ordered')).toBe('1\\. not ordered')
    expect(escapeMarkdown('> quote')).toBe('\\> quote')
    expect(escapeMarkdown('a *b* _c_ [link](x) <b>|`x`~&amp;')).toBe(
      'a \\*b\\* \\_c\\_ \\[link\\](x) \\<b\\>\\|\\`x\\`\\~\\&amp;'
    )
    expect(escapeMarkdown('line one\n## line two')).toBe('line one ## line two')
  })

  it('fences inline code and blocks longer than any backtick run inside them', () => {
    expect(inlineCode('a`b')).toBe('``a`b``')
    expect(inlineCode('`edge`')).toBe('`` `edge` ``')
    expect(inlineCode('multi\nline')).toBe('`multi line`')
    expect(codeBlock('select 1', 'sql')).toBe('```sql\nselect 1\n```')
    expect(codeBlock('x\n```\n# y')).toBe('````\nx\n```\n# y\n````')
  })

  it('formats durations without consulting the locale', () => {
    expect(formatDuration(0.4)).toBe('<1 ms')
    expect(formatDuration(60.25)).toBe('60.3 ms')
    expect(formatDuration(1234)).toBe('1.23 s')
    expect(formatDuration(null)).toBe('')
  })
})

describe('profileMarkdown', () => {
  it('renders the request, normalized SQL with N+1 call sites, exceptions, and notes', () => {
    const {markdown, omissions} = profileMarkdown(profile())

    expect(markdown).toContain('# BootUI request profile: `GET /api/todos`')
    expect(markdown).toContain('- **Request:** `GET /api/todos`')
    expect(markdown).toContain('- **Activity entry id:** `req-1`')
    expect(markdown).toContain('- **Status:** 500')
    expect(markdown).toContain('- **Timing:** 6 SQL statements, 60.3 ms in SQL (50.2% of the request)')
    expect(markdown).toContain('## SQL (exact, trace id)')
    expect(markdown).toContain('### Statement group 1: N+1 suspected')
    expect(markdown).toContain('```sql\nselect * from todo where id = ?\n```')
    expect(markdown).toContain('- `com.example.TodoRepository.findById(TodoRepository.java:42)`')
    expect(markdown).toContain('### Exception 1: `java.lang.IllegalStateException`')
    expect(markdown).toContain('- **Exception group id:** `g-1`')
    expect(markdown).toContain('```text\nboom\n```')
    expect(markdown).toContain('- Exceptions are correlated exactly by trace id trace-1.')
    expect(markdown).not.toContain('Omitted from this export')
    expect(omissions).toEqual([])
  })

  it('says so when no SQL was correlated', () => {
    const {markdown} = profileMarkdown(profile({sqlGroups: [], exceptions: [], sections: []}))

    expect(markdown).toContain('No SQL was correlated to this request.')
    expect(markdown).not.toContain('```sql')
    expect(markdown).not.toContain('## Exceptions')
  })

  it('lists truncated and unavailable sections as omissions', () => {
    const {markdown, omissions} = profileMarkdown(
      profile({
        sections: [
          {type: 'SQL', available: true, tier: 'TRACE_ID', childTiers: [], total: 205, truncated: 5},
          {type: 'EXCEPTION', available: true, tier: 'TRACE_ID', childTiers: ['TRACE_ID'], total: 1, truncated: 0},
          {
            type: 'REST_CLIENT',
            available: false,
            unavailableReason: 'The REST Client panel is disabled.',
            tier: null,
            childTiers: [],
            total: 0,
            truncated: 0
          }
        ]
      })
    )

    expect(omissions).toContain(
      'SQL: Execution counts and timing cover all 205 correlated statements; the profile lists the first 200.'
    )
    expect(omissions).toContain('REST client calls unavailable: The REST Client panel is disabled.')
    expect(markdown).toContain('## Omitted from this export')
  })

  it('counts masked values and keeps them masked', () => {
    const {markdown, omissions} = profileMarkdown(
      profile({
        request: {...profile().request, principal: '******'},
        exceptions: [{...profile().exceptions[0], message: 'password=****** token=******'}]
      })
    )

    expect(omissions[0]).toBe('3 values were masked by BootUI and appear as ******.')
    expect(markdown).toContain('- 3 values were masked by BootUI and appear as `******`.')
    expect(markdown).not.toMatch(/password=(?!\*{6})/)
  })

  it('honors METADATA_ONLY by exporting no message and saying why', () => {
    const {markdown, omissions} = profileMarkdown(profile({exceptions: [{...profile().exceptions[0], message: null}]}))

    expect(markdown).not.toContain('#### Message')
    expect(omissions).toContain(
      'No message for java.lang.IllegalStateException: none was captured, or bootui.expose-values is METADATA_ONLY.'
    )
  })

  it('reports an unknown or evicted request as unavailable', () => {
    const {markdown} = profileMarkdown({
      available: false,
      unavailableReason: 'Request nope is no longer in the buffer',
      request: null
    })

    expect(markdown).toContain('# BootUI request profile\n')
    expect(markdown).toContain('The profile is unavailable: Request nope is no longer in the buffer')
  })

  it('adds the cause chain with application frames marked when exception details are loaded', () => {
    const {markdown} = profileMarkdown(profile(), {exceptionDetails: {'g-1': {detail: detail()}}})

    expect(markdown).toContain('Lines starting with → are application frames.')
    expect(markdown).toContain(
      [
        'java.lang.IllegalStateException',
        '→ at com.example.TodoService.load(TodoService.java:10)',
        '  at org.springframework.Dispatcher.handle(Unknown Source)',
        '  Caused by: java.io.IOException: disk full',
        '→     at com.example.Disk.write(Disk.java:7)',
        '      ... 2 more'
      ].join('\n')
    )
    expect(markdown).toContain('#### Recent occurrences (1)')
    expect(markdown).toContain(
      '- 2023-11-14T22:13:20.010Z · web · request `GET /api/todos` · thread `http-nio-1` · handler `TodoController#get`'
    )
  })

  it('reports an exception detail that could not be loaded and renders a repeated group once', () => {
    const second = {...profile().exceptions[0], timestamp: 1700000000020}
    const {markdown, omissions} = profileMarkdown(profile({exceptions: [profile().exceptions[0], second]}), {
      exceptionDetails: {'g-1': {error: 'the Exceptions panel no longer retains it.'}}
    })

    expect(omissions).toContain(
      'Stack trace and occurrences of java.lang.IllegalStateException: the Exceptions panel no longer retains it.'
    )
    expect(markdown).toContain('Stack trace and occurrences: see exception 1, the same exception group.')
  })

  it('bounds long stack traces and occurrence lists', () => {
    const frame = {declaringClass: 'a.B', methodName: 'c', fileName: 'B.java', lineNumber: 1, applicationFrame: false}
    const occurrence = detail().occurrences[0]
    const long = detail({
      frames: Array.from({length: MAX_FRAMES + 3}, () => frame),
      occurrences: Array.from({length: MAX_OCCURRENCES + 2}, () => occurrence)
    })
    const {markdown, omissions} = profileMarkdown(profile(), {exceptionDetails: {'g-1': {detail: long}}})

    expect(markdown).toContain('... 3 more frames not exported')
    expect(markdown).toContain(`Recent occurrences (${MAX_OCCURRENCES} of ${MAX_OCCURRENCES + 2} retained)`)
    expect(omissions).toHaveLength(2)
  })

  it('keeps the document structure whatever Markdown the captured values contain', () => {
    const hostile = '```\n# Injected heading\n- injected item\n</details>[x](javascript:alert(1))'
    const {markdown} = profileMarkdown(
      profile({
        request: {...profile().request, path: '/x`|`## y', principal: '- admin'},
        sqlGroups: [{...profile().sqlGroups[0], sql: hostile, callSites: ['`a`\n# b']}],
        exceptions: [{...profile().exceptions[0], message: hostile, location: hostile}],
        notes: [hostile]
      }),
      {exceptionDetails: {'g-1': {detail: detail({causes: [{...detail().causes[0], message: hostile}]})}}}
    )
    const {lines, closed} = structuralLines(markdown)

    expect(closed).toBe(true)
    expect(lines.filter((line) => line.startsWith('#'))).toEqual([
      '# BootUI request profile: ``GET /x`|`## y``',
      '## Request',
      '## SQL (exact, trace id)',
      '### Statement group 1: N+1 suspected',
      '## Exceptions (trace id)',
      '### Exception 1: `java.lang.IllegalStateException`',
      '#### Message',
      '#### Stack trace',
      '#### Recent occurrences (1)',
      '## Notes'
    ])
    expect(lines.filter((line) => /^\s*(- injected|<\/details>|\[x\])/.test(line))).toEqual([])
  })

  it('produces identical text for identical DTOs, as every adapter serializes them', () => {
    const one = profileMarkdown(profile(), {exceptionDetails: {'g-1': {detail: detail()}}})
    const roundTripped = profileMarkdown(JSON.parse(JSON.stringify(profile())), {
      exceptionDetails: {'g-1': {detail: JSON.parse(JSON.stringify(detail()))}}
    })

    expect(roundTripped).toEqual(one)
  })
})

describe('exceptionMarkdown', () => {
  it('renders the summary, message, cause chain, occurrences, and correlated SQL', () => {
    const {markdown, omissions} = exceptionMarkdown(detail(), {
      correlated: {requestId: 'req-1', profile: profile()}
    })

    expect(markdown).toContain('# BootUI exception: `java.lang.IllegalStateException`')
    expect(markdown).toContain('- **Status:** Open')
    expect(markdown).toContain(
      '- **Occurrences:** 3, first seen 2023-11-14T22:13:20.000Z, last seen 2023-11-14T22:13:20.010Z'
    )
    expect(markdown).toContain('- **Origin:** application code')
    expect(markdown).toContain('## Message\n\n```text\nboom\n```')
    expect(markdown).toContain('→ at com.example.TodoService.load(TodoService.java:10)')
    expect(markdown).toContain('## Correlated request: `GET /api/todos` → 500')
    expect(markdown).toContain('- **Activity entry id:** `req-1`')
    expect(markdown).toContain('#### Statement group 1: N+1 suspected')
    expect(markdown).toContain('- `com.example.TodoRepository.findById(TodoRepository.java:42)`')
    expect(omissions).toEqual([])
  })

  it('explains why correlated SQL is missing', () => {
    const {omissions} = exceptionMarkdown(detail(), {
      correlationUnavailableReason: 'the latest occurrence is not correlated to a captured HTTP request.'
    })

    expect(omissions).toContain('Correlated SQL: the latest occurrence is not correlated to a captured HTTP request.')
  })
})

describe('loading an export', () => {
  it('loads each referenced exception group once, within the bound, through the read endpoint', async () => {
    const ids = Array.from({length: MAX_EXCEPTION_DETAILS + 2}, (_, i) => `g-${i}`)
    const exceptions = [...ids, 'g-0'].map((id) => ({...profile().exceptions[0], exceptionGroupId: id}))
    const fetchJson = vi.fn((url) =>
      url === 'api/exceptions/g-1' ? Promise.reject(new ApiError(404)) : Promise.resolve(detail())
    )

    const {exceptionDetails, omissions} = await loadProfileExceptionDetails(profile({exceptions}), fetchJson)

    expect(fetchJson.mock.calls.map((call) => call[0])).toEqual(
      ids.slice(0, MAX_EXCEPTION_DETAILS).map((id) => `api/exceptions/${id}`)
    )
    expect(exceptionDetails['g-0'].detail).toBeTruthy()
    expect(exceptionDetails['g-1'].error).toBe('the Exceptions panel no longer retains it.')
    expect(omissions).toEqual(['Stack traces of 2 more exception groups: only the first 5 are loaded.'])
  })

  it('profiles the request the latest occurrence belongs to', async () => {
    const fetchJson = vi.fn((url) => {
      if (url === 'api/activity') {
        return Promise.resolve({
          available: true,
          entries: [
            {id: 'exc-g-1', type: 'EXCEPTION', parentId: 'req-1'},
            {id: 'req-1', type: 'REQUEST', profileable: true}
          ]
        })
      }
      return Promise.resolve(profile())
    })

    const correlation = await loadExceptionCorrelation(detail(), fetchJson)

    expect(fetchJson.mock.calls.map((call) => call[0])).toEqual(['api/activity', 'api/activity/request/req-1'])
    expect(correlation.correlated.requestId).toBe('req-1')
  })

  it.each([
    [{available: true, entries: []}, 'Live Activity no longer lists this exception.'],
    [
      {
        available: true,
        entries: [
          {id: 'exc-g-1', type: 'EXCEPTION', parentId: 'sched-1'},
          {id: 'sched-1', type: 'SCHEDULED'}
        ]
      },
      'the latest occurrence is not correlated to a captured HTTP request.'
    ],
    [
      {
        available: true,
        entries: [
          {id: 'exc-g-1', type: 'EXCEPTION', parentId: 'req-1'},
          {id: 'req-1', type: 'REQUEST', profileable: false}
        ]
      },
      'the correlated request cannot be profiled.'
    ]
  ])('explains a missing correlation without reading further', async (activity, reason) => {
    const fetchJson = vi.fn(() => Promise.resolve(activity))

    const correlation = await loadExceptionCorrelation(detail(), fetchJson)

    expect(correlation).toEqual({correlated: null, correlationUnavailableReason: reason})
    expect(fetchJson).toHaveBeenCalledTimes(1)
  })

  it('reports a disabled Live Activity panel', async () => {
    const correlation = await loadExceptionCorrelation(detail(), () => Promise.reject(new ApiError(403)))

    expect(correlation.correlationUnavailableReason).toBe(
      'Live Activity could not be read: the Live Activity panel is disabled or not permitted.'
    )
  })
})
