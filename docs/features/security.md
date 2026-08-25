# Security

## Spring Security

![BootUI Spring Security panel](../images/bootui-spring-security.webp)

The Spring Security panel inspects Spring Security filter chains and provides best-effort endpoint rule explanations. It is
meant to explain local security wiring without exposing credentials or replacing a full security audit.

On **Spring Boot WebFlux**, the same panel reads ordered application `SecurityWebFilterChain` beans and lists their
`WebFilter` pipelines. Chain matching remains fully non-blocking and uses each chain's public reactive matcher. Explain
and annotation-endpoint authorization views use a sanitized path-and-method-only exchange: they never reuse the current
request's headers, cookies, principal, session, body, or network metadata, and mark reduced results as best effort instead
of guessing context-dependent rules. Functional `RouterFunction` routes are not listed. The compatibility
`sessionManagementPresent` signal is labelled **Security context** on WebFlux and does not claim that `WebSession`
persistence is configured.

## Security Logs

![BootUI Security Logs panel](../images/bootui-security-logs.webp)

The Security Logs panel reads recent Spring Boot audit events from the application's `AuditEventRepository`, including
authentication successes/failures and authorization denials when Spring Security audit listeners are active. When BootUI is
active and the panel is enabled, it contributes an in-memory repository if the host app has not already defined one, which
also lets Spring Boot create its standard audit listeners. It supports filtering by principal, event type, and time window,
summarizes retained event counts by type, refreshes live over **Server-Sent Events** (the browser subscribes to
`/bootui/api/security-logs/stream` and re-fetches when the server signals a new audit event, instead of polling on a timer),
and masks sensitive event data before rendering. Responses are bounded by `bootui.security-logs.max-logs`, which defaults to
`500`; if audit support is explicitly disabled with `management.auditevents.enabled=false`, the panel remains unavailable.

On Quarkus, the panel sources its events from CDI security events (`io.quarkus.security.spi.runtime.SecurityEvent`) captured into a capped buffer instead of an `AuditEventRepository`. This is honestly partial: it requires a security extension with `quarkus.security.events.enabled=true`, and only authentication success/failure and authorization failure events are emitted — there is no Quarkus equivalent for logout/session events — otherwise the panel reports unavailable with a clear reason. Filtering, type summary, masking, and the `bootui.security-logs.max-logs` cap are identical across both frameworks.

On Spring Boot WebFlux the panel is available and identical: it reads from the same `AuditEventRepository`
abstraction, which is itself framework-neutral (Spring publishes audit events over the ordinary
`ApplicationEventPublisher`, regardless of servlet or reactive), so no reactive-specific capture code was needed
beyond wiring the same fallback in-memory repository.
