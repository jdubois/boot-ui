package io.github.jdubois.bootui.quarkus.mcp;

import io.github.jdubois.bootui.core.dto.RestClientTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.SqlTraceRecordingRequest;
import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolDescriptions;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
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
import io.github.jdubois.bootui.quarkus.web.FlywayResource;
import io.github.jdubois.bootui.quarkus.web.GitHubResource;
import io.github.jdubois.bootui.quarkus.web.GrpcResource;
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
            VulnerabilitiesResource vulnerabilities,
            LoggersResource loggers,
            ScheduledResource scheduled,
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
            GrpcResource grpc,
            DevServicesResource devServices,
            GitHubResource github,
            CopilotResource copilot,
            ClaudeCodeResource claudeCode) {
        List<McpTool> registry = new ArrayList<>();

        // --- Advisor tools (panel actions; behind the LocalhostGuard write floor) ---
        addIfAvailable(
                registry,
                availability,
                action(
                        "architecture_scan",
                        McpToolDescriptions.quarkus("architecture_scan"),
                        BootUiPanels.ARCHITECTURE,
                        args -> architecture.scan()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_architecture_report",
                        McpToolDescriptions.quarkus("get_architecture_report"),
                        BootUiPanels.ARCHITECTURE,
                        args -> architecture.architecture()));
        addIfAvailable(
                registry,
                availability,
                action(
                        "spring_scan",
                        McpToolDescriptions.quarkus("spring_scan"),
                        BootUiPanels.SPRING,
                        args -> spring.scan()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_spring_report",
                        McpToolDescriptions.quarkus("get_spring_report"),
                        BootUiPanels.SPRING,
                        args -> spring.spring()));
        addIfAvailable(
                registry,
                availability,
                action(
                        "hibernate_scan",
                        McpToolDescriptions.quarkus("hibernate_scan"),
                        BootUiPanels.HIBERNATE,
                        args -> hibernate.scan()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_hibernate_report",
                        McpToolDescriptions.quarkus("get_hibernate_report"),
                        BootUiPanels.HIBERNATE,
                        args -> hibernate.hibernate()));
        addIfAvailable(
                registry,
                availability,
                action(
                        "database_advisor_scan",
                        McpToolDescriptions.quarkus("database_advisor_scan"),
                        BootUiPanels.DATABASE_ADVISOR,
                        args -> databaseAdvisor.scan()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_database_advisor_report",
                        McpToolDescriptions.quarkus("get_database_advisor_report"),
                        BootUiPanels.DATABASE_ADVISOR,
                        args -> databaseAdvisor.databaseAdvisor()));
        addIfAvailable(
                registry,
                availability,
                action(
                        "memory_scan",
                        McpToolDescriptions.quarkus("memory_scan"),
                        BootUiPanels.MEMORY,
                        args -> memory.scan()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_memory_report",
                        McpToolDescriptions.quarkus("get_memory_report"),
                        BootUiPanels.MEMORY,
                        args -> memory.memory()));
        addIfAvailable(
                registry,
                availability,
                action(
                        "security_scan",
                        McpToolDescriptions.quarkus("security_scan"),
                        BootUiPanels.SECURITY,
                        args -> security.scan()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_security_report",
                        McpToolDescriptions.quarkus("get_security_report"),
                        BootUiPanels.SECURITY,
                        args -> security.security()));
        addIfAvailable(
                registry,
                availability,
                action(
                        "pentest_scan",
                        McpToolDescriptions.quarkus("pentest_scan"),
                        BootUiPanels.PENTESTING,
                        args -> pentesting.scan()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_pentest_report",
                        McpToolDescriptions.quarkus("get_pentest_report"),
                        BootUiPanels.PENTESTING,
                        args -> pentesting.pentesting()));
        addIfAvailable(
                registry,
                availability,
                action(
                        "rest_api_scan",
                        McpToolDescriptions.quarkus("rest_api_scan"),
                        BootUiPanels.REST_API,
                        args -> restApi.scan()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_rest_api_report",
                        McpToolDescriptions.quarkus("get_rest_api_report"),
                        BootUiPanels.REST_API,
                        args -> restApi.restApi()));
        addIfAvailable(
                registry,
                availability,
                action(
                        "vulnerabilities_scan",
                        McpToolDescriptions.quarkus("vulnerabilities_scan"),
                        BootUiPanels.VULNERABILITIES,
                        args -> vulnerabilities.scan()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_vulnerabilities_report",
                        McpToolDescriptions.quarkus("get_vulnerabilities_report"),
                        BootUiPanels.VULNERABILITIES,
                        args -> vulnerabilities.dependencies()));

        // --- Diagnostics / runtime tools ---
        addIfAvailable(
                registry,
                availability,
                limitRead(
                        "get_live_activity",
                        McpToolDescriptions.quarkus("get_live_activity"),
                        BootUiPanels.ACTIVITY,
                        args -> liveActivity.activity(args.limit(), null, null, null, null, null, null, null)));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_exceptions",
                        McpToolDescriptions.quarkus("get_exceptions"),
                        BootUiPanels.EXCEPTIONS,
                        args -> exceptions.list()));
        addIfAvailable(
                registry,
                availability,
                idRead(
                        "get_exception_detail",
                        McpToolDescriptions.quarkus("get_exception_detail"),
                        BootUiPanels.EXCEPTIONS,
                        args -> exceptions.detail(args.id())));
        addIfAvailable(
                registry,
                availability,
                action(
                        "clear_exceptions",
                        McpToolDescriptions.quarkus("clear_exceptions"),
                        BootUiPanels.EXCEPTIONS,
                        args -> {
                            exceptions.clear();
                            return Map.of("cleared", true);
                        }));
        addIfAvailable(
                registry,
                availability,
                limitRead(
                        "get_security_logs",
                        McpToolDescriptions.quarkus("get_security_logs"),
                        BootUiPanels.SECURITY_LOGS,
                        args -> securityLogs.logs(null, null, null, null, args.limit())));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_sql_traces",
                        McpToolDescriptions.quarkus("get_sql_traces"),
                        BootUiPanels.SQL_TRACE,
                        args -> sqlTrace.trace()));
        addIfAvailable(
                registry,
                availability,
                action(
                        "clear_sql_traces",
                        McpToolDescriptions.quarkus("clear_sql_traces"),
                        BootUiPanels.SQL_TRACE,
                        args -> sqlTrace.clear()));
        addIfAvailable(
                registry,
                availability,
                action(
                        "pause_sql_trace_recording",
                        McpToolDescriptions.quarkus("pause_sql_trace_recording"),
                        BootUiPanels.SQL_TRACE,
                        args -> sqlTrace.recording(new SqlTraceRecordingRequest(false))));
        addIfAvailable(
                registry,
                availability,
                action(
                        "resume_sql_trace_recording",
                        McpToolDescriptions.quarkus("resume_sql_trace_recording"),
                        BootUiPanels.SQL_TRACE,
                        args -> sqlTrace.recording(new SqlTraceRecordingRequest(true))));
        addIfAvailable(
                registry,
                availability,
                limitRead(
                        "get_traces",
                        McpToolDescriptions.quarkus("get_traces"),
                        BootUiPanels.TRACES,
                        args -> traces.list(args.limit())));
        addIfAvailable(
                registry,
                availability,
                action("clear_traces", McpToolDescriptions.quarkus("clear_traces"), BootUiPanels.TRACES, args -> {
                    traces.clear();
                    return Map.of("cleared", true);
                }));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_log_tail",
                        McpToolDescriptions.quarkus("get_log_tail"),
                        BootUiPanels.LOG_TAIL,
                        args -> Map.of("entries", logTail.recent())));
        addIfAvailable(
                registry,
                availability,
                limitRead(
                        "get_http_exchanges",
                        McpToolDescriptions.quarkus("get_http_exchanges"),
                        BootUiPanels.HTTP_EXCHANGES,
                        args -> httpExchanges.exchanges(null, null, null, null, args.limit())));

        // --- Core context read tools ---
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_overview",
                        McpToolDescriptions.quarkus("get_overview"),
                        BootUiPanels.OVERVIEW,
                        args -> overview.overview()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_health",
                        McpToolDescriptions.quarkus("get_health"),
                        BootUiPanels.HEALTH,
                        args -> health.health()));
        addIfAvailable(
                registry,
                availability,
                searchRead(
                        "get_config",
                        McpToolDescriptions.quarkus("get_config"),
                        BootUiPanels.CONFIG,
                        args -> config.list(args.query(), null, false, null, args.limit())));
        addIfAvailable(
                registry,
                availability,
                searchRead(
                        "get_beans",
                        McpToolDescriptions.quarkus("get_beans"),
                        BootUiPanels.BEANS,
                        args -> beans.beans(args.query(), null, null, args.limit())));
        addIfAvailable(
                registry,
                availability,
                searchRead(
                        "get_mappings",
                        McpToolDescriptions.quarkus("get_mappings"),
                        BootUiPanels.MAPPINGS,
                        args -> mappings.flatMappings(args.query(), null, args.limit())));
        addIfAvailable(
                registry,
                availability,
                searchRead(
                        "get_loggers",
                        McpToolDescriptions.quarkus("get_loggers"),
                        BootUiPanels.LOGGERS,
                        args -> loggers.loggers(args.query(), null, args.limit())));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_scheduled_tasks",
                        McpToolDescriptions.quarkus("get_scheduled_tasks"),
                        BootUiPanels.SCHEDULED,
                        args -> scheduled.scheduled()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_cache_stats",
                        McpToolDescriptions.quarkus("get_cache_stats"),
                        BootUiPanels.CACHE,
                        args -> cache.cache()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_database_connection_pools",
                        McpToolDescriptions.quarkus("get_database_connection_pools"),
                        BootUiPanels.DATABASE_CONNECTION_POOLS,
                        args -> connectionPools.pools()));

        // --- Additional panel tools ---
        addIfAvailable(
                registry,
                availability,
                searchRead(
                        "get_metrics",
                        McpToolDescriptions.quarkus("get_metrics"),
                        BootUiPanels.METRICS,
                        args -> metrics.metrics(args.query(), null, "0", String.valueOf(args.limit()))
                                .getEntity()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_live_memory",
                        McpToolDescriptions.quarkus("get_live_memory"),
                        BootUiPanels.LIVE_MEMORY,
                        args -> liveMemory.memory(null, null, null, null, null)));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_jvm_tuning",
                        McpToolDescriptions.quarkus("get_jvm_tuning"),
                        BootUiPanels.JVM_TUNING,
                        args -> jvmTuning.jvmTuning(null, null, null, null, null)));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_heap_dump_report",
                        McpToolDescriptions.quarkus("get_heap_dump_report"),
                        BootUiPanels.HEAP_DUMP,
                        args -> heapDump.report("", "")));
        addIfAvailable(
                registry,
                availability,
                action(
                        "analyze_heap_dump",
                        McpToolDescriptions.quarkus("analyze_heap_dump"),
                        BootUiPanels.HEAP_DUMP,
                        args -> heapDump.analyze()));
        addIfAvailable(
                registry,
                availability,
                searchRead(
                        "get_threads",
                        McpToolDescriptions.quarkus("get_threads"),
                        BootUiPanels.THREADS,
                        args -> threads.threads(args.query(), null, 0, args.limit())));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_profile_diff",
                        McpToolDescriptions.quarkus("get_profile_diff"),
                        BootUiPanels.PROFILE_DIFF,
                        args -> profileDiff.profiles()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_flyway_migrations",
                        McpToolDescriptions.quarkus("get_flyway_migrations"),
                        BootUiPanels.FLYWAY,
                        args -> flyway.migrations()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_liquibase_changesets",
                        McpToolDescriptions.quarkus("get_liquibase_changesets"),
                        BootUiPanels.LIQUIBASE,
                        args -> liquibase.changeSets()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_rest_client_traces",
                        McpToolDescriptions.quarkus("get_rest_client_traces"),
                        BootUiPanels.REST_CLIENT_TRACE,
                        args -> restClientTrace.trace()));
        addIfAvailable(
                registry,
                availability,
                action(
                        "clear_rest_client_traces",
                        McpToolDescriptions.quarkus("clear_rest_client_traces"),
                        BootUiPanels.REST_CLIENT_TRACE,
                        args -> restClientTrace.clear()));
        addIfAvailable(
                registry,
                availability,
                action(
                        "pause_rest_client_recording",
                        McpToolDescriptions.quarkus("pause_rest_client_recording"),
                        BootUiPanels.REST_CLIENT_TRACE,
                        args -> restClientTrace.recording(new RestClientTraceRecordingRequest(false))));
        addIfAvailable(
                registry,
                availability,
                action(
                        "resume_rest_client_recording",
                        McpToolDescriptions.quarkus("resume_rest_client_recording"),
                        BootUiPanels.REST_CLIENT_TRACE,
                        args -> restClientTrace.recording(new RestClientTraceRecordingRequest(true))));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_ai_overview",
                        McpToolDescriptions.quarkus("get_ai_overview"),
                        BootUiPanels.AI,
                        args -> ai.overview()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_emails",
                        McpToolDescriptions.quarkus("get_emails"),
                        BootUiPanels.EMAIL,
                        args -> email.list()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_kafka_activity",
                        McpToolDescriptions.quarkus("get_kafka_activity"),
                        BootUiPanels.KAFKA,
                        args -> kafka.list()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_rabbitmq_activity",
                        McpToolDescriptions.quarkus("get_rabbitmq_activity"),
                        BootUiPanels.RABBITMQ,
                        args -> rabbit.list()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_grpc_registry",
                        McpToolDescriptions.quarkus("get_grpc_registry"),
                        BootUiPanels.GRPC,
                        args -> grpc.grpc()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_dev_services",
                        McpToolDescriptions.quarkus("get_dev_services"),
                        BootUiPanels.DEV_SERVICES,
                        args -> devServices.list()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_github_dashboard",
                        McpToolDescriptions.quarkus("get_github_dashboard"),
                        BootUiPanels.GITHUB,
                        args -> github.dashboard()));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_copilot_sessions",
                        McpToolDescriptions.quarkus("get_copilot_sessions"),
                        BootUiPanels.COPILOT,
                        args -> copilot.sessions(null, null)));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_claude_code_sessions",
                        McpToolDescriptions.quarkus("get_claude_code_sessions"),
                        BootUiPanels.CLAUDE_CODE,
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

    private static McpTool action(
            String name, String description, String panelId, Function<McpArguments, Object> handler) {
        return new McpTool(name, description, McpToolSchema.NONE, panelId, true, handler);
    }

    private static McpTool read(
            String name, String description, String panelId, Function<McpArguments, Object> handler) {
        return new McpTool(name, description, McpToolSchema.NONE, panelId, false, handler);
    }

    private static McpTool limitRead(
            String name, String description, String panelId, Function<McpArguments, Object> handler) {
        return new McpTool(name, description, McpToolSchema.LIMIT, panelId, false, handler);
    }

    private static McpTool searchRead(
            String name, String description, String panelId, Function<McpArguments, Object> handler) {
        return new McpTool(name, description, McpToolSchema.QUERY_LIMIT, panelId, false, handler);
    }

    private static McpTool idRead(
            String name, String description, String panelId, Function<McpArguments, Object> handler) {
        return new McpTool(name, description, McpToolSchema.ID, panelId, false, handler);
    }
}
