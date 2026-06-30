package io.github.jdubois.bootui.quarkus.mcp;

import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpTool;
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
import io.github.jdubois.bootui.quarkus.web.LogTailResource;
import io.github.jdubois.bootui.quarkus.web.MappingsResource;
import io.github.jdubois.bootui.quarkus.web.MemoryResource;
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
 * Spring-specific concerns with no meaningful Quarkus equivalent). The {@code get_overview} tool is
 * also absent: the Overview <em>dashboard panel</em> is not yet ported to Quarkus (only the
 * shell-chrome {@code /bootui/api/overview} endpoint is served), so its panel reports unavailable and
 * the availability gate drops the tool.
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
            SecurityLogsResource securityLogs,
            SqlTraceResource sqlTrace,
            TracesResource traces,
            LogTailResource logTail,
            HttpExchangesResource httpExchanges,
            HealthResource health,
            ConfigResource config,
            BeansResource beans,
            MappingsResource mappings) {
        List<McpTool> registry = new ArrayList<>();

        // --- Advisor tools (panel actions; behind the LocalhostGuard write floor) ---
        addIfAvailable(
                registry,
                availability,
                action(
                        "architecture_scan",
                        "Run the Architecture advisor and return layering/dependency findings to fix.",
                        BootUiPanels.ARCHITECTURE,
                        args -> architecture.scan()));
        addIfAvailable(
                registry,
                availability,
                action(
                        "spring_scan",
                        "Run the Quarkus advisor and return Quarkus configuration/idiom findings to fix.",
                        BootUiPanels.SPRING,
                        args -> spring.scan()));
        addIfAvailable(
                registry,
                availability,
                action(
                        "hibernate_scan",
                        "Run the Hibernate advisor and return JPA/Hibernate mapping and query findings.",
                        BootUiPanels.HIBERNATE,
                        args -> hibernate.scan()));
        addIfAvailable(
                registry,
                availability,
                action(
                        "memory_scan",
                        "Run the Memory advisor (triggers a class histogram) and return memory findings.",
                        BootUiPanels.MEMORY,
                        args -> memory.scan()));
        addIfAvailable(
                registry,
                availability,
                action(
                        "security_scan",
                        "Run the Security advisor and return application security findings to fix.",
                        BootUiPanels.SECURITY,
                        args -> security.scan()));
        addIfAvailable(
                registry,
                availability,
                action(
                        "pentest_scan",
                        "Run the Pentesting advisor and return probing-based security findings.",
                        BootUiPanels.PENTESTING,
                        args -> pentesting.scan()));
        addIfAvailable(
                registry,
                availability,
                action(
                        "rest_api_scan",
                        "Run the REST API advisor and return REST resource/design findings to fix.",
                        BootUiPanels.REST_API,
                        args -> restApi.scan()));

        // --- Diagnostics / runtime read tools ---
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_exceptions",
                        "List recent unhandled exceptions captured at runtime (most recent first).",
                        BootUiPanels.EXCEPTIONS,
                        args -> exceptions.list()));
        addIfAvailable(
                registry,
                availability,
                limitRead(
                        "get_security_logs",
                        "List recent security audit events (authentication, authorization, etc.).",
                        BootUiPanels.SECURITY_LOGS,
                        args -> securityLogs.logs(null, null, null, null, args.limit())));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_sql_traces",
                        "Return recently recorded SQL statements and timings from the SQL Trace recorder.",
                        BootUiPanels.SQL_TRACE,
                        args -> sqlTrace.trace()));
        addIfAvailable(
                registry,
                availability,
                limitRead(
                        "get_traces",
                        "Return recent distributed/local traces captured by BootUI.",
                        BootUiPanels.TRACES,
                        args -> traces.list(args.limit())));
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_log_tail",
                        "Return the most recent buffered application log lines.",
                        BootUiPanels.LOG_TAIL,
                        args -> logTail.recent()));
        addIfAvailable(
                registry,
                availability,
                limitRead(
                        "get_http_exchanges",
                        "List recent HTTP request/response exchanges handled by the application.",
                        BootUiPanels.HTTP_EXCHANGES,
                        args -> httpExchanges.exchanges(null, null, null, null, args.limit())));

        // --- Core context read tools ---
        addIfAvailable(
                registry,
                availability,
                read(
                        "get_health",
                        "Return the aggregated application health tree (SmallRye Health).",
                        BootUiPanels.HEALTH,
                        args -> health.health()));
        addIfAvailable(
                registry,
                availability,
                searchRead(
                        "get_config",
                        "Return effective configuration properties (secret values masked). Optional 'query' "
                                + "filters by property name/value.",
                        BootUiPanels.CONFIG,
                        args -> config.list(args.query(), null, false, null, args.limit())));
        addIfAvailable(
                registry,
                availability,
                searchRead(
                        "get_beans",
                        "List CDI beans. Optional 'query' filters by bean name or type.",
                        BootUiPanels.BEANS,
                        args -> beans.beans(args.query(), null, null, args.limit())));
        addIfAvailable(
                registry,
                availability,
                searchRead(
                        "get_mappings",
                        "List request mappings (URL patterns to handlers). Optional 'query' filters them.",
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
}
