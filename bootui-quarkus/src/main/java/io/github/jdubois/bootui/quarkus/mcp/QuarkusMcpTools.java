package io.github.jdubois.bootui.quarkus.mcp;

import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolDescriptions;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.quarkus.QuarkusPanelAvailability;
import io.github.jdubois.bootui.quarkus.web.ArchitectureResource;
import io.github.jdubois.bootui.quarkus.web.BeansResource;
import io.github.jdubois.bootui.quarkus.web.ConfigResource;
import io.github.jdubois.bootui.quarkus.web.ExceptionsResource;
import io.github.jdubois.bootui.quarkus.web.HealthResource;
import io.github.jdubois.bootui.quarkus.web.HibernateResource;
import io.github.jdubois.bootui.quarkus.web.HttpExchangesResource;
import io.github.jdubois.bootui.quarkus.web.LiveActivityResource;
import io.github.jdubois.bootui.quarkus.web.LogTailResource;
import io.github.jdubois.bootui.quarkus.web.MappingsResource;
import io.github.jdubois.bootui.quarkus.web.MemoryResource;
import io.github.jdubois.bootui.quarkus.web.OverviewResource;
import io.github.jdubois.bootui.quarkus.web.PentestingResource;
import io.github.jdubois.bootui.quarkus.web.RestApiResource;
import io.github.jdubois.bootui.quarkus.web.SecurityLogsResource;
import io.github.jdubois.bootui.quarkus.web.SecurityResource;
import io.github.jdubois.bootui.quarkus.web.SpringResource;
import io.github.jdubois.bootui.quarkus.web.SqlTraceResource;
import io.github.jdubois.bootui.quarkus.web.TracesResource;
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
 * <p>Two Spring advisor tools have no Quarkus counterpart and are deliberately absent:
 * {@code graalvm_scan} and {@code crac_scan} (GraalVM native-image readiness and CRaC are
 * Spring-specific concerns with no meaningful Quarkus equivalent). The {@code get_overview} tool
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
            OverviewResource overview) {
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
                action(
                        "spring_scan",
                        McpToolDescriptions.quarkus("spring_scan"),
                        BootUiPanels.SPRING,
                        args -> spring.scan()));
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
                action(
                        "memory_scan",
                        McpToolDescriptions.quarkus("memory_scan"),
                        BootUiPanels.MEMORY,
                        args -> memory.scan()));
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
                action(
                        "pentest_scan",
                        McpToolDescriptions.quarkus("pentest_scan"),
                        BootUiPanels.PENTESTING,
                        args -> pentesting.scan()));
        addIfAvailable(
                registry,
                availability,
                action(
                        "rest_api_scan",
                        McpToolDescriptions.quarkus("rest_api_scan"),
                        BootUiPanels.REST_API,
                        args -> restApi.scan()));

        // --- Diagnostics / runtime read tools ---
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
                limitRead(
                        "get_traces",
                        McpToolDescriptions.quarkus("get_traces"),
                        BootUiPanels.TRACES,
                        args -> traces.list(args.limit())));
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
