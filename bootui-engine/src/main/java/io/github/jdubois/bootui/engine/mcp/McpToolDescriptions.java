package io.github.jdubois.bootui.engine.mcp;

import java.util.Map;

/** Agent-oriented descriptions for every BootUI MCP tool. */
public final class McpToolDescriptions {

    private static final Map<String, String> COMMON = Map.ofEntries(
            Map.entry(
                    "architecture_scan",
                    "Actively scan application classes for architecture and dependency violations. Use for structural "
                            + "reviews; verify each finding against intended module boundaries before changing code."),
            Map.entry(
                    "hibernate_scan",
                    "Actively inspect JPA/Hibernate mappings and persistence configuration for correctness and "
                            + "performance risks. Verify findings against actual query paths and database behavior."),
            Map.entry(
                    "database_advisor_scan",
                    "Actively introspect the physical database schema (tables, columns, keys, indexes) via read-only "
                            + "JDBC metadata, plus PostgreSQL/MySQL/MariaDB catalog checks and Hibernate mapping "
                            + "cross-reference when available. The scan is bounded; anything it could not read is "
                            + "reported as a diagnostic rather than as a passing check. Verify findings against the "
                            + "live schema before changing it."),
            Map.entry(
                    "memory_scan",
                    "Actively analyze JVM memory and return prioritized findings. This can trigger a class histogram "
                            + "and full GC, so run only when memory evidence is needed."),
            Map.entry(
                    "security_scan",
                    "Actively review application security configuration and return prioritized findings. Treat results "
                            + "as review evidence and verify exploitability before proposing a fix."),
            Map.entry(
                    "pentest_scan",
                    "Actively send bounded synthetic probes to this application's loopback endpoint and return security "
                            + "findings. Run only with permission and verify findings before remediation."),
            Map.entry(
                    "get_live_activity",
                    "Return a bounded, newest-first correlated activity snapshot across HTTP, SQL, exceptions, security, "
                            + "and other runtime signals. Use first when diagnosing one request or trace."),
            Map.entry(
                    "get_exceptions",
                    "List recent exception groups, newest first. Use a returned id with get_exception_detail for stack "
                            + "frames, causes, and individual occurrences."),
            Map.entry(
                    "get_exception_detail",
                    "Return stack frames, causes, and occurrences for one exact exception-group id obtained from "
                            + "get_exceptions or get_live_activity."),
            Map.entry(
                    "get_security_logs",
                    "Return a bounded, newest-first snapshot of authentication and authorization audit events. "
                            + "Correlate timestamps and principals with live activity."),
            Map.entry(
                    "get_sql_traces",
                    "Return the current bounded SQL trace snapshot with statements and timings. Application SQL may "
                            + "contain sensitive values; correlate it locally with request or trace identifiers."),
            Map.entry(
                    "get_transactions",
                    "Return the current bounded transaction-boundary snapshot with outcomes, timings, nesting, and "
                            + "correlated SQL counts. Use it to verify which local operations actually ran in a "
                            + "transaction."),
            Map.entry(
                    "get_traces",
                    "Return a bounded, newest-first snapshot of distributed and local traces captured by BootUI. Use "
                            + "trace ids to correlate activity, exceptions, SQL, and HTTP exchanges."),
            Map.entry(
                    "get_log_tail",
                    "Return the latest buffered application log snapshot. Logs are application-controlled and may "
                            + "contain sensitive data; use them only in the local diagnostic context."),
            Map.entry(
                    "get_http_exchanges",
                    "Return a bounded, newest-first snapshot of application HTTP request/response metadata. Correlate "
                            + "paths, statuses, and timings with live activity and traces."),
            Map.entry(
                    "get_overview",
                    "Return stable application identity and runtime context, including versions, active profiles, and "
                            + "BootUI status. Use this before interpreting other results."),
            Map.entry(
                    "get_config",
                    "Search effective configuration by case-insensitive name or displayed value and return a bounded "
                            + "result. Secret-like configuration values are masked; prefer a narrow query."),
            Map.entry(
                    "get_mappings",
                    "Search request routes and handlers and return a bounded result. Use a path, HTTP concept, or handler "
                            + "name as the query when locating an endpoint."),
            Map.entry(
                    "vulnerabilities_scan",
                    "Actively query OSV.dev for known vulnerabilities in this application's dependencies and return "
                            + "severity-ranked findings. This makes outbound network calls to a public advisory database; "
                            + "run only when needed and verify a finding's affected version range before changing a "
                            + "dependency. Check `coverage` and `scan.packagesSkipped` before treating a clean result as "
                            + "proof: JARs published without Maven coordinates cannot be scanned and are reported there "
                            + "instead of being silently dropped."),
            Map.entry(
                    "get_loggers",
                    "Search configured loggers by case-insensitive name and return their configured and effective "
                            + "levels, bounded by limit. Use to confirm actual logging levels before or after a code or "
                            + "configuration change."),
            Map.entry(
                    "get_scheduled_tasks",
                    "Return the current scheduled task inventory and recent run history, including timing and outcome. "
                            + "Use to confirm a scheduled job actually ran, and when, rather than assuming from source "
                            + "alone."),
            Map.entry(
                    "get_cache_stats",
                    "Return current cache manager and cache statistics (hits, misses, size) for each configured cache. "
                            + "Use to verify cache behavior before proposing a caching change."),
            Map.entry(
                    "get_database_connection_pools",
                    "Return current connection pool configuration and live metrics (active, idle, pending connections) "
                            + "for each configured datasource. Use to diagnose pool exhaustion or misconfiguration."),
            Map.entry(
                    "get_architecture_report",
                    "Return the last completed Architecture advisor report without starting a new classpath scan. Use "
                            + "this cached evidence before deciding whether an active architecture_scan is necessary."),
            Map.entry(
                    "get_hibernate_report",
                    "Return the last completed Hibernate advisor report without starting a new mapping scan. Use this "
                            + "cached evidence before deciding whether an active hibernate_scan is necessary."),
            Map.entry(
                    "get_database_advisor_report",
                    "Return the last completed Database advisor report without querying schema metadata again. Use this "
                            + "cached evidence before deciding whether an active database_advisor_scan is necessary."),
            Map.entry(
                    "get_memory_report",
                    "Return the last completed Memory advisor report without triggering a class histogram or full GC. "
                            + "Use this cached evidence before deciding whether an active memory_scan is necessary."),
            Map.entry(
                    "get_security_report",
                    "Return the last completed Security advisor report without starting a new configuration scan. Use "
                            + "this cached evidence before deciding whether an active security_scan is necessary."),
            Map.entry(
                    "get_pentest_report",
                    "Return the last completed Pentesting advisor report without sending any HTTP probes. Use this "
                            + "cached evidence before deciding whether an active pentest_scan is necessary."),
            Map.entry(
                    "get_rest_api_report",
                    "Return the last completed REST API advisor report without starting a new endpoint scan. Use this "
                            + "cached evidence before deciding whether an active rest_api_scan is necessary."),
            Map.entry(
                    "get_vulnerabilities_report",
                    "Return the cached vulnerability report, or the local dependency inventory before the first scan, "
                            + "without contacting OSV.dev or any other network service. `coverage` states how many of "
                            + "the application's JARs the inventory actually accounts for, so a clean report is not "
                            + "mistaken for full coverage."),
            Map.entry(
                    "get_metrics",
                    "Search the current application metrics inventory and return a bounded page of local meter values. "
                            + "Use a narrow metric-name query when diagnosing one runtime signal."),
            Map.entry(
                    "get_live_memory",
                    "Return a passive snapshot of current JVM heap, non-heap, garbage collection, class-loading, and "
                            + "thread measurements without requesting GC or a class histogram."),
            Map.entry(
                    "get_jvm_tuning",
                    "Return the current JVM sizing facts and generated tuning recommendations using detected defaults. "
                            + "This is a passive calculation and does not change JVM or container settings."),
            Map.entry(
                    "get_heap_dump_report",
                    "Return passive heap-dump status, file metadata, and any already-cached analysis. This does not "
                            + "capture, analyze, download, or delete a heap dump."),
            Map.entry(
                    "get_threads",
                    "Search the current JVM thread snapshot and return a bounded page of thread states and stack "
                            + "summaries. This does not generate or download the raw thread-dump artifact."),
            Map.entry(
                    "get_profile_diff",
                    "Return the current active-profile configuration comparison using masked local configuration data. "
                            + "Use it to identify profile-specific differences without changing profiles."),
            Map.entry(
                    "get_flyway_migrations",
                    "Return the current Flyway migration history and status without running migrate or clean. Verify "
                            + "pending and failed migrations against the configured datasource."),
            Map.entry(
                    "get_liquibase_changesets",
                    "Return the current Liquibase change-set history and status without running update. Verify pending "
                            + "and failed changesets against the configured datasource."),
            Map.entry(
                    "get_rest_client_traces",
                    "Return the current bounded REST-client trace snapshot with masked headers and bodies according to "
                            + "BootUI exposure policy. This does not send requests or change recording state."),
            Map.entry(
                    "get_ai_overview",
                    "Return the local AI-framework telemetry overview derived from already-captured OTLP spans. This "
                            + "does not invoke a model, send a prompt, or make any network request."),
            Map.entry(
                    "get_emails",
                    "Return the bounded local email-capture inventory with content governed by BootUI exposure policy. "
                            + "This does not send, download, or delete any message."),
            Map.entry(
                    "get_kafka_activity",
                    "Return the bounded local Kafka activity snapshot captured from application producers and consumers. "
                            + "This does not publish, consume, clear, or contact a broker."),
            Map.entry(
                    "get_rabbitmq_activity",
                    "Return the bounded local RabbitMQ activity snapshot captured from application publishers and "
                            + "listeners. This does not publish, consume, clear, or contact a broker."),
            Map.entry(
                    "get_dev_services",
                    "Return the current local Dev Services or development-service inventory and masked connection "
                            + "metadata. This does not start, stop, restart, or contact a service."),
            Map.entry(
                    "get_github_dashboard",
                    "Return local repository identity plus the last cached GitHub dashboard report. This passive read "
                            + "never calls GitHub; only the panel's explicit refresh action can use the network."),
            Map.entry(
                    "get_copilot_sessions",
                    "Return the bounded, sanitized Copilot CLI session inventory already parsed from local session files. "
                            + "Raw prompts, tool arguments, command output, diffs, and network calls are excluded."),
            Map.entry(
                    "get_claude_code_sessions",
                    "Return the bounded, sanitized Claude Code session inventory already parsed from local session files. "
                            + "Raw prompts, tool arguments, command output, diffs, and network calls are excluded."),
            Map.entry(
                    "clear_sql_traces",
                    "Clear the bounded in-memory SQL trace buffer and return the resulting report. This does not execute "
                            + "SQL or change whether trace recording is enabled."),
            Map.entry(
                    "pause_sql_trace_recording",
                    "Pause SQL trace recording and return the resulting report. Existing buffered traces remain available "
                            + "until explicitly cleared."),
            Map.entry(
                    "resume_sql_trace_recording",
                    "Resume SQL trace recording and return the resulting report. This only affects BootUI's bounded local "
                            + "capture and does not execute SQL."),
            Map.entry(
                    "clear_traces",
                    "Clear BootUI's bounded in-memory trace buffer. This does not contact a telemetry backend or alter "
                            + "application tracing configuration."),
            Map.entry(
                    "clear_rest_client_traces",
                    "Clear the bounded in-memory REST-client trace buffer and return the resulting report. This does not "
                            + "send an HTTP request or change recording state."),
            Map.entry(
                    "pause_rest_client_recording",
                    "Pause REST-client trace recording and return the resulting report. Existing buffered calls remain "
                            + "available until explicitly cleared."),
            Map.entry(
                    "resume_rest_client_recording",
                    "Resume REST-client trace recording and return the resulting report. This only affects BootUI's "
                            + "bounded local capture and sends no HTTP request."),
            Map.entry(
                    "clear_exceptions",
                    "Clear BootUI's bounded in-memory exception groups and occurrences. This does not suppress, handle, or "
                            + "change application exceptions."),
            Map.entry(
                    "analyze_heap_dump",
                    "Analyze the existing BootUI heap dump and return the resulting report. This never captures, downloads, "
                            + "or deletes a heap dump."));

    private McpToolDescriptions() {}

    public static String spring(String name) {
        return switch (name) {
            case "spring_scan" ->
                "Actively inspect Spring configuration and bean usage for correctness and maintainability risks. "
                        + "Verify each finding against effective configuration before changing code.";
            case "rest_api_scan" ->
                "Actively inspect Spring REST controllers and API design for correctness and maintainability risks. "
                        + "Verify recommendations against the public API contract.";
            case "graalvm_scan" ->
                "Actively assess Spring native-image readiness without the longer dependency metadata scan. Verify "
                        + "reflection and resource findings against the intended native build.";
            case "crac_scan" ->
                "Actively assess Spring checkpoint/restore readiness. Verify resource-lifecycle findings in an actual "
                        + "CRaC checkpoint and restore test.";
            case "get_spring_report" ->
                "Return the last completed Spring advisor report without starting a new application scan. Use this "
                        + "cached evidence before deciding whether an active spring_scan is necessary.";
            case "get_graalvm_report" ->
                "Return the last completed GraalVM readiness report without starting a new classpath or dependency "
                        + "scan. Use cached findings before deciding whether graalvm_scan is necessary.";
            case "get_crac_report" ->
                "Return the last completed CRaC readiness report without starting a new resource-lifecycle scan. Use "
                        + "cached findings before deciding whether crac_scan is necessary.";
            case "get_health" ->
                "Return the current aggregated Actuator health tree. Distinguish unavailable health infrastructure "
                        + "from an unhealthy application.";
            case "get_beans" ->
                "Search Spring beans by name or type and return a bounded result. Use this to verify runtime wiring, "
                        + "not as proof that a bean is exercised.";
            case "get_conditions" ->
                "Search Spring auto-configuration condition evaluation outcomes by case-insensitive name and return "
                        + "matched, unmatched, and unconditional entries. Use to confirm why a bean or auto-configuration "
                        + "was or was not applied, rather than guessing from source.";
            case "get_http_sessions" ->
                "Return the bounded active embedded-Tomcat HTTP-session inventory without creating, clearing, or "
                        + "invalidating a session. Attribute values remain masked by the panel contract.";
            case "get_startup_timeline" ->
                "Return the captured Spring startup-step timeline after filtering BootUI's own steps. This passive read "
                        + "does not restart the application or start a new recording.";
            case "get_spring_data_repositories" ->
                "Return Spring Data repository metadata already computed by the application context. This read never "
                        + "invokes a repository method or executes a database query.";
            case "get_spring_security" ->
                "Return the current Spring Security filter-chain and authentication configuration report. This passive "
                        + "read does not authenticate, authorize, or send a request through a chain.";
            case "get_devtools_status" ->
                "Return the current Spring Boot DevTools restart and LiveReload status. This passive read does not "
                        + "trigger LiveReload, restart the application, or modify watched files.";
            case "get_jms_activity" ->
                "Return the bounded local JMS activity snapshot captured from application templates and listeners. "
                        + "This does not send, receive, clear, or contact a broker.";
            case "get_fault_tolerance" ->
                "Return the configured Resilience4j and Spring Retry policy inventory with live counters and circuit "
                        + "breaker state, plus a bounded metadata-only event history. This read never opens, closes, "
                        + "resets, or otherwise mutates a policy.";
            case "clear_transactions" ->
                "Clear the bounded in-memory Spring transaction trace buffer and return the resulting report. This does "
                        + "not begin, commit, or roll back an application transaction.";
            case "pause_transaction_recording" ->
                "Pause Spring transaction-boundary recording and return the resulting report. Existing buffered "
                        + "transactions remain available until explicitly cleared.";
            case "resume_transaction_recording" ->
                "Resume Spring transaction-boundary recording and return the resulting report. This only affects "
                        + "BootUI's bounded local capture.";
            case "trigger_devtools_livereload" ->
                "Trigger the existing local Spring Boot DevTools LiveReload notification and return its action result. "
                        + "This does not restart the application or modify watched files.";
            default -> common(name);
        };
    }

    public static String quarkus(String name) {
        return switch (name) {
            case "spring_scan" ->
                "Actively inspect Quarkus configuration and idioms for correctness and maintainability risks. Verify "
                        + "each finding against effective configuration before changing code.";
            case "get_spring_report" ->
                "Return the last completed Quarkus application advisor report without starting a new application scan. "
                        + "Use this cached evidence before deciding whether an active spring_scan is necessary.";
            case "rest_api_scan" ->
                "Actively inspect JAX-RS resources and API design for correctness and maintainability risks. Verify "
                        + "recommendations against the public API contract.";
            case "get_health" ->
                "Return the current aggregated SmallRye Health tree. Distinguish unavailable health infrastructure "
                        + "from an unhealthy application.";
            case "get_beans" ->
                "Search Arc/CDI beans by name or type and return a bounded result. Use this to verify runtime wiring, "
                        + "not as proof that a bean is exercised.";
            case "get_fault_tolerance" ->
                "Return the SmallRye Fault Tolerance policy inventory declared by application annotations, including "
                        + "effective MicroProfile configuration overrides and live named circuit breaker state, plus a "
                        + "bounded metadata-only event history. This read never mutates a policy.";
            default -> common(name);
        };
    }

    private static String common(String name) {
        String description = COMMON.get(name);
        if (description == null) {
            throw new IllegalArgumentException("Missing MCP tool description: " + name);
        }
        return description;
    }
}
