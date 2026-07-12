package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.architecture.ArchitectureController;
import io.github.jdubois.bootui.autoconfigure.crac.CracController;
import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmController;
import io.github.jdubois.bootui.autoconfigure.hibernate.HibernateController;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryController;
import io.github.jdubois.bootui.autoconfigure.pentesting.PentestingController;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiController;
import io.github.jdubois.bootui.autoconfigure.spring.SpringController;
import io.github.jdubois.bootui.autoconfigure.web.BeansController;
import io.github.jdubois.bootui.autoconfigure.web.ConfigController;
import io.github.jdubois.bootui.autoconfigure.web.HealthController;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.MappingsController;
import io.github.jdubois.bootui.autoconfigure.web.OverviewController;
import io.github.jdubois.bootui.autoconfigure.web.TracesController;
import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Reactive (WebFlux) MCP tool catalog: mirrors {@code BootUiMcpTools} but binds the handful of
 * controller types whose implementations differ on WebFlux.
 */
public class ReactiveBootUiMcpTools {

    private final List<McpTool> tools;

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
            ObjectProvider<TracesController> traces,
            ObjectProvider<ReactiveLogTailController> logTail,
            ObjectProvider<HttpExchangesController> httpExchanges,
            ObjectProvider<ArchitectureController> architecture,
            ObjectProvider<SpringController> spring,
            ObjectProvider<HibernateController> hibernate,
            ObjectProvider<MemoryController> memory,
            ObjectProvider<PentestingController> pentesting,
            ObjectProvider<RestApiController> restApi,
            ObjectProvider<GraalVmController> graalvm,
            ObjectProvider<CracController> crac) {
        OverviewController overviewBean = overview.getIfAvailable();
        HealthController healthBean = health.getIfAvailable();
        ConfigController configBean = config.getIfAvailable();
        BeansController beansBean = beans.getIfAvailable();
        MappingsController mappingsBean = mappings.getIfAvailable();
        ReactiveExceptionsController exceptionsBean = exceptions.getIfAvailable();
        ReactiveLiveActivityController liveActivityBean = liveActivity.getIfAvailable();
        ReactiveSecurityLogsController securityLogsBean = securityLogs.getIfAvailable();
        ReactiveSqlTraceController sqlTraceBean = sqlTrace.getIfAvailable();
        TracesController tracesBean = traces.getIfAvailable();
        ReactiveLogTailController logTailBean = logTail.getIfAvailable();
        HttpExchangesController httpExchangesBean = httpExchanges.getIfAvailable();
        ArchitectureController architectureBean = architecture.getIfAvailable();
        SpringController springBean = spring.getIfAvailable();
        HibernateController hibernateBean = hibernate.getIfAvailable();
        MemoryController memoryBean = memory.getIfAvailable();
        PentestingController pentestingBean = pentesting.getIfAvailable();
        RestApiController restApiBean = restApi.getIfAvailable();
        GraalVmController graalvmBean = graalvm.getIfAvailable();
        CracController cracBean = crac.getIfAvailable();

        List<McpTool> registry = new ArrayList<>();

        if (architectureBean != null) {
            registry.add(action(
                    "architecture_scan",
                    "Run the Architecture advisor and return layering/dependency findings to fix.",
                    BootUiPanels.ARCHITECTURE,
                    args -> architectureBean.scan()));
        }
        if (springBean != null) {
            registry.add(action(
                    "spring_scan",
                    "Run the Spring advisor and return Spring configuration/bean findings to fix.",
                    BootUiPanels.SPRING,
                    args -> springBean.scan()));
        }
        if (hibernateBean != null) {
            registry.add(action(
                    "hibernate_scan",
                    "Run the Hibernate advisor and return JPA/Hibernate mapping and query findings.",
                    BootUiPanels.HIBERNATE,
                    args -> hibernateBean.scan()));
        }
        if (memoryBean != null) {
            registry.add(action(
                    "memory_scan",
                    "Run the Memory advisor (triggers a class histogram) and return memory findings.",
                    BootUiPanels.MEMORY,
                    args -> memoryBean.scan()));
        }
        if (pentestingBean != null) {
            registry.add(action(
                    "pentest_scan",
                    "Run the Pentesting advisor and return probing-based security findings.",
                    BootUiPanels.PENTESTING,
                    args -> pentestingBean.scan()));
        }
        if (restApiBean != null) {
            registry.add(action(
                    "rest_api_scan",
                    "Run the REST API advisor and return REST controller/design findings to fix.",
                    BootUiPanels.REST_API,
                    args -> restApiBean.scan()));
        }
        if (graalvmBean != null) {
            registry.add(action(
                    "graalvm_scan",
                    "Run the GraalVM readiness advisor (without the longer dependency scan) and return "
                            + "native-image readiness findings.",
                    BootUiPanels.GRAALVM,
                    args -> graalvmBean.scan(false)));
        }
        if (cracBean != null) {
            registry.add(action(
                    "crac_scan",
                    "Run the CRaC readiness advisor and return checkpoint/restore readiness findings.",
                    BootUiPanels.CRAC,
                    args -> cracBean.scan()));
        }

        if (liveActivityBean != null) {
            registry.add(limitRead(
                    "get_live_activity",
                    "Return the correlated live activity feed: HTTP requests, SQL statements, exceptions, "
                            + "and security events, grouped by request/trace so related signals (e.g. the "
                            + "slow query or exception behind one HTTP request) are easy to spot together.",
                    BootUiPanels.ACTIVITY,
                    args -> liveActivityBean.activity(null, null, 0, args.limit(), null, null, null, 0)));
        }
        if (exceptionsBean != null) {
            registry.add(read(
                    "get_exceptions",
                    "List recent unhandled exceptions captured at runtime (most recent first).",
                    BootUiPanels.EXCEPTIONS,
                    args -> exceptionsBean.list()));
            registry.add(idRead(
                    "get_exception_detail",
                    "Return full detail for one exception group by id: stack trace frames, causes, and "
                            + "individual occurrences (request method/path/handler/thread/traceId). Use the "
                            + "'id' from get_exceptions or get_live_activity.",
                    BootUiPanels.EXCEPTIONS,
                    args -> exceptionsBean.detail(args.id())));
        }
        if (securityLogsBean != null) {
            registry.add(limitRead(
                    "get_security_logs",
                    "List recent security audit events (authentication, authorization, etc.).",
                    BootUiPanels.SECURITY_LOGS,
                    args -> securityLogsBean.logs(null, null, null, null, args.limit())));
        }
        if (sqlTraceBean != null) {
            registry.add(read(
                    "get_sql_traces",
                    "Return recently recorded SQL statements and timings from the SQL Trace recorder.",
                    BootUiPanels.SQL_TRACE,
                    args -> sqlTraceBean.trace()));
        }
        if (tracesBean != null) {
            registry.add(limitRead(
                    "get_traces",
                    "Return recent distributed/local traces captured by BootUI.",
                    BootUiPanels.TRACES,
                    args -> tracesBean.list(args.limit())));
        }
        if (logTailBean != null) {
            registry.add(read(
                    "get_log_tail",
                    "Return the most recent buffered application log lines.",
                    BootUiPanels.LOG_TAIL,
                    args -> logTailBean.recent()));
        }
        if (httpExchangesBean != null) {
            registry.add(limitRead(
                    "get_http_exchanges",
                    "List recent HTTP request/response exchanges handled by the application.",
                    BootUiPanels.HTTP_EXCHANGES,
                    args -> httpExchangesBean.exchanges(null, null, null, null, args.limit())));
        }

        if (overviewBean != null) {
            registry.add(read(
                    "get_overview",
                    "Return the application overview: name, versions, profiles, and BootUI status.",
                    BootUiPanels.OVERVIEW,
                    args -> overviewBean.overview()));
        }
        if (healthBean != null) {
            registry.add(read(
                    "get_health",
                    "Return the aggregated application health tree (Actuator health).",
                    BootUiPanels.HEALTH,
                    args -> healthBean.health()));
        }
        if (configBean != null) {
            registry.add(searchRead(
                    "get_config",
                    "Return effective configuration properties (secret values masked). Optional 'query' "
                            + "filters by property name/value.",
                    BootUiPanels.CONFIG,
                    args -> configBean.list(args.query(), null, false, null, args.limit())));
        }
        if (beansBean != null) {
            registry.add(searchRead(
                    "get_beans",
                    "List Spring beans. Optional 'query' filters by bean name or type.",
                    BootUiPanels.BEANS,
                    args -> beansBean.beans(args.query(), null, null, args.limit())));
        }
        if (mappingsBean != null) {
            registry.add(searchRead(
                    "get_mappings",
                    "List request mappings (URL patterns to handlers). Optional 'query' filters them.",
                    BootUiPanels.MAPPINGS,
                    args -> mappingsBean.flatMappings(args.query(), null, args.limit())));
        }

        this.tools = List.copyOf(registry);
    }

    ReactiveBootUiMcpTools(List<McpTool> tools) {
        this.tools = List.copyOf(tools);
    }

    public List<McpTool> tools() {
        return tools;
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
