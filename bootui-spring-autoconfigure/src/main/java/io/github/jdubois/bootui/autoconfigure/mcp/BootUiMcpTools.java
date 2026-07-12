package io.github.jdubois.bootui.autoconfigure.mcp;

import io.github.jdubois.bootui.autoconfigure.activity.LiveActivityController;
import io.github.jdubois.bootui.autoconfigure.architecture.ArchitectureController;
import io.github.jdubois.bootui.autoconfigure.crac.CracController;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionsController;
import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmController;
import io.github.jdubois.bootui.autoconfigure.hibernate.HibernateController;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryController;
import io.github.jdubois.bootui.autoconfigure.pentesting.PentestingController;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiController;
import io.github.jdubois.bootui.autoconfigure.security.SecurityController;
import io.github.jdubois.bootui.autoconfigure.spring.SpringController;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceController;
import io.github.jdubois.bootui.autoconfigure.web.BeansController;
import io.github.jdubois.bootui.autoconfigure.web.ConfigController;
import io.github.jdubois.bootui.autoconfigure.web.HealthController;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.LogTailController;
import io.github.jdubois.bootui.autoconfigure.web.MappingsController;
import io.github.jdubois.bootui.autoconfigure.web.OverviewController;
import io.github.jdubois.bootui.autoconfigure.web.SecurityLogsController;
import io.github.jdubois.bootui.autoconfigure.web.TracesController;
import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolDescriptions;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Builds the catalog of MCP tools exposed by the BootUI MCP server.
 *
 * <p>Tools are thin adapters over the existing BootUI controllers; they reuse the same services,
 * immutable {@code record} DTOs, {@code SecretMasker}/{@code expose-values} handling, and self-data
 * filtering, so the agent sees exactly the sanitized shape the browser UI sees. Each tool is bound
 * to a {@link BootUiPanels} id so the engine {@code McpDispatcher} can enforce per-panel
 * enable/read-only toggles. Argument normalization (the optional {@code query} filter and the
 * {@code bootui.mcp.max-results} cap on {@code limit}) is applied once by the engine, so each handler
 * simply reads {@link McpArguments#query()} / {@link McpArguments#limit()}.
 */
public class BootUiMcpTools {

    private final List<McpTool> tools;

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
            ObjectProvider<CracController> crac) {
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

        List<McpTool> registry = new ArrayList<>();

        // --- Advisor tools (panel actions; refused when the backing panel is read-only) ---
        if (architectureBean != null) {
            registry.add(action(
                    "architecture_scan",
                    McpToolDescriptions.spring("architecture_scan"),
                    BootUiPanels.ARCHITECTURE,
                    args -> architectureBean.scan()));
        }
        if (springBean != null) {
            registry.add(action(
                    "spring_scan",
                    McpToolDescriptions.spring("spring_scan"),
                    BootUiPanels.SPRING,
                    args -> springBean.scan()));
        }
        if (hibernateBean != null) {
            registry.add(action(
                    "hibernate_scan",
                    McpToolDescriptions.spring("hibernate_scan"),
                    BootUiPanels.HIBERNATE,
                    args -> hibernateBean.scan()));
        }
        if (memoryBean != null) {
            registry.add(action(
                    "memory_scan",
                    McpToolDescriptions.spring("memory_scan"),
                    BootUiPanels.MEMORY,
                    args -> memoryBean.scan()));
        }
        if (securityBean != null) {
            registry.add(action(
                    "security_scan",
                    McpToolDescriptions.spring("security_scan"),
                    BootUiPanels.SECURITY,
                    args -> securityBean.scan()));
        }
        if (pentestingBean != null) {
            registry.add(action(
                    "pentest_scan",
                    McpToolDescriptions.spring("pentest_scan"),
                    BootUiPanels.PENTESTING,
                    args -> pentestingBean.scan()));
        }
        if (restApiBean != null) {
            registry.add(action(
                    "rest_api_scan",
                    McpToolDescriptions.spring("rest_api_scan"),
                    BootUiPanels.REST_API,
                    args -> restApiBean.scan()));
        }
        if (graalvmBean != null) {
            registry.add(action(
                    "graalvm_scan",
                    McpToolDescriptions.spring("graalvm_scan"),
                    BootUiPanels.GRAALVM,
                    args -> graalvmBean.scan(false)));
        }
        if (cracBean != null) {
            registry.add(action(
                    "crac_scan", McpToolDescriptions.spring("crac_scan"), BootUiPanels.CRAC, args -> cracBean.scan()));
        }

        // --- Diagnostics / runtime read tools (panel reads; allowed when the panel is enabled) ---
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
        }
        if (tracesBean != null) {
            registry.add(limitRead(
                    "get_traces",
                    McpToolDescriptions.spring("get_traces"),
                    BootUiPanels.TRACES,
                    args -> tracesBean.list(args.limit())));
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

        // --- Core context read tools ---
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

        this.tools = List.copyOf(registry);
    }

    /** Test/extensibility hook that builds the registry from an explicit tool list. */
    BootUiMcpTools(List<McpTool> tools) {
        this.tools = List.copyOf(tools);
    }

    /** All tools in advertised order. */
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
