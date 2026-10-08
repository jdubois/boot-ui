package io.github.jdubois.bootui.engine.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a caller needs to call a tool right the first time ({@code docs/PLAN-v2.md} M4-21): one example of its
 * arguments, where an {@code id} comes from, and the words a {@code query} understands. Agents in the benchmark spent
 * almost half their calls on {@code --help}, mostly to learn these three things, so they are written once here and
 * projected into the CLI's help, the MCP descriptions, and the missing-argument errors.
 *
 * <p>Values in angle brackets, such as {@code <id>}, are placeholders for an opaque value another tool returns; every
 * other value is one a caller would plausibly type.
 */
public final class McpToolGuide {

    /**
     * Where a tool's {@code id} comes from.
     *
     * @param noun what the id is, such as {@code an observation id}
     * @param fromTools the tools whose answers carry it, empty when the caller names it
     */
    public record IdSource(String noun, List<String> fromTools) {

        public IdSource {
            fromTools = List.copyOf(fromTools);
        }

        /** The id described for an MCP client: {@code an observation id from get_runtime_insights}. */
        public String describe() {
            return fromTools.isEmpty() ? noun : noun + " from " + String.join(" or ", fromTools);
        }
    }

    private static final Map<String, Map<String, Object>> EXAMPLES = Map.ofEntries(
            Map.entry("get_live_activity", args("query", "SQL", "limit", 20)),
            Map.entry("get_sql_traces", args("query", "orders")),
            Map.entry("get_startup_timeline", args("limit", 10)),
            Map.entry("get_log_tail", args("query", "WARN")),
            Map.entry("get_copilot_sessions", args("limit", 5)),
            Map.entry("get_claude_code_sessions", args("limit", 5)),
            Map.entry("get_vulnerabilities_report", args("query", "CRITICAL")),
            Map.entry("get_agent_status", args("query", "executors")),
            Map.entry("get_security_logs", args("limit", 20)),
            Map.entry("get_traces", args("limit", 20)),
            Map.entry("get_http_exchanges", args("limit", 20)),
            Map.entry("get_http_routes", args("limit", 5)),
            Map.entry("get_runtime_insights", args("query", "security")),
            Map.entry("get_config", args("query", "server.port")),
            Map.entry("get_beans", args("query", "dataSource")),
            Map.entry("get_mappings", args("query", "/api")),
            Map.entry("get_loggers", args("query", "org.hibernate.SQL")),
            Map.entry("get_conditions", args("query", "DataSource")),
            Map.entry("get_metrics", args("query", "jvm.memory")),
            Map.entry("get_threads", args("query", "http", "limit", 10)),
            Map.entry("get_code_inventory", args("query", "changed")),
            Map.entry("get_code_paths", args("query", "GET /orders")),
            Map.entry("get_side_effects", args("query", "processes", "limit", 20)),
            Map.entry("get_request_profile", args("id", "<id>")),
            Map.entry("get_runtime_insight", args("id", "<id>")),
            Map.entry("get_runtime_impact", args("id", "OrderService#total")),
            Map.entry("get_runtime_run_comparison", args("id", "previous")),
            Map.entry("get_exception_detail", args("id", "<id>")),
            Map.entry("start_method_probe", args("id", "com.example.OrderService#total")),
            Map.entry("get_method_probe", args("id", "<id>")),
            Map.entry("get_architecture_rule_violations", ruleViolations()),
            Map.entry("get_spring_rule_violations", ruleViolations()),
            Map.entry("get_hibernate_rule_violations", ruleViolations()),
            Map.entry("get_memory_rule_violations", ruleViolations()),
            Map.entry("get_rest_api_rule_violations", ruleViolations()),
            Map.entry("get_security_rule_violations", ruleViolations()),
            Map.entry("get_database_advisor_rule_violations", ruleViolations()));

    private static final Map<String, IdSource> ID_SOURCES = Map.ofEntries(
            Map.entry(
                    "get_request_profile",
                    new IdSource(
                            "a profileable request or execution id (an entry id or an observation's exemplarRequestId)",
                            List.of("get_live_activity", "get_runtime_insights"))),
            Map.entry("get_runtime_insight", new IdSource("an observation id", List.of("get_runtime_insights"))),
            Map.entry(
                    "get_runtime_impact",
                    new IdSource(
                            "a route, bean, class, Class#method or a bare method name, repository, table, cache, host, or"
                                    + " event type",
                            List.of())),
            Map.entry(
                    "get_runtime_run_comparison",
                    new IdSource("previous (the default), or a run id from the runs it answers with", List.of())),
            Map.entry(
                    "get_exception_detail",
                    new IdSource("an exception group id", List.of("get_exceptions", "get_live_activity"))),
            Map.entry(
                    "start_method_probe",
                    new IdSource("an application method (binary.Class#name)", List.of("get_code_paths"))),
            Map.entry("get_method_probe", new IdSource("a probe id", List.of("start_method_probe"))),
            Map.entry("get_architecture_rule_violations", ruleSource("get_architecture_report")),
            Map.entry("get_spring_rule_violations", ruleSource("get_spring_report")),
            Map.entry("get_hibernate_rule_violations", ruleSource("get_hibernate_report")),
            Map.entry("get_memory_rule_violations", ruleSource("get_memory_report")),
            Map.entry("get_rest_api_rule_violations", ruleSource("get_rest_api_report")),
            Map.entry("get_security_rule_violations", ruleSource("get_security_report")),
            Map.entry("get_database_advisor_rule_violations", ruleSource("get_database_advisor_report")));

    private static final Map<String, String> QUERY_WORDS = Map.ofEntries(
            Map.entry(
                    "get_live_activity",
                    "an entry type (SQL, EXCEPTION, REST_CLIENT, ...), a severity (SLOW, WARN, ERROR), or text in the"
                            + " summary, detail, path, or method"),
            Map.entry("get_agent_status", "a sensor id, such as executors, for its hooks and self-test steps"),
            Map.entry(
                    "get_sql_traces",
                    "text in the SQL, category, call site, or error, or a request, trace, or execution id"),
            Map.entry("get_startup_timeline", "a step name or tag value, such as a bean name"),
            Map.entry("get_log_tail", "a level, logger, thread, or text in the message"),
            Map.entry("get_copilot_sessions", "a session id, model, working directory, status, or last activity"),
            Map.entry("get_claude_code_sessions", "a session id, model, working directory, status, or last activity"),
            Map.entry(
                    "get_vulnerabilities_report",
                    "group:artifact coordinates, a severity, or an advisory id or alias such as a CVE"),
            Map.entry(
                    "get_runtime_insights",
                    "empty (the default list), all (every observation), new, security, diff, latency, an observation"
                            + " kind such as repeated-selects, or a route, table, bean, or class"),
            Map.entry(
                    "get_code_inventory",
                    "changed (the default), never-executed, not-tracked, executed, dependencies, or a package,"
                            + " class, or method name"),
            Map.entry("get_code_paths", "empty (the slowest routes), or a route or method name"),
            Map.entry(
                    "get_side_effects",
                    "a sensor id such as processes, network, files, environment, blocking, thread-activity,"
                            + " thread-locals, or resources, which also lists its hooks, not captured (outbound calls no panel"
                            + " captured), or part of a route, target, client, or call site"));

    private McpToolGuide() {}

    /** One example of the tool's arguments, in the order a command line passes them; empty when it takes none. */
    public static Map<String, Object> example(String tool) {
        return EXAMPLES.getOrDefault(tool, Map.of());
    }

    /** Where the tool's {@code id} comes from, or {@code null} when it takes no {@code id}. */
    public static IdSource idSource(String tool) {
        return ID_SOURCES.get(tool);
    }

    /** The words the tool's {@code query} understands beyond a plain substring, or {@code null}. */
    public static String queryWords(String tool) {
        return QUERY_WORDS.get(tool);
    }

    /** Every tool that has an example. */
    public static Set<String> toolsWithExamples() {
        return EXAMPLES.keySet();
    }

    /** Every tool that has an id source. */
    public static Set<String> toolsWithIdSources() {
        return ID_SOURCES.keySet();
    }

    /** Every tool that has query words. */
    public static Set<String> toolsWithQueryWords() {
        return QUERY_WORDS.keySet();
    }

    private static IdSource ruleSource(String report) {
        return new IdSource("a rule id and the violationDetails.scanId", List.of(report));
    }

    private static Map<String, Object> ruleViolations() {
        return args("id", "<ruleId>", "scanId", "<scanId>", "limit", 100);
    }

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            arguments.put((String) pairs[i], pairs[i + 1]);
        }
        return Collections.unmodifiableMap(arguments);
    }
}
