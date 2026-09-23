# Security

These two panels show the security wiring and the security events of the running application. The rule-based scans live
in the [Security, Pentesting, and Vulnerabilities advisors](advisors.md).

::: tip A score is not a certification
The security advisors score the evidence they could observe. When coverage is incomplete they show **Results
available** with secondary **Scan notes**, and a partial 100 does not prove that unchecked controls passed. Failed,
skipped, and UNKNOWN-only evidence cannot establish a score, even after dismissal. See
[score eligibility](advisors.md#score-eligibility).
:::

## Spring Security

![BootUI Spring Security panel](../images/bootui-spring-security.webp)

The Spring Security panel lists the application's filter chains and explains, on a best-effort basis, which rules apply
to an endpoint. It describes local wiring. It does not expose credentials and does not replace a security audit.

On WebFlux the panel reads ordered `SecurityWebFilterChain` beans and lists their `WebFilter` pipelines. Chain matching
stays fully non-blocking and uses each chain's own public reactive matcher.

::: details What the WebFlux view cannot tell you
The explain and annotation-endpoint authorization views run against a sanitized exchange that carries only a path and a
method. They never reuse the current request's headers, cookies, principal, session, body, or network metadata, and
they mark reduced results as best effort rather than guessing context-dependent rules.

Functional `RouterFunction` routes are not listed. The compatibility `sessionManagementPresent` signal is labelled
**Security context** and does not claim that `WebSession` persistence is configured.
:::

## Security Logs

![BootUI Security Logs panel](../images/bootui-security-logs.webp)

The Security Logs panel shows recent Spring Boot audit events, including authentication successes and failures and
authorization denials, whenever Spring Security's audit listeners are active. You can filter by principal, event type,
and time window. The panel summarizes retained event counts by type and masks sensitive data before rendering.

The list refreshes over Server-Sent Events. The browser subscribes to `/bootui/api/security-logs/stream` and re-fetches
when the server signals a new audit event, instead of polling on a timer.

When the panel is enabled and the application has not defined an `AuditEventRepository`, BootUI contributes an
in-memory one, which also lets Spring Boot create its standard audit listeners. Responses are bounded by
`bootui.security-logs.max-logs`, which defaults to `500`. Setting `management.auditevents.enabled=false` leaves the
panel unavailable.

WebFlux behaves identically, because `AuditEventRepository` is framework-neutral: Spring publishes audit events through
the ordinary `ApplicationEventPublisher` on both stacks.

::: details On Quarkus
Events come from CDI security events (`io.quarkus.security.spi.runtime.SecurityEvent`) captured into a capped buffer
rather than from an `AuditEventRepository`. This requires a security extension with
`quarkus.security.events.enabled=true`; otherwise the panel reports unavailable with a clear reason.

Coverage is partial. Only authentication success, authentication failure, and authorization failure events are emitted,
because Quarkus has no logout or session equivalent. Filtering, the type summary, masking, and the
`bootui.security-logs.max-logs` cap are identical to Spring.
:::
