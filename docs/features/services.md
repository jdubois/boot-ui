# Services

## Scheduled Tasks

![BootUI Scheduled Tasks panel](../images/bootui-scheduled-tasks.webp)

The Scheduled Tasks panel lists scheduled jobs registered with Spring scheduling infrastructure. It shows task type and
trigger metadata so background activity is visible during local development.

The panel is identical on Quarkus over the same `/bootui/api/scheduled` contract, but the data source differs: annotated
`@Scheduled` methods are captured from the Jandex index at build time. Only annotation-discovered tasks are captured.

::: details How Quarkus captures scheduled tasks
The runtime `io.quarkus.scheduler.Scheduler` exposes only trigger ids and next-fire times — neither of which the shared
task contract carries — while the cron/`every` expressions and target method are known only at build time. So the
Quarkus adapter captures every `@Scheduled` method from the application's Jandex index at **build time** (the same
pattern as Architecture base-package and Vulnerabilities dependency capture). It maps each onto the same
trigger/expression/initial-delay fields: a `cron` member becomes a `CRON` row, an `every` member a `FIXED_RATE` row (with
the duration parsed to milliseconds), and a `delay`/`delayed` initial delay is carried through. The panel is available
only when the `quarkus-scheduler` extension is present; programmatic `Scheduler.newJob()` jobs are not captured.
:::

## REST Client

![BootUI REST Client panel](../images/bootui-rest-client-trace.webp)

The REST Client panel shows outbound HTTP calls your application recently made — through Spring's REST clients on the
Spring adapter, or Quarkus REST Client Reactive proxies on Quarkus — captured without a third-party HTTP proxy library. A
capture failure never disrupts the outbound call itself.

Each call records its method, host, path, sanitized query string, response status, wall-clock duration, success/failure,
client type, a trace id when one is active, the executing thread, and — when call-site capture is enabled — the call site
in your own code that issued it.

::: details How calls are intercepted
When BootUI is active it customizes every auto-configured `RestClient` and `RestTemplate` with a shared
`ClientHttpRequestInterceptor` (Spring), or hooks every `@RegisterRestClient` proxy via the MicroProfile
`RestClientListener` SPI (Quarkus). Both instrumentation points let the request through and only best-effort record
around it, so an instrumentation error never breaks the call.
:::

### Reading the panel

Calls are retained in a bounded, most-recent-first ring buffer alongside aggregate stats: retained count, average and
slowest duration, a configurable slow-call count, and — unlike SQL Trace's single failure counter — two distinct failure
counts, because an outbound HTTP call can fail two ways:

- **Failed** counts transport-level failures (the call never got a response — connection refused, timeout, DNS failure).
- **Error responses** counts calls that completed with a `4xx`/`5xx` status.

A "Most frequent calls" table groups calls by method, host, and normalized path, and flags high-frequency groups as
**chatty**. Each row expands for full detail, and rows filter by HTTP method, a slow-only toggle, or free text.
Local-only **Pause/Resume** and **Clear** actions stop recording or empty the buffer without removing instrumentation.

::: details Breakdowns, grouping, and chatty detection
A per-method breakdown badges GET/POST/PUT/DELETE/other counts, and an "Instrumented clients" row lists which client
types (`RestClient`, `RestTemplate`, `WebClient`, or `Quarkus REST Client Reactive`) are actually wired in. The panel
groups calls by method, host, and normalized path — numeric and UUID segments collapse to `{id}`, so `/orders/1` and
`/orders/2` group under `/orders/{id}`. It flags a group at or above `bootui.rest-client-trace.chatty-call-threshold`
calls as a **chatty** (repeated-call) pattern. Unlike SQL's N+1 rule, which only flags repeated `SELECT`s, a chatty
pattern is flagged for calls of *any* HTTP method, since looping a `POST`/`PUT` per item is just as costly as looping a
`GET`. A flagged group also lists the distinct call site(s) that issued it, most-recently-seen first and bounded to a
handful of entries. Each call row expands to reveal the full URI, request headers when that adapter supports them, client
type, trace id, executing thread, call site, and error message. Rows filter by HTTP method, a slow-only toggle, or free
text across URI, host, method, client, and thread.
:::

### Privacy

The panel is read-mostly and privacy-conscious: Spring retains the URI and masks query values by name, while Quarkus is
strictly metadata-only and never reads bodies, headers, or credentials. Call-site capture names only your own code
(class, method, line), never a value, so it is **not** privacy-gated.

::: details Exact redaction rules
- **Spring** retains the URI and masks query values **by name** (the same `SecretMasker` rules Config and HTTP Exchanges
  use, matched percent-decoded so a URL-encoded parameter name cannot slip through). URI authority credentials such as
  `user:secret@host` are removed before buffering, and the client error message is flattened, credential-redacted, and
  truncated — a transport exception routinely quotes the whole request URL. Request headers are withheld by default;
  `bootui.rest-client-trace.capture-headers=true` opts into bounded, exposure-aware header capture.
- **Quarkus** is deliberately stricter: always metadata-only, it ignores that header property, never reads or retains
  request/response bodies, arbitrary headers, authorization, cookies, credentials, or tokens, and strips URI
  user-info/fragments plus masks sensitive path/query values **before storage**.

`bootui.rest-client-trace.capture-call-site` defaults to `true` and only trades a small, defensively-bounded stack walk
per call for the ability to see where a call came from. On Quarkus this attribution is best-effort, because a reactive
client callback can run after the issuing stack has unwound.
:::

### Availability and streaming

**REST Client's dedicated panel is available on Spring MVC (servlet), Spring WebFlux (reactive), and Quarkus adapters.**
The recorder bean is registered unconditionally whenever the panel is enabled — it doubles as the source for Live
Activity's REST entries.

| Adapter        | Instrumentation                                                     | When the panel becomes available            |
| -------------- | ------------------------------------------------------------------- | ------------------------------------------- |
| Spring MVC     | `RestClientCustomizer` / `RestTemplateCustomizer` hooks             | Once the recorder has instrumented anything |
| Spring WebFlux | `ExchangeFilterFunction` on the auto-configured `WebClient.Builder` | After that builder has customized a client  |
| Quarkus        | `RestClientListener` SPI attaching a filter on every proxy          | When `quarkus-rest-client` is present       |

The panel refreshes over **Server-Sent Events** rather than fixed-interval polling, and recent calls also surface in
**Live Activity**, nested under the request that made them with a deep link back to this panel.

::: details Per-adapter wiring detail
On Spring, the customizer that wires a given client type fails open, skipping itself entirely when that client's Spring
Boot module (for example `spring-boot-webclient`) is not on the classpath, so an app without `WebClient` simply never
gets a `WebClient` customizer rather than failing startup. On WebFlux, `WebClient` calls are captured through an
`ExchangeFilterFunction` installed by Spring Boot's auto-configured `WebClient.Builder`, and its reactive `/stream`
endpoint provides the same pause/resume, clear, and live-refresh behavior without linking servlet classes.

On Quarkus the manifest is capability-driven because REST Client proxies are built lazily: when `quarkus-rest-client` is
present the panel stays visible and reports that no proxy has been initialized until the first one is built, then its SSE
stream refreshes on the first captured call. The `QuarkusRestClientTraceListener` (registered via
`ServiceProviderBuildItem` when the capability is present) attaches a `QuarkusRestClientTraceFilter` on every proxy. The
filter runs after application request filters and before application response filters, so it brackets the transport
without replacing application customization. Quarkus reports a pre-response transport failure to the response filter with
status `0`; BootUI records that as a failed call with no invented HTTP status, while any real `4xx`/`5xx` response remains
a transport-successful error response. The same `RestClientTraceRecorder` backs both adapters, so the panel shape is
identical.
:::

::: details Streaming and Live Activity detail
Because the trace buffer is genuinely event-driven, the browser subscribes to `/bootui/api/rest-client-trace/stream` and
the server pushes a small coalesced notification the moment a call is captured, the buffer is cleared, or recording is
paused/resumed. The push carries no data — masking and truncation still apply through the regular endpoint — and bursts
of calls are folded into a single refresh so high-volume workloads do not flood the UI. Recent calls surface in Live
Activity using the same trace-id-first, serving-thread-second correlation SQL statements use. The "chatty" grouping above
is not yet surfaced as a row-level badge in the merged stream the way SQL's N+1 suspicion is — it is visible only in this
panel's own "Most frequent calls" table. Tracing, the initial recording state, header capture, call-site capture, buffer
size, the slow-call and chatty-call thresholds, and URI/header truncation limits are all configurable under
`bootui.rest-client-trace.*`.
:::

## Fault Tolerance

![BootUI Fault Tolerance panel](../images/bootui-fault-tolerance.webp)

The Fault Tolerance panel makes an application's protective policies visible. It lists every circuit breaker, retry, rate
limiter, bulkhead, time limiter, and fallback the application declares — with the settings that actually apply, the
protected operation, live circuit breaker state, and call counters where the library exposes them. Below the inventory, a
bounded event feed shows what the machinery actually did: retried calls, exhausted retries, rejected calls, timeouts,
short circuits, and circuit breaker state transitions.

Three providers are supported, and several can be active at once:

- **Resilience4j** (Spring MVC and Spring WebFlux)
- **Spring Retry** (Spring MVC and Spring WebFlux)
- **SmallRye Fault Tolerance** (Quarkus)

The panel is **strictly capture-only**: BootUI never opens, closes, resets, forces, or otherwise mutates a policy, and it
never triggers a protected call itself. Event capture is metadata only. Fault tolerance events also appear in Live
Activity as `FAULT_TOLERANCE` entries, correlated with the request that produced them. Set
`bootui.fault-tolerance.enabled=false` to keep the live policy inventory while recording no events at all.

::: details Per-provider sources and capture scope
- **Resilience4j** — read live from the `CircuitBreakerRegistry`, `RetryRegistry`, `RateLimiterRegistry`,
  `BulkheadRegistry`, `ThreadPoolBulkheadRegistry`, and `TimeLimiterRegistry` beans, including entries created lazily at
  runtime. Resilience4j's own event publishers feed the event feed, so state transitions and retries appear without any
  wrapping or proxying by BootUI.
- **Spring Retry** — `@Retryable` metadata plus an additive `RetryListener` bean that records retry attempts and
  exhaustion.
- **SmallRye Fault Tolerance** — `@CircuitBreaker`, `@Retry`, `@Timeout`, `@Bulkhead`, `@RateLimit`, and `@Fallback`
  annotations captured from the Jandex index at build time, with MicroProfile configuration overrides resolved at
  runtime and marked `configured`. This includes the MicroProfile `enabled` switches, so a policy disabled through
  configuration is listed with a leading `enabled` = `false` setting rather than shown as if it still applied. Live
  circuit breaker state and state-transition events come from `CircuitBreakerMaintenance` for breakers carrying
  `@CircuitBreakerName`; SmallRye publishes no per-call event stream, so retries and rejections are not individually
  captured.

Event capture is metadata only: policy name, outcome, attempt number, duration, the *simple name* of a failure's
exception class, and circuit breaker state. Method arguments, return values, payloads, and exception messages are never
recorded. Clicking a Live Activity `FAULT_TOLERANCE` entry opens this panel filtered to that policy.
:::

## WebSockets

![BootUI WebSockets panel](../images/bootui-websockets.webp)

The WebSockets panel shows the WebSocket endpoints your application declares, the connections currently open against
them, the STOMP destinations those connections subscribed to, and a bounded log of recent frame **metadata** — never a
message payload. It answers what a local WebSocket developer actually asks: is my endpoint mapped where I think, did the
client really connect, did the subscription land on the destination I expected, and are frames flowing both ways?

### The four tabs

- **Endpoints** — each declared endpoint with its path, kind (`STOMP` or `HANDLER` on Spring, `ENDPOINT` on Quarkus), a
  SockJS badge when enabled, the handler class, the number of open connections, whether BootUI has a frame-capture seam
  installed, and the callbacks or `@MessageMapping` destinations it serves. When a STOMP broker is configured, the panel
  also shows the broker prefixes, application destination prefixes, and user destination prefix, so a mis-prefixed
  `/topic` versus `/app` destination is obvious at a glance.
- **Sessions** — live connections with an opaque session id, negotiated path and subprotocol, open/closed state,
  per-session frame and byte counters, and the remote address.
- **Subscriptions** — each STOMP subscription mapped to its session and destination.
- **Activity** — the recent frame log: timestamp, direction, frame type (`OPEN`, `TEXT`, `BINARY`, `PING`, `PONG`,
  `CONNECT`, `SUBSCRIBE`, `UNSUBSCRIBE`, `CLOSE`), destination when the frame carries one, session, payload **size**, and
  success/error category.

### Capture-only and metadata-only

BootUI never reads, decodes, buffers, or stores a message payload on any stack; frame size comes from the transport's own
counters and is omitted when unavailable. Raw session ids are replaced by a salted, one-way hash and destinations are
redacted, so you can correlate rows without a replayable identifier. Nothing about a WebSocket is touched on page load.

::: details How metadata-only capture works
Frame size comes from the transport's own `getPayloadLength()` (Spring) or from the length of an already-materialized
`byte[]` on the messaging channel, and is simply omitted when neither is available, so no application message is ever
consumed or copied. Raw provider session ids are never exposed — each is replaced by a salted, per-process, one-way hash,
and destinations are redacted into the buffer, so Spring's resolved user destinations (`/queue/x-user<simpSessionId>`)
surface as `/queue/x-user{session}` rather than leaking a live, addressable identifier. BootUI reads the endpoint
registry Spring or Quarkus already built and the connections that already exist, and never opens, closes, probes, or
writes to a connection. Local-only **Pause/Resume** (frame capture) and **Clear** (buffer and counters) actions are
available where capture is supported, and both honor `bootui.panels.websockets.read-only`.
:::

### Where frame capture is installed

Frame capture is installed only where the framework offers a sanctioned seam, and the panel says so honestly rather than
pretending. Spring MVC with `@EnableWebSocketMessageBroker` supports it; Spring WebFlux and Quarkus report endpoints and
live connections but no frame capture, with the concrete reason shown.

::: details Per-stack capture and session tracking
- On **Spring MVC** with `@EnableWebSocketMessageBroker`, BootUI registers a `WebSocketHandlerDecoratorFactory` plus
  inbound and outbound `ChannelInterceptor`s through the public `WebSocketMessageBrokerConfigurer` contract, so STOMP
  endpoints report `frameCaptureSupported=true` and are badged **installed**. Native `WebSocketHandler` endpoints
  registered without the message broker report their full topology but are badged **metadata**, because decorating them
  would require reaching into non-public state.
- On **Spring WebFlux** and **Quarkus**, the panel reports endpoints and live connections but
  `frameCaptureSupported=false` with the concrete reason: `@EnableWebSocketMessageBroker` is servlet-only, and Quarkus
  WebSockets Next exposes no message-interception SPI.

Live session tracking is reported with the same honesty through `sessionTrackingSupported` and
`sessionTrackingUnavailableReason`: Spring MVC and Quarkus observe connection lifecycle, while Spring WebFlux exposes no
session registry, so its empty Sessions table says *not supported on this stack* instead of implying nothing is
connected. Buffer sizes, initial capture state, and per-collection caps are configurable under `bootui.websockets.*`;
every collection is independently truncated and the panel says when it was.
:::

### Availability and streaming

**WebSockets is available on Spring MVC (servlet), Spring WebFlux (reactive), and Quarkus adapters**, with the
capture-capability difference above. The panel refreshes over **Server-Sent Events** on `/bootui/api/websockets/stream`,
so a connection opening or a frame arriving pushes a small coalesced change notification rather than polling on a timer.

::: details Gating and streaming detail
On Spring, the panel is gated on `spring-websocket` and `spring-messaging` being on the classpath. On Quarkus it is gated
on `quarkus-websockets-next` being present and at least one `@WebSocket` endpoint being declared, with the endpoint
topology captured at build time from the Jandex index and connection lifecycle observed through `@Open`/`@Closed` CDI
events over `OpenConnections`. The SSE push carries no data — the regular endpoint still applies every masking, ordering,
and truncation rule.
:::

## AI Framework

![BootUI AI Framework panel](../images/bootui-ai.webp)

The AI Framework panel summarizes Spring AI and LangChain4j activity collected from OpenTelemetry spans emitted by their
built-in observability. It groups chat client and chat model spans by conversation, showing request count, token usage
(prompt, completion, total), latency, model, and the prompt/response snippet when content capture is configured. An
inline chart shows total token usage over recent calls, and vector store and embedding spans appear alongside chat spans.

Data is sourced from BootUI's local telemetry capture, is in-memory only, and is cleared on restart.

### Availability and setup

The sidebar dims the panel when telemetry is disabled or neither Spring AI nor LangChain4j is on the classpath, and the
view explains the unavailable state with a setup checklist for each framework.

::: details Telemetry sourcing and setup states
The BootUI starter captures local application spans automatically when telemetry is enabled, and cooperating local
services can still send OTLP spans to the embedded receiver. As with the Traces panel, recent chats, model breakdowns,
token-series windows, spans, and attributes are all bounded so large local runs stay responsive. When no framework is
detected, the setup checklist shows two side-by-side guides — one for Spring AI and one for LangChain4j — explaining the
dependency and configuration each needs to emit GenAI spans (including optional prompt/completion content capture). When
both prerequisites are ready but no chat spans have arrived yet, the panel shows a ready empty state rather than setup
guidance.
:::

::: details Content-capture configuration
Prompt/response snippets appear only when the framework is configured to capture content. For Spring AI, set
`spring.ai.chat.client.observations.log-prompt`, `spring.ai.chat.observations.log-prompt`, and
`spring.ai.chat.observations.log-completion`. For LangChain4j, enable GenAI message-content capture on the OpenTelemetry
instrumentation.
:::

The panel is identical on Quarkus, reading from the same in-memory telemetry store; GenAI spans are captured when the
application depends on `quarkus-opentelemetry`.

::: details Quarkus setup differences
GenAI spans are captured when the application depends on `quarkus-opentelemetry` (for example alongside
`quarkus-langchain4j`, or any OpenTelemetry GenAI instrumentation that emits the `gen_ai.*` semantic-convention spans).
When no framework is detected, the setup checklist adapts to the platform: on Quarkus it shows a single LangChain4j guide
using `quarkus-langchain4j` plus `quarkus-opentelemetry` and BootUI's in-process capture model — no embedded OTLP
receiver — instead of the Spring AI / LangChain4j side-by-side guides.
:::

## Cache

![BootUI Cache panel](../images/bootui-cache.webp)

The Cache panel inspects the application's cache infrastructure on **both** frameworks from one shared panel and report
contract: Spring's cache abstraction on Spring Boot, and `quarkus-cache` on Quarkus (covered below). On Spring Boot it
lists cache manager beans, known caches, native implementations, safe local sizes, Micrometer cache metrics when
registered, and discovered `@Cacheable`, `@CachePut`, and `@CacheEvict` operations. Cache clear actions are enabled by
default for local development, require explicit browser confirmation, and can be disabled with
`bootui.cache.clear-enabled=false`.

### Tiering and hit ratios

Each cache row discloses the **backing tiers** the cache implementation describes through its own public API, and the
**native effectiveness counters** that implementation records. Tier detail is collapsed behind a keyboard-operable
disclosure button so the caches table stays scannable, and provider statistics are never blended with Micrometer meters.

::: details What a tier and its counters carry
A tier carries its level (`L0` is consulted first), implementation type, locality (in this JVM or remote), configured
maximum entry count, and configured expiry. When the provider's own configuration makes a bare number misleading, it also
carries a short **policy note** — a weight-bounded Caffeine cache, for instance, states that its bound is a total weight
rather than an entry count. Counters are shown as their own labelled series — provider statistics are never blended with
Micrometer meters, and both are rendered side by side when both exist. A Micrometer series that has recorded no request
yet shows *ratio unknown*, rather than a misleading 0%.
:::

### Read from public APIs only

Everything here is read from public, supported APIs only, and BootUI never fabricates a value: undescribed storage reports
**no tiers**, an unavailable counter is **omitted** rather than shown as zero, statistics appear only when the provider is
recording, and reading tiers and counters **never contacts anything over the network**.

::: details Exactly how honesty is preserved
- A cache implementation that does not describe its storage reports **no tiers at all** and is marked *Not described*
  with a reason, rather than having a tier inferred from its class name.
- A counter a provider does not expose (Caffeine has no put or explicit-removal counter, for instance) is **omitted**,
  never rendered as zero.
- Statistics are reported as available only when the provider says it is **recording**. Caffeine without `recordStats()`
  and Spring Data Redis without `enableStatistics()` both report unavailable with the reason and the fix, instead of an
  all-zero series that reads like a cold cache.
- A **hit ratio is derived only** from a hit and a miss counter the adapter declared comparable (same counter family,
  scope, and window) and only when their sum is positive. An idle cache shows *ratio unknown* with the reason, never "0%".
- Reading tiers and counters **never contacts anything over the network**. No Redis entry count is reported, because
  counting keys would be an unsolicited network round trip on panel render. Local reads stay cheap too, with one honest
  exception: the Quarkus adapter reads a Caffeine cache's entry count through Quarkus' own `keySet()` accessor, which
  copies the key set, so a very large Quarkus cache pays a proportional local cost when opened. The Quarkus extension is
  dev/test-only and BootUI does not read entry counts anywhere else.
- Large topologies are bounded (100 managers, 500 caches per manager, 20 tiers per cache) and truncation is stated in the
  report's warnings rather than silently dropping rows.
:::

::: details Concrete examples
A Caffeine cache built with `recordStats()` shows hits, misses, requests, evictions, load successes/failures, a hit
ratio, and its configured maximum size and expiry. A `RedisCache` shows a remote tier with its configured time-to-live
and, once `spring.cache.redis.enable-statistics=true`, the locally recorded gets/hits/misses/puts/deletes with the
instant they have been accumulating from. A `spring.cache.type=simple` cache shows one local in-memory map tier and
honestly reports that a plain map records nothing.
:::

::: details On Quarkus

The same panel (kept under the shared id `cache`) is served over `quarkus-cache`, reading the live topology from the
application's `CacheManager` and overlaying the same Micrometer metrics. Quarkus's public `CaffeineCache` interface
exposes **no statistics accessor**, so hit/miss counters are reported unavailable and the panel points at Micrometer
cache metrics instead. The panel is gated on the `quarkus-cache` extension (the `CACHE` capability).

**How the topology, metrics, and clear action work**

BootUI reads the live cache topology from the application's `io.quarkus.cache.CacheManager`, overlays the same Micrometer
cache metrics (when a `quarkus-micrometer` registry is present and per-cache metrics are enabled), and the clear action
evicts via `cache.invalidateAll()`. Tier metadata is reported the same way as on Spring: one local Caffeine tier per
cache, with the maximum size and expiry configured under `quarkus.cache.caffeine."<name>".*`. Rather than reaching into
Quarkus's internal `CaffeineCacheImpl` by reflection, the panel reports the hit and miss counters as unavailable with
that reason. Because Quarkus binds caching with build-time annotations (`@CacheResult`, `@CacheInvalidate`,
`@CacheInvalidateAll`) woven into methods, there is no runtime registry of cached operations, so the operations table is
replaced by a short explanatory note and the panel shows cache names, metrics, and clear. The panel is reported
unavailable, with a capability hint, on applications that do not use `quarkus-cache`.

:::

## Email

![BootUI Email panel](../images/bootui-email.webp)

The Email panel captures outgoing application mail. It intercepts the application's `JavaMailSender` so every outgoing
`send(...)` call is recorded into a bounded ring buffer *before* delegating to the real sender — pass-through by default,
so application behaviour is unchanged. Captured messages list newest-first with sender, recipients, subject, and
attachment count; opening one shows the parsed addresses, a sandboxed HTML preview, the plain-text alternative, and
attachment metadata (name/type/size, never contents). Each message downloads as a `.eml` file, and the buffer can be
cleared. The panel is available only when a `JavaMailSender` bean is present (e.g. `spring-boot-starter-mail`); otherwise
it reports a clear unavailable reason.

Recipients, subjects, and bodies are revealed by default, consistent with BootUI's other data-capture panels (HTTP
Exchanges, SQL Trace). Opt into masking with `bootui.email.mask-content=true`. An optional, opt-in **dev-trap** mode
(`bootui.email.dev-trap=true`) records messages without actually sending them; it is off by default so BootUI never
silently swallows application mail.

::: details Masking, HTML preview, dev-trap, and truncation detail
Opening a message shows the parsed `from`/`to`/`cc`/`bcc`, an HTML preview rendered in a sandboxed iframe (scripts and
same-origin access both disabled), the plain-text alternative, and attachment metadata (name/type/size, never contents).
Email content is not treated as a config secret, so it is not masked by the global `bootui.expose-values` flag. Teams
routing real customer PII through a shared dev environment can opt into masking with `bootui.email.mask-content=true`,
which reuses the same name-based `SecretMasker` heuristic as Configuration/HTTP Exchanges and is only then lifted back
under `bootui.expose-values=FULL`. The dev-trap mode is similar to MailDev/GreenMail. Each captured message's text/HTML
body is truncated at `bootui.email.max-body-length` characters (default 200,000, matching
`EmailStore.DEFAULT_MAX_BODY_LENGTH`) so a single oversized message cannot spike memory before the
`bootui.email.max-entries` entry-count cap would evict it. Attachment content is never captured (metadata only), so this
cap only applies to bodies.
:::

::: details On Quarkus

The panel is identical on Quarkus over the same `/bootui/api/email` contract, available when `quarkus-mailer` is on the
classpath. One behaviour differs by necessity: because Quarkus fires its capture event *after* the send, the
recorded-but-not-sent distinction reflects Quarkus's own mock-mail mode (`quarkus.mailer.mock=true`, the default in dev
and test) rather than a BootUI trap, and such messages are labelled **mock** instead of **dev-trap**.

**Quarkus capture wiring and attachment sizes**

The `.eml` bytes are produced by the shared engine renderer so they match Spring's. Quarkus's blocking/reactive/Mutiny
`Mailer` beans all funnel through one internal mailer that fires a CDI `SentMail` event after every successful send, so a
single `@Observes SentMail` observer captures every send style — the Quarkus analogue of Spring's
`CapturingJavaMailSender` decorator. The panel is available when `quarkus-mailer` is on the classpath (and dark in
production); otherwise it reports a clear unavailable reason. Attachment sizes are shown as unknown on Quarkus, since the
sent-attachment API exposes none.

:::

## Kafka

![BootUI Kafka panel](../images/bootui-kafka.webp)

The Kafka panel is a dedicated, filterable view over the same producer/consumer capture that already feeds `MESSAGING`
entries into Live Activity: every application-owned `KafkaTemplate` send and `@KafkaListener` consume is recorded into a
bounded ring buffer, newest-first, without altering delivery. Each row shows the timestamp, direction (produced/consumed,
with an icon), topic, partition, offset (consumed records only), a short hash of the key, processing duration (consume
only), success/failure with privacy-safe generic failure text, and — for consumed records — the consumer group id and
listener identifier. **The message value/payload and raw exception messages are never captured, only bounded metadata.** A
text filter matches topic, key, group, and listener; a direction filter isolates produced or consumed records; and the
whole buffer can be cleared. The panel is available only when a `KafkaTemplate` bean is present (e.g.
`spring-boot-starter-kafka`); otherwise it reports a clear unavailable reason.

::: details Configuration and payload policy
A produced record's offset isn't known at send time, and a producer send's duration is not exposed by either framework's
callback. A Kafka payload is an arbitrary, potentially large and sensitive object with no generic masking strategy, so
only metadata is retained. Capture is on by default whenever a Kafka integration is present and the panel is enabled, and
is tuned through the same `bootui.kafka.*` properties Live Activity uses (`enabled`, `capture-key`, `max-entries`,
`max-key-length`) — see `docs/PROPERTIES.md`. Turning off key capture (`bootui.kafka.capture-key=false`) is reflected
with a notice instead of blank hashes, and turning off capture entirely (`bootui.kafka.enabled=false`) leaves
already-captured messages visible with a similar notice.
:::

On Quarkus the same UI and `/bootui/api/kafka` contract (list/clear) run over the shared engine, with the reduced
metadata SmallRye Reactive Messaging exposes: the listener identifier is the channel name, while consumer group id and
producer duration are unavailable. The panel is available when `quarkus-messaging-kafka` is on the classpath in a
non-production launch.

::: details Quarkus capture specifics
Configured `@Incoming`/`@Outgoing` channels determine whether the panel receives any activity. Incoming deliveries carry
connector metadata automatically; outgoing messages are captured only when they already carry
`OutgoingKafkaRecordMetadata`, so a payload-only emission that relies entirely on channel configuration is not recorded.
Without the extension the panel reports a clear unavailable reason.
:::

## RabbitMQ

![BootUI RabbitMQ panel](../images/bootui-rabbitmq.webp)

The RabbitMQ panel is a dedicated, filterable view over AMQP publish/consume capture that also feeds `MESSAGING` entries
into Live Activity. Each row shows timestamp, direction (PUBLISH/CONSUME, with an icon), exchange, routing key, queue
(consume side), processing duration (consume only), and success/failure; when `capture-correlation-id` is enabled
(opt-in, default `false`), a truncated SHA-256 hash of the correlation ID is shown. **The message body/payload and
arbitrary headers are never captured** — only bounded routing metadata, timing, and success/failure are retained, with
generic failure text so exception messages cannot leak payload or credential data. Capture is on by default whenever a
RabbitMQ integration is present and the panel is enabled, tuned via `bootui.rabbitmq.*` (see `docs/PROPERTIES.md`). The
panel is available when a `RabbitTemplate` bean is present (e.g. `spring-rabbit` / `spring-boot-starter-amqp`); otherwise
it reports a clear unavailable reason.

::: details How publish/consume is intercepted
On Spring, BootUI installs a `MessagePostProcessor` on every `RabbitTemplate` bean via the public
`addBeforePublishPostProcessors` API and prepends a `MethodInterceptor` to every
`AbstractRabbitListenerContainerFactory`'s advice chain — composing with, not replacing, any existing post-processors or
advice. On Quarkus, it hooks SmallRye Reactive Messaging's `OutgoingInterceptor`/`IncomingInterceptor` SPI, so the same
recorder is fed from either framework. Spring publishes are captured at the supported before-publish hook and therefore
represent a publish attempt; Quarkus publish ack/nack and both consumer paths represent terminal outcomes. The tuning
properties are `bootui.rabbitmq.enabled`, `bootui.rabbitmq.capture-correlation-id`, `bootui.rabbitmq.max-entries`, and
`bootui.rabbitmq.max-correlation-id-length`.
:::

On Quarkus the same UI and `/bootui/api/rabbitmq` contract (list/clear) run over the shared engine. SmallRye does not
expose a producer exchange, consumer queue, or producer duration at these callbacks, so those per-message fields render
as unavailable; routing key, outcome, and opt-in correlation-ID hash remain available. The panel is available when
`quarkus-messaging-rabbitmq` is on the classpath in a non-production launch.

::: details Quarkus capture specifics
Incoming deliveries carry connector metadata automatically; outgoing messages are captured only when they already carry
`OutgoingRabbitMQMetadata`, so a payload-only emission that relies entirely on channel configuration is not recorded.
Without the extension the panel reports a clear unavailable reason.
:::

## JMS

![BootUI JMS panel](../images/bootui-jms.webp)

The JMS panel is the dedicated, filterable view over the Spring JMS producer/consumer capture that also feeds `MESSAGING`
entries into Live Activity. It lists retained messages newest-first with their direction, sanitized queue or topic
destination, processing duration, success/failure, subscription and listener identifiers, and — when enabled — a short
SHA-256 hash of the provider-assigned message ID. The message payload, arbitrary headers/properties, raw message ID, and
exception message are never captured. Filter by destination, message-ID hash, subscription, listener, or failure type;
narrow the table to produced or consumed messages; and use the confirmation-gated clear action to reset the bounded
buffer. The panel is available when a `JmsTemplate` bean is present (for example through Spring Boot's Artemis starter);
otherwise it remains visible with a clear unavailable reason.

::: details Configuration and native-image support
Capture uses the same `JmsActivityRecorder` as Live Activity, so both surfaces stay synchronized and clearing the panel
also clears retained JMS entries from the merged feed. `bootui.jms.enabled`, `bootui.jms.capture-message-id`,
`bootui.jms.max-entries`, and `bootui.jms.max-message-id-length` configure both surfaces. The Spring interception uses
runtime class proxies, so the JMS panel reports unavailable in a GraalVM native image rather than claiming capture is
active when those proxies cannot be generated.
:::

JMS capture is currently Spring-only. On Quarkus the shared route remains visible but reports the panel not yet
available; BootUI directs Quarkus applications to the Kafka and RabbitMQ panels backed by Reactive Messaging instead.
