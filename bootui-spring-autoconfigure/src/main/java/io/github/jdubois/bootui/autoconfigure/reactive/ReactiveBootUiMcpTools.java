package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.architecture.ArchitectureController;
import io.github.jdubois.bootui.autoconfigure.crac.CracController;
import io.github.jdubois.bootui.autoconfigure.databaseadvisor.DatabaseAdvisorController;
import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmController;
import io.github.jdubois.bootui.autoconfigure.hibernate.HibernateController;
import io.github.jdubois.bootui.autoconfigure.jms.JmsController;
import io.github.jdubois.bootui.autoconfigure.kafka.KafkaController;
import io.github.jdubois.bootui.autoconfigure.mail.EmailController;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryController;
import io.github.jdubois.bootui.autoconfigure.pentesting.PentestingController;
import io.github.jdubois.bootui.autoconfigure.rabbit.RabbitController;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiController;
import io.github.jdubois.bootui.autoconfigure.spring.SpringController;
import io.github.jdubois.bootui.autoconfigure.web.AiController;
import io.github.jdubois.bootui.autoconfigure.web.BeansController;
import io.github.jdubois.bootui.autoconfigure.web.ConditionsController;
import io.github.jdubois.bootui.autoconfigure.web.ConfigController;
import io.github.jdubois.bootui.autoconfigure.web.DataController;
import io.github.jdubois.bootui.autoconfigure.web.DatabaseConnectionPoolsController;
import io.github.jdubois.bootui.autoconfigure.web.DevServicesController;
import io.github.jdubois.bootui.autoconfigure.web.DevToolsController;
import io.github.jdubois.bootui.autoconfigure.web.FlywayController;
import io.github.jdubois.bootui.autoconfigure.web.GitHubController;
import io.github.jdubois.bootui.autoconfigure.web.GrpcController;
import io.github.jdubois.bootui.autoconfigure.web.HealthController;
import io.github.jdubois.bootui.autoconfigure.web.HeapDumpController;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.JvmTuningController;
import io.github.jdubois.bootui.autoconfigure.web.LiquibaseController;
import io.github.jdubois.bootui.autoconfigure.web.LiveMemoryController;
import io.github.jdubois.bootui.autoconfigure.web.LoggersController;
import io.github.jdubois.bootui.autoconfigure.web.MappingsController;
import io.github.jdubois.bootui.autoconfigure.web.MetricsController;
import io.github.jdubois.bootui.autoconfigure.web.OverviewController;
import io.github.jdubois.bootui.autoconfigure.web.PanelsController;
import io.github.jdubois.bootui.autoconfigure.web.ProfileDiffController;
import io.github.jdubois.bootui.autoconfigure.web.ScheduledController;
import io.github.jdubois.bootui.autoconfigure.web.SpringCacheController;
import io.github.jdubois.bootui.autoconfigure.web.StartupController;
import io.github.jdubois.bootui.autoconfigure.web.ThreadDumpController;
import io.github.jdubois.bootui.autoconfigure.web.TracesController;
import io.github.jdubois.bootui.autoconfigure.web.VulnerabilitiesController;
import io.github.jdubois.bootui.core.dto.RestClientTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.SqlTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.TransactionRecordingRequest;
import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolDescriptions;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.reactivesecurity.ReactiveSecurityAdvisorService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Reactive (WebFlux) MCP tool catalog: mirrors {@code BootUiMcpTools} but binds the handful of
 * controller types whose implementations differ on WebFlux.
 */
public class ReactiveBootUiMcpTools {

    private volatile List<McpTool> tools;
    private volatile PanelsController panelsController;

    public ReactiveBootUiMcpTools(
            ObjectProvider<OverviewController> overview,
            ObjectProvider<HealthController> health,
            ObjectProvider<ConfigController> config,
            ObjectProvider<BeansController> beans,
            ObjectProvider<MappingsController> mappings,
            ObjectProvider<ReactiveExceptionsController> exceptions,
            ObjectProvider<ReactiveLiveActivityController> liveActivity,
            ObjectProvider<ReactiveSecurityLogsController> securityLogs,
            ObjectProvider<ReactiveSqlTraceController> sqlTrace,
            ObjectProvider<ReactiveTransactionsController> transactions,
            ObjectProvider<TracesController> traces,
            ObjectProvider<ReactiveLogTailController> logTail,
            ObjectProvider<HttpExchangesController> httpExchanges,
            ObjectProvider<ArchitectureController> architecture,
            ObjectProvider<SpringController> spring,
            ObjectProvider<HibernateController> hibernate,
            ObjectProvider<MemoryController> memory,
            ObjectProvider<ReactiveSecurityAdvisorService> security,
            ObjectProvider<PentestingController> pentesting,
            ObjectProvider<RestApiController> restApi,
            ObjectProvider<GraalVmController> graalvm,
            ObjectProvider<CracController> crac,
            ObjectProvider<DatabaseAdvisorController> databaseAdvisor,
            ObjectProvider<VulnerabilitiesController> vulnerabilities,
            ObjectProvider<LoggersController> loggers,
            ObjectProvider<ConditionsController> conditions,
            ObjectProvider<ScheduledController> scheduled,
            ObjectProvider<SpringCacheController> cache,
            ObjectProvider<DatabaseConnectionPoolsController> connectionPools) {
        OverviewController overviewBean = overview.getIfAvailable();
        HealthController healthBean = health.getIfAvailable();
        ConfigController configBean = config.getIfAvailable();
        BeansController beansBean = beans.getIfAvailable();
        MappingsController mappingsBean = mappings.getIfAvailable();
        ReactiveExceptionsController exceptionsBean = exceptions.getIfAvailable();
        ReactiveLiveActivityController liveActivityBean = liveActivity.getIfAvailable();
        ReactiveSecurityLogsController securityLogsBean = securityLogs.getIfAvailable();
        ReactiveSqlTraceController sqlTraceBean = sqlTrace.getIfAvailable();
        ReactiveTransactionsController transactionsBean = transactions.getIfAvailable();
        TracesController tracesBean = traces.getIfAvailable();
        ReactiveLogTailController logTailBean = logTail.getIfAvailable();
        HttpExchangesController httpExchangesBean = httpExchanges.getIfAvailable();
        ArchitectureController architectureBean = architecture.getIfAvailable();
        SpringController springBean = spring.getIfAvailable();
        HibernateController hibernateBean = hibernate.getIfAvailable();
        MemoryController memoryBean = memory.getIfAvailable();
        ReactiveSecurityAdvisorService securityBean = security.getIfAvailable();
        PentestingController pentestingBean = pentesting.getIfAvailable();
        RestApiController restApiBean = restApi.getIfAvailable();
        GraalVmController graalvmBean = graalvm.getIfAvailable();
        CracController cracBean = crac.getIfAvailable();
        DatabaseAdvisorController databaseAdvisorBean = databaseAdvisor.getIfAvailable();
        VulnerabilitiesController vulnerabilitiesBean = vulnerabilities.getIfAvailable();
        LoggersController loggersBean = loggers.getIfAvailable();
        ConditionsController conditionsBean = conditions.getIfAvailable();
        ScheduledController scheduledBean = scheduled.getIfAvailable();
        SpringCacheController cacheBean = cache.getIfAvailable();
        DatabaseConnectionPoolsController connectionPoolsBean = connectionPools.getIfAvailable();

        List<McpTool> registry = new ArrayList<>();

        if (architectureBean != null) {
            registry.add(action(
                    "architecture_scan",
                    McpToolDescriptions.spring("architecture_scan"),
                    BootUiPanels.ARCHITECTURE,
                    args -> architectureBean.scan()));
            registry.add(read(
                    "get_architecture_report",
                    McpToolDescriptions.spring("get_architecture_report"),
                    BootUiPanels.ARCHITECTURE,
                    args -> architectureBean.architecture()));
        }
        if (springBean != null) {
            registry.add(action(
                    "spring_scan",
                    McpToolDescriptions.spring("spring_scan"),
                    BootUiPanels.SPRING,
                    args -> springBean.scan()));
            registry.add(read(
                    "get_spring_report",
                    McpToolDescriptions.spring("get_spring_report"),
                    BootUiPanels.SPRING,
                    args -> springBean.spring()));
        }
        if (hibernateBean != null) {
            registry.add(action(
                    "hibernate_scan",
                    McpToolDescriptions.spring("hibernate_scan"),
                    BootUiPanels.HIBERNATE,
                    args -> hibernateBean.scan()));
            registry.add(read(
                    "get_hibernate_report",
                    McpToolDescriptions.spring("get_hibernate_report"),
                    BootUiPanels.HIBERNATE,
                    args -> hibernateBean.hibernate()));
        }
        if (memoryBean != null) {
            registry.add(action(
                    "memory_scan",
                    McpToolDescriptions.spring("memory_scan"),
                    BootUiPanels.MEMORY,
                    args -> memoryBean.scan()));
            registry.add(read(
                    "get_memory_report",
                    McpToolDescriptions.spring("get_memory_report"),
                    BootUiPanels.MEMORY,
                    args -> memoryBean.memory()));
        }
        if (securityBean != null) {
            registry.add(action(
                    "security_scan",
                    McpToolDescriptions.spring("security_scan"),
                    BootUiPanels.SECURITY,
                    args -> securityBean.scan()));
            registry.add(read(
                    "get_security_report",
                    McpToolDescriptions.spring("get_security_report"),
                    BootUiPanels.SECURITY,
                    args -> securityBean.report()));
        }
        if (pentestingBean != null) {
            registry.add(action(
                    "pentest_scan",
                    McpToolDescriptions.spring("pentest_scan"),
                    BootUiPanels.PENTESTING,
                    args -> pentestingBean.scan()));
            registry.add(read(
                    "get_pentest_report",
                    McpToolDescriptions.spring("get_pentest_report"),
                    BootUiPanels.PENTESTING,
                    args -> pentestingBean.pentesting()));
        }
        if (restApiBean != null) {
            registry.add(action(
                    "rest_api_scan",
                    McpToolDescriptions.spring("rest_api_scan"),
                    BootUiPanels.REST_API,
                    args -> restApiBean.scan()));
            registry.add(read(
                    "get_rest_api_report",
                    McpToolDescriptions.spring("get_rest_api_report"),
                    BootUiPanels.REST_API,
                    args -> restApiBean.restApi()));
        }
        if (graalvmBean != null) {
            registry.add(action(
                    "graalvm_scan",
                    McpToolDescriptions.spring("graalvm_scan"),
                    BootUiPanels.GRAALVM,
                    args -> graalvmBean.scan(false)));
            registry.add(read(
                    "get_graalvm_report",
                    McpToolDescriptions.spring("get_graalvm_report"),
                    BootUiPanels.GRAALVM,
                    args -> graalvmBean.graalvm()));
        }
        if (cracBean != null) {
            registry.add(action(
                    "crac_scan", McpToolDescriptions.spring("crac_scan"), BootUiPanels.CRAC, args -> cracBean.scan()));
            registry.add(read(
                    "get_crac_report",
                    McpToolDescriptions.spring("get_crac_report"),
                    BootUiPanels.CRAC,
                    args -> cracBean.crac()));
        }
        if (databaseAdvisorBean != null) {
            registry.add(action(
                    "database_advisor_scan",
                    McpToolDescriptions.spring("database_advisor_scan"),
                    BootUiPanels.DATABASE_ADVISOR,
                    args -> databaseAdvisorBean.scan()));
            registry.add(read(
                    "get_database_advisor_report",
                    McpToolDescriptions.spring("get_database_advisor_report"),
                    BootUiPanels.DATABASE_ADVISOR,
                    args -> databaseAdvisorBean.databaseAdvisor()));
        }
        if (vulnerabilitiesBean != null) {
            registry.add(action(
                    "vulnerabilities_scan",
                    McpToolDescriptions.spring("vulnerabilities_scan"),
                    BootUiPanels.VULNERABILITIES,
                    args -> vulnerabilitiesBean.scan()));
            registry.add(read(
                    "get_vulnerabilities_report",
                    McpToolDescriptions.spring("get_vulnerabilities_report"),
                    BootUiPanels.VULNERABILITIES,
                    args -> vulnerabilitiesBean.dependencies()));
        }

        if (liveActivityBean != null) {
            registry.add(limitRead(
                    "get_live_activity",
                    McpToolDescriptions.spring("get_live_activity"),
                    BootUiPanels.ACTIVITY,
                    args -> liveActivityBean.activity(null, null, 0, args.limit(), null, null, null, 0)));
        }
        if (exceptionsBean != null) {
            registry.add(read(
                    "get_exceptions",
                    McpToolDescriptions.spring("get_exceptions"),
                    BootUiPanels.EXCEPTIONS,
                    args -> exceptionsBean.list()));
            registry.add(idRead(
                    "get_exception_detail",
                    McpToolDescriptions.spring("get_exception_detail"),
                    BootUiPanels.EXCEPTIONS,
                    args -> exceptionsBean.detail(args.id())));
            registry.add(action(
                    "clear_exceptions",
                    McpToolDescriptions.spring("clear_exceptions"),
                    BootUiPanels.EXCEPTIONS,
                    args -> {
                        exceptionsBean.clear();
                        return Map.of("cleared", true);
                    }));
        }
        if (securityLogsBean != null) {
            registry.add(limitRead(
                    "get_security_logs",
                    McpToolDescriptions.spring("get_security_logs"),
                    BootUiPanels.SECURITY_LOGS,
                    args -> securityLogsBean.logs(null, null, null, null, args.limit())));
        }
        if (sqlTraceBean != null) {
            registry.add(read(
                    "get_sql_traces",
                    McpToolDescriptions.spring("get_sql_traces"),
                    BootUiPanels.SQL_TRACE,
                    args -> sqlTraceBean.trace()));
            registry.add(action(
                    "clear_sql_traces",
                    McpToolDescriptions.spring("clear_sql_traces"),
                    BootUiPanels.SQL_TRACE,
                    args -> sqlTraceBean.clear()));
            registry.add(action(
                    "pause_sql_trace_recording",
                    McpToolDescriptions.spring("pause_sql_trace_recording"),
                    BootUiPanels.SQL_TRACE,
                    args -> sqlTraceBean.recording(new SqlTraceRecordingRequest(false))));
            registry.add(action(
                    "resume_sql_trace_recording",
                    McpToolDescriptions.spring("resume_sql_trace_recording"),
                    BootUiPanels.SQL_TRACE,
                    args -> sqlTraceBean.recording(new SqlTraceRecordingRequest(true))));
        }
        if (transactionsBean != null) {
            registry.add(read(
                    "get_transactions",
                    McpToolDescriptions.spring("get_transactions"),
                    BootUiPanels.TRANSACTIONS,
                    args -> transactionsBean.trace()));
            registry.add(action(
                    "clear_transactions",
                    McpToolDescriptions.spring("clear_transactions"),
                    BootUiPanels.TRANSACTIONS,
                    args -> transactionsBean.clear()));
            registry.add(action(
                    "pause_transaction_recording",
                    McpToolDescriptions.spring("pause_transaction_recording"),
                    BootUiPanels.TRANSACTIONS,
                    args -> transactionsBean.recording(new TransactionRecordingRequest(false))));
            registry.add(action(
                    "resume_transaction_recording",
                    McpToolDescriptions.spring("resume_transaction_recording"),
                    BootUiPanels.TRANSACTIONS,
                    args -> transactionsBean.recording(new TransactionRecordingRequest(true))));
        }
        if (tracesBean != null) {
            registry.add(limitRead(
                    "get_traces",
                    McpToolDescriptions.spring("get_traces"),
                    BootUiPanels.TRACES,
                    args -> tracesBean.list(args.limit())));
            registry.add(
                    action("clear_traces", McpToolDescriptions.spring("clear_traces"), BootUiPanels.TRACES, args -> {
                        tracesBean.clear();
                        return Map.of("cleared", true);
                    }));
        }
        if (logTailBean != null) {
            registry.add(read(
                    "get_log_tail",
                    McpToolDescriptions.spring("get_log_tail"),
                    BootUiPanels.LOG_TAIL,
                    args -> Map.of("entries", logTailBean.recent())));
        }
        if (httpExchangesBean != null) {
            registry.add(limitRead(
                    "get_http_exchanges",
                    McpToolDescriptions.spring("get_http_exchanges"),
                    BootUiPanels.HTTP_EXCHANGES,
                    args -> httpExchangesBean.exchanges(null, null, null, null, args.limit())));
        }

        if (overviewBean != null) {
            registry.add(read(
                    "get_overview",
                    McpToolDescriptions.spring("get_overview"),
                    BootUiPanels.OVERVIEW,
                    args -> overviewBean.overview()));
        }
        if (healthBean != null) {
            registry.add(read(
                    "get_health",
                    McpToolDescriptions.spring("get_health"),
                    BootUiPanels.HEALTH,
                    args -> healthBean.health()));
        }
        if (configBean != null) {
            registry.add(searchRead(
                    "get_config",
                    McpToolDescriptions.spring("get_config"),
                    BootUiPanels.CONFIG,
                    args -> configBean.list(args.query(), null, false, null, args.limit())));
        }
        if (beansBean != null) {
            registry.add(searchRead(
                    "get_beans",
                    McpToolDescriptions.spring("get_beans"),
                    BootUiPanels.BEANS,
                    args -> beansBean.beans(args.query(), null, null, args.limit())));
        }
        if (mappingsBean != null) {
            registry.add(searchRead(
                    "get_mappings",
                    McpToolDescriptions.spring("get_mappings"),
                    BootUiPanels.MAPPINGS,
                    args -> mappingsBean.flatMappings(args.query(), null, args.limit())));
        }
        if (loggersBean != null) {
            registry.add(searchRead(
                    "get_loggers",
                    McpToolDescriptions.spring("get_loggers"),
                    BootUiPanels.LOGGERS,
                    args -> loggersBean.loggers(args.query(), null, args.limit())));
        }
        if (conditionsBean != null) {
            registry.add(searchRead(
                    "get_conditions",
                    McpToolDescriptions.spring("get_conditions"),
                    BootUiPanels.CONDITIONS,
                    args -> conditionsBean.conditions(args.query(), null, null, args.limit())));
        }
        if (scheduledBean != null) {
            registry.add(read(
                    "get_scheduled_tasks",
                    McpToolDescriptions.spring("get_scheduled_tasks"),
                    BootUiPanels.SCHEDULED,
                    args -> scheduledBean.scheduled()));
        }
        if (cacheBean != null) {
            registry.add(read(
                    "get_cache_stats",
                    McpToolDescriptions.spring("get_cache_stats"),
                    BootUiPanels.CACHE,
                    args -> cacheBean.springCache()));
        }
        if (connectionPoolsBean != null) {
            registry.add(read(
                    "get_database_connection_pools",
                    McpToolDescriptions.spring("get_database_connection_pools"),
                    BootUiPanels.DATABASE_CONNECTION_POOLS,
                    args -> connectionPoolsBean.pools()));
        }

        this.tools = List.copyOf(registry);
    }

    /**
     * Adds availability-aware passive reads and bounded actions backed by shared or WebFlux-native
     * controllers.
     *
     * <p>The existing auto-configuration factory keeps its stable constructor call; Spring injects these optional
     * providers after construction. The final manifest filter excludes the servlet-only HTTP Sessions panel and
     * any other controller whose panel is unavailable in the running reactive application.</p>
     */
    @Autowired
    void addPassiveReadTools(
            ObjectProvider<PanelsController> panels,
            ObjectProvider<MetricsController> metrics,
            ObjectProvider<LiveMemoryController> liveMemory,
            ObjectProvider<JvmTuningController> jvmTuning,
            ObjectProvider<HeapDumpController> heapDump,
            ObjectProvider<ThreadDumpController> threads,
            ObjectProvider<StartupController> startup,
            ObjectProvider<ProfileDiffController> profileDiff,
            ObjectProvider<DataController> data,
            ObjectProvider<FlywayController> flyway,
            ObjectProvider<LiquibaseController> liquibase,
            ObjectProvider<ReactiveSpringSecurityController> springSecurity,
            ObjectProvider<ReactiveRestClientTraceController> restClientTrace,
            ObjectProvider<AiController> ai,
            ObjectProvider<EmailController> email,
            ObjectProvider<KafkaController> kafka,
            ObjectProvider<RabbitController> rabbit,
            ObjectProvider<JmsController> jms,
            ObjectProvider<GrpcController> grpc,
            ObjectProvider<DevToolsController> devTools,
            ObjectProvider<DevServicesController> devServices,
            ObjectProvider<GitHubController> github,
            ObjectProvider<ReactiveCopilotController> copilot,
            ObjectProvider<ReactiveClaudeCodeController> claudeCode) {
        List<McpTool> registry = new ArrayList<>(tools);

        MetricsController metricsBean = metrics.getIfAvailable();
        if (metricsBean != null) {
            registry.add(searchRead(
                    "get_metrics",
                    McpToolDescriptions.spring("get_metrics"),
                    BootUiPanels.METRICS,
                    args -> metricsBean.metrics(args.query(), null, "0", String.valueOf(args.limit()))));
        }
        LiveMemoryController liveMemoryBean = liveMemory.getIfAvailable();
        if (liveMemoryBean != null) {
            registry.add(read(
                    "get_live_memory",
                    McpToolDescriptions.spring("get_live_memory"),
                    BootUiPanels.LIVE_MEMORY,
                    args -> liveMemoryBean.memory(null, null, null, null, null)));
        }
        JvmTuningController jvmTuningBean = jvmTuning.getIfAvailable();
        if (jvmTuningBean != null) {
            registry.add(read(
                    "get_jvm_tuning",
                    McpToolDescriptions.spring("get_jvm_tuning"),
                    BootUiPanels.JVM_TUNING,
                    args -> jvmTuningBean.jvmTuning(null, null, null, null, null)));
        }
        HeapDumpController heapDumpBean = heapDump.getIfAvailable();
        if (heapDumpBean != null) {
            registry.add(read(
                    "get_heap_dump_report",
                    McpToolDescriptions.spring("get_heap_dump_report"),
                    BootUiPanels.HEAP_DUMP,
                    args -> heapDumpBean.report("", "")));
            registry.add(action(
                    "analyze_heap_dump",
                    McpToolDescriptions.spring("analyze_heap_dump"),
                    BootUiPanels.HEAP_DUMP,
                    args -> heapDumpBean.analyze()));
        }
        ThreadDumpController threadsBean = threads.getIfAvailable();
        if (threadsBean != null) {
            registry.add(searchRead(
                    "get_threads",
                    McpToolDescriptions.spring("get_threads"),
                    BootUiPanels.THREADS,
                    args -> threadsBean.threads(args.query(), null, 0, args.limit())));
        }
        StartupController startupBean = startup.getIfAvailable();
        if (startupBean != null) {
            registry.add(read(
                    "get_startup_timeline",
                    McpToolDescriptions.spring("get_startup_timeline"),
                    BootUiPanels.STARTUP,
                    args -> startupBean.startup()));
        }
        ProfileDiffController profileDiffBean = profileDiff.getIfAvailable();
        if (profileDiffBean != null) {
            registry.add(read(
                    "get_profile_diff",
                    McpToolDescriptions.spring("get_profile_diff"),
                    BootUiPanels.PROFILE_DIFF,
                    args -> profileDiffBean.profiles()));
        }
        DataController dataBean = data.getIfAvailable();
        if (dataBean != null) {
            registry.add(read(
                    "get_spring_data_repositories",
                    McpToolDescriptions.spring("get_spring_data_repositories"),
                    BootUiPanels.DATA,
                    args -> dataBean.repositories()));
        }
        FlywayController flywayBean = flyway.getIfAvailable();
        if (flywayBean != null) {
            registry.add(read(
                    "get_flyway_migrations",
                    McpToolDescriptions.spring("get_flyway_migrations"),
                    BootUiPanels.FLYWAY,
                    args -> flywayBean.migrations()));
        }
        LiquibaseController liquibaseBean = liquibase.getIfAvailable();
        if (liquibaseBean != null) {
            registry.add(read(
                    "get_liquibase_changesets",
                    McpToolDescriptions.spring("get_liquibase_changesets"),
                    BootUiPanels.LIQUIBASE,
                    args -> liquibaseBean.changeSets()));
        }
        ReactiveSpringSecurityController springSecurityBean = springSecurity.getIfAvailable();
        if (springSecurityBean != null) {
            registry.add(read(
                    "get_spring_security",
                    McpToolDescriptions.spring("get_spring_security"),
                    BootUiPanels.SPRING_SECURITY,
                    args -> springSecurityBean.security().block()));
        }
        ReactiveRestClientTraceController restClientTraceBean = restClientTrace.getIfAvailable();
        if (restClientTraceBean != null) {
            registry.add(read(
                    "get_rest_client_traces",
                    McpToolDescriptions.spring("get_rest_client_traces"),
                    BootUiPanels.REST_CLIENT_TRACE,
                    args -> restClientTraceBean.trace()));
            registry.add(action(
                    "clear_rest_client_traces",
                    McpToolDescriptions.spring("clear_rest_client_traces"),
                    BootUiPanels.REST_CLIENT_TRACE,
                    args -> restClientTraceBean.clear()));
            registry.add(action(
                    "pause_rest_client_recording",
                    McpToolDescriptions.spring("pause_rest_client_recording"),
                    BootUiPanels.REST_CLIENT_TRACE,
                    args -> restClientTraceBean.recording(new RestClientTraceRecordingRequest(false))));
            registry.add(action(
                    "resume_rest_client_recording",
                    McpToolDescriptions.spring("resume_rest_client_recording"),
                    BootUiPanels.REST_CLIENT_TRACE,
                    args -> restClientTraceBean.recording(new RestClientTraceRecordingRequest(true))));
        }
        AiController aiBean = ai.getIfAvailable();
        if (aiBean != null) {
            registry.add(read(
                    "get_ai_overview",
                    McpToolDescriptions.spring("get_ai_overview"),
                    BootUiPanels.AI,
                    args -> aiBean.overview()));
        }
        EmailController emailBean = email.getIfAvailable();
        if (emailBean != null) {
            registry.add(read(
                    "get_emails",
                    McpToolDescriptions.spring("get_emails"),
                    BootUiPanels.EMAIL,
                    args -> emailBean.list()));
        }
        KafkaController kafkaBean = kafka.getIfAvailable();
        if (kafkaBean != null) {
            registry.add(read(
                    "get_kafka_activity",
                    McpToolDescriptions.spring("get_kafka_activity"),
                    BootUiPanels.KAFKA,
                    args -> kafkaBean.list()));
        }
        RabbitController rabbitBean = rabbit.getIfAvailable();
        if (rabbitBean != null) {
            registry.add(read(
                    "get_rabbitmq_activity",
                    McpToolDescriptions.spring("get_rabbitmq_activity"),
                    BootUiPanels.RABBITMQ,
                    args -> rabbitBean.list()));
        }
        JmsController jmsBean = jms.getIfAvailable();
        if (jmsBean != null) {
            registry.add(read(
                    "get_jms_activity",
                    McpToolDescriptions.spring("get_jms_activity"),
                    BootUiPanels.JMS,
                    args -> jmsBean.list()));
        }
        GrpcController grpcBean = grpc.getIfAvailable();
        if (grpcBean != null) {
            registry.add(read(
                    "get_grpc_registry",
                    McpToolDescriptions.spring("get_grpc_registry"),
                    BootUiPanels.GRPC,
                    args -> grpcBean.grpc()));
        }
        DevToolsController devToolsBean = devTools.getIfAvailable();
        if (devToolsBean != null) {
            registry.add(read(
                    "get_devtools_status",
                    McpToolDescriptions.spring("get_devtools_status"),
                    BootUiPanels.DEVTOOLS,
                    args -> devToolsBean.status()));
            registry.add(action(
                    "trigger_devtools_livereload",
                    McpToolDescriptions.spring("trigger_devtools_livereload"),
                    BootUiPanels.DEVTOOLS,
                    args -> devToolsBean.triggerLiveReload().getBody()));
        }
        DevServicesController devServicesBean = devServices.getIfAvailable();
        if (devServicesBean != null) {
            registry.add(read(
                    "get_dev_services",
                    McpToolDescriptions.spring("get_dev_services"),
                    BootUiPanels.DEV_SERVICES,
                    args -> devServicesBean.list()));
        }
        GitHubController githubBean = github.getIfAvailable();
        if (githubBean != null) {
            registry.add(read(
                    "get_github_dashboard",
                    McpToolDescriptions.spring("get_github_dashboard"),
                    BootUiPanels.GITHUB,
                    args -> githubBean.dashboard()));
        }
        ReactiveCopilotController copilotBean = copilot.getIfAvailable();
        if (copilotBean != null) {
            registry.add(read(
                    "get_copilot_sessions",
                    McpToolDescriptions.spring("get_copilot_sessions"),
                    BootUiPanels.COPILOT,
                    args -> copilotBean.sessions(null, null)));
        }
        ReactiveClaudeCodeController claudeCodeBean = claudeCode.getIfAvailable();
        if (claudeCodeBean != null) {
            registry.add(read(
                    "get_claude_code_sessions",
                    McpToolDescriptions.spring("get_claude_code_sessions"),
                    BootUiPanels.CLAUDE_CODE,
                    args -> claudeCodeBean.sessions(null, null)));
        }

        this.panelsController = panels.getIfAvailable();
        this.tools = List.copyOf(registry);
    }

    ReactiveBootUiMcpTools(List<McpTool> tools) {
        this.panelsController = null;
        this.tools = List.copyOf(tools);
    }

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
