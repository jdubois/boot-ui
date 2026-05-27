# BootUI feature details

BootUI panels are documented here in the same order as the application menu. When a panel's backing infrastructure is missing, the sidebar keeps the route visible but dims it so you can tell at a glance that the panel has no live data for the current application.

## Overview

The Overview panel gives a fast summary of the running application: application name, Spring Boot version, Java version, active profiles, web type, ports, startup duration when available, activation reason, and local safety state. It is the first place to confirm whether BootUI is active for the reason you expect.

![BootUI Overview panel](images/bootui-overview.png)

## Startup Timeline

The Startup Timeline panel visualizes Spring Boot startup steps from Actuator startup data. It helps identify expensive startup phases, slow bean initialization, and the overall application startup shape. If the host app does not expose startup data, the panel shows an empty state instead of failing.

![BootUI Startup Timeline panel](images/bootui-startup-timeline.png)

## Memory

The Memory panel summarizes JVM heap and non-heap usage, memory pools, garbage collectors, and selected runtime memory settings. It also suggests JVM options that are useful during local development when memory pressure or startup behavior needs tuning.

![BootUI Memory panel](images/bootui-memory.png)

## Health

The Health panel displays the Actuator health tree, including nested contributors and detailed status information when the host app exposes it. It keeps unavailable health data separate from unhealthy application state so missing Actuator infrastructure is clear.

![BootUI Health panel](images/bootui-health.png)

## Metrics

The Metrics panel browses Micrometer meters exposed by Actuator. You can inspect meter descriptions, base units, tags, available measurements, and render a local live chart for a selected metric/tag combination.

![BootUI Metrics panel](images/bootui-metrics.png)

## Conditions

The Conditions panel explains Spring Boot auto-configuration decisions. It groups positive matches, negative matches, and unconditional classes so you can see why an auto-configuration applied or why it was skipped.

![BootUI Conditions panel](images/bootui-conditions.png)

## Beans

The Beans panel helps answer which Spring beans exist and where they came from. It supports search across bean names, classes, packages, scopes, resources, dependencies, aliases, and BootUI classifications such as application, BootUI, Spring framework, Java/Jakarta, and other beans.

![BootUI Beans panel](images/bootui-beans.png)

## Mappings

The Mappings panel lists HTTP routes from Actuator mappings data. It shows request methods, path patterns, handlers, and produces/consumes metadata so the running application's web surface is visible without reading controllers manually.

![BootUI Mappings panel](images/bootui-mappings.png)

## Configuration

The Configuration panel shows effective configuration properties, sources, metadata descriptions, defaults when known, active profiles, and masked values. It can create, update, and delete local runtime overrides persisted to `.bootui/application-bootui.properties`, with restart and rebinding caveats shown for every mutation.

![BootUI Configuration panel](images/bootui-configuration.png)

## Profile Diff

The Profile Diff panel compares profile-specific property sources and values. It is useful for understanding what changes between local development profiles while still routing browser-visible names and values through BootUI's secret masking rules.

![BootUI Profile Diff panel](images/bootui-profile-diff.png)

## Loggers

The Loggers panel lists runtime logger configuration from Actuator. It shows configured and effective levels, supports search, and can update or clear logger levels without restarting the application.

![BootUI Loggers panel](images/bootui-loggers.png)

## Log Tail

The Log Tail panel reads recent local application logs and streams new log events from the running process. It is intended for quick local diagnosis without leaving the BootUI console.

![BootUI Log Tail panel](images/bootui-log-tail.png)

## HTTP Probe

The HTTP Probe panel sends local-only requests to the running application and displays response status, headers, duration, and body. It is designed for quick route checks from inside the same local development context as BootUI.

![BootUI HTTP Probe panel](images/bootui-http-probe.png)

## DevTools

The DevTools panel reports Spring Boot DevTools availability, LiveReload status, and restart support. Restart actions are shown only when available and require explicit confirmation before execution.

![BootUI DevTools panel](images/bootui-devtools.png)

## Dev Services

The Dev Services panel surfaces local development services discovered from Docker Compose snapshots, Testcontainers beans, and service connection metadata. It masks sensitive connection information, can show bounded logs for supported services, and shows restart controls only for supported Testcontainers services when `bootui.dev-services.restart-enabled=true`.

![BootUI Dev Services panel](images/bootui-dev-services.png)

## Scheduled Tasks

The Scheduled Tasks panel lists scheduled jobs registered with Spring scheduling infrastructure. It shows task type and trigger metadata so background activity is visible during local development.

![BootUI Scheduled Tasks panel](images/bootui-scheduled-tasks.png)

## Data

The Data panel inspects Spring Data repositories. It shows repository interfaces, domain types, ID types, and query methods, and degrades to a clear empty state when Spring Data is not present or no repositories are registered.

![BootUI Data panel](images/bootui-data.png)

## Cache

The Cache panel inspects Spring Cache infrastructure. It lists cache manager beans, known caches, native implementations, safe local sizes, Micrometer cache metrics when registered, and discovered `@Cacheable`, `@CachePut`, and `@CacheEvict` operations. Cache clear actions are enabled by default for local development, require explicit browser confirmation, and can be disabled with `bootui.cache.clear-enabled=false`.

![BootUI Cache panel](images/bootui-cache.png)

## Traces

The Traces panel shows distributed tracing spans collected by BootUI's embedded OTLP/HTTP receiver at `/bootui/api/otlp/v1/traces`. Any Spring Boot service that uses Micrometer Tracing with the OpenTelemetry bridge — including the host application itself when self-tracing is enabled — can export to this endpoint and have its traces appear here. The list shows the most recent traces with service name, root span name, status, duration, and span count; opening a trace renders a waterfall view of its spans so you can see latency contributions, errors, and parent/child relationships across services. Spans emitted by BootUI's own API are filtered out by default to keep the view focused on application traffic; this can be tuned with `bootui.telemetry.exclude-self-spans=false`. The in-memory trace buffer is bounded by `bootui.telemetry.max-traces` and is reset on application restart or via the panel's clear action.

![BootUI Traces panel](images/bootui-traces.png)

## AI Usage

The AI Usage panel summarizes Spring AI activity collected from OpenTelemetry spans emitted by Spring AI's built-in observability. It groups chat client and chat model spans by conversation so you can see request count, token usage (prompt, completion, total), latency, model, and the prompt/response snippet when Spring AI is configured to capture content (`spring.ai.chat.client.observations.log-prompt`, `spring.ai.chat.observations.log-prompt`, `spring.ai.chat.observations.log-completion`). A small inline chart shows total token usage over recent calls so you can spot expensive interactions during local development. Vector store and embedding spans appear alongside chat spans when present. As with the Traces panel, data is sourced from the embedded OTLP receiver, is in-memory only, and is cleared on restart.

![BootUI AI Usage panel](images/bootui-ai.png)

## Security

The Security panel inspects Spring Security filter chains and provides best-effort endpoint rule explanations. It is meant to explain local security wiring without exposing credentials or replacing a full security audit.

![BootUI Security panel](images/bootui-security.png)

## Vulnerabilities

The Vulnerabilities panel shows dependency inventory and local OSV vulnerability scan results. It helps identify known vulnerable dependencies from the running project's dependency set during the local development loop.

![BootUI Vulnerabilities panel](images/bootui-vulnerabilities.png)
