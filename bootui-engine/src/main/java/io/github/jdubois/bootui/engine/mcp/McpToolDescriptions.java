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
                            + "name as the query when locating an endpoint."));

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
            case "get_health" ->
                "Return the current aggregated Actuator health tree. Distinguish unavailable health infrastructure "
                        + "from an unhealthy application.";
            case "get_beans" ->
                "Search Spring beans by name or type and return a bounded result. Use this to verify runtime wiring, "
                        + "not as proof that a bean is exercised.";
            default -> common(name);
        };
    }

    public static String quarkus(String name) {
        return switch (name) {
            case "spring_scan" ->
                "Actively inspect Quarkus configuration and idioms for correctness and maintainability risks. Verify "
                        + "each finding against effective configuration before changing code.";
            case "rest_api_scan" ->
                "Actively inspect JAX-RS resources and API design for correctness and maintainability risks. Verify "
                        + "recommendations against the public API contract.";
            case "get_health" ->
                "Return the current aggregated SmallRye Health tree. Distinguish unavailable health infrastructure "
                        + "from an unhealthy application.";
            case "get_beans" ->
                "Search Arc/CDI beans by name or type and return a bounded result. Use this to verify runtime wiring, "
                        + "not as proof that a bean is exercised.";
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
