# Diagnostics


## Traces

The Traces panel shows distributed tracing spans captured locally by the BootUI starter when telemetry and the Traces
panel are enabled. The starter contributes the tracing dependencies and sampling default needed for local development, so
the host application does not need manual `management.*` tracing properties. Because that default raises sampling to
100% (`management.tracing.sampling.probability=1.0`), the OpenTelemetry SDK and Micrometer Tracing span/propagation code
runs on every request; to keep that from flooding the console when the host's root logger is at `DEBUG`, BootUI also
pins `logging.level.io.opentelemetry` and `logging.level.io.micrometer.tracing` to `INFO` as overridable defaults (set
either key yourself to opt back in). BootUI also keeps an embedded OTLP/HTTP
receiver at `/bootui/api/otlp/v1/traces` so cooperating local services can export spans into the same in-memory store.
The list shows the most recent traces with service name, the HTTP request path each trace served (falling back to the
root span name when no path attribute is present), status, duration, and span count; opening a trace
renders a waterfall view of its spans so you can see latency contributions, errors, and parent/child relationships across
services. Traces emitted by BootUI's own API are filtered out on ingestion by default: as soon as any span in a trace is
recognized as BootUI traffic (for example the path-bearing HTTP server span for `/bootui/api/**`), the whole trace is
dropped, including nested spans that carry no path of their own such as Spring Security `security filterchain
before`/`after` observations. Retained self-only traces are also hidden from the panel, to keep the view focused on
application traffic. Span ingestion can be tuned with
`bootui.telemetry.exclude-self-spans=false`; read-time panel filtering follows `bootui.monitoring.exclude-self`. When
`bootui.telemetry.enabled=false`, the sidebar dims the panel and the view shows a disabled state instead of implying that
tracing is merely empty. The in-memory trace buffer is bounded by `bootui.telemetry.max-traces`,
`bootui.telemetry.max-spans-per-trace`, request-size limits, and attribute-value truncation, with additional internal
caps to keep misconfigured local exporters from overflowing the UI. Trace data is reset on application restart or via
the panel's clear action.

The capture mechanics above (starter-contributed tracing dependencies, the `management.tracing.sampling.probability`
default, and the `logging.level.io.opentelemetry`/`io.micrometer.tracing` pins) and the embedded OTLP/HTTP receiver are
specific to the Spring Boot starter. On Quarkus the same Traces panel and in-memory store are served by the extension,
but spans are captured **in-process** through an OpenTelemetry `SpanProcessor` that is registered only when the
application depends on `quarkus-opentelemetry` — there is no embedded OTLP receiver. Self-span filtering and the
`bootui.telemetry.*` retention bounds behave identically on both platforms. The panel's empty-state guidance adapts too:
on Quarkus it points to `quarkus-opentelemetry` and the in-process capture model rather than the embedded
`/bootui/api/otlp/v1/traces` receiver.

![BootUI Traces panel](../images/bootui-traces.webp)

## Log Tail

The Log Tail panel reads recent local application logs and streams new log events from the running process. It is
intended for quick local diagnosis without leaving the BootUI console.

![BootUI Log Tail panel](../images/bootui-log-tail.webp)

## Exceptions

The Exceptions panel captures exceptions thrown by the running application and groups repeated failures into a single
entry with an occurrence count. BootUI records exceptions from two
complementary sources while it is active: a non-intrusive Spring MVC `HandlerExceptionResolver` that observes exceptions
escaping web request handlers (capturing the request method, path, and handler), and a logback appender that picks up
anything logged with a throwable from scheduled tasks, async work, or `log.error("…", ex)` calls. A failure that is both
handled and logged is de-duplicated by throwable identity so it is counted only once.

Exceptions are grouped by a stable fingerprint derived from the exception type and the top stack frames, so a recurring
error collapses into one row showing its type, latest message, first/last seen times, originating location, and total
count. Opening a group shows the representative stack trace with application frames highlighted, the full cause chain
(`Caused by: …` with `… N more` common-frame folding), and the most recent occurrences with their thread, source, and
request context. The list updates live over **Server-Sent Events** — the browser subscribes to `/bootui/api/exceptions/stream`
and re-fetches whenever an exception is captured or the store is cleared, rather than polling on a fixed interval — and can be
filtered by text, by capture source (web vs. logged), or to application-originated exceptions only.

On top of that existing grouping, each group carries a Sentry-style triage status — **Open** (the default for every new
group), **Acknowledged** (seen, still being investigated), or **Resolved** (believed fixed) — shown as a badge on the row
and changed inline with a button group, the same one-click convention used by the Loggers panel's per-logger level
setter. Changing status calls `POST /bootui/api/exceptions/{id}/status` with `{"status": "..."}`, validated against the
three values (400 on anything else, 404 for an unknown group), and returns the updated group. If a group marked
**Resolved** throws again, BootUI treats this as a regression: the group automatically reopens to **Open** and a
lifetime "Reopened ×N" counter is incremented and surfaced next to the status badge, so a developer immediately sees
that a failure they thought was fixed has come back. An **Acknowledged** group does not auto-transition on new
occurrences — it keeps accumulating its count and last-seen time, since the developer already knows about it and
hasn't claimed it's fixed; only a **Resolved** group can regress. An optional status filter (All/Open/Acknowledged/
Resolved) narrows the list alongside the existing text/source filters.

Exception messages follow the same exposure policy as the rest of BootUI: they are scrubbed of secret-like
`key=value` assignments under the default `bootui.expose-values=MASKED`, omitted entirely under `METADATA_ONLY`, and shown
verbatim only under `FULL`. Request paths are captured without their query string so query-string secrets are never
surfaced, and stack frames carry only class/method/file/line information. The in-memory store is bounded by
`bootui.exceptions.max-groups` (default 100, evicting the least-recently-seen group), `bootui.exceptions.max-occurrences-per-group`
(default 25), and `bootui.exceptions.max-stack-frames` (default 50), and is reset on application restart or via the
panel's clear action. The panel can be disabled with `bootui.panels.exceptions.enabled=false`, and clearing honors the
panel's read-only setting.

On Quarkus the panel is identical, running over the same framework-neutral engine store and `ExceptionsService`, so
the wire is byte-identical to Spring. In place of the MVC resolver and logback appender, capture comes from two
complementary sources: a `java.util.logging` handler that records anything logged with a throwable (excluding BootUI's
own loggers), and a Vert.x failure handler that records the throwable escaping a failed request with its method and
path. The shared store still de-duplicates by throwable identity across the cause chain, so a failure seen by both
sources is counted once. Capture is installed on `StartupEvent` and detached on `ShutdownEvent`, wired in dev/test
only and never in production, and bounded by the same `bootui.exceptions.*` limits. The triage workflow and regression
detection above are engine-level, so they behave identically on Quarkus: `ExceptionsResource` exposes the same
`POST /bootui/api/exceptions/{id}/status` endpoint with the same validation and status codes.

On Spring Boot WebFlux the panel is available too, capturing into the same `ExceptionStore` over the same
`GET /bootui/api/exceptions`/SSE contract and the same triage workflow. In place of the MVC `HandlerExceptionResolver`,
capture comes from a `WebExceptionHandler` at the highest precedence, plus the same logback appender used on the
servlet adapter. One honest, documented fidelity gap: a `@RestController`'s own local `@ExceptionHandler` method
consumes an exception *inside* the WebFlux dispatch pipeline, before any `WebExceptionHandler` sees it — narrower than
the servlet adapter's resolver-chain-based capture, which observes `@ExceptionHandler`-resolved exceptions too.
Unhandled exceptions (the common case) are captured identically on both stacks; see
[docs/WEBFLUX-SUPPORT.md](../WEBFLUX-SUPPORT.md) for detail.

![BootUI Exceptions panel](../images/bootui-exceptions.webp)

## HTTP Exchanges

The HTTP Exchanges panel records recent inbound requests handled by the running application. It lists timestamp, method,
path, status, duration, response size when a `Content-Length` header is present, and trace identifiers from common
propagation headers. Expanding a row shows request and response headers, with secret-like headers and query parameters
masked unless `bootui.expose-values=FULL` is explicitly configured. BootUI self-requests are hidden from the panel by
default through `bootui.monitoring.exclude-self`, though they still count against the bounded in-memory recorder.

BootUI contributes an in-memory `HttpExchangeRepository` when the panel is enabled and no application repository already
exists. The default buffer retains 200 exchanges and can be changed with `bootui.http-exchanges.max-exchanges`; changing
that capacity requires an application restart. If the repository is unavailable, the panel shows a clear unavailable
state instead of implying that no traffic has occurred.

Row details also offer **Copy as cURL**, which turns the retained metadata into a command *template* you can paste into a
terminal. It is deliberately not a byte-for-byte replay, and the action explains every difference before you copy it:

- Copying runs entirely in the browser. No request is sent, no capture state changes, and nothing is written back to the
  application.
- The generated command is shown in full before you copy it, so you can read exactly what will land on your clipboard —
  and still copy it by hand if the browser denies clipboard access.
- Query-parameter names are preserved — including repeated, empty and encoded ones — but every value becomes a `VALUE`
  placeholder, so retained values never reach the clipboard. A masked name, or a segment with no `=` at all, is dropped
  and reported, because an unstructured segment can be a bare token rather than a name.
- Only a short allowlist of boring request headers is copied (`Accept`, `Accept-Language`, `Cache-Control`,
  `Content-Type` and `User-Agent`), and only while their values are actually exposed and unmasked. Authorization,
  cookies, proxy credentials, API keys, forwarding headers, tracing headers and unknown custom headers are omitted under
  every exposure mode, including `bootui.expose-values=FULL`.
- BootUI never captures request bodies, so the command carries none; body-carrying methods say a body may have existed
  and invite you to add your own `--data`.
- The URL, method and every header argument are POSIX single-quoted, so shell metacharacters captured from a request
  cannot escape their argument or append another command. Credentials embedded in a recorded URL are dropped with the
  rest of the authority userinfo.
- The path is copied exactly as recorded, so a traversal probe such as `/a/%2e%2e/admin` stays visible instead of being
  normalized into a different target, and `--globoff` keeps recorded brackets literal so one exchange never becomes
  several requests. `HEAD` uses `-I` so the command cannot hang waiting for a body.
- When the retained metadata has no absolute `http(s)` URL or no recognizable method, the action is deactivated and
  announces the reason instead of producing a misleading command.

The command is generated by a shared frontend helper over the same `HttpExchangeDto` every adapter serves, so Spring MVC,
Spring WebFlux and Quarkus produce byte-identical text for the same exchange.

On Quarkus the panel is identical, but Quarkus has no Actuator `HttpExchangeRepository`, so capture is done by a small
Vert.x route filter that samples each completed request — recorded in the response body-end handler so status, duration
and size are final — into a capped, framework-neutral ring buffer sized by the same `bootui.http-exchanges.max-exchanges` key (default 200) as Spring. The
masking, trace-id extraction, self-exclusion and paging run through the same shared engine service, so the wire is
byte-identical to Spring. Capture is wired in dev/test only and never in production.

![BootUI HTTP Exchanges panel](../images/bootui-http-exchanges.webp)

## HTTP Probe

The HTTP Probe panel sends local-only requests to the running application and displays response status, headers,
duration, and body. It is designed for quick route checks from inside the same local development context as BootUI.

Probe input is bounded like every other BootUI operation: the method, path, request body, header count and header
name/value sizes each have an explicit ceiling (64 KiB for the request body, 2 KiB for the path, 50 headers), measured
in UTF-8 bytes and checked before anything is sent to the application. Exceeding a ceiling is invalid input, so it is
rejected with the canonical `400` and `{"error": ...}` body on Spring MVC, Spring WebFlux and Quarkus alike, and the
panel shows that message instead of a probe result. A probe that actually runs and fails — connection refused, timeout
— is still reported as a probe outcome, and its response body is truncated at the response byte budget.

On Quarkus the panel is identical: the probe always targets the application's *own* loopback address, so it can never
reach an external host. The only platform difference is how the live local port is resolved — Quarkus has no single
config key that always equals the bound port, so the adapter selects `quarkus.http.test-port` or `quarkus.http.port` by
launch mode (and a random `=0` port still resolves, because Quarkus rewrites the property to the actual port once the
server is up). As a state-changing action it is gated by the same localhost-only safety floor as every other write.

![BootUI HTTP Probe panel](../images/bootui-http-probe.webp)
