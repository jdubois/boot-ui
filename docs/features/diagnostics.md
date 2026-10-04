# Diagnostics

## Traces

![BootUI Traces panel](../images/bootui-traces.webp)

The Traces panel shows distributed tracing spans captured locally. The starter contributes the tracing dependencies and
the sampling default that local development needs, so your application does not need manual `management.*` tracing
properties.

The list shows the most recent traces with their service name, the HTTP path each trace served, status, duration, and
span count. When a trace has no path attribute, the root span name is used instead. Opening a trace renders a waterfall
of its spans, so you can see latency contributions, errors, and parent and child relationships across services.

Trace summaries and span details, including the spans in request profiles, follow the live value-exposure rules
described below. AI chat details also follow `bootui.expose-values`: `MASKED` applies secret-key and free-text masking,
including nested attributes and events; `METADATA_ONLY` omits their attributes and events; `FULL` shows captured values.
Changing the policy affects the next read, not what is retained in memory.

On Spring Boot, BootUI also runs an embedded OTLP/HTTP receiver at `/bootui/api/otlp/v1/traces`, so cooperating local
services can export spans into the same in-memory store. On Quarkus, spans are captured in-process through an
OpenTelemetry `SpanProcessor` registered only when the application depends on `quarkus-opentelemetry`, and there is no
embedded receiver. The empty state points at whichever model applies.

Trace data resets on application restart and through the panel's clear action. When `bootui.telemetry.enabled=false`,
the sidebar dims the panel and the view shows a disabled state, so an empty list never reads as "no traces yet".

### Trace value exposure

Spans are stored as captured, and every read applies the live `bootui.expose-values` and `bootui.mask-secrets` policy,
so a runtime change takes effect on the next read without a restart. The same rule covers `GET /bootui/api/traces/{id}`,
the trace embedded in the [per-request profile](overview.md#the-per-request-profiler), and the `get_request_profile` MCP
tool and `bootui request-profile` command on Spring MVC, Spring WebFlux, and Quarkus:

- **Status messages and exception events.** A span's status message and its `exception.message` and
  `exception.stacktrace` event attributes, like `error.message` and captured generative-AI content (prompts,
  completions, input and output messages, system instructions, tool call arguments and results, and vector query
  content and returned documents), follow the [exception message](#exposure-and-bounds) rule: secret-like assignments
  and authorization credentials are masked under the default `MASKED`, the text is omitted under `METADATA_ONLY`, and
  it is verbatim only under `FULL`.
- **URLs.** `url.full`, `http.url`, `http.target`, `url.query`, and `url.path` are masked like the HTTP Exchanges URI:
  user-info is always removed, sensitive query and matrix parameter values are masked under `MASKED`, and query values
  are dropped under `METADATA_ONLY`.
- **Headers and bound parameters.** `http.request.header.*` and `http.response.header.*` values with a sensitive name,
  such as `authorization` or `cookie`, are masked under `MASKED`, and every header and `db.query.parameter.*` value is
  omitted under `METADATA_ONLY`.
- **Other attributes.** A string attribute whose key looks sensitive, such as `app.api_key`, is masked, and any other
  string attribute, such as `db.statement`, has secret-like assignments masked, unless the mode is `FULL`. Numbers and
  booleans, such as token counts and status codes, are never masked.

Keys, types, span and event names, ids, kinds, and timings are never changed. An omitted value is `null`, so the span
keeps its shape. Masked text is safer to show, not guaranteed secret-free: like log messages, only secrets with a
recognizable shape are detected. The [AI Framework](services.md#ai-framework-value-exposure) chat detail applies the
same rule to the chat span it returns.

::: details Sampling defaults and log-level pins

The starter raises sampling to 100 % (`management.tracing.sampling.probability=1.0`), so the OpenTelemetry SDK and the
Micrometer Tracing span and propagation code run on every request. To keep that from flooding the console when the
host's root logger is at `DEBUG`, BootUI pins `logging.level.io.opentelemetry` and `logging.level.io.micrometer.tracing`
to `INFO` as overridable defaults. Set either key yourself to opt back in.

:::

::: details Self-trace filtering and buffer bounds

Traces from BootUI's own API are filtered out on ingestion. As soon as any span in a trace is recognized as BootUI
traffic, such as the HTTP server span for `/bootui/api/**`, the whole trace is dropped, including nested spans that
carry no path of their own, such as Spring Security `security filterchain before` and `after` observations. Retained
self-only traces are also hidden from the panel.

Ingestion filtering follows `bootui.telemetry.exclude-self-spans`, and read-time panel filtering follows
`bootui.monitoring.exclude-self`. The in-memory buffer is bounded by `bootui.telemetry.max-traces`,
`bootui.telemetry.max-spans-per-trace`, request-size limits, and attribute-value truncation, plus internal caps that
keep a misconfigured local exporter from overflowing the UI. These bounds behave identically on all three stacks.

:::

## Log Tail

![BootUI Log Tail panel](../images/bootui-log-tail.webp)

The Log Tail panel reads recent local application logs and streams new log events from the running process, for quick
diagnosis without leaving the console.

### Log message exposure

Log messages follow the same exposure rule as [exception messages](#exposure-and-bounds). Under the default
`bootui.expose-values=MASKED`, on every line of a multi-line message:

- The value of each secret-like `key=value` or `key: value` assignment, such as a password, token, or API key, is
  replaced with `******`.
- After an `authorization` key, the credential that follows an HTTP authorization scheme is masked while the key and
  the scheme stay visible, as in `Authorization: Bearer ******`, `"authorization": "Basic ******"`, or
  `Proxy-Authorization: Digest ******`. Every comma-separated parameter of a Digest, OAuth, or AWS signature
  credential and every value of a multi-valued header, as in `Authorization=[Basic ******, Bearer ******]` or
  Spring's `Authorization:"Bearer ******", "Bearer ******"`, is covered.
  A scheme BootUI does not recognize is masked together with its credential, because it cannot be told apart from a
  bare credential. After any other secret-like key, a scheme is masked together with its credential, as in
  `X-Auth-Token: ******`.
- A credential after `Bearer`, `Basic`, `Negotiate`, or `NTLM` is masked even when no key precedes it, as in
  `sending Bearer ******`, when its shape shows it is one: a `Bearer` credential must have at least eight characters
  including a digit, or at least twenty, a `Basic` credential must decode to `user:password`, and a `Negotiate` or
  `NTLM` credential must decode to an NTLM message or a SPNEGO token. Prose such as `missing Bearer token`,
  `Basic auth is enabled`, or `unable to negotiate TLS_AES_128_GCM_SHA256` stays readable. Other scheme names are
  common words, so they are recognized only after a secret-like key.

Under `METADATA_ONLY` the message is omitted while the timestamp, level, logger, and thread remain, and the panel marks
each such line **message omitted by policy** rather than showing it empty. Only under `FULL`, or with
`bootui.mask-secrets=false`, are messages shown verbatim. Any other assignment has only the first word of its value
masked, and a bare token or a credential inside a connection string is not detected. Treat log output as local
diagnostic data.

The rule applies when a line is read, not when it is captured, so it covers the recent snapshot
(`GET /bootui/api/log-tail/recent`), the SSE stream including its replayed backlog, the `get_log_tail` MCP tool, and
`bootui logs tail` on Spring MVC, Spring WebFlux, and Quarkus alike. Changing `bootui.expose-values` or
`bootui.mask-secrets` at runtime applies to the next snapshot and the next streamed line, including lines captured
before the change, without a restart. Each line carries a `messageOmitted` flag that is `true` only when the policy
withheld its message.

## Exceptions

![BootUI Exceptions panel](../images/bootui-exceptions.webp)

The Exceptions panel captures exceptions thrown by the running application and groups repeated failures into one entry
with an occurrence count.

Grouping uses a stable fingerprint derived from the exception type and the top stack frames, so a recurring error
collapses into a single row. That row shows the type, the latest message, first and last seen times, the originating
location, and a total count. Opening a group shows the representative stack trace with application frames highlighted, the full cause chain
with `… N more` common-frame folding, and the most recent occurrences with their thread, source, and request context.
Each occurrence also carries the `requestId` of the request that threw it, when BootUI saw one, so the profiler can
attribute it exactly with or without tracing.

The list updates over Server-Sent Events: the browser subscribes to `/bootui/api/exceptions/stream` and re-fetches
whenever an exception is captured or the store is cleared. You can filter by text, by capture source, or to
application-originated exceptions only.

On Spring MVC, BootUI records from two complementary sources: a non-intrusive `HandlerExceptionResolver` that observes
exceptions escaping web request handlers, capturing the request method, path, and handler; and a logback appender that
picks up anything logged with a throwable from scheduled tasks, async work, or `log.error("…", ex)`. A failure that is
both handled and logged is de-duplicated by throwable identity.

### Triage status

Each group carries a Sentry-style triage status, shown as a badge and changed inline with a button group:

| Status | Meaning | On a new occurrence |
| ------ | ------- | ------------------- |
| **Open** | Default for every new group | Keeps accumulating |
| **Acknowledged** | Seen, still being investigated | Keeps accumulating; never auto-transitions |
| **Resolved** | Believed fixed | Regresses: auto-reopens to **Open** |

When a **Resolved** group throws again, BootUI treats it as a regression. The group reopens to **Open** and a lifetime
"Reopened ×N" counter appears next to the badge, so a failure you thought was fixed is immediately visible. Only a
**Resolved** group can regress.

A status filter narrows the list alongside the text and source filters. Changing a status calls
`POST /bootui/api/exceptions/{id}/status` with `{"status": "..."}`, validated against the three values: anything else
returns `400`, and an unknown group returns `404`.

### Exposure and bounds

Exception messages follow the same exposure policy as the rest of BootUI. Under the default `bootui.expose-values=MASKED`
they are scrubbed of secret-like `key=value` assignments and of the credential after an authorization scheme, exactly
as [log messages](#log-message-exposure) are, under `METADATA_ONLY` they are omitted entirely, and only under `FULL`
are they shown verbatim. Request paths are captured without their query string, and stack frames carry
only class, method, file, and line information.

The in-memory store resets on restart and through the panel's clear action, which honors the panel's read-only setting.
It is bounded by three properties:

| Property | Default | Bounds |
| -------- | ------- | ------ |
| `bootui.exceptions.max-groups` | `100` | Groups retained, evicting the least recently seen |
| `bootui.exceptions.max-occurrences-per-group` | `25` | Occurrences kept per group |
| `bootui.exceptions.max-stack-frames` | `50` | Frames kept per stack trace |

Triage and regression detection are engine-level, so they behave identically on all three stacks. Only the capture
sources differ.

::: details Capture sources on Quarkus and WebFlux

**Quarkus** replaces the MVC resolver and the logback appender with a `java.util.logging` handler that records anything
logged with a throwable, excluding BootUI's own loggers, and a Vert.x failure handler that records the throwable
escaping a failed request with its method and path. The shared store still de-duplicates by throwable identity across
the cause chain. Capture is installed on `StartupEvent`, detached on `ShutdownEvent`, wired in dev and test only, and
bounded by the same `bootui.exceptions.*` limits.

**Spring Boot WebFlux** captures through a `WebExceptionHandler` at the highest precedence, plus the same logback
appender as the servlet adapter. One fidelity gap follows from the dispatch order: a `@RestController`'s own local
`@ExceptionHandler` method consumes an exception inside the WebFlux pipeline, before any `WebExceptionHandler` sees it.
The servlet adapter's resolver-chain capture observes those too. Unhandled exceptions, the common case, are captured
identically on both stacks.

:::

### Copy for AI

An open group offers **Copy for AI**, which turns the detail into one Markdown document to paste into an agent: a
summary of the group, its exposure-governed message, the stack trace and cause chain with application frames marked,
and the most recent occurrences with their request context. When Live Activity can still profile the request the
latest occurrence belongs to, the document adds that request's timing and correlated SQL, grouped by normalized
statement with N+1 groups and the call sites that issued them.

The full document is shown before anything is copied, with a list of what it leaves out, such as masked values, a
message withheld under `METADATA_ONLY`, bounded frames or occurrences, or why no correlated SQL could be added.
Preparing it reads only the existing Live Activity feed and profile endpoints; the copy itself sends nothing and
changes no state. It renders through the same helper as the [profile drawer's export](overview.md#copy-profile-and-copy-for-ai),
so captured text cannot break the Markdown, and identical evidence reads identically on every stack.

## HTTP Exchanges

![BootUI HTTP Exchanges panel](../images/bootui-http-exchanges.webp)

The HTTP Exchanges panel records recent inbound requests. It lists the timestamp, method, path, status, duration,
response size when a `Content-Length` header is present, and trace identifiers from common propagation headers.
Expanding a row shows the request and response headers, with secret-like headers and query parameters masked unless
`bootui.expose-values=FULL` is configured.

BootUI's own requests are not recorded while `bootui.monitoring.exclude-self` is on, which is the default, so console
polling never takes a slot from application traffic. The check uses the request path below the servlet context path or
WebFlux base path, never the query string, so an application request whose query mentions `/bootui` is still recorded. The buffer retains 200 exchanges by default;
change it with `bootui.http-exchanges.max-exchanges`, which takes effect on the next restart.

On Spring Boot, BootUI contributes its own bounded `HttpExchangeRepository`, and the Actuator filter that records into
it, when the panel is enabled and the application has not defined a repository. If no repository is available, the
panel says so, so an empty list never reads as "no traffic yet". Quarkus has no Actuator repository, so a small Vert.x
route filter samples each completed request in the response body-end handler, where status, duration, and size are
final, into a bounded buffer sized by the same property. That filter is wired in dev and test only, never in
production. Masking, trace-id extraction, self-exclusion, and paging run through the shared engine service, so the wire
format is identical.

### Failure-preserving retention

A burst of successful requests would otherwise evict the one failure you came to investigate. Every BootUI-owned
capture buffer — HTTP Exchanges on all three stacks, [SQL Trace](database.md#sql-trace), and
[REST Client](services.md#rest-client) — therefore reserves a share of its capacity for failed and slow records:

| Buffer         | Reserved for                                                                              | Slow threshold                                        |
| -------------- | ----------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| HTTP Exchanges | `5xx` responses and requests at or above the slow threshold                               | `bootui.activity.request-slow-threshold-ms` (`1000`)   |
| SQL Trace      | Failed statements and statements at or above the slow threshold                           | `bootui.sql-trace.slow-query-threshold-millis` (`100`) |
| REST Client    | Calls that failed, received a `4xx` or `5xx` response, or took at least the slow threshold | `bootui.rest-client-trace.slow-call-threshold-millis` (`1000`) |

Routine records are evicted first. The reserved share evicts its own oldest record only once it is full, so under a
flood of successes the most recent failed and slow records survive up to that share. The reservation is carved out of
the existing capacity, never added to it, and never takes the whole buffer, so the newest record is always kept.
Records are classified once, when they are captured, and the list stays newest-first across both tiers.

The share is 25% of each buffer by default. Set `bootui.http-exchanges.reserved-share-percent`,
`bootui.sql-trace.reserved-share-percent`, or `bootui.rest-client-trace.reserved-share-percent` to change it, or to `0`
to evict strictly oldest first. A slow threshold of `0` disables slow classification for that buffer, so only failures
are reserved.

With [Live Activity durable history](overview.md#durable-history) on, persistence recognizes a reserved record by the
same rule and slow threshold as the buffer that holds it, including a slow `4xx` request, which Live Activity shows as
`WARN` rather than `SLOW`. A reserved record that newer entries pushed out of the stream and that later reappears is
therefore not stored twice. Persistence remembers each kind of entry separately, so a burst of another kind, such as a
failing scheduled job, cannot make it forget a reserved request; only more than
`bootui.activity.persistence.buffer-max-entries` newer remembered records of the same kind can.

Each panel states its window above the list — records kept of the capacity, how many sit in the reserved share, and how
many were evicted since startup — so no panel implies it holds every request. The same counts are in the `retention`
object of each report, and therefore in `get_http_exchanges`, `get_sql_traces`, and `get_rest_client_traces` for MCP
clients and the `bootui` CLI.

On Spring Boot these guarantees apply only while BootUI owns recording. When the application defines its own
`HttpExchangeRepository`, BootUI leaves it untouched. When it defines its own `HttpExchangesFilter` or
`HttpExchangesWebFilter`, BootUI keeps its repository's former behavior: every exchange it is given is kept, oldest
evicted first, and BootUI's own requests are hidden at read time instead. In both cases the panel reports retention as
managed by the application.

Every exchange carries the route it belongs to, shown under its path when the two differ, and a **Profile** link that
opens the request's profile in [Live Activity](overview.md#the-per-request-profiler). Stacks that correlate a profile
by trace id alone explain in the profile when a request carried none.

Every exchange also carries BootUI's own `requestId`, generated when the request starts, whether or not OpenTelemetry
is present. On Spring MVC it covers the servlet thread and any asynchronous redispatch of the same request. On Spring
WebFlux it travels in the Reactor context to every scheduler the request hops to, including error rendering. On
Quarkus it follows the request from the Vert.x event loop to the worker or virtual thread that continues it. It is the
exchange's `id`, so identical requests that overlap never share an id, and the search field matches it. An exchange
recorded without one, for example by an application-provided repository, keeps a hash of the exchange as its id.

### Route rankings

Above the exchange list, a route table summarizes the retained window: summary → exchanges → profile. Each row is one
method and route, with its request count; 2xx, 3xx, 4xx, and 5xx counts; average, p50, p95, p99, and maximum duration;
and share of retained request time. **Rank by** orders the table by requests, total time, p95, slowest request, or
errors. **Exchanges** filters the list below to exactly that route's exchanges, and **Show every route** clears it.

A route is resolved exactly as [SQL Trace](database.md#rankings) attributes database time, and each row says which of
three sources it came from:

| Source        | Meaning                                                                                         |
| ------------- | ----------------------------------------------------------------------------------------------- |
| `template`    | The handler pattern Spring MVC or Spring WebFlux matched, such as `/api/orders/{id}`.            |
| `declared`    | The single best route the application declares in its mappings. Ties resolve to no template.    |
| `masked path` | No template matched, so every path segment that reads like a value is replaced with `{value}`. |

A template is shown the same way whichever source produced it, so `{id:[0-9]+}` reads `{id}` and a route never splits
into two rows, while a catch-all such as `/**` is kept as declared, so it never merges with a masked path. Two
handlers on the same method and path that differ only in their variable patterns, such as `{id:[0-9]+}` and
`{id:[a-z]+}`, therefore share one row: a pattern is never shown. Quarkus has no runtime route template, so its routes come from the declared JAX-RS mappings, matched under
`quarkus.http.root-path` and `quarkus.rest.path`; a prefix contributed only by `@ApplicationPath` is not known at
runtime, so those routes fall back to masked paths. Spring
WebFlux records the matched handler pattern with the OpenTelemetry integration, which the reactive starter includes;
without it, WebFlux routes fall back to masked paths, and the panel says so. Query strings are never part of a route.

With Spring for GraphQL, every operation is posted to one endpoint, so BootUI adds the operation graphql-java parsed to
the route, such as `/graphql (query ProductList)`, and each operation is ranked and filtered on its own. Only the
operation's type and name are kept, never its variables or selection. It needs Spring Boot's observation support,
which Actuator brings.

A masked path also masks every segment that a matching declared route marks as a parameter, even when two declarations
match equally well. Only a path that no declaration matches at all keeps segments that read like route words, so a
word-shaped value such as a user name on an undeclared path is shown as captured, exactly as the exchange list shows
it.

::: details How the figures are bounded

- Every figure covers only the retained, visible exchanges. They are diagnostic evidence for that window, not lifetime
  or service-level metrics; the Metrics panel's `http.server.requests` meter covers the application's lifetime.
- The line above the table states the evidence window: retained exchanges, the buffer size, evictions, the oldest
  retained exchange, and how many BootUI exchanges were hidden. BootUI's own buffers report their capacity and
  evictions from the same snapshot as the ranked exchanges; a value an application-managed recorder does not report,
  such as its capacity or evictions, reads as not reported.
- Percentiles are exact nearest-rank values over each route's timed exchanges, computed by the same helper as SQL Trace
  and the Live Activity KPIs. A route whose exchanges carry no duration shows no timings rather than zeros.
- The response returns the union of each criterion's top 25 routes and marks which criteria each route leads, so the
  browser shows exactly the server's list for every criterion. When more routes are retained, the panel says how many
  are not shown. Ties break on the route name in plain character order, never on buffer order or browser locale.
- A route that a link names, such as the Live Activity slowest request, is always returned and shown after the
  ranking, even when it is outside every top list.
- Rankings refresh every 30 seconds while the exchange list follows auto-refresh, because every refresh is itself a
  request the Spring buffer records before hiding it. The window line says when the rankings were computed, and
  **Refresh** or opening a route's exchanges updates both at once.
- BootUI's own exchanges stay out of the rankings while `bootui.monitoring.exclude-self` is on.

:::

`GET /bootui/api/http-exchanges/routes` serves the rankings, `?route=<id>` pins one route's row, and
`GET /bootui/api/http-exchanges?route=<id>` lists that route's exchanges. Agents read the same rankings with the `get_http_routes` MCP tool, and the command line with
`bootui http routes`.

### Copy as cURL

Row details offer **Copy as cURL**, which turns the retained metadata into a command *template*. It is not a
byte-for-byte replay, and the action shows every difference before you copy.

::: details What the command changes, and why

- Copying runs entirely in the browser. No request is sent, no capture state changes, and nothing is written back to
  the application.
- The command is shown in full before you copy it, so you can read exactly what will land on your clipboard, and still
  copy it by hand if the browser denies clipboard access.
- Query-parameter names are preserved, including repeated, empty, and encoded ones, but every value becomes a `VALUE`
  placeholder. A masked name, or a segment with no `=` at all, is dropped and reported, because an unstructured segment
  can be a bare token rather than a name.
- Only a short allowlist of headers is copied — `Accept`, `Accept-Language`, `Cache-Control`, `Content-Type`, and
  `User-Agent` — and only while their values are exposed and unmasked. Authorization, cookies, proxy credentials, API
  keys, forwarding headers, tracing headers, and unknown custom headers are omitted under every exposure mode,
  including `FULL`.
- BootUI never captures request bodies, so the command carries none. Body-carrying methods say a body may have existed
  and invite you to add your own `--data`.
- The URL, the method, and every header argument are POSIX single-quoted, so shell metacharacters captured from a
  request cannot escape their argument. Credentials embedded in a recorded URL are dropped with the rest of the
  authority userinfo.
- The path is copied exactly as recorded, so a traversal probe such as `/a/%2e%2e/admin` stays visible instead of being
  normalized into a different target, and `--globoff` keeps recorded brackets literal. `HEAD` uses `-I` so the command
  cannot hang waiting for a body.
- When the metadata has no absolute `http(s)` URL or no recognizable method, the action is deactivated and announces
  the reason instead of producing a misleading command.

:::

A shared frontend helper generates the command from the same `HttpExchangeDto` every adapter serves, so all three
stacks produce identical text for the same exchange.

## HTTP Probe

![BootUI HTTP Probe panel](../images/bootui-http-probe.webp)

The HTTP Probe panel sends local-only requests to the running application and shows the response status, headers,
duration, and body. The probe always targets the application's own loopback address, so it can never reach an external
host, and as a state-changing action it is gated by the same localhost-only safety floor as every other write.

Probe input is bounded. The method, path, request body, header count, and header name and value sizes each have a
ceiling, measured in UTF-8 bytes and checked before anything is sent:

| Input | Ceiling |
| ----- | ------- |
| Request body | 64 KiB |
| Path | 2 KiB |
| Headers | 50 entries, 256 bytes per name, 8 KiB per value, 32 KiB combined |
| Method | 32 bytes |

Exceeding a ceiling is invalid input, so it is rejected with the canonical `400` and `{"error": ...}` body on all three
stacks, and the panel shows that message instead of a result. A probe that runs and fails, through a refused connection
or a timeout, is still reported as a probe outcome, with its response body truncated at the response byte budget.

::: details Resolving the port on Quarkus
Quarkus has no config key that always equals the bound port, so the adapter selects `quarkus.http.test-port` or
`quarkus.http.port` by launch mode. A random `=0` port still resolves, because Quarkus rewrites the property to the
actual port once the server is up.
:::

## Code Inventory

The Code Inventory panel answers the question an agent or a developer asks right after an edit: **did the code I changed
actually run?** It needs the [BootUI agent](java-agent.md)'s `inventory` sensor, on by default once the agent is
attached; without it the panel is unavailable with the Java Agent panel's reason and a link to it, and every read and
`get_code_inventory` answer `available: false` with that reason. It is view-only on Spring MVC, Spring WebFlux, and
Quarkus.

Its header states the run and **N of M application methods executed**, where M counts the methods the agent tracked in
this run. Three tabs follow:

- **Changed since the previous run**, first when a previous run of the application was kept in this JVM: the methods
  whose code changed, and those added, since the previous DevTools restart or Quarkus live reload, without git. Each says
  whether it executed in this run, with the first request and route that ran it, which opens that request in Live
  Activity. Methods not executed come first; removed methods are a count.
- **Application code**: the claimed packages, then their classes and methods, with executed, never-executed, and
  not-tracked counts, filterable to *never executed*.
- **Dependencies**: the application's declared dependencies (the Vulnerabilities panel's inventory), matched by
  `groupId:artifactId` from each jar's Maven metadata, else by its file name, to the jars the agent saw define classes:
  classes loaded in this run, whether the first loaded at startup or later, and the route of the request that loaded it.
  A declared jar with no class loaded is **not loaded in this run**, never "unused": a route this run did not exercise
  may load it. BootUI's own jars and the agent are left out.

How it works:

- At each run, BootUI scans the application's own class files in the claimed packages, off the request path, in the
  directories and jars the application class loader finds them in (test roots excluded), and hashes each method over
  its resolved instructions: constant-pool operands by their symbolic value, `invokedynamic` through its bootstrap
  arguments, branch targets as instruction positions, its exception table, and its runtime-visible annotations, never
  line numbers or other debug attributes. The class's own annotations (a `@RequestMapping` prefix) count toward every
  method, its fields' annotations (`@Value`, `@Autowired`) toward its constructors, and abstract methods (a repository's
  `@Query`) are listed too. A recompilation with other debug settings, or another constant-pool order, hashes the same,
  and so does a lambda or anonymous class the compiler only renumbered because another was added before it; a changed
  literal, method reference, or `@GetMapping` value does not. The scan is bounded by
  `bootui.code-inventory.max-classes` (20,000) and `bootui.code-inventory.scan-timeout` (30 seconds), finding its roots
  included; past either it is partial and says so. A class file unchanged since the previous scan is not parsed again,
  nor a class-path jar opened again, and a class file that cannot be parsed, or a class directory or jar that cannot be
  read, makes the scan partial (failed when nothing could be read), so its methods are never reported as removed. While the scan runs,
  or after it failed, the **Changed** tab says so rather than "no previous run", and nothing is compared.
- The previous run's method hashes are kept across restarts in the same JVM, at most 1 MB a run (12 bytes a method),
  per application, beside the run summaries of [run comparison](overview.md). Only classes both scans covered are
  compared. When another application claimed the agent in between, the panel says the runs are mixed. A full JVM
  restart, or BootUI itself loaded by the restart class loader, keeps no previous run, which the panel says too.
- Which methods executed comes from the agent's hit flags, exactly, and the first request, route, and time from its
  records, which a full ring may drop (counted, and said).
- The first request and route belong to [HTTP Exchanges](#http-exchanges): while that panel is disabled, every read,
  `get_code_inventory`, and Runtime Insights' `changed-code-not-executed` leave them out, with the reason, and say only
  which methods executed and when.
- A method counts as executed or never executed only when the agent instrumented its class in this run, or when its
  class has not loaded in this run at all (after a DevTools restart, a class the new class loader has not loaded yet
  has not run). Any other method on disk is **not tracked**, with its reason (static initializer, abstract method,
  `$`-prefixed name, synthetic class, a class the agent never instruments by name such as a generated proxy, transform
  failed, over the agent's method limit, or **ran before instrumentation** when its class loaded before the agent
  instrumented it), and never counted as executed or never executed. A method that executed but has no class file in
  the scanned roots, as in a generated class, is counted apart as **generated**. Methods called before BootUI claimed
  the agent are not seen.

API, all `GET`, paged with `offset` and `limit` where they list:

| Path | Returns |
| --- | --- |
| `/bootui/api/code-inventory` | The run, the scan, the method counts, the change counts, the dependency counts, and the limitations |
| `/bootui/api/code-inventory/changes` | The changed and added methods, not executed first |
| `/bootui/api/code-inventory/methods` | Methods filtered by `package`, `class`, and `status` (`executed`, `never-executed`, `not-tracked`, `generated`), with package and class counts |
| `/bootui/api/code-inventory/dependencies` | The dependency use, declared jars not loaded first, filtered by `status` |

`get_code_inventory` and `bootui code inventory` return the counts first, then at most `limit` (25) rows of `query`:
`changed` (the default), `never-executed`, `not-tracked`, `executed`, `dependencies`, or a package or class. The
`verify_after_change` MCP prompt starts from it, and Runtime Insights reports a changed method no request executed as
`changed-code-not-executed`.

## Code Paths

The Code Paths panel names the application methods a route spends its time in: it turns "handler 80 ms" into
"`SlowPricingService.quote` 55 ms", across the route's warm requests, without tracing, spans, or a profiler session. It
needs the [BootUI agent](java-agent.md)'s `code-paths` sensor, on by default once the agent is attached; without it the
panel is unavailable with the Java Agent panel's reason and a link to it, and every read and `get_code_paths` answer
`available: false` with that reason. It is view-only on Spring MVC, Spring WebFlux, and Quarkus.

- **Routes**, ranked by their warm median: each route's warm requests, first recorded request, median and 95th
  percentile, and its top methods by self time. A route marked **assembly only** has a handler that ran on an event
  loop, returned a reactive or asynchronous result, or BootUI could not tell where its work ran, so its tree times the
  handler's assembly, not the work that ran later or elsewhere.
- **The selected route's tree** as an indented table: method, calls per request, total and self time per request, an
  approximate median (≈) per request that reached it, and its share of the handler's time in application methods (of the request's own time when no handler phase is known,
  as on WebFlux), with a share bar. Work an executor ran for the request is marked **async** and shown apart under the
  method that submitted it, never subtracted from it; a parent's methods past the tree's node budget are one **Other**
  node.
- **Calls under methods**: under each method, its SQL statements, REST client calls, cache accesses, and AI calls per
  request, with their time: the calls recorded while it was the innermost instrumented method open on their thread. A
  statement Hibernate flushes at commit runs after the `@Transactional` method returned, in the transaction interceptor
  around it, so it shows under the method that called the `@Transactional` one. Calls issued while no instrumented
  method was open, as in a filter or while the response is written, and calls recorded on another thread, as a
  streaming AI call's, show under no method; the limitations count each apart and say why.
- **Selecting a method** shows its callers within the tree and every route whose tree reaches it.
- **Beans at runtime**, a tab beside the routes: the calls between beans observed in this run's route trees, with their
  counts, beside the dependencies the beans declare, as the Beans panel lists them. A filter keeps only the declared
  dependencies **not called in this run**, which is all a run can say: never "unused", since a path no request took or
  work outside a request may still call it. Calls come from each route's warm requests and its first request. A
  dependency is not called only when a call would have been observed: both beans' classes are instrumented and none of
  their methods was adaptively excluded; otherwise it is **not observable**, with why, as with a repository whose class
  a framework generates, and never counted as not called.
- **Excluded methods**: the methods the sensor stopped timing in this run, called more than 50,000 times a second under
  2 µs each, whose time stays in their callers.

How it works:

- The agent times the public and protected methods of the application's bean classes (Spring beans, ArC beans) and
  builds a per-thread fragment of each request's call tree; the engine merges a request's fragments into its request
  tree, then, about two seconds after its last fragment, merges the settled tree into its **route tree**: per node, the
  requests that reached it, its calls, total and self time, and a log2 histogram of the time each request spent in it
  with its least and most, from which an approximate (≈) median and 95th percentile are read: interpolated within a
  bucket and clamped to that least and most. Each route's first recorded request, the first whose tree settled, is kept
  apart, as its time and request id only.
- Route trees are keyed by the routes and outcomes [HTTP Exchanges](#http-exchanges) owns: while that panel is
  disabled, the panel, every read, `get_code_paths`, and the handler split are unavailable with that reason.
- A node is its caller, its method, and the request phase it was entered in: a helper called by the handler and again
  while the response is written is two nodes, so its response-write time never counts as handler time, whichever
  request reached it first.
- A request's tree joins its route tree once the request's exchange is recorded. A slow reactive response's tree can
  settle before its response completes: it waits for the exchange, looked up again with a back-off, up to the executor
  handoff window (five minutes), and only then is kept without a route.
- Route trees are bounded: 2,000 nodes a route with one **Other** node per parent and phase past the budget, 100,000
  nodes and 500 routes across the run. A call that finds no node left keeps its time in its caller's self time. Route
  trees cover the current run only: a DevTools restart or Quarkus live reload starts new ones.
- A method's self time is its time outside its recorded child methods, so a JDK, framework, or library method shows
  only as its caller's self time, and so do the SQL, REST client, cache, and AI calls it waited on.
- **Call-site stamps.** Where the SQL, REST client, cache, and AI recorders already capture their application call site,
  on the thread that issued the call, they also take the agent's stamp of the innermost instrumented method open there:
  its fragment and node, packed into one number. When the request's tree settles, each stamped call is counted under the
  exact node that issued it, and the route tree sums them per node. A call recorded on another thread than the one that
  issued it carries no stamp and shows under no method: a streaming AI call, one received over OTLP, or a WebClient call
  subscribed on another thread (Spring's WebClient filter takes the stamp where the exchange is subscribed, which is the
  issuing thread when the caller blocks or subscribes in place; Quarkus's REST client, where its request filter runs). A
  call issued on the request's thread while no instrumented method was open, as in a filter, while the response is
  written, or in a commit after the outermost instrumented method returned, is counted apart. An executor's handoff
  carries the submitting method's stamp too, so its work shows under that method, and the thread's previous submitter
  comes back when the work is over, as for work a caller-runs executor ran in place.
- **Assembly only**: a handler that ran on an event loop, returned a reactive or asynchronous result, or whose work
  BootUI could not place. That is a Spring MVC handler that started async processing, every Spring WebFlux handler (the
  fragment covers the request's subscription on the assembling thread, up to its first asynchronous boundary; the
  WebFlux configuration marks every tree once, not each request), and a Quarkus resource method that runs on the event
  loop or returns `Uni`, `Multi`, `CompletionStage`, or a publisher. When Quarkus cannot tell which method ran, the
  request is marked assembly only rather than guessed.
- **Handler split.** With the sensor active, Runtime Insights' `route-time-breakdown` splits a route's **Handler, other
  work** into its top five handler-phase methods by own time, the rest as **Other handler time**, for every route whose
  tree is not assembly only. A method's own time is its self time minus the recorded calls stamped to it, which the
  breakdown already names as SQL, REST client, or AI time. Each method takes the share its own time per request has of
  the handler's other work (or of the handler's methods, when they add up to more), so the parts never exceed it. A
  recorded call without a stamp stays in the own time of the method that waited on it, and the observation says how
  many there were. Only handler-phase nodes count. Each method keeps its own row, labelled `Class.method`, with its
  parameter types when two overloads would share a label, and its package when two classes share a name.
- **Issuing method.** Runtime Insights' `repeated-selects` names the method that issued the repeated statements, such
  as a service method looping over a repository, beside SQL Trace's call site: the instrumented method that was
  innermost on the statements' thread, since a repository's own methods are not instrumented.

API, all `GET`:

| Path | Returns |
| --- | --- |
| `/bootui/api/code-paths` | The sensor's status, the routes with a tree ranked by warm median with their top methods, the excluded methods, and the limitations |
| `/bootui/api/code-paths/route?route=` | One route's tree, paged by `depth` (8 by default, at most 33), `offset`, and `limit` (200, at most 500), with each listed method's callers and the routes that reach it, and the route's exemplar request ids |
| `/bootui/api/code-paths/requests/{requestId}` | One request's tree while the run keeps it: its recent requests and each route's slowest and latest failed |
| `/bootui/api/code-paths/beans` | Beans at runtime: the observed calls between beans and the declared dependencies, observed first, with how many declared dependencies on a timed bean were not called in this run |

Each tree node carries `calls`: per kind (`SQL`, `REST`, `CACHE`, `AI`), the calls it issued per request and their
time, `null` for cache accesses, which have none. The runtime model gains an observed `INVOKES` edge between two beans
per call pair, with its count; change impact never walks it, since a call observed in one run is evidence of the paths
its requests took, not of what a change can reach. Change impact by method reads each route's own tree instead: every
route counts, per method, the requests whose tree ran it, at any depth and before its tree folds methods into Other
nodes, the first request and executor work included, at most 4,096 methods per route and 200,000 per run; a fragment
that arrives after its request's tree was merged amends its route rather than opening a second tree, and a route whose
trees may miss methods says so ([Change impact](overview.md#runtime-insights)).

`get_code_paths` and `bootui code paths` return at most `limit` (10) routes matching `query` (a route, or part of a
route or of a method), slowest warm median first, each with its top methods; for a single route, its method nodes with
the most self time, each with its calls. The `diagnose_runtime_issue` MCP prompt points to it for a slow route's handler.
