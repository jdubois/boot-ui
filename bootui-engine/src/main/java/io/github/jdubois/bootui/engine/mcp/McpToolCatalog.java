package io.github.jdubois.bootui.engine.mcp;

import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single, declarative source of truth for every BootUI MCP tool: its name, argument schema, backing
 * panel, action-vs-read kind, and which request stacks advertise it.
 *
 * <p>Each adapter tool registry ({@code BootUiMcpTools}, {@code ReactiveBootUiMcpTools},
 * {@code QuarkusMcpTools}) supplies only a name, a description, and a handler; the schema, panel id, and
 * action flag are read back from this catalog. A tool an adapter registers under a name this catalog does
 * not know fails fast at startup, and the three facts that decide how a tool is gated and invoked can no
 * longer be spelled differently in three places.
 *
 * <p>This catalog is also what the {@code bootui} CLI's command tree is generated from, so a tool added to
 * the MCP registry cannot silently miss the CLI: the generated command manifest is derived from these
 * entries and pinned by a build-time check.
 *
 * <p>Membership here is <em>advertisement intent</em>, not runtime availability. A tool listed for a stack is
 * still only advertised when its controller/resource bean exists and its panel is available, exactly as
 * before.
 */
public final class McpToolCatalog {

    /** A BootUI request stack that can advertise MCP tools. */
    public enum Stack {
        /** Spring Boot servlet (Spring MVC), the complete reference stack. */
        SPRING_MVC,
        /** Spring Boot reactive (Spring WebFlux). */
        SPRING_WEBFLUX,
        /** The Quarkus extension. */
        QUARKUS
    }

    private static final Set<Stack> ALL_STACKS = EnumSet.allOf(Stack.class);

    /**
     * One catalog entry.
     *
     * @param name the machine name advertised to MCP clients (e.g. {@code architecture_scan})
     * @param schema the argument-schema shape
     * @param panelId the {@code BootUiPanels} id backing this tool; used to enforce panel toggles
     * @param action {@code true} when the tool changes state and must be refused on a read-only panel
     * @param stacks the request stacks whose registries advertise this tool
     */
    public record Entry(String name, McpToolSchema schema, String panelId, boolean action, Set<Stack> stacks) {

        public Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(schema, "schema");
            Objects.requireNonNull(panelId, "panelId");
            stacks = Set.copyOf(Objects.requireNonNull(stacks, "stacks"));
            if (stacks.isEmpty()) {
                throw new IllegalArgumentException("MCP tool " + name + " must be advertised by at least one stack");
            }
            BootUiPanels.Panel panel = BootUiPanels.byId(panelId)
                    .orElseThrow(
                            () -> new IllegalArgumentException("Unknown BootUI panel id for MCP tool: " + panelId));
            if (action && !panel.actionCapable()) {
                throw new IllegalArgumentException(
                        "MCP action tool " + name + " must reference an action-capable panel: " + panelId);
            }
        }

        /** Whether {@code stack} advertises this tool. */
        public boolean advertisedBy(Stack stack) {
            return stacks.contains(stack);
        }
    }

    private static final List<Entry> ENTRIES = List.of(
            entry("architecture_scan", McpToolSchema.NONE, BootUiPanels.ARCHITECTURE, true, ALL_STACKS),
            entry("get_architecture_report", McpToolSchema.NONE, BootUiPanels.ARCHITECTURE, false, ALL_STACKS),
            entry("spring_scan", McpToolSchema.NONE, BootUiPanels.SPRING, true, ALL_STACKS),
            entry("get_spring_report", McpToolSchema.NONE, BootUiPanels.SPRING, false, ALL_STACKS),
            entry("hibernate_scan", McpToolSchema.NONE, BootUiPanels.HIBERNATE, true, ALL_STACKS),
            entry("get_hibernate_report", McpToolSchema.NONE, BootUiPanels.HIBERNATE, false, ALL_STACKS),
            entry("memory_scan", McpToolSchema.NONE, BootUiPanels.MEMORY, true, ALL_STACKS),
            entry("get_memory_report", McpToolSchema.NONE, BootUiPanels.MEMORY, false, ALL_STACKS),
            entry("security_scan", McpToolSchema.NONE, BootUiPanels.SECURITY, true, ALL_STACKS),
            entry("get_security_report", McpToolSchema.NONE, BootUiPanels.SECURITY, false, ALL_STACKS),
            entry("pentest_scan", McpToolSchema.NONE, BootUiPanels.PENTESTING, true, ALL_STACKS),
            entry("get_pentest_report", McpToolSchema.NONE, BootUiPanels.PENTESTING, false, ALL_STACKS),
            entry("rest_api_scan", McpToolSchema.NONE, BootUiPanels.REST_API, true, ALL_STACKS),
            entry("get_rest_api_report", McpToolSchema.NONE, BootUiPanels.REST_API, false, ALL_STACKS),
            entry(
                    "graalvm_scan",
                    McpToolSchema.NONE,
                    BootUiPanels.GRAALVM,
                    true,
                    Set.of(Stack.SPRING_MVC, Stack.SPRING_WEBFLUX)),
            entry(
                    "get_graalvm_report",
                    McpToolSchema.NONE,
                    BootUiPanels.GRAALVM,
                    false,
                    Set.of(Stack.SPRING_MVC, Stack.SPRING_WEBFLUX)),
            entry(
                    "crac_scan",
                    McpToolSchema.NONE,
                    BootUiPanels.CRAC,
                    true,
                    Set.of(Stack.SPRING_MVC, Stack.SPRING_WEBFLUX)),
            entry(
                    "get_crac_report",
                    McpToolSchema.NONE,
                    BootUiPanels.CRAC,
                    false,
                    Set.of(Stack.SPRING_MVC, Stack.SPRING_WEBFLUX)),
            entry("database_advisor_scan", McpToolSchema.NONE, BootUiPanels.DATABASE_ADVISOR, true, ALL_STACKS),
            entry("get_database_advisor_report", McpToolSchema.NONE, BootUiPanels.DATABASE_ADVISOR, false, ALL_STACKS),
            entry("postgresql_read", McpToolSchema.NONE, BootUiPanels.POSTGRESQL, true, ALL_STACKS),
            entry("get_postgresql_report", McpToolSchema.NONE, BootUiPanels.POSTGRESQL, false, ALL_STACKS),
            entry("vulnerabilities_scan", McpToolSchema.NONE, BootUiPanels.VULNERABILITIES, true, ALL_STACKS),
            entry("get_vulnerabilities_report", McpToolSchema.NONE, BootUiPanels.VULNERABILITIES, false, ALL_STACKS),
            entry("get_live_activity", McpToolSchema.LIMIT, BootUiPanels.ACTIVITY, false, ALL_STACKS),
            entry("get_exceptions", McpToolSchema.NONE, BootUiPanels.EXCEPTIONS, false, ALL_STACKS),
            entry("get_exception_detail", McpToolSchema.ID, BootUiPanels.EXCEPTIONS, false, ALL_STACKS),
            entry("clear_exceptions", McpToolSchema.NONE, BootUiPanels.EXCEPTIONS, true, ALL_STACKS),
            entry("get_security_logs", McpToolSchema.LIMIT, BootUiPanels.SECURITY_LOGS, false, ALL_STACKS),
            entry("get_sql_traces", McpToolSchema.NONE, BootUiPanels.SQL_TRACE, false, ALL_STACKS),
            entry("clear_sql_traces", McpToolSchema.NONE, BootUiPanels.SQL_TRACE, true, ALL_STACKS),
            entry("pause_sql_trace_recording", McpToolSchema.NONE, BootUiPanels.SQL_TRACE, true, ALL_STACKS),
            entry("resume_sql_trace_recording", McpToolSchema.NONE, BootUiPanels.SQL_TRACE, true, ALL_STACKS),
            entry(
                    "get_transactions",
                    McpToolSchema.NONE,
                    BootUiPanels.TRANSACTIONS,
                    false,
                    Set.of(Stack.SPRING_MVC, Stack.SPRING_WEBFLUX)),
            entry(
                    "clear_transactions",
                    McpToolSchema.NONE,
                    BootUiPanels.TRANSACTIONS,
                    true,
                    Set.of(Stack.SPRING_MVC, Stack.SPRING_WEBFLUX)),
            entry(
                    "pause_transaction_recording",
                    McpToolSchema.NONE,
                    BootUiPanels.TRANSACTIONS,
                    true,
                    Set.of(Stack.SPRING_MVC, Stack.SPRING_WEBFLUX)),
            entry(
                    "resume_transaction_recording",
                    McpToolSchema.NONE,
                    BootUiPanels.TRANSACTIONS,
                    true,
                    Set.of(Stack.SPRING_MVC, Stack.SPRING_WEBFLUX)),
            entry("get_traces", McpToolSchema.LIMIT, BootUiPanels.TRACES, false, ALL_STACKS),
            entry("clear_traces", McpToolSchema.NONE, BootUiPanels.TRACES, true, ALL_STACKS),
            entry("get_log_tail", McpToolSchema.NONE, BootUiPanels.LOG_TAIL, false, ALL_STACKS),
            entry("get_http_exchanges", McpToolSchema.LIMIT, BootUiPanels.HTTP_EXCHANGES, false, ALL_STACKS),
            entry("get_overview", McpToolSchema.NONE, BootUiPanels.OVERVIEW, false, ALL_STACKS),
            entry("get_health", McpToolSchema.NONE, BootUiPanels.HEALTH, false, ALL_STACKS),
            entry("get_config", McpToolSchema.QUERY_LIMIT, BootUiPanels.CONFIG, false, ALL_STACKS),
            entry("get_beans", McpToolSchema.QUERY_LIMIT, BootUiPanels.BEANS, false, ALL_STACKS),
            entry("get_mappings", McpToolSchema.QUERY_LIMIT, BootUiPanels.MAPPINGS, false, ALL_STACKS),
            entry("get_loggers", McpToolSchema.QUERY_LIMIT, BootUiPanels.LOGGERS, false, ALL_STACKS),
            entry(
                    "get_conditions",
                    McpToolSchema.QUERY_LIMIT,
                    BootUiPanels.CONDITIONS,
                    false,
                    Set.of(Stack.SPRING_MVC, Stack.SPRING_WEBFLUX)),
            entry("get_scheduled_tasks", McpToolSchema.NONE, BootUiPanels.SCHEDULED, false, ALL_STACKS),
            entry("get_fault_tolerance", McpToolSchema.NONE, BootUiPanels.FAULT_TOLERANCE, false, ALL_STACKS),
            entry("get_cache_stats", McpToolSchema.NONE, BootUiPanels.CACHE, false, ALL_STACKS),
            entry(
                    "get_database_connection_pools",
                    McpToolSchema.NONE,
                    BootUiPanels.DATABASE_CONNECTION_POOLS,
                    false,
                    ALL_STACKS),
            entry("get_metrics", McpToolSchema.QUERY_LIMIT, BootUiPanels.METRICS, false, ALL_STACKS),
            entry("get_http_sessions", McpToolSchema.NONE, BootUiPanels.HTTP_SESSIONS, false, Set.of(Stack.SPRING_MVC)),
            entry("get_live_memory", McpToolSchema.NONE, BootUiPanels.LIVE_MEMORY, false, ALL_STACKS),
            entry("get_jvm_tuning", McpToolSchema.NONE, BootUiPanels.JVM_TUNING, false, ALL_STACKS),
            entry("get_heap_dump_report", McpToolSchema.NONE, BootUiPanels.HEAP_DUMP, false, ALL_STACKS),
            entry("analyze_heap_dump", McpToolSchema.NONE, BootUiPanels.HEAP_DUMP, true, ALL_STACKS),
            entry("get_threads", McpToolSchema.QUERY_LIMIT, BootUiPanels.THREADS, false, ALL_STACKS),
            entry(
                    "get_startup_timeline",
                    McpToolSchema.NONE,
                    BootUiPanels.STARTUP,
                    false,
                    Set.of(Stack.SPRING_MVC, Stack.SPRING_WEBFLUX)),
            entry("get_profile_diff", McpToolSchema.NONE, BootUiPanels.PROFILE_DIFF, false, ALL_STACKS),
            entry(
                    "get_spring_data_repositories",
                    McpToolSchema.NONE,
                    BootUiPanels.DATA,
                    false,
                    Set.of(Stack.SPRING_MVC, Stack.SPRING_WEBFLUX)),
            entry("get_flyway_migrations", McpToolSchema.NONE, BootUiPanels.FLYWAY, false, ALL_STACKS),
            entry("get_liquibase_changesets", McpToolSchema.NONE, BootUiPanels.LIQUIBASE, false, ALL_STACKS),
            entry(
                    "get_spring_security",
                    McpToolSchema.NONE,
                    BootUiPanels.SPRING_SECURITY,
                    false,
                    Set.of(Stack.SPRING_MVC, Stack.SPRING_WEBFLUX)),
            entry("get_rest_client_traces", McpToolSchema.NONE, BootUiPanels.REST_CLIENT_TRACE, false, ALL_STACKS),
            entry("clear_rest_client_traces", McpToolSchema.NONE, BootUiPanels.REST_CLIENT_TRACE, true, ALL_STACKS),
            entry("pause_rest_client_recording", McpToolSchema.NONE, BootUiPanels.REST_CLIENT_TRACE, true, ALL_STACKS),
            entry("resume_rest_client_recording", McpToolSchema.NONE, BootUiPanels.REST_CLIENT_TRACE, true, ALL_STACKS),
            entry("get_ai_overview", McpToolSchema.NONE, BootUiPanels.AI, false, ALL_STACKS),
            entry("get_emails", McpToolSchema.NONE, BootUiPanels.EMAIL, false, ALL_STACKS),
            entry("get_kafka_activity", McpToolSchema.NONE, BootUiPanels.KAFKA, false, ALL_STACKS),
            entry("get_rabbitmq_activity", McpToolSchema.NONE, BootUiPanels.RABBITMQ, false, ALL_STACKS),
            entry(
                    "get_jms_activity",
                    McpToolSchema.NONE,
                    BootUiPanels.JMS,
                    false,
                    Set.of(Stack.SPRING_MVC, Stack.SPRING_WEBFLUX)),
            entry(
                    "get_devtools_status",
                    McpToolSchema.NONE,
                    BootUiPanels.DEVTOOLS,
                    false,
                    Set.of(Stack.SPRING_MVC, Stack.SPRING_WEBFLUX)),
            entry(
                    "trigger_devtools_livereload",
                    McpToolSchema.NONE,
                    BootUiPanels.DEVTOOLS,
                    true,
                    Set.of(Stack.SPRING_MVC, Stack.SPRING_WEBFLUX)),
            entry("get_dev_services", McpToolSchema.NONE, BootUiPanels.DEV_SERVICES, false, ALL_STACKS),
            entry("get_github_dashboard", McpToolSchema.NONE, BootUiPanels.GITHUB, false, ALL_STACKS),
            entry("get_copilot_sessions", McpToolSchema.NONE, BootUiPanels.COPILOT, false, ALL_STACKS),
            entry("get_claude_code_sessions", McpToolSchema.NONE, BootUiPanels.CLAUDE_CODE, false, ALL_STACKS));

    private static final Map<String, Entry> BY_NAME =
            ENTRIES.stream().collect(Collectors.toUnmodifiableMap(Entry::name, Function.identity()));

    private McpToolCatalog() {}

    /** Every catalog entry, in advertised order. */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /** The entries {@code stack} advertises, in advertised order. */
    public static List<Entry> entriesFor(Stack stack) {
        Objects.requireNonNull(stack, "stack");
        return ENTRIES.stream().filter(entry -> entry.advertisedBy(stack)).toList();
    }

    /** Every tool name, in advertised order. */
    public static Set<String> names() {
        return ENTRIES.stream().map(Entry::name).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** The tool names {@code stack} advertises, in advertised order. */
    public static Set<String> namesFor(Stack stack) {
        return entriesFor(stack).stream().map(Entry::name).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Looks up one entry by machine name. */
    public static Optional<Entry> byName(String name) {
        return Optional.ofNullable(BY_NAME.get(name));
    }

    /**
     * Looks up the entry {@code stack} advertises under {@code name}.
     *
     * @throws IllegalArgumentException when the name is unknown, or is not advertised by {@code stack}
     */
    public static Entry require(String name, Stack stack) {
        Entry entry = BY_NAME.get(name);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown BootUI MCP tool: " + name);
        }
        if (!entry.advertisedBy(stack)) {
            throw new IllegalArgumentException("BootUI MCP tool " + name + " is not advertised by stack " + stack);
        }
        return entry;
    }

    private static Entry entry(String name, McpToolSchema schema, String panelId, boolean action, Set<Stack> stacks) {
        return new Entry(name, schema, panelId, action, stacks);
    }
}
