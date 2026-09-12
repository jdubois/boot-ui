package io.github.jdubois.bootui.quarkus.mcp;

import io.github.jdubois.bootui.core.dto.RestClientTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.SqlTraceRecordingRequest;
import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolCatalog;
import io.github.jdubois.bootui.engine.mcp.McpToolDescriptions;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.github.jdubois.bootui.quarkus.web.AiResource;
import io.github.jdubois.bootui.quarkus.web.ArchitectureResource;
import io.github.jdubois.bootui.quarkus.web.BeansResource;
import io.github.jdubois.bootui.quarkus.web.CacheResource;
import io.github.jdubois.bootui.quarkus.web.ClaudeCodeResource;
import io.github.jdubois.bootui.quarkus.web.ConfigResource;
import io.github.jdubois.bootui.quarkus.web.ConnectionPoolsResource;
import io.github.jdubois.bootui.quarkus.web.CopilotResource;
import io.github.jdubois.bootui.quarkus.web.DatabaseAdvisorResource;
import io.github.jdubois.bootui.quarkus.web.DevServicesResource;
import io.github.jdubois.bootui.quarkus.web.EmailResource;
import io.github.jdubois.bootui.quarkus.web.ExceptionsResource;
import io.github.jdubois.bootui.quarkus.web.FaultToleranceResource;
import io.github.jdubois.bootui.quarkus.web.FlywayResource;
import io.github.jdubois.bootui.quarkus.web.GitHubResource;
import io.github.jdubois.bootui.quarkus.web.HealthResource;
import io.github.jdubois.bootui.quarkus.web.HeapDumpResource;
import io.github.jdubois.bootui.quarkus.web.HibernateResource;
import io.github.jdubois.bootui.quarkus.web.HttpExchangesResource;
import io.github.jdubois.bootui.quarkus.web.JvmTuningResource;
import io.github.jdubois.bootui.quarkus.web.KafkaResource;
import io.github.jdubois.bootui.quarkus.web.LiquibaseResource;
import io.github.jdubois.bootui.quarkus.web.LiveActivityResource;
import io.github.jdubois.bootui.quarkus.web.LiveMemoryResource;
import io.github.jdubois.bootui.quarkus.web.LogTailResource;
import io.github.jdubois.bootui.quarkus.web.LoggersResource;
import io.github.jdubois.bootui.quarkus.web.MappingsResource;
import io.github.jdubois.bootui.quarkus.web.MemoryResource;
import io.github.jdubois.bootui.quarkus.web.MetricsResource;
import io.github.jdubois.bootui.quarkus.web.OverviewResource;
import io.github.jdubois.bootui.quarkus.web.PentestingResource;
import io.github.jdubois.bootui.quarkus.web.PostgresqlResource;
import io.github.jdubois.bootui.quarkus.web.ProfileDiffResource;
import io.github.jdubois.bootui.quarkus.web.RabbitResource;
import io.github.jdubois.bootui.quarkus.web.RestApiResource;
import io.github.jdubois.bootui.quarkus.web.RestClientTraceResource;
import io.github.jdubois.bootui.quarkus.web.ScheduledResource;
import io.github.jdubois.bootui.quarkus.web.SecurityLogsResource;
import io.github.jdubois.bootui.quarkus.web.SecurityResource;
import io.github.jdubois.bootui.quarkus.web.SpringResource;
import io.github.jdubois.bootui.quarkus.web.SqlTraceResource;
import io.github.jdubois.bootui.quarkus.web.ThreadsResource;
import io.github.jdubois.bootui.quarkus.web.TracesResource;
import io.github.jdubois.bootui.quarkus.web.VulnerabilitiesResource;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds the catalog of MCP tools exposed by the BootUI MCP server on Quarkus.
 *
 * <p>The Quarkus twin of the Spring {@code BootUiMcpTools}: each tool is a thin adapter over the same
 * thin JAX-RS resource the browser UI hits, so the agent sees exactly the sanitized DTO shape the UI
 * sees (same {@code SecretMasker}/{@code expose-values} handling, same self-data filtering). Argument
 * normalization (the optional {@code query} filter and the {@code bootui.mcp.max-results} cap on
 * {@code limit}) is applied once by the engine {@code McpDispatcher}, so each handler simply reads
 * {@link McpArguments#query()} / {@link McpArguments#limit()}.
 *
 * <p><strong>Availability gate (B1).</strong> Every tool is gated on
 * {@link QuarkusPanelAvailability#isPanelAvailable(String)} — the same source of truth the panel
 * manifest uses — <em>not</em> on whether its backing CDI bean resolves. The engine services are
 * produced unconditionally on Quarkus (they render empty/unavailable when their optional backing is
 * absent), so a resolvability check would wrongly advertise tools (e.g. {@code hibernate_scan} in an
 * app without Hibernate ORM). Gating on panel availability means a tool is advertised iff its backing
 * panel is live, matching the sidebar the user sees.
 *
 * <p>Spring-specific or currently unavailable concepts are deliberately absent: GraalVM readiness,
 * CRaC, condition matches, startup steps, HTTP sessions, Spring Data, Spring Security, DevTools, JMS,
 * and transaction-boundary capture. The {@code get_overview} tool
 * <em>is</em> advertised on Quarkus: the Overview panel is available here (its dashboard renders
 * client-side from the advisor endpoints), and the tool returns the same shell {@code OverviewDto}
 * the Spring adapter exposes.
 */
@Singleton
public class QuarkusMcpTools {

    private final List<McpTool> tools;

    public QuarkusMcpTools(
            QuarkusPanelAvailability availability,
            ArchitectureResource architecture,
            SpringResource spring,
            HibernateResource hibernate,
            MemoryResource memory,
            SecurityResource security,
            PentestingResource pentesting,
            RestApiResource restApi,
            ExceptionsResource exceptions,
            LiveActivityResource liveActivity,
            SecurityLogsResource securityLogs,
            SqlTraceResource sqlTrace,
            TracesResource traces,
            LogTailResource logTail,
            HttpExchangesResource httpExchanges,
            HealthResource health,
            ConfigResource config,
            BeansResource beans,
            MappingsResource mappings,
            OverviewResource overview,
            DatabaseAdvisorResource databaseAdvisor,
            PostgresqlResource postgresql,
            VulnerabilitiesResource vulnerabilities,
            LoggersResource loggers,
            ScheduledResource scheduled,
            FaultToleranceResource faultTolerance,
            CacheResource cache,
            ConnectionPoolsResource connectionPools,
            MetricsResource metrics,
            LiveMemoryResource liveMemory,
            JvmTuningResource jvmTuning,
            HeapDumpResource heapDump,
            ThreadsResource threads,
            ProfileDiffResource profileDiff,
            FlywayResource flyway,
            LiquibaseResource liquibase,
            RestClientTraceResource restClientTrace,
            AiResource ai,
            EmailResource email,
            KafkaResource kafka,
            RabbitResource rabbit,
            DevServicesResource devServices,
            GitHubResource github,
            CopilotResource copilot,
            ClaudeCodeResource claudeCode) {
        List<McpTool> registry = new ArrayList<>();

        // --- Advisor tools (panel actions; behind the LocalhostGuard write floor) ---
        addIfAvailable(
                registry,
                availability,
                tool(
                        "architecture_scan",
                        McpToolDescriptions.quarkus("architecture_scan"),
                        args -> architecture.scan()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_architecture_report",
                        McpToolDescriptions.quarkus("get_architecture_report"),
                        args -> architecture.architecture()));
        addIfAvailable(
                registry,
                availability,
                tool("spring_scan", McpToolDescriptions.quarkus("spring_scan"), args -> spring.scan()));
        addIfAvailable(
                registry,
                availability,
                tool("get_spring_report", McpToolDescriptions.quarkus("get_spring_report"), args -> spring.spring()));
        addIfAvailable(
                registry,
                availability,
                tool("hibernate_scan", McpToolDescriptions.quarkus("hibernate_scan"), args -> hibernate.scan()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_hibernate_report",
                        McpToolDescriptions.quarkus("get_hibernate_report"),
                        args -> hibernate.hibernate()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "database_advisor_scan",
                        McpToolDescriptions.quarkus("database_advisor_scan"),
                        args -> databaseAdvisor.scan()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_database_advisor_report",
                        McpToolDescriptions.quarkus("get_database_advisor_report"),
                        args -> databaseAdvisor.databaseAdvisor()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "postgresql_read",
                        McpToolDescriptions.quarkus("postgresql_read"),
                        args -> postgresql.read()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_postgresql_report",
                        McpToolDescriptions.quarkus("get_postgresql_report"),
                        args -> postgresql.postgresql()));
        addIfAvailable(
                registry,
                availability,
                tool("memory_scan", McpToolDescriptions.quarkus("memory_scan"), args -> memory.scan()));
        addIfAvailable(
                registry,
                availability,
                tool("get_memory_report", McpToolDescriptions.quarkus("get_memory_report"), args -> memory.memory()));
        addIfAvailable(
                registry,
                availability,
                tool("security_scan", McpToolDescriptions.quarkus("security_scan"), args -> security.scan()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_security_report",
                        McpToolDescriptions.quarkus("get_security_report"),
                        args -> security.security()));
        addIfAvailable(
                registry,
                availability,
                tool("pentest_scan", McpToolDescriptions.quarkus("pentest_scan"), args -> pentesting.scan()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_pentest_report",
                        McpToolDescriptions.quarkus("get_pentest_report"),
                        args -> pentesting.pentesting()));
        addIfAvailable(
                registry,
                availability,
                tool("rest_api_scan", McpToolDescriptions.quarkus("rest_api_scan"), args -> restApi.scan()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_rest_api_report",
                        McpToolDescriptions.quarkus("get_rest_api_report"),
                        args -> restApi.restApi()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "vulnerabilities_scan",
                        McpToolDescriptions.quarkus("vulnerabilities_scan"),
                        args -> vulnerabilities.scan()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_vulnerabilities_report",
                        McpToolDescriptions.quarkus("get_vulnerabilities_report"),
                        args -> vulnerabilities.dependencies()));

        // --- Diagnostics / runtime tools ---
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_live_activity",
                        McpToolDescriptions.quarkus("get_live_activity"),
                        args -> liveActivity.activity(args.limit(), null, null, null, null, null, null, null)));
        addIfAvailable(
                registry,
                availability,
                tool("get_exceptions", McpToolDescriptions.quarkus("get_exceptions"), args -> exceptions.list()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_exception_detail",
                        McpToolDescriptions.quarkus("get_exception_detail"),
                        args -> exceptions.detail(args.id())));
        addIfAvailable(
                registry,
                availability,
                tool("clear_exceptions", McpToolDescriptions.quarkus("clear_exceptions"), args -> {
                    exceptions.clear();
                    return Map.of("cleared", true);
                }));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_security_logs",
                        McpToolDescriptions.quarkus("get_security_logs"),
                        args -> securityLogs.logs(null, null, null, null, args.limit())));
        addIfAvailable(
                registry,
                availability,
                tool("get_sql_traces", McpToolDescriptions.quarkus("get_sql_traces"), args -> sqlTrace.trace()));
        addIfAvailable(
                registry,
                availability,
                tool("clear_sql_traces", McpToolDescriptions.quarkus("clear_sql_traces"), args -> sqlTrace.clear()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "pause_sql_trace_recording",
                        McpToolDescriptions.quarkus("pause_sql_trace_recording"),
                        args -> sqlTrace.recording(new SqlTraceRecordingRequest(false))));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "resume_sql_trace_recording",
                        McpToolDescriptions.quarkus("resume_sql_trace_recording"),
                        args -> sqlTrace.recording(new SqlTraceRecordingRequest(true))));
        addIfAvailable(
                registry,
                availability,
                tool("get_traces", McpToolDescriptions.quarkus("get_traces"), args -> traces.list(args.limit())));
        addIfAvailable(
                registry, availability, tool("clear_traces", McpToolDescriptions.quarkus("clear_traces"), args -> {
                    traces.clear();
                    return Map.of("cleared", true);
                }));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_log_tail",
                        McpToolDescriptions.quarkus("get_log_tail"),
                        args -> Map.of("entries", logTail.recent())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_http_exchanges",
                        McpToolDescriptions.quarkus("get_http_exchanges"),
                        args -> httpExchanges.exchanges(null, null, null, null, args.limit())));

        // --- Core context read tools ---
        addIfAvailable(
                registry,
                availability,
                tool("get_overview", McpToolDescriptions.quarkus("get_overview"), args -> overview.overview()));
        addIfAvailable(
                registry,
                availability,
                tool("get_health", McpToolDescriptions.quarkus("get_health"), args -> health.health()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_config",
                        McpToolDescriptions.quarkus("get_config"),
                        args -> config.list(args.query(), null, false, null, args.limit())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_beans",
                        McpToolDescriptions.quarkus("get_beans"),
                        args -> beans.beans(args.query(), null, null, args.limit())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_mappings",
                        McpToolDescriptions.quarkus("get_mappings"),
                        args -> mappings.flatMappings(args.query(), null, args.limit())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_loggers",
                        McpToolDescriptions.quarkus("get_loggers"),
                        args -> loggers.loggers(args.query(), null, args.limit())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_scheduled_tasks",
                        McpToolDescriptions.quarkus("get_scheduled_tasks"),
                        args -> scheduled.scheduled()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_fault_tolerance",
                        McpToolDescriptions.quarkus("get_fault_tolerance"),
                        args -> faultTolerance.faultTolerance()));
        addIfAvailable(
                registry,
                availability,
                tool("get_cache_stats", McpToolDescriptions.quarkus("get_cache_stats"), args -> cache.cache()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_database_connection_pools",
                        McpToolDescriptions.quarkus("get_database_connection_pools"),
                        args -> connectionPools.pools()));

        // --- Additional panel tools ---
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_metrics",
                        McpToolDescriptions.quarkus("get_metrics"),
                        args -> metrics.metrics(args.query(), null, null, null, null, "0", String.valueOf(args.limit()))
                                .getEntity()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_live_memory",
                        McpToolDescriptions.quarkus("get_live_memory"),
                        args -> liveMemory.memory(null, null, null, null, null)));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_jvm_tuning",
                        McpToolDescriptions.quarkus("get_jvm_tuning"),
                        args -> jvmTuning.jvmTuning(null, null, null, null, null)));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_heap_dump_report",
                        McpToolDescriptions.quarkus("get_heap_dump_report"),
                        args -> heapDump.report("", "")));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "analyze_heap_dump",
                        McpToolDescriptions.quarkus("analyze_heap_dump"),
                        args -> heapDump.analyze()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_threads",
                        McpToolDescriptions.quarkus("get_threads"),
                        args -> threads.threads(args.query(), null, 0, args.limit())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_profile_diff",
                        McpToolDescriptions.quarkus("get_profile_diff"),
                        args -> profileDiff.profiles()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_flyway_migrations",
                        McpToolDescriptions.quarkus("get_flyway_migrations"),
                        args -> flyway.migrations()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_liquibase_changesets",
                        McpToolDescriptions.quarkus("get_liquibase_changesets"),
                        args -> liquibase.changeSets()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_rest_client_traces",
                        McpToolDescriptions.quarkus("get_rest_client_traces"),
                        args -> restClientTrace.trace()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "clear_rest_client_traces",
                        McpToolDescriptions.quarkus("clear_rest_client_traces"),
                        args -> restClientTrace.clear()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "pause_rest_client_recording",
                        McpToolDescriptions.quarkus("pause_rest_client_recording"),
                        args -> restClientTrace.recording(new RestClientTraceRecordingRequest(false))));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "resume_rest_client_recording",
                        McpToolDescriptions.quarkus("resume_rest_client_recording"),
                        args -> restClientTrace.recording(new RestClientTraceRecordingRequest(true))));
        addIfAvailable(
                registry,
                availability,
                tool("get_ai_overview", McpToolDescriptions.quarkus("get_ai_overview"), args -> ai.overview()));
        addIfAvailable(
                registry,
                availability,
                tool("get_emails", McpToolDescriptions.quarkus("get_emails"), args -> email.list()));
        addIfAvailable(
                registry,
                availability,
                tool("get_kafka_activity", McpToolDescriptions.quarkus("get_kafka_activity"), args -> kafka.list()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_rabbitmq_activity",
                        McpToolDescriptions.quarkus("get_rabbitmq_activity"),
                        args -> rabbit.list()));
        addIfAvailable(
                registry,
                availability,
                tool("get_dev_services", McpToolDescriptions.quarkus("get_dev_services"), args -> devServices.list()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_github_dashboard",
                        McpToolDescriptions.quarkus("get_github_dashboard"),
                        args -> github.dashboard()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_copilot_sessions",
                        McpToolDescriptions.quarkus("get_copilot_sessions"),
                        args -> copilot.sessions(null, null)));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_claude_code_sessions",
                        McpToolDescriptions.quarkus("get_claude_code_sessions"),
                        args -> claudeCode.sessions(null, null)));

        this.tools = List.copyOf(registry);
    }

    /** All tools in advertised order. */
    public List<McpTool> tools() {
        return tools;
    }

    private static void addIfAvailable(List<McpTool> registry, QuarkusPanelAvailability availability, McpTool tool) {
        if (availability.isPanelAvailable(tool.panelId())) {
            registry.add(tool);
        }
    }

    /**
     * Builds one advertised tool from the shared {@link McpToolCatalog}.
     *
     * <p>Only the name, description, and handler are adapter-specific. The argument schema, backing panel,
     * and action flag are read back from the catalog, so they cannot be spelled differently here than in the
     * other stacks, and a name this stack is not supposed to advertise fails fast at startup.
     *
     * <p>Every handler is wrapped by {@link QuarkusMcpToolFailures} at this single point, so a tool that
     * delegates to a controller method cannot report its client error as a server fault by being registered
     * through a path that forgot to translate.
     */
    private static McpTool tool(String name, String description, Function<McpArguments, Object> handler) {
        McpToolCatalog.Entry entry = McpToolCatalog.require(name, McpToolCatalog.Stack.QUARKUS);
        return new McpTool(
                name,
                description,
                entry.schema(),
                entry.panelId(),
                entry.action(),
                QuarkusMcpToolFailures.translating(handler));
    }
}
