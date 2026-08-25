# Overview


The Overview panel is the BootUI landing page and acts as a guided "understand your app in minutes" dashboard rather than
a static summary. It opens with the standard panel header and a link to the running application's homepage, matching the
calm, instrument-like layout of every other panel.

It is built around an on-demand security & health scoring dashboard. Before any scan has run, the overall-score card
stays honest — it shows how many scanners have been scored and a prompt to run them rather than an empty gauge. Once at
least one scanner has scored, an overall score out of 100 summarizes the application's posture, with a qualitative band
(Good at 80+, Needs attention at 50+, At risk below 50) and a breakdown of how much each scanner deducted from a perfect
score. A single "Run all scanners" button triggers every available scanner, or each scanner card can be run individually.
After a run-all, a dismissible tip points to the MCP Server panel, since enabling the BootUI MCP Server lets an AI agent
read these same scan results and fix the findings for you.

Each scanner card shows its own 0–100 score, status, and severity counts. The severity-based scanners are Architecture, Memory,
REST API, Spring, Database, Hibernate, Security, Pentesting, and Vulnerabilities; scores start at 100
and subtract a fixed weighted
penalty per finding (critical 25, high 10, medium 3, low 1), so a clean scan stays at 100. The GitHub card is not a
severity scanner: it connects to the local repository and, only when the credential is connected and authenticated,
contributes a score derived from open security alerts. The overall score is the mean of the scanners that were actually
scored, and only scanners whose panels are available for the current application are shown, so the dashboard degrades
gracefully when optional infrastructure is missing.

On Quarkus the Overview panel is fully available. Its scoring *dashboard* is rendered entirely in the browser:
the shell aggregates each advisor's own scan/report endpoints (only those whose panels are available on Quarkus
contribute) and computes the same combined score, so no backend dashboard service is involved. The shared shell
chrome around every panel — the header application name, framework and version (for example "Quarkus 3.33"), Java
version, active profiles, and the active/disabled status — is populated by the same framework-neutral
`GET /bootui/api/overview` endpoint that both adapters expose for the shell.

On Spring Boot WebFlux the Overview panel is fully available and identical in behavior — the dashboard aggregates
whichever scanner panels are available on the reactive adapter (see [docs/WEBFLUX-SUPPORT.md](../WEBFLUX-SUPPORT.md) for
the current per-panel list), and the shell chrome is populated by the same `GET /bootui/api/overview` endpoint.

![BootUI Overview panel](../images/bootui-overview.webp)

## Live Activity

The Live Activity panel is the diagnostics "home base": a single reverse-chronological stream of everything the
application just did, plus a per-request profiler for drilling into any single request. It adds no new instrumentation
for six of its nine signals — instead it reuses BootUI's existing in-memory signal buffers by calling the same
controllers that back the HTTP Exchanges, SQL Trace, REST Client, Exceptions, Security Logs, and Email panels, so
every value is already masked, self-filtered, and bounded exactly as those panels are. The seventh signal, cache
accesses, is captured by a small dedicated recorder (Spring servlet and WebFlux adapters only — see below) that only
ever stores a hashed cache key, never a raw key or value. The eighth signal, scheduled-task runs, captures each
`@Scheduled` method *execution* (start, success, failure, duration) on both adapters: Spring taps its own scheduling
observability hook (no extra proxying), Quarkus observes the CDI `SuccessfulExecution`/`FailedExecution` events its
scheduler always fires — feeding a bounded in-memory buffer the same way the other sources do. The ninth signal,
messaging activity (Kafka and RabbitMQ on both adapters, plus JMS on Spring), is described below.

The stream merges nine signal types into one feed: requests (`REQUEST`), SQL statements (`SQL`), exceptions
(`EXCEPTION`), security events (`SECURITY`), scheduled-task runs (`SCHEDULED`), messaging producer/consumer activity
(`MESSAGING`), and captured emails (`MAIL`) on both adapters, plus — on the Spring servlet and WebFlux adapters only —
outbound REST client calls (`REST_CLIENT`) and cache accesses (`CACHE`). Each row carries a timestamp, a type icon, a
color-coded severity
(`OK`, `SLOW`, `WARN`, `ERROR`), a one-line summary, and a duration where applicable; failed rows are highlighted and
slow requests are tinted on a graduated yellow-to-red heat scale (crossing 100, 200, 500, and 1000 ms) with a matching
latency badge so you can see at a glance *how* slow a request was. A request whose correlated SQL contains a suspected
N+1 access pattern carries a red **N+1** badge right in the row — the same detection the per-request profiler flags in
detail, computed with the identical threshold/logic so the two views never disagree — so a developer scanning the feed
can spot a suspect request without opening every drawer. A `CACHE` row summarizes the operation and cache name (e.g.
"MISS orders"), with a `WARN` severity for a miss and `OK` for every other operation, and its detail shows only a short
hashed key (`key a1b2c3…`) — the raw key or value is never captured, even under full value exposure. Adjacent identical
entries are collapsed with an
occurrence count to cut noise, and the feed can be narrowed
by type, severity, a free-text needle (path, status, SQL, or exception class), and an **errors-only** quick toggle — the
chosen filters are persisted in the browser so they survive a reload. A small **requests-over-time** sparkline above the
table makes spikes and error bursts (drawn in red) visible at a glance. A KPI strip across the top summarises requests per
minute, error rate, p50/p95 latency, SQL rate, (Spring servlet/WebFlux only) outbound REST-call error rate/p95 latency,
the slowest recent endpoint, active exception count, health status, heap
usage, (Spring servlet/WebFlux only) the cache hit ratio, and scheduled-task failure count, computed from the same buffers (sub-millisecond SQL is shown as `<1 ms`).
Several KPI cards are themselves
launchpads: the outbound-errors card opens **REST Client**, the slowest-endpoint card opens **HTTP Exchanges**
pre-filtered to that endpoint, while the
active-exceptions, health, heap-usage, cache-hit-ratio, and scheduled-failures cards jump to the **Exceptions**, **Health**, **Heap Dump**,
**Cache**, and **Scheduled Tasks** panels
respectively. Because the merged feed is genuinely event-driven, it refreshes over **Server-Sent Events** instead of
fixed-interval polling: the browser subscribes to
`/bootui/api/activity/stream` and re-fetches whenever any source signals a change (a new request, SQL statement, REST
client call, exception, security event, cache access, scheduled-task run, messaging event, or captured email), and the
feed can be paused and resumed so a row you are inspecting does not scroll away.
When the feed is unfiltered, correlated signals are **nested chronologically under the request that produced them**: the
SQL statements, REST client calls, exceptions, security events, cache accesses, and emails that BootUI can pin precisely
to a request — by trace id, by the
request's serving thread, or by request method and path — are folded into a collapsible group beneath that request row
(expanded by default), so one click reveals exactly what a single request did, in order. Requests that triggered a
security event are flagged as **authenticated** — a lock icon plus a gray pill naming the caller's principal — so a
secured call and who made it are obvious without opening the profiler, and the nested child rows are shaded a distinct
gray so they read clearly as belonging to the request above them. Signals that cannot be tied to a
request stay top-level, and applying any filter or free-text search flattens the feed again so the query spans every
signal.

Every row is also a launchpad: clicking anywhere on a request row opens its profiler, and each row carries a deep link
that jumps to the dedicated panel with the originating record pre-filtered — requests open in **HTTP Exchanges**, SQL in
**SQL Trace**, REST client calls in **REST Client**, exceptions in **Exceptions**, cache accesses in **Cache**,
scheduled-task runs in **Scheduled Tasks**, and captured emails open the very same message's detail drawer in **Email**
(the `MAIL` entry's id is the captured message's own id, so the link opens that exact email rather than only a
filtered list). REST client calls nest under their correlated request in the stream
using the same trace-id-first, serving-thread-second join described below for SQL, exactly like cache accesses and
emails — but, unlike SQL, exceptions, and security events, none of REST client calls, cache accesses, or scheduled-task
runs are yet part of the per-request profiler drawer's correlated timeline or **Copy profile** export; that
correlation, and the N+1-style "chatty" badge, stay scoped to the REST Client panel itself for now. The
per-request profiler drawer is a Symfony-style view that correlates
that single request's signals using a tiered join that degrades gracefully and never fabricates data: the distributed
trace is matched by trace id, exceptions are matched by request method, path, and time window — and, when the
request's serving thread is uniquely known, further disambiguated by that thread so a concurrent identical request
cannot steal the occurrence — security audit events are
matched by time window and the request principal (so an `AUTHENTICATION_SUCCESS` or `AUTHORIZATION_FAILURE` raised while
serving a secured endpoint is linked to that very request) — and, like SQL, are pinned **exactly to the request's
serving thread** when BootUI captured the audit event on it, so two concurrent requests sharing a principal no longer
trade security events; an event proven to have fired on another thread is excluded and an on-thread one is badged
**exact**. SQL is matched
**exactly by trace id** when Micrometer Tracing is present (BootUI threads the active `traceId` from the SLF4J MDC onto
each captured statement). When no trace id is available — the common local-dev case — SQL is still matched **exactly by
the request's serving thread** within its handling window: a servlet request runs start-to-finish on one worker thread
that serves only one request at a time, so statements on that thread are unambiguously its own. Only when the serving
thread cannot be uniquely identified (for example two genuinely concurrent identical requests, or SQL run on an async
thread) does SQL fall back to a time-window heuristic, which is then clearly labelled **approximate** in the drawer;
identical repeated `SELECT`s above
`bootui.activity.n-plus-one-threshold` are flagged as a potential N+1, and each flagged group lists the distinct call
site(s) in your own application code that issued it — the class, method, and line captured by SQL Trace's call-site
capture (`bootui.sql-trace.capture-call-site`, on by default) — so you know exactly which repository or service method
to go fix. The drawer also shows the request's timing
breakdown (time spent in SQL versus the rest), its auth/principal context, and the trace span list, can be dismissed with
the **Escape** key (with focus trapped inside while open), and offers a **Copy profile** action that exports the
already-masked correlated timeline (request + SQL + exceptions + security events, including any flagged N+1 call sites)
as plain text to paste straight into a bug report.

The panel inherits BootUI's full safety model (loopback filter, Host allow-list, cross-site write defenses, value
masking); its reads are read-only, and its one state-changing action (switching to a database, described below) is
confirmation-gated and blocked like any other action when the app or panel is read-only. The stream is capped by
`bootui.activity.max-entries`, the slow-request threshold is `bootui.activity.request-slow-threshold-ms`, and individual
sources can be turned off through their existing `bootui.panels.*` toggles (a disabled source simply drops out of the
stream).

When Kafka support is present, BootUI captures producer/consumer activity into the stream as `MESSAGING` entries. On
Spring, it wraps every application-owned `KafkaTemplate` (a `ProducerListener`) and `@KafkaListener` container factory
(a `RecordInterceptor`) — composing with, never replacing, any listener/interceptor the application already configured,
exactly like `HttpExchangesController`'s repository wrapper. On Quarkus, it hooks SmallRye Reactive Messaging's Kafka
interceptors. Each entry records topic, partition, offset (for consumed records), a hash of the key, direction (`→`/`←`
for produce/consume), success/failure, and — for consumed records — the consumer group id, a listener identifier, and
processing duration (a producer send's duration is not exposed by either framework's callback, so it is not tracked).
That listener identifier is intentionally framework-specific: on Spring it is currently the **listener container
factory bean name** (the per-`@KafkaListener` id is not exposed at the factory-wide interception point), while on
Quarkus it is the channel name. **The message value/payload is never captured** — only metadata — since a Kafka payload
is an arbitrary, potentially large and sensitive application object with no generic masking strategy. Raw exception
messages are not retained either; failed operations carry only generic failure text. Kafka entries are top-level in the
feed today (not yet nested under a correlated request). Capture is on by default whenever the relevant Kafka integration
is present and the panel is enabled, and can be tuned or disabled entirely via `bootui.kafka.enabled`,
`bootui.kafka.capture-key`, `bootui.kafka.max-entries`, and `bootui.kafka.max-key-length` — see `docs/PROPERTIES.md`.

When Spring JMS support is present (`spring-jms` and `jakarta.jms` on the classpath), BootUI also captures JMS producer and consumer
activity into the same `MESSAGING` stream. Every `JmsTemplate` bean is wrapped via a CGLIB proxy that intercepts
`send`/`convertAndSend` calls; every `AbstractJmsListenerContainerFactory` bean is similarly proxied so that each
container it creates has its message listener wrapped by a matching plain or session-aware capture adapter. Both the
proxy and adapters compose with the application's existing converters, callbacks, and error handlers without replacing
them or changing the listener dispatch interface. Direct `JMSContext`, `MessageProducer`, or `MessageConsumer` usage is
outside this capture seam, matching Kafka and RabbitMQ's framework-integration-level instrumentation.
Each entry records a sanitized JMS destination name (only explicit names and standard `Queue`/`Topic` accessors are
trusted), direction, success/failure, and duration. When a `MessageCreator` or `MessagePostProcessor` exposes the
provider-assigned JMS message ID, BootUI retains only its one-way hash. **The message payload, arbitrary
headers/properties, raw message ID, and exception message are never captured.** JMS uses its own framework-neutral
`JmsActivityRecorder`, bounded buffer, and `MESSAGING` mapper, so JMS traffic cannot evict Kafka panel history and either
transport can be disabled independently. Tune it with `bootui.jms.enabled`, `bootui.jms.capture-message-id`,
`bootui.jms.max-entries`, and `bootui.jms.max-message-id-length`. JMS capture is available on Spring MVC and WebFlux;
no Quarkus JMS equivalent is claimed.

By default the stream is in-memory only, so history is lost on a restart and the feed can only show as far back as the
small buffers behind it reach. Setting `bootui.activity.persistence.enabled=true` additionally buffers
captured entries and flushes them to a SQL database over direct JDBC every `bootui.activity.persistence.flush-interval`
(5 seconds by default), so history survives a restart and the dashboard can page back much further. The backing table
(`bootui.activity.persistence.table-name`, default `bootui_activity`) is created automatically the first time it is
needed, and several BootUI instances — for example several replicas of the same application — can safely point at the
same table: each instance tags its own rows with an `instanceId` (defaulting to the `HOSTNAME` environment variable) and
never reads or prunes another instance's rows. Recently captured entries are visible in the dashboard immediately, even
before they have been flushed, because reads always merge the in-memory buffer with the durable store; if a flush ever
fails, its entries are put back in the buffer rather than lost, and are retried on the next flush. Once persistence is
confirmed on, the panel gains a **Load older** button beneath the stream that pages further back into history, and the
type/severity/free-text filters are additionally pushed to the database as a real query instead of only filtering the
entries already on screen; a small "· persisted history" note next to the panel subtitle confirms durable storage is
active. By default (persistence off) none of this changes anything: no extra bean, thread, or connection is created, and
the feed behaves exactly as before. See `docs/PROPERTIES.md` for the full list of `bootui.activity.persistence.*`
properties, including how to point at a small dedicated connection instead of reusing the host application's own
`DataSource`.

Turning persistence on does not require editing configuration or restarting the app. Whenever it is not yet active, a
"Currently saving N events in memory" tip appears next to the panel title alongside a **Use a database** button; opening
it reveals setup documentation and, if the application already has a `DataSource` bean, a **Use the existing
datasource** action. That action is confirmation-gated exactly like other destructive/state-changing actions elsewhere
in BootUI (Flyway migrate/clean, Liquibase update, Cache clear): once confirmed, it checks the current datasource,
creates the backing table if it does not already exist, and hot-switches the running instance from the in-memory buffer
to durable storage — with no dropped entries and no restart. This switch is **runtime-only**: it changes nothing on
disk, so a later restart reverts to the in-memory default unless `bootui.activity.persistence.enabled=true` is also set
in configuration. If no `DataSource` is present, the button instead links straight to the setup documentation for
configuring one (a dedicated one, just for Live Activity, or reusing an existing one).

On Quarkus the panel merges eight signals: HTTP requests (from the same Vert.x-fed ring buffer as HTTP Exchanges), SQL
trace, exceptions, security events, scheduled-task runs, messaging activity, captured emails, and outbound
REST Client Reactive calls, alongside JVM heap KPIs. Cache accesses remain Spring-servlet/WebFlux-only today (see their
own section above), so only that slot stays empty/unavailable on Quarkus. SQL trace
contributes only when a JDBC datasource is
configured (the recorder is gated on Agroal); when none is present those entries drop out and the report carries a clear
note. Signal-to-request correlation works by **trace id**: Spring's thread-per-request anchor is unportable on the Vert.x
event loop (a thread does not map to a single request), so when `quarkus-opentelemetry` is present the adapter stamps the
active server span's trace id at each capture point — the HTTP filter, REST Client recorder, SQL recorder, exception store,
and CDI security-event observer — and the engine nests REST Client, SQL, exception, security, and email entries under the
request sharing that trace id, exactly as on Spring (scheduled-task runs and messaging activity always stay top-level); the
OpenTelemetry context propagates across the event-loop→worker hop, so the same trace id is available even for
blocking JDBC on a worker thread or a security event fired from a CDI observer. A request whose trace id uniquely matches
a correlated security event is flagged **authenticated** exactly like Spring, naming the audit event's principal; Quarkus's
own security layer authenticating the caller (surfaced directly on the captured HTTP exchange) takes precedence over a
correlated audit event when both are known. With OpenTelemetry absent, entries carry no trace id and the feed renders
flat. The per-request **profiler** drawer (the Symfony-style drill-down, `GET /bootui/api/activity/{id}`) is available on
Quarkus too, but in a reduced, trace-id-only form: when the request carries a trace id it correlates SQL, exceptions, and
security events that share that exact trace id (`sqlCorrelationApproximate: false`, since trace-id matching is exact); it
does **not** attempt Spring's time-window/thread-based tiers for requests without a trace id, since those lean on
serving-thread identity that the Vert.x event-loop model has no equivalent for. Without `quarkus-opentelemetry` present —
or for a request that has no trace id captured — the drawer honestly reports itself unavailable with a clear reason
rather than fabricating a partial profile (see `docs/QUARKUS-SUPPORT.md` for the detailed reasoning). N+1 detection, its
list-level row badge, and call-site capture are computed by the same shared engine code Spring uses (the correlation
tier above only changes *which* SQL gets grouped, never how a group is flagged or its call sites collected), so a
Quarkus request that resolves any SQL correlation gets byte-identical N+1 flagging to Spring.

The optional durable persistence backend described above is available on Quarkus too, with an identical config surface,
wire contract, and shared engine machinery (`ActivityStore`/`BufferedActivityStore`/`JdbcActivityStore`). A dedicated
`QuarkusActivityCapture` CDI bean owns the capture-poller lifecycle (starting it at `@Observes StartupEvent` and
stopping it, with a final flush, at `@Observes ShutdownEvent`) where the Spring adapter instead wires the same
poller/coordinator inline in its controller. One narrower, pre-existing gap carries over: because Quarkus's baseline
feed has no server-side `type`/`severity`/`since` filtering to begin with (see above), those filters only take effect
on Quarkus once persistence is switched on. The runtime "Use the existing datasource" switch described above works
identically on Quarkus: the same engine-level `ActivitySwitchService` backs a thin JAX-RS mirror of Spring's endpoint,
so the tip, button, and confirmation flow behave the same regardless of adapter.

On Spring Boot WebFlux the panel is available too, merging all nine signals like the servlet adapter does. Seven of
them needed no new *capture* pipeline at all: HTTP requests, SQL trace, exceptions, security events, scheduled-task
runs, messaging activity, and captured emails are each already reactive-safe — their engine beans live
in the shared `BootUiEngineConfiguration` both the servlet and reactive auto-configurations import — so the WebFlux
port is purely a merge over those existing sources (see their own sections below). The remaining two needed one each.
Cache accesses reuse the exact same `CacheActivityRecorder`/`CacheActivityCacheManagerBeanPostProcessor` pair the
servlet adapter uses — both wired once in that same shared configuration so servlet and WebFlux behave identically —
rather than a WebFlux-specific implementation. Outbound REST/WebClient calls work the same way: the shared
`RestClientTraceRecorder` + Spring Boot client customizers live in that common engine wiring too, so a WebFlux
application's own `WebClient` calls are captured and merged here as well. Correlation is **trace-id only** for cache
and REST-client entries — the same shared-engine rule SQL/exceptions/security already use on WebFlux and Quarkus —
because Reactor Netty has no thread-per-request model to correlate by (a request isn't served start-to-finish on one
dedicated worker thread), so the servlet adapter's thread-based/time-window correlation tiers, including its
serving-thread fallback for `CACHE` (the same one it uses for `SQL`), do not apply. Both Spring adapters stamp the
server-created trace id onto Actuator's trace-id-less HTTP exchange model through the same bounded
`HttpExchangeTraceRegistry`: MVC reads the SLF4J MDC value already used by its SQL/cache/REST capture, while WebFlux
reads the active OpenTelemetry span across Reactor hops. When a shared trace id is present on both sides, matching
signals nest under the request exactly as on Quarkus; without one, every signal
still appears in the feed, just flat/top-level rather than nested per-request. The per-request **profiler** drawer is
available too, in the same reduced, trace-id-only form as Quarkus: it correlates by exact trace id when the request
has one, and honestly reports itself unavailable rather than fabricating a partial profile when it does not. N+1
detection, its row badge, and call-site capture are computed by the same shared engine code as every other adapter,
so a WebFlux request that resolves a trace-id correlation gets byte-identical flagging to Spring MVC and Quarkus. The
optional durable persistence backend and the "Use the existing datasource" hot-switch described above work
identically on WebFlux too, over the same shared engine machinery. The dedicated REST Client panel is also available
on WebFlux (see [docs/WEBFLUX-SUPPORT.md](../WEBFLUX-SUPPORT.md) for the full detail), delivering the same
pause/resume controls, retained-call table, and "Most frequent calls" grouping over `WebClient` calls captured via
the reactive adapter.

### Live flow (service map)

Live Activity opens with a compact **Live flow** service map between the KPI summary and the feed controls. Its viewport
adapts to the graph's content (up to a bounded scrolling height for dense maps) and centres on **This application**.
Developers who want a denser event-first view can minimize the map; that preference is remembered in the browser, while
the feed remains visible and usable underneath. Where the feed answers "what just happened, in order?", the map answers
"what does this application actually talk to?" — the running application at the centre, a single generic **Local HTTP
clients** lane feeding into it, and one node per outbound dependency, grouped by safe identity. It is served by
`GET /bootui/api/activity/service-map` on Spring MVC, Spring WebFlux, and Quarkus.

Nothing new is instrumented and nothing is contacted. The map is assembled entirely from bounded buffers other panels
already fill: completed inbound requests (HTTP Exchanges), outbound HTTP calls grouped to a `scheme://host[:port]`
origin (REST Client), configured JDBC pools with a target that the map independently strips of JDBC user-info and
driver parameters even under full value exposure, retained SQL
statements (SQL Trace), cache accesses grouped by cache manager/cache name (Cache — see below), Kafka **producer**
topics, and RabbitMQ **publisher** exchange/routing destinations. Opening the
map performs no network call, probe, DNS lookup, connection attempt, or scan. Consumed Kafka records and consumed AMQP
messages are inbound work this application performs, so they are deliberately never drawn as outbound dependencies.

**Cache is a first-class dependency on Spring MVC and Spring WebFlux when at least one `CacheManager` was successfully
instrumented.** The same recorder behind the Cache panel and Live Activity's `CACHE` entries feeds a `CACHE`-protocol
node here too, filterable and iconed like every other dependency, grouped by cache manager and cache name — never the
accessed key or value — and showing the same `HIT`/`MISS`/`PUT`/`EVICT`/`CLEAR` operations. An enabled recorder without
an instrumented manager does not advertise a source that cannot receive runtime evidence. Selecting a cache node
deep-links into the Cache panel. A cache `MISS` is a normal, expected outcome, never a retained failure. Quarkus
honestly reports no cache dependency at all here: `quarkus-cache`'s built-in interceptors leave no comparable
interception seam, the same reason its Live Activity feed has no `CACHE` entries either.

The map separates what is **configured** from what has been **observed**, and never collapses the two: a declared
datasource with no traffic is drawn with a dashed outline and reads "configured, no recent evidence" rather than
disappearing, and an observed HTTP origin is never presented as a declared dependency. Selecting any node — by click or
by keyboard — opens an evidence panel with the retained interaction and failure counts, the distinct-operation count
where the source can report one honestly, when it was last seen, an explicit note about what that node does and does not
prove, the small tail of recent interactions, and a deep link into the panel the evidence came from. A retained failure
is reported as debugging evidence, never as a health check of the remote system, because BootUI has not contacted it.

Statement evidence is only attributed to a pool when attribution is unambiguous — exactly one configured pool and
exactly one traced datasource whose name matches that pool. With multiple or unmatched sources, or with no pool metadata
at all, the statements are summarized on their own **SQL statements** node, the pools stay configured-only, and the
reason is stated as a warning. BootUI does not invent a statement-to-pool relationship it cannot prove.

Identity is deliberately subtractive. An HTTP dependency is only ever an origin: user-info credentials, paths, query
strings, and fragments are dropped before anything is serialized, and a call that cannot be reduced to a safe origin is
left off the map with a visible warning rather than shown under a guessed identity. JDBC targets reuse the existing
masking and independently lose authority/Oracle credentials plus any driver parameter tail. Complete sanitized
identities drive grouping and stable opaque ids; only display labels are truncated, so shared long prefixes do not
collapse distinct dependencies. SQL text, bound parameters, message keys, payloads, and headers
never reach this contract at all. Cardinality is capped before rendering (28 dependencies, with a small per-edge
interaction tail); anything withheld is reported as a visible count, never dropped silently.

Motion is evidence, not decoration. The map refreshes off the same Server-Sent Events tick as the feed, and animates a
short particle only when a **stable** edge — one present both before and after the refresh — carries an interaction id
the previous snapshot did not. A first load animates nothing, a brand-new dependency simply appears, and an idle
application is completely still. Bursts are coalesced to a small per-edge count and a hard concurrent cap rather than
queued, so motion can never lag behind reality.

When freshly animated interactions share a non-null opaque flow id, the inbound pulse starts immediately and downstream
pulses replay only after it would have arrived at the application, in retained completion-time order with a small,
bounded stagger. A downstream pulse whose current batch carries no retained inbound item fires immediately rather than
waiting for evidence that may already have scrolled out of the retained tail; uncorrelated pulses are never delayed.
Sequencing changes only the pacing of already-completed evidence, never the evidence itself or the queue's existing
concurrency and per-edge bounds.

Slow interactions pulse a calm amber for longer than normal completions or failures, with a restrained trailing halo,
so timing carries meaning without relying on color alone. A matching temporary target ring and text chip (`SLOW · 1.3
s` or `ERROR`) appears only for the pulse's scheduled window: inbound HTTP targets the application and outbound
evidence targets its dependency. Retained failures remain visible in counts, details, recent rows, and accessible text,
but never leave the map's nodes or edges permanently red. Under `prefers-reduced-motion`, particles are replaced by a
brief static target/edge highlight plus a polite live-region sentence naming what changed and its duration.

The spatial model is hybrid and deterministic: inbound lane on the left, application hub in the centre, and an airy
right-facing fan for up to six dependencies before denser maps switch to a two-column rack. The fan uses a fixed
288-pixel radius and 72-pixel vertical pitch, keeping typical maps around 800–844 logical pixels wide. The dense rack
uses a 72-pixel application gap, 32-pixel column gap, and 72-pixel row pitch, and is bounded at 1,040 pixels wide and
1,046 pixels tall at the 28-dependency cap inside the scrollable stage. Smooth fan connectors and deterministic
collision-free rack routes are reused exactly by each pulse and slow trail through CSS Motion Path, so dynamically
inserted evidence starts on its own mount-relative delay instead of the SVG document timeline. The whole map is keyboard
navigable (arrow keys move between nodes, Enter or Space selects), carries a hidden textual list of every node and
relationship for screen readers, and supports protocol and free-text filters plus zoom. Evidence from a disabled or
unavailable source panel never reaches the map.

![BootUI Live Activity panel](../images/bootui-activity.webp)

## GitHub

The GitHub panel sits in the Overview group and summarizes the current project's GitHub state from the local `origin`
remote. It uses BootUI's standard auto-refresh control with a one-minute interval while the tab is visible; the initial
refresh and each interval are bounded and blocked by the panel's read-only settings.

The panel shows repository metadata and an eight-card summary grid with click-through detail drawers for open pull
requests, open issues, the latest GitHub Actions executions, quotas, Copilot usage report availability, and the three
security signals. The open-issues drawer summarizes the label/staleness buckets and then lists the bounded set of open
issues returned by the refresh, linking each to its issue page with its author, labels, comment count, and last-updated
time (pull requests returned by the issues endpoint are excluded). GitHub Actions execution rows link to the matching
run, show the workflow, branch, event, status, and
duration, and mirror the recent-run list from the GitHub Actions page. The workflow failure count only considers the
latest execution for each workflow and branch, so older failures drop out once a later run fixes that workflow on that
branch; security signal drawers link to the matching GitHub alert pages. The Dependabot drawer additionally lists the
bounded set of open alerts with their package, ecosystem, severity, advisory ID, summary, affected range, and fixed
version (capped by `bootui.github.max-security-alerts`); code scanning and secret scanning stay count-only and never
inline secret values or vulnerable code snippets.
The quota card shows the lowest remaining quota percentage with a red-to-green threshold palette. The quota drawer is
hidden by default, renders every resource returned by GitHub's `/rate_limit` response dynamically,
highlights resources with 10% or less remaining or at quota, then adds best-effort cards for repository or owner quotas
such as Actions cache, artifacts, and Actions billing when the credential can access those endpoints. Copilot usage uses
GitHub's organization report metadata endpoint when available; BootUI shows the report window and link count only, without
downloading or exposing signed report URLs.

Credentials are read from the current device only: `GITHUB_TOKEN`, `GH_TOKEN`, or an existing `gh auth token` login. The
token is never sent to the browser, persisted by BootUI, or included in warnings; without a token, public repositories use
GitHub's unauthenticated rate limits. Refreshes are bounded by per-request timeouts, a maximum API-call budget, and a quota
safety threshold that skips optional sections before exhausting the core API quota.

On Quarkus the panel is identical, running over the same framework-neutral engine `GitHubDashboardService` and the same
`/bootui/api/github` contract. The Quarkus adapter supplies a Jackson 2 (`com.fasterxml.jackson.*`)
`GitHubClient` implementation in place of the Spring adapter's Jackson 3 one — the only difference, since Quarkus ships
Jackson 2 while Spring Boot 4 ships Jackson 3 — and reuses the shared, framework-free `DefaultGitHubTokenProvider` (env
tokens + `gh` CLI) for credentials. The same `bootui.github.*` keys and defaults bind from MicroProfile Config, panel
availability is computed the same way (the host application's working directory is a GitHub-origin git checkout on an
allow-listed API host), and the no-network-on-render rule holds: `GET /bootui/api/github` never calls GitHub, and only the
explicit `POST /bootui/api/github/refresh` action does (gated by `bootui.github.api-enabled` and the host allow-list).


![BootUI GitHub panel](../images/bootui-github.webp)
