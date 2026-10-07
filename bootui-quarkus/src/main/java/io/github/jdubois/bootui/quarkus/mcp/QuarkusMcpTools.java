package io.github.jdubois.bootui.quarkus.mcp;

import io.github.jdubois.bootui.core.dto.RestClientTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.SqlTraceRecordingRequest;
import io.github.jdubois.bootui.engine.insights.RuntimeInsightsAgentView;
import io.github.jdubois.bootui.engine.mcp.McpAgentViews;
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
import io.github.jdubois.bootui.quarkus.web.CodeInventoryResource;
import io.github.jdubois.bootui.quarkus.web.CodePathsResource;
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
import io.github.jdubois.bootui.quarkus.web.HibernateStatisticsResource;
import io.github.jdubois.bootui.quarkus.web.HttpExchangesResource;
import io.github.jdubois.bootui.quarkus.web.JavaAgentResource;
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
import io.github.jdubois.bootui.quarkus.web.MySqlResource;
import io.github.jdubois.bootui.quarkus.web.OverviewResource;
import io.github.jdubois.bootui.quarkus.web.PentestingResource;
import io.github.jdubois.bootui.quarkus.web.PostgresqlResource;
import io.github.jdubois.bootui.quarkus.web.ProfileDiffResource;
import io.github.jdubois.bootui.quarkus.web.RabbitResource;
import io.github.jdubois.bootui.quarkus.web.RestApiResource;
import io.github.jdubois.bootui.quarkus.web.RestClientTraceResource;
import io.github.jdubois.bootui.quarkus.web.RuntimeInsightsResource;
import io.github.jdubois.bootui.quarkus.web.ScheduledResource;
import io.github.jdubois.bootui.quarkus.web.SecurityLogsResource;
import io.github.jdubois.bootui.quarkus.web.SecurityResource;
import io.github.jdubois.bootui.quarkus.web.SideEffectsResource;
import io.github.jdubois.bootui.quarkus.web.SpringResource;
import io.github.jdubois.bootui.quarkus.web.SqlTraceResource;
import io.github.jdubois.bootui.quarkus.web.ThreadsResource;
import io.github.jdubois.bootui.quarkus.web.TracesResource;
import io.github.jdubois.bootui.quarkus.web.VulnerabilitiesResource;
import io.github.jdubois.bootui.quarkus.web.WebSocketsResource;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

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
    private final QuarkusPanelAvailability availability;

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
            MySqlResource mysql,
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
            ClaudeCodeResource claudeCode,
            RuntimeInsightsResource runtimeInsights,
            JavaAgentResource javaAgent,
            CodeInventoryResource codeInventory,
            CodePathsResource codePaths,
            SideEffectsResource sideEffects,
            HibernateStatisticsResource hibernateStatistics,
            WebSocketsResource webSockets) {
        this.availability = availability;
        List<McpTool> registry = new ArrayList<>();

        // --- Advisor tools (panel actions; behind the LocalhostGuard write floor) ---
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_architecture_rule_violations",
                        McpToolDescriptions.quarkus("get_architecture_rule_violations"),
                        args -> architecture.ruleViolations(args.id(), args.scanId(), args.offset(), args.limit())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_spring_rule_violations",
                        McpToolDescriptions.quarkus("get_spring_rule_violations"),
                        args -> spring.ruleViolations(args.id(), args.scanId(), args.offset(), args.limit())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_hibernate_rule_violations",
                        McpToolDescriptions.quarkus("get_hibernate_rule_violations"),
                        args -> hibernate.ruleViolations(args.id(), args.scanId(), args.offset(), args.limit())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_database_advisor_rule_violations",
                        McpToolDescriptions.quarkus("get_database_advisor_rule_violations"),
                        args -> databaseAdvisor.ruleViolations(args.id(), args.scanId(), args.offset(), args.limit())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_memory_rule_violations",
                        McpToolDescriptions.quarkus("get_memory_rule_violations"),
                        args -> memory.ruleViolations(args.id(), args.scanId(), args.offset(), args.limit())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_security_rule_violations",
                        McpToolDescriptions.quarkus("get_security_rule_violations"),
                        args -> security.ruleViolations(args.id(), args.scanId(), args.offset(), args.limit())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_rest_api_rule_violations",
                        McpToolDescriptions.quarkus("get_rest_api_rule_violations"),
                        args -> restApi.ruleViolations(args.id(), args.scanId(), args.offset(), args.limit())));
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
                tool("postgresql_read", McpToolDescriptions.quarkus("postgresql_read"), args -> postgresql.read()));
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
                tool("mysql_read", McpToolDescriptions.quarkus("mysql_read"), args -> mysql.read()));
        addIfAvailable(
                registry,
                availability,
                tool("get_mysql_report", McpToolDescriptions.quarkus("get_mysql_report"), args -> mysql.mysql()));
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
                        args -> McpAgentViews.vulnerabilities(
                                vulnerabilities.dependencies(), args.query(), args.limit())));

        // --- Diagnostics / runtime tools ---
        addIfAvailable(
                registry,
                availability,
                tool("get_live_activity", McpToolDescriptions.quarkus("get_live_activity"), args -> {
                    McpAgentViews.ActivityFilter filter = McpAgentViews.ActivityFilter.of(args.query());
                    return McpAgentViews.liveActivity(
                            liveActivity.activity(
                                    McpAgentViews.liveActivityFetch(filter, args.limit()),
                                    filter.type(),
                                    filter.severity(),
                                    filter.text(),
                                    null,
                                    null,
                                    null,
                                    null),
                            filter,
                            args.limit());
                }));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_request_profile",
                        McpToolDescriptions.quarkus("get_request_profile"),
                        args -> liveActivity.agentProfile(args.id())));
        // --- The BootUI Java agent (docs/PLAN-v2.md §5.13) ---
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_agent_status",
                        McpToolDescriptions.quarkus("get_agent_status"),
                        args -> McpAgentViews.agentStatus(javaAgent.report(), args.query(), args.limit())));
        // Code Inventory, advertised while the BootUI agent's inventory sensor records this start (§5.15).
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_code_inventory",
                        McpToolDescriptions.quarkus("get_code_inventory"),
                        args -> codeInventory.agentReport(args.query(), args.limit())));
        // Code Paths, advertised while the BootUI agent's code-paths sensor records this start (§5.14).
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_code_paths",
                        McpToolDescriptions.quarkus("get_code_paths"),
                        args -> codePaths.agentReport(args.query(), args.limit())));
        // Method probes, Code Paths' actions (M5-8): start_method_probe is refused on a read-only panel.
        addIfAvailable(
                registry,
                availability,
                tool(
                        "start_method_probe",
                        McpToolDescriptions.quarkus("start_method_probe"),
                        args -> codePaths.agentStartProbe(args.id())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_method_probe",
                        McpToolDescriptions.quarkus("get_method_probe"),
                        args -> codePaths.agentProbe(args.id())));
        // Side Effects, advertised while the BootUI agent is armed for this start (§5.16).
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_side_effects",
                        McpToolDescriptions.quarkus("get_side_effects"),
                        args -> sideEffects.agentReport(args.query(), args.limit())));
        // --- Runtime Insights for agents (docs/PLAN-v2.md §5.6) ---
        // Read at call time, so a next step never names a tool this application does not advertise (M4-21).
        Predicate<String> advertised = RuntimeInsightsAgentView.advertisedBy(this::tools);
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_runtime_insights",
                        McpToolDescriptions.quarkus("get_runtime_insights"),
                        args -> RuntimeInsightsAgentView.list(
                                runtimeInsights.report(), args.query(), args.limit(), advertised)));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_runtime_insight",
                        McpToolDescriptions.quarkus("get_runtime_insight"),
                        args -> RuntimeInsightsAgentView.detail(runtimeInsights.insight(args.id()), advertised)));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_runtime_impact",
                        McpToolDescriptions.quarkus("get_runtime_impact"),
                        args -> RuntimeInsightsAgentView.impact(runtimeInsights.impact(args.id()), advertised)));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_runtime_run_comparison",
                        McpToolDescriptions.quarkus("get_runtime_run_comparison"),
                        args -> RuntimeInsightsAgentView.comparison(
                                runtimeInsights.comparison(RuntimeInsightsAgentView.runId(args.id())),
                                args.id(),
                                advertised)));
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
                tool(
                        "get_sql_traces",
                        McpToolDescriptions.quarkus("get_sql_traces"),
                        args -> McpAgentViews.sqlTraces(sqlTrace.trace(), args.query(), args.limit())));
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
                        args -> McpAgentViews.logTail(logTail.recent(), args.query(), args.limit())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_http_exchanges",
                        McpToolDescriptions.quarkus("get_http_exchanges"),
                        args -> httpExchanges.exchanges(null, null, null, null, args.limit())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_http_routes",
                        McpToolDescriptions.quarkus("get_http_routes"),
                        args -> httpExchanges.routes(args.limit())));

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
                        args -> McpAgentViews.config(config.list(args.query(), null, false, null, args.limit()))));
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
                        args -> McpAgentViews.sessions(copilot.sessions(null, null), args.query(), args.limit())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_claude_code_sessions",
                        McpToolDescriptions.quarkus("get_claude_code_sessions"),
                        args -> McpAgentViews.sessions(claudeCode.sessions(null, null), args.query(), args.limit())));

        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_hibernate_statistics",
                        McpToolDescriptions.quarkus("get_hibernate_statistics"),
                        args -> hibernateStatistics.statistics()));
        addIfAvailable(
                registry,
                availability,
                tool("get_websockets", McpToolDescriptions.quarkus("get_websockets"), args -> webSockets.report()));

        this.tools = List.copyOf(registry);
    }

    /**
     * Why a panel is unavailable in this application, in the panel manifest's own words, or {@code null} when it is
     * available, so a call to a tool this registry does not advertise can say why.
     */
    public String panelUnavailableReason(String panelId) {
        return availability == null ? null : availability.panelUnavailableReason(panelId);
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
