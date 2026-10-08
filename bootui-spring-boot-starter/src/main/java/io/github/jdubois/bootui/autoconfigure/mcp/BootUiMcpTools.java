package io.github.jdubois.bootui.autoconfigure.mcp;

import io.github.jdubois.bootui.autoconfigure.activity.LiveActivityController;
import io.github.jdubois.bootui.autoconfigure.architecture.ArchitectureController;
import io.github.jdubois.bootui.autoconfigure.crac.CracController;
import io.github.jdubois.bootui.autoconfigure.databaseadvisor.DatabaseAdvisorController;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionsController;
import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmController;
import io.github.jdubois.bootui.autoconfigure.hibernate.HibernateController;
import io.github.jdubois.bootui.autoconfigure.hibernate.HibernateStatisticsController;
import io.github.jdubois.bootui.autoconfigure.insights.RuntimeInsightsController;
import io.github.jdubois.bootui.autoconfigure.javaagent.CodeInventoryController;
import io.github.jdubois.bootui.autoconfigure.javaagent.CodePathsController;
import io.github.jdubois.bootui.autoconfigure.javaagent.JavaAgentController;
import io.github.jdubois.bootui.autoconfigure.javaagent.SideEffectsController;
import io.github.jdubois.bootui.autoconfigure.jms.JmsController;
import io.github.jdubois.bootui.autoconfigure.kafka.KafkaController;
import io.github.jdubois.bootui.autoconfigure.mail.EmailController;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryController;
import io.github.jdubois.bootui.autoconfigure.mysql.MySqlController;
import io.github.jdubois.bootui.autoconfigure.pentesting.PentestingController;
import io.github.jdubois.bootui.autoconfigure.postgres.PostgresqlController;
import io.github.jdubois.bootui.autoconfigure.rabbit.RabbitController;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiController;
import io.github.jdubois.bootui.autoconfigure.restclienttrace.RestClientTraceController;
import io.github.jdubois.bootui.autoconfigure.security.SecurityController;
import io.github.jdubois.bootui.autoconfigure.spring.SpringController;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceController;
import io.github.jdubois.bootui.autoconfigure.transactions.TransactionsController;
import io.github.jdubois.bootui.autoconfigure.web.AiController;
import io.github.jdubois.bootui.autoconfigure.web.BeansController;
import io.github.jdubois.bootui.autoconfigure.web.ClaudeCodeController;
import io.github.jdubois.bootui.autoconfigure.web.ConditionsController;
import io.github.jdubois.bootui.autoconfigure.web.ConfigController;
import io.github.jdubois.bootui.autoconfigure.web.CopilotController;
import io.github.jdubois.bootui.autoconfigure.web.DataController;
import io.github.jdubois.bootui.autoconfigure.web.DatabaseConnectionPoolsController;
import io.github.jdubois.bootui.autoconfigure.web.DevServicesController;
import io.github.jdubois.bootui.autoconfigure.web.DevToolsController;
import io.github.jdubois.bootui.autoconfigure.web.FaultToleranceController;
import io.github.jdubois.bootui.autoconfigure.web.FlywayController;
import io.github.jdubois.bootui.autoconfigure.web.GitHubController;
import io.github.jdubois.bootui.autoconfigure.web.HealthController;
import io.github.jdubois.bootui.autoconfigure.web.HeapDumpController;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.HttpSessionsController;
import io.github.jdubois.bootui.autoconfigure.web.JvmTuningController;
import io.github.jdubois.bootui.autoconfigure.web.LiquibaseController;
import io.github.jdubois.bootui.autoconfigure.web.LiveMemoryController;
import io.github.jdubois.bootui.autoconfigure.web.LogTailController;
import io.github.jdubois.bootui.autoconfigure.web.LoggersController;
import io.github.jdubois.bootui.autoconfigure.web.MappingsController;
import io.github.jdubois.bootui.autoconfigure.web.MetricsController;
import io.github.jdubois.bootui.autoconfigure.web.OverviewController;
import io.github.jdubois.bootui.autoconfigure.web.PanelsController;
import io.github.jdubois.bootui.autoconfigure.web.ProfileDiffController;
import io.github.jdubois.bootui.autoconfigure.web.ScheduledController;
import io.github.jdubois.bootui.autoconfigure.web.SecurityLogsController;
import io.github.jdubois.bootui.autoconfigure.web.SpringCacheController;
import io.github.jdubois.bootui.autoconfigure.web.SpringSecurityController;
import io.github.jdubois.bootui.autoconfigure.web.StartupController;
import io.github.jdubois.bootui.autoconfigure.web.ThreadDumpController;
import io.github.jdubois.bootui.autoconfigure.web.TracesController;
import io.github.jdubois.bootui.autoconfigure.web.VulnerabilitiesController;
import io.github.jdubois.bootui.autoconfigure.websocket.WebSocketController;
import io.github.jdubois.bootui.core.dto.RestClientTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.SqlTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.TransactionRecordingRequest;
import io.github.jdubois.bootui.engine.insights.RuntimeInsightsAgentView;
import io.github.jdubois.bootui.engine.mcp.McpAgentViews;
import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpControlAcks;
import io.github.jdubois.bootui.engine.mcp.McpScanSummaries;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolCatalog;
import io.github.jdubois.bootui.engine.mcp.McpToolDescriptions;
import io.github.jdubois.bootui.engine.memory.MemoryAgentViews;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Builds the catalog of MCP tools exposed by the BootUI MCP server.
 *
 * <p>Tools are thin adapters over the existing BootUI controllers; they reuse the same services,
 * immutable {@code record} DTOs, {@code SecretMasker}/{@code expose-values} handling, and self-data
 * filtering, so the agent sees exactly the sanitized shape the browser UI sees. Each tool's argument
 * schema, backing panel id, and action flag come from {@link McpToolCatalog}, the shared registry every
 * stack is built from, so the engine {@code McpDispatcher} can enforce per-panel enable/read-only
 * toggles and no two stacks can describe the same tool differently. Argument normalization (the
 * optional {@code query} filter and the
 * {@code bootui.mcp.max-results} cap on {@code limit}) is applied once by the engine, so each handler
 * simply reads {@link McpArguments#query()} / {@link McpArguments#limit()}.
 */
public class BootUiMcpTools {

    private volatile List<McpTool> tools;
    private volatile PanelsController panelsController;

    public BootUiMcpTools(
            ObjectProvider<OverviewController> overview,
            ObjectProvider<HealthController> health,
            ObjectProvider<ConfigController> config,
            ObjectProvider<BeansController> beans,
            ObjectProvider<MappingsController> mappings,
            ObjectProvider<ExceptionsController> exceptions,
            ObjectProvider<LiveActivityController> liveActivity,
            ObjectProvider<SecurityLogsController> securityLogs,
            ObjectProvider<SqlTraceController> sqlTrace,
            ObjectProvider<TransactionsController> transactions,
            ObjectProvider<TracesController> traces,
            ObjectProvider<LogTailController> logTail,
            ObjectProvider<HttpExchangesController> httpExchanges,
            ObjectProvider<ArchitectureController> architecture,
            ObjectProvider<SpringController> spring,
            ObjectProvider<HibernateController> hibernate,
            ObjectProvider<MemoryController> memory,
            ObjectProvider<SecurityController> security,
            ObjectProvider<PentestingController> pentesting,
            ObjectProvider<RestApiController> restApi,
            ObjectProvider<GraalVmController> graalvm,
            ObjectProvider<CracController> crac,
            ObjectProvider<DatabaseAdvisorController> databaseAdvisor,
            ObjectProvider<PostgresqlController> postgresql,
            ObjectProvider<MySqlController> mysql,
            ObjectProvider<VulnerabilitiesController> vulnerabilities,
            ObjectProvider<LoggersController> loggers,
            ObjectProvider<ConditionsController> conditions,
            ObjectProvider<ScheduledController> scheduled,
            ObjectProvider<FaultToleranceController> faultTolerance,
            ObjectProvider<SpringCacheController> cache,
            ObjectProvider<DatabaseConnectionPoolsController> connectionPools,
            ObjectProvider<RuntimeInsightsController> runtimeInsights,
            ObjectProvider<JavaAgentController> javaAgent,
            ObjectProvider<CodeInventoryController> codeInventory,
            ObjectProvider<CodePathsController> codePaths,
            ObjectProvider<SideEffectsController> sideEffects) {
        // Resolve each (lazy) controller bean; conditionally-registered controllers (e.g. Hibernate,
        // Spring Security) may be absent depending on the host app's classpath, so the matching tool is
        // simply not advertised rather than failing the whole server.
        OverviewController overviewBean = overview.getIfAvailable();
        HealthController healthBean = health.getIfAvailable();
        ConfigController configBean = config.getIfAvailable();
        BeansController beansBean = beans.getIfAvailable();
        MappingsController mappingsBean = mappings.getIfAvailable();
        ExceptionsController exceptionsBean = exceptions.getIfAvailable();
        LiveActivityController liveActivityBean = liveActivity.getIfAvailable();
        SecurityLogsController securityLogsBean = securityLogs.getIfAvailable();
        SqlTraceController sqlTraceBean = sqlTrace.getIfAvailable();
        TransactionsController transactionsBean = transactions.getIfAvailable();
        TracesController tracesBean = traces.getIfAvailable();
        LogTailController logTailBean = logTail.getIfAvailable();
        HttpExchangesController httpExchangesBean = httpExchanges.getIfAvailable();
        ArchitectureController architectureBean = architecture.getIfAvailable();
        SpringController springBean = spring.getIfAvailable();
        HibernateController hibernateBean = hibernate.getIfAvailable();
        MemoryController memoryBean = memory.getIfAvailable();
        SecurityController securityBean = security.getIfAvailable();
        PentestingController pentestingBean = pentesting.getIfAvailable();
        RestApiController restApiBean = restApi.getIfAvailable();
        GraalVmController graalvmBean = graalvm.getIfAvailable();
        CracController cracBean = crac.getIfAvailable();
        DatabaseAdvisorController databaseAdvisorBean = databaseAdvisor.getIfAvailable();
        PostgresqlController postgresqlBean = postgresql.getIfAvailable();
        MySqlController mysqlBean = mysql.getIfAvailable();
        VulnerabilitiesController vulnerabilitiesBean = vulnerabilities.getIfAvailable();
        LoggersController loggersBean = loggers.getIfAvailable();
        ConditionsController conditionsBean = conditions.getIfAvailable();
        ScheduledController scheduledBean = scheduled.getIfAvailable();
        FaultToleranceController faultToleranceBean = faultTolerance.getIfAvailable();
        SpringCacheController cacheBean = cache.getIfAvailable();
        DatabaseConnectionPoolsController connectionPoolsBean = connectionPools.getIfAvailable();
        RuntimeInsightsController runtimeInsightsBean = runtimeInsights.getIfAvailable();
        JavaAgentController javaAgentBean = javaAgent.getIfAvailable();
        CodeInventoryController codeInventoryBean = codeInventory.getIfAvailable();
        CodePathsController codePathsBean = codePaths.getIfAvailable();
        SideEffectsController sideEffectsBean = sideEffects.getIfAvailable();

        List<McpTool> registry = new ArrayList<>();

        // --- Advisor tools (panel actions; refused when the backing panel is read-only) ---
        if (architectureBean != null) {
            registry.add(tool(
                    "get_architecture_rule_violations",
                    McpToolDescriptions.spring("get_architecture_rule_violations"),
                    args -> architectureBean.ruleViolations(args.id(), args.scanId(), args.offset(), args.limit())));
            registry.add(tool(
                    "architecture_scan",
                    McpToolDescriptions.spring("architecture_scan"),
                    args -> McpScanSummaries.architecture(architectureBean.scan())));
            registry.add(tool(
                    "get_architecture_report",
                    McpToolDescriptions.spring("get_architecture_report"),
                    args -> architectureBean.architecture()));
        }
        if (springBean != null) {
            registry.add(tool(
                    "get_spring_rule_violations",
                    McpToolDescriptions.spring("get_spring_rule_violations"),
                    args -> springBean.ruleViolations(args.id(), args.scanId(), args.offset(), args.limit())));
            registry.add(tool(
                    "spring_scan",
                    McpToolDescriptions.spring("spring_scan"),
                    args -> McpScanSummaries.spring(springBean.scan())));
            registry.add(tool(
                    "get_spring_report", McpToolDescriptions.spring("get_spring_report"), args -> springBean.spring()));
        }
        if (hibernateBean != null) {
            registry.add(tool(
                    "get_hibernate_rule_violations",
                    McpToolDescriptions.spring("get_hibernate_rule_violations"),
                    args -> hibernateBean.ruleViolations(args.id(), args.scanId(), args.offset(), args.limit())));
            registry.add(tool(
                    "hibernate_scan",
                    McpToolDescriptions.spring("hibernate_scan"),
                    args -> McpScanSummaries.hibernate(hibernateBean.scan())));
            registry.add(tool(
                    "get_hibernate_report",
                    McpToolDescriptions.spring("get_hibernate_report"),
                    args -> hibernateBean.hibernate()));
        }
        if (memoryBean != null) {
            registry.add(tool(
                    "get_memory_rule_violations",
                    McpToolDescriptions.spring("get_memory_rule_violations"),
                    args -> memoryBean.ruleViolations(args.id(), args.scanId(), args.offset(), args.limit())));
            registry.add(tool(
                    "memory_scan",
                    McpToolDescriptions.spring("memory_scan"),
                    args -> McpScanSummaries.memory(memoryBean.scan())));
            registry.add(tool(
                    "get_memory_report", McpToolDescriptions.spring("get_memory_report"), args -> memoryBean.memory()));
        }
        if (securityBean != null) {
            registry.add(tool(
                    "get_security_rule_violations",
                    McpToolDescriptions.spring("get_security_rule_violations"),
                    args -> securityBean.ruleViolations(args.id(), args.scanId(), args.offset(), args.limit())));
            registry.add(tool(
                    "security_scan",
                    McpToolDescriptions.spring("security_scan"),
                    args -> McpScanSummaries.security(securityBean.scan())));
            registry.add(tool(
                    "get_security_report",
                    McpToolDescriptions.spring("get_security_report"),
                    args -> securityBean.security()));
        }
        if (pentestingBean != null) {
            registry.add(tool(
                    "pentest_scan",
                    McpToolDescriptions.spring("pentest_scan"),
                    args -> McpScanSummaries.pentest(pentestingBean.scan())));
            registry.add(tool(
                    "get_pentest_report",
                    McpToolDescriptions.spring("get_pentest_report"),
                    args -> pentestingBean.pentesting()));
        }
        if (restApiBean != null) {
            registry.add(tool(
                    "get_rest_api_rule_violations",
                    McpToolDescriptions.spring("get_rest_api_rule_violations"),
                    args -> restApiBean.ruleViolations(args.id(), args.scanId(), args.offset(), args.limit())));
            registry.add(tool(
                    "rest_api_scan",
                    McpToolDescriptions.spring("rest_api_scan"),
                    args -> McpScanSummaries.restApi(restApiBean.scan())));
            registry.add(tool(
                    "get_rest_api_report",
                    McpToolDescriptions.spring("get_rest_api_report"),
                    args -> restApiBean.restApi()));
        }
        if (graalvmBean != null) {
            registry.add(tool(
                    "graalvm_scan",
                    McpToolDescriptions.spring("graalvm_scan"),
                    args -> McpScanSummaries.graalvm(graalvmBean.scan(false))));
            registry.add(tool(
                    "get_graalvm_report",
                    McpToolDescriptions.spring("get_graalvm_report"),
                    args -> graalvmBean.graalvm()));
        }
        if (cracBean != null) {
            registry.add(tool(
                    "crac_scan",
                    McpToolDescriptions.spring("crac_scan"),
                    args -> McpScanSummaries.crac(cracBean.scan())));
            registry.add(
                    tool("get_crac_report", McpToolDescriptions.spring("get_crac_report"), args -> cracBean.crac()));
        }
        if (databaseAdvisorBean != null) {
            registry.add(tool(
                    "get_database_advisor_rule_violations",
                    McpToolDescriptions.spring("get_database_advisor_rule_violations"),
                    args -> databaseAdvisorBean.ruleViolations(args.id(), args.scanId(), args.offset(), args.limit())));
            registry.add(tool(
                    "database_advisor_scan",
                    McpToolDescriptions.spring("database_advisor_scan"),
                    args -> McpScanSummaries.databaseAdvisor(databaseAdvisorBean.scan())));
            registry.add(tool(
                    "get_database_advisor_report",
                    McpToolDescriptions.spring("get_database_advisor_report"),
                    args -> databaseAdvisorBean.databaseAdvisor()));
        }
        if (postgresqlBean != null) {
            registry.add(tool(
                    "postgresql_read",
                    McpToolDescriptions.spring("postgresql_read"),
                    args -> McpScanSummaries.postgresql(postgresqlBean.read())));
            registry.add(tool(
                    "get_postgresql_report",
                    McpToolDescriptions.spring("get_postgresql_report"),
                    args -> postgresqlBean.postgresql()));
        }
        if (mysqlBean != null) {
            registry.add(tool(
                    "mysql_read",
                    McpToolDescriptions.spring("mysql_read"),
                    args -> McpScanSummaries.mysql(mysqlBean.read())));
            registry.add(tool(
                    "get_mysql_report", McpToolDescriptions.spring("get_mysql_report"), args -> mysqlBean.report()));
        }
        if (vulnerabilitiesBean != null) {
            registry.add(tool(
                    "vulnerabilities_scan",
                    McpToolDescriptions.spring("vulnerabilities_scan"),
                    args -> McpScanSummaries.vulnerabilities(vulnerabilitiesBean.scan())));
            registry.add(tool(
                    "get_vulnerabilities_report",
                    McpToolDescriptions.spring("get_vulnerabilities_report"),
                    args -> McpAgentViews.vulnerabilities(
                            vulnerabilitiesBean.dependencies(), args.query(), args.limit())));
        }

        // --- Diagnostics / runtime tools ---
        if (liveActivityBean != null) {
            registry.add(tool("get_live_activity", McpToolDescriptions.spring("get_live_activity"), args -> {
                McpAgentViews.ActivityFilter filter = McpAgentViews.ActivityFilter.of(args.query());
                return McpAgentViews.liveActivity(
                        liveActivityBean.activity(
                                McpAgentViews.adapterType(filter),
                                null,
                                0,
                                McpAgentViews.liveActivityFetch(filter, args.limit()),
                                null,
                                null,
                                null,
                                0),
                        filter,
                        args.limit());
            }));
            registry.add(tool(
                    "get_request_profile",
                    McpToolDescriptions.spring("get_request_profile"),
                    args -> liveActivityBean.agentProfile(args.id())));
        }
        // --- The BootUI Java agent (docs/PLAN-v2.md §5.13) ---
        if (javaAgentBean != null) {
            registry.add(tool(
                    "get_agent_status",
                    McpToolDescriptions.spring("get_agent_status"),
                    args -> McpAgentViews.agentStatus(javaAgentBean.report(), args.query())));
        }
        if (codeInventoryBean != null) {
            registry.add(tool(
                    "get_code_inventory",
                    McpToolDescriptions.spring("get_code_inventory"),
                    args -> codeInventoryBean.agentReport(args.query(), args.limit())));
        }
        if (codePathsBean != null) {
            registry.add(tool(
                    "get_code_paths",
                    McpToolDescriptions.spring("get_code_paths"),
                    args -> codePathsBean.agentReport(args.query(), args.limit())));
            registry.add(tool(
                    "start_method_probe",
                    McpToolDescriptions.spring("start_method_probe"),
                    args -> codePathsBean.agentStartProbe(args.id())));
            registry.add(tool(
                    "get_method_probe",
                    McpToolDescriptions.spring("get_method_probe"),
                    args -> codePathsBean.agentProbe(args.id())));
        }
        // Side Effects, advertised while the BootUI agent is armed for this run (docs/PLAN-v2.md §5.16).
        if (sideEffectsBean != null) {
            registry.add(tool(
                    "get_side_effects",
                    McpToolDescriptions.spring("get_side_effects"),
                    args -> sideEffectsBean.agentReport(args.query(), args.limit())));
        }
        // --- Runtime Insights for agents (docs/PLAN-v2.md §5.6) ---
        // Read at call time, so a next step never names a tool this application does not advertise (M4-21).
        Predicate<String> advertised = RuntimeInsightsAgentView.advertisedBy(this::tools);
        if (runtimeInsightsBean != null) {
            registry.add(tool(
                    "get_runtime_insights",
                    McpToolDescriptions.spring("get_runtime_insights"),
                    args -> RuntimeInsightsAgentView.list(
                            runtimeInsightsBean.report(), args.query(), args.limit(), advertised)));
            registry.add(tool(
                    "get_runtime_insight",
                    McpToolDescriptions.spring("get_runtime_insight"),
                    args -> RuntimeInsightsAgentView.detail(runtimeInsightsBean.insight(args.id()), advertised)));
            registry.add(tool(
                    "get_runtime_impact",
                    McpToolDescriptions.spring("get_runtime_impact"),
                    args -> RuntimeInsightsAgentView.impact(runtimeInsightsBean.impact(args.id()), advertised)));
            registry.add(tool(
                    "get_runtime_run_comparison",
                    McpToolDescriptions.spring("get_runtime_run_comparison"),
                    args -> RuntimeInsightsAgentView.comparison(
                            runtimeInsightsBean.comparison(RuntimeInsightsAgentView.runId(args.id())),
                            args.id(),
                            advertised)));
        }
        if (exceptionsBean != null) {
            registry.add(tool(
                    "get_exceptions", McpToolDescriptions.spring("get_exceptions"), args -> exceptionsBean.list()));
            registry.add(tool(
                    "get_exception_detail",
                    McpToolDescriptions.spring("get_exception_detail"),
                    args -> exceptionsBean.detail(args.id())));
            registry.add(tool("clear_exceptions", McpToolDescriptions.spring("clear_exceptions"), args -> {
                exceptionsBean.clear();
                return McpControlAcks.exceptionsCleared(exceptionsBean.list());
            }));
        }
        if (securityLogsBean != null) {
            registry.add(tool(
                    "get_security_logs",
                    McpToolDescriptions.spring("get_security_logs"),
                    args -> securityLogsBean.logs(null, null, null, null, args.limit())));
        }
        if (sqlTraceBean != null) {
            registry.add(tool(
                    "get_sql_traces",
                    McpToolDescriptions.spring("get_sql_traces"),
                    args -> McpAgentViews.sqlTraces(sqlTraceBean.trace(), args.query(), args.limit())));
            registry.add(tool(
                    "clear_sql_traces",
                    McpToolDescriptions.spring("clear_sql_traces"),
                    args -> McpControlAcks.sqlTrace(McpControlAcks.CLEARED, sqlTraceBean.clear())));
            registry.add(tool(
                    "pause_sql_trace_recording",
                    McpToolDescriptions.spring("pause_sql_trace_recording"),
                    args -> McpControlAcks.sqlTrace(
                            McpControlAcks.PAUSED, sqlTraceBean.recording(new SqlTraceRecordingRequest(false)))));
            registry.add(tool(
                    "resume_sql_trace_recording",
                    McpToolDescriptions.spring("resume_sql_trace_recording"),
                    args -> McpControlAcks.sqlTrace(
                            McpControlAcks.RESUMED, sqlTraceBean.recording(new SqlTraceRecordingRequest(true)))));
        }
        if (transactionsBean != null) {
            registry.add(tool(
                    "get_transactions",
                    McpToolDescriptions.spring("get_transactions"),
                    args -> transactionsBean.trace()));
            registry.add(tool(
                    "clear_transactions",
                    McpToolDescriptions.spring("clear_transactions"),
                    args -> McpControlAcks.transactions(McpControlAcks.CLEARED, transactionsBean.clear())));
            registry.add(tool(
                    "pause_transaction_recording",
                    McpToolDescriptions.spring("pause_transaction_recording"),
                    args -> McpControlAcks.transactions(
                            McpControlAcks.PAUSED,
                            transactionsBean.recording(new TransactionRecordingRequest(false)))));
            registry.add(tool(
                    "resume_transaction_recording",
                    McpToolDescriptions.spring("resume_transaction_recording"),
                    args -> McpControlAcks.transactions(
                            McpControlAcks.RESUMED,
                            transactionsBean.recording(new TransactionRecordingRequest(true)))));
        }
        if (tracesBean != null) {
            registry.add(tool(
                    "get_traces", McpToolDescriptions.spring("get_traces"), args -> tracesBean.list(args.limit())));
            registry.add(tool("clear_traces", McpToolDescriptions.spring("clear_traces"), args -> {
                tracesBean.clear();
                return McpControlAcks.tracesCleared(tracesBean.list(1));
            }));
        }
        if (logTailBean != null) {
            registry.add(tool(
                    "get_log_tail",
                    McpToolDescriptions.spring("get_log_tail"),
                    args -> McpAgentViews.logTail(logTailBean.recent(), args.query(), args.limit())));
        }
        if (httpExchangesBean != null) {
            registry.add(tool(
                    "get_http_exchanges",
                    McpToolDescriptions.spring("get_http_exchanges"),
                    args -> httpExchangesBean.exchanges(null, null, null, null, args.limit())));
            registry.add(tool(
                    "get_http_routes",
                    McpToolDescriptions.spring("get_http_routes"),
                    args -> httpExchangesBean.routes(args.limit())));
        }

        // --- Core context read tools ---
        if (overviewBean != null) {
            registry.add(
                    tool("get_overview", McpToolDescriptions.spring("get_overview"), args -> overviewBean.overview()));
        }
        if (healthBean != null) {
            registry.add(tool("get_health", McpToolDescriptions.spring("get_health"), args -> healthBean.health()));
        }
        if (configBean != null) {
            registry.add(tool(
                    "get_config",
                    McpToolDescriptions.spring("get_config"),
                    args -> McpAgentViews.config(configBean.list(args.query(), null, false, null, args.limit()))));
        }
        if (beansBean != null) {
            registry.add(tool(
                    "get_beans",
                    McpToolDescriptions.spring("get_beans"),
                    args -> beansBean.beans(args.query(), null, null, args.limit())));
        }
        if (mappingsBean != null) {
            registry.add(tool(
                    "get_mappings",
                    McpToolDescriptions.spring("get_mappings"),
                    args -> mappingsBean.flatMappings(args.query(), null, args.limit())));
        }
        if (loggersBean != null) {
            registry.add(tool(
                    "get_loggers",
                    McpToolDescriptions.spring("get_loggers"),
                    args -> loggersBean.loggers(args.query(), null, args.limit())));
        }
        if (conditionsBean != null) {
            registry.add(tool(
                    "get_conditions",
                    McpToolDescriptions.spring("get_conditions"),
                    args -> conditionsBean.conditions(args.query(), null, null, args.limit())));
        }
        if (scheduledBean != null) {
            registry.add(tool(
                    "get_scheduled_tasks",
                    McpToolDescriptions.spring("get_scheduled_tasks"),
                    args -> scheduledBean.scheduled()));
        }
        if (faultToleranceBean != null) {
            registry.add(tool(
                    "get_fault_tolerance",
                    McpToolDescriptions.spring("get_fault_tolerance"),
                    args -> faultToleranceBean.faultTolerance()));
        }
        if (cacheBean != null) {
            registry.add(tool(
                    "get_cache_stats", McpToolDescriptions.spring("get_cache_stats"), args -> cacheBean.springCache()));
        }
        if (connectionPoolsBean != null) {
            registry.add(tool(
                    "get_database_connection_pools",
                    McpToolDescriptions.spring("get_database_connection_pools"),
                    args -> connectionPoolsBean.pools()));
        }

        this.tools = List.copyOf(registry);
    }

    /**
     * Adds passive reads and bounded actions whose controllers are not part of the original MCP
     * constructor contract.
     *
     * <p>Setter injection keeps the existing auto-configuration factory signature stable while still resolving
     * every optional controller through {@link ObjectProvider}. The final filter uses the same panel manifest as
     * the UI, so a controller that renders an unavailable state does not cause its MCP tool to be advertised.</p>
     */
    @Autowired
    void addPassiveReadTools(
            ObjectProvider<PanelsController> panels,
            ObjectProvider<MetricsController> metrics,
            ObjectProvider<HttpSessionsController> httpSessions,
            ObjectProvider<LiveMemoryController> liveMemory,
            ObjectProvider<JvmTuningController> jvmTuning,
            ObjectProvider<HeapDumpController> heapDump,
            ObjectProvider<ThreadDumpController> threads,
            ObjectProvider<StartupController> startup,
            ObjectProvider<ProfileDiffController> profileDiff,
            ObjectProvider<DataController> data,
            ObjectProvider<FlywayController> flyway,
            ObjectProvider<LiquibaseController> liquibase,
            ObjectProvider<SpringSecurityController> springSecurity,
            ObjectProvider<RestClientTraceController> restClientTrace,
            ObjectProvider<AiController> ai,
            ObjectProvider<EmailController> email,
            ObjectProvider<KafkaController> kafka,
            ObjectProvider<RabbitController> rabbit,
            ObjectProvider<JmsController> jms,
            ObjectProvider<DevToolsController> devTools,
            ObjectProvider<DevServicesController> devServices,
            ObjectProvider<GitHubController> github,
            ObjectProvider<CopilotController> copilot,
            ObjectProvider<ClaudeCodeController> claudeCode,
            ObjectProvider<HibernateStatisticsController> hibernateStatistics,
            ObjectProvider<WebSocketController> webSockets) {
        List<McpTool> registry = new ArrayList<>(tools);

        MetricsController metricsBean = metrics.getIfAvailable();
        if (metricsBean != null) {
            registry.add(tool(
                    "get_metrics",
                    McpToolDescriptions.spring("get_metrics"),
                    args -> metricsBean.metrics(
                            args.query(), null, null, null, null, "0", String.valueOf(args.limit()))));
        }
        HttpSessionsController httpSessionsBean = httpSessions.getIfAvailable();
        if (httpSessionsBean != null) {
            registry.add(tool(
                    "get_http_sessions",
                    McpToolDescriptions.spring("get_http_sessions"),
                    args -> httpSessionsBean.sessions(null)));
        }
        LiveMemoryController liveMemoryBean = liveMemory.getIfAvailable();
        if (liveMemoryBean != null) {
            registry.add(tool(
                    "get_live_memory",
                    McpToolDescriptions.spring("get_live_memory"),
                    args -> MemoryAgentViews.liveMemory(liveMemoryBean.memory(null, null, null, null, null))));
        }
        JvmTuningController jvmTuningBean = jvmTuning.getIfAvailable();
        if (jvmTuningBean != null) {
            registry.add(tool(
                    "get_jvm_tuning",
                    McpToolDescriptions.spring("get_jvm_tuning"),
                    args -> MemoryAgentViews.jvmTuning(jvmTuningBean.jvmTuning(null, null, null, null, null))));
        }
        HeapDumpController heapDumpBean = heapDump.getIfAvailable();
        if (heapDumpBean != null) {
            registry.add(tool(
                    "get_heap_dump_report",
                    McpToolDescriptions.spring("get_heap_dump_report"),
                    args -> heapDumpBean.report("", "")));
            registry.add(tool(
                    "analyze_heap_dump",
                    McpToolDescriptions.spring("analyze_heap_dump"),
                    args -> heapDumpBean.analyze()));
        }
        ThreadDumpController threadsBean = threads.getIfAvailable();
        if (threadsBean != null) {
            registry.add(tool(
                    "get_threads",
                    McpToolDescriptions.spring("get_threads"),
                    args -> threadsBean.threads(args.query(), null, 0, args.limit())));
        }
        StartupController startupBean = startup.getIfAvailable();
        if (startupBean != null) {
            registry.add(tool(
                    "get_startup_timeline",
                    McpToolDescriptions.spring("get_startup_timeline"),
                    args -> McpAgentViews.startup(startupBean.startup(), args.query(), args.limit())));
        }
        ProfileDiffController profileDiffBean = profileDiff.getIfAvailable();
        if (profileDiffBean != null) {
            registry.add(tool(
                    "get_profile_diff",
                    McpToolDescriptions.spring("get_profile_diff"),
                    args -> profileDiffBean.profiles()));
        }
        DataController dataBean = data.getIfAvailable();
        if (dataBean != null) {
            registry.add(tool(
                    "get_spring_data_repositories",
                    McpToolDescriptions.spring("get_spring_data_repositories"),
                    args -> dataBean.repositories()));
        }
        FlywayController flywayBean = flyway.getIfAvailable();
        if (flywayBean != null) {
            registry.add(tool(
                    "get_flyway_migrations",
                    McpToolDescriptions.spring("get_flyway_migrations"),
                    args -> flywayBean.migrations()));
        }
        LiquibaseController liquibaseBean = liquibase.getIfAvailable();
        if (liquibaseBean != null) {
            registry.add(tool(
                    "get_liquibase_changesets",
                    McpToolDescriptions.spring("get_liquibase_changesets"),
                    args -> liquibaseBean.changeSets()));
        }
        SpringSecurityController springSecurityBean = springSecurity.getIfAvailable();
        if (springSecurityBean != null) {
            registry.add(tool(
                    "get_spring_security",
                    McpToolDescriptions.spring("get_spring_security"),
                    args -> springSecurityBean.security()));
        }
        RestClientTraceController restClientTraceBean = restClientTrace.getIfAvailable();
        if (restClientTraceBean != null) {
            registry.add(tool(
                    "get_rest_client_traces",
                    McpToolDescriptions.spring("get_rest_client_traces"),
                    args -> restClientTraceBean.trace()));
            registry.add(tool(
                    "clear_rest_client_traces",
                    McpToolDescriptions.spring("clear_rest_client_traces"),
                    args -> McpControlAcks.restClientTrace(McpControlAcks.CLEARED, restClientTraceBean.clear())));
            registry.add(tool(
                    "pause_rest_client_recording",
                    McpToolDescriptions.spring("pause_rest_client_recording"),
                    args -> McpControlAcks.restClientTrace(
                            McpControlAcks.PAUSED,
                            restClientTraceBean.recording(new RestClientTraceRecordingRequest(false)))));
            registry.add(tool(
                    "resume_rest_client_recording",
                    McpToolDescriptions.spring("resume_rest_client_recording"),
                    args -> McpControlAcks.restClientTrace(
                            McpControlAcks.RESUMED,
                            restClientTraceBean.recording(new RestClientTraceRecordingRequest(true)))));
        }
        AiController aiBean = ai.getIfAvailable();
        if (aiBean != null) {
            registry.add(
                    tool("get_ai_overview", McpToolDescriptions.spring("get_ai_overview"), args -> aiBean.overview()));
        }
        EmailController emailBean = email.getIfAvailable();
        if (emailBean != null) {
            registry.add(tool("get_emails", McpToolDescriptions.spring("get_emails"), args -> emailBean.list()));
        }
        KafkaController kafkaBean = kafka.getIfAvailable();
        if (kafkaBean != null) {
            registry.add(tool(
                    "get_kafka_activity", McpToolDescriptions.spring("get_kafka_activity"), args -> kafkaBean.list()));
        }
        RabbitController rabbitBean = rabbit.getIfAvailable();
        if (rabbitBean != null) {
            registry.add(tool(
                    "get_rabbitmq_activity",
                    McpToolDescriptions.spring("get_rabbitmq_activity"),
                    args -> rabbitBean.list()));
        }
        JmsController jmsBean = jms.getIfAvailable();
        if (jmsBean != null) {
            registry.add(
                    tool("get_jms_activity", McpToolDescriptions.spring("get_jms_activity"), args -> jmsBean.list()));
        }
        DevToolsController devToolsBean = devTools.getIfAvailable();
        if (devToolsBean != null) {
            registry.add(tool(
                    "get_devtools_status",
                    McpToolDescriptions.spring("get_devtools_status"),
                    args -> devToolsBean.status()));
            registry.add(tool(
                    "trigger_devtools_livereload",
                    McpToolDescriptions.spring("trigger_devtools_livereload"),
                    args -> devToolsBean.triggerLiveReload().getBody()));
        }
        DevServicesController devServicesBean = devServices.getIfAvailable();
        if (devServicesBean != null) {
            registry.add(tool(
                    "get_dev_services",
                    McpToolDescriptions.spring("get_dev_services"),
                    args -> devServicesBean.list()));
        }
        GitHubController githubBean = github.getIfAvailable();
        if (githubBean != null) {
            registry.add(tool(
                    "get_github_dashboard",
                    McpToolDescriptions.spring("get_github_dashboard"),
                    args -> githubBean.dashboard()));
        }
        CopilotController copilotBean = copilot.getIfAvailable();
        if (copilotBean != null) {
            registry.add(tool(
                    "get_copilot_sessions",
                    McpToolDescriptions.spring("get_copilot_sessions"),
                    args -> McpAgentViews.sessions(copilotBean.sessions(null, null), args.query(), args.limit())));
        }
        ClaudeCodeController claudeCodeBean = claudeCode.getIfAvailable();
        if (claudeCodeBean != null) {
            registry.add(tool(
                    "get_claude_code_sessions",
                    McpToolDescriptions.spring("get_claude_code_sessions"),
                    args -> McpAgentViews.sessions(claudeCodeBean.sessions(null, null), args.query(), args.limit())));
        }
        HibernateStatisticsController hibernateStatisticsBean = hibernateStatistics.getIfAvailable();
        if (hibernateStatisticsBean != null) {
            registry.add(tool(
                    "get_hibernate_statistics",
                    McpToolDescriptions.spring("get_hibernate_statistics"),
                    args -> hibernateStatisticsBean.statistics()));
        }
        WebSocketController webSocketsBean = webSockets.getIfAvailable();
        if (webSocketsBean != null) {
            registry.add(tool(
                    "get_websockets", McpToolDescriptions.spring("get_websockets"), args -> webSocketsBean.report()));
        }

        this.panelsController = panels.getIfAvailable();
        this.tools = List.copyOf(registry);
    }

    /** Test/extensibility hook that builds the registry from an explicit tool list. */
    BootUiMcpTools(List<McpTool> tools) {
        this.panelsController = null;
        this.tools = List.copyOf(tools);
    }

    /**
     * Why a panel is unavailable in this application, in the panel manifest's own words, or {@code null} when it is
     * available or not known, so a call to a tool this registry does not advertise can say why.
     */
    public String panelUnavailableReason(String panelId) {
        if (panelsController == null) {
            return null;
        }
        return panelsController.panels().panels().stream()
                .filter(panel -> panel.id().equals(panelId) && !panel.available())
                .map(panel -> panel.unavailableReason() == null ? "" : panel.unavailableReason())
                .findFirst()
                .orElse(null);
    }

    /** All tools in advertised order. */
    public List<McpTool> tools() {
        if (panelsController == null) {
            return tools;
        }
        Set<String> availablePanelIds = panelsController.panels().panels().stream()
                .filter(panel -> panel.available())
                .map(panel -> panel.id())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return tools.stream()
                .filter(tool -> availablePanelIds.contains(tool.panelId()))
                .toList();
    }

    /**
     * Builds one advertised tool from the shared {@link McpToolCatalog}.
     *
     * <p>Only the name, description, and handler are adapter-specific. The argument schema, backing panel,
     * and action flag are read back from the catalog, so they cannot be spelled differently here than in the
     * other stacks, and a name this stack is not supposed to advertise fails fast at startup.
     *
     * <p>Every handler is wrapped by {@link SpringMcpToolFailures} at this single point, so a tool that
     * delegates to a controller method cannot report its client error as a server fault by being registered
     * through a path that forgot to translate.
     */
    private static McpTool tool(String name, String description, Function<McpArguments, Object> handler) {
        McpToolCatalog.Entry entry = McpToolCatalog.require(name, McpToolCatalog.Stack.SPRING_MVC);
        return new McpTool(
                name,
                description,
                entry.schema(),
                entry.panelId(),
                entry.action(),
                SpringMcpToolFailures.translating(handler));
    }
}
