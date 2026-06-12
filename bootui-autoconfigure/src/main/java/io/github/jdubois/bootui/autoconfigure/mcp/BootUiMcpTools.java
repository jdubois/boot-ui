package io.github.jdubois.bootui.autoconfigure.mcp;

import io.github.jdubois.bootui.autoconfigure.architecture.ArchitectureController;
import io.github.jdubois.bootui.autoconfigure.crac.CracController;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionsController;
import io.github.jdubois.bootui.autoconfigure.graalvm.GraalVmController;
import io.github.jdubois.bootui.autoconfigure.hibernate.HibernateController;
import io.github.jdubois.bootui.autoconfigure.memory.MemoryController;
import io.github.jdubois.bootui.autoconfigure.panel.BootUiPanels;
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the catalog of MCP tools exposed by the BootUI MCP server.
 *
 * <p>Tools are thin adapters over the existing BootUI controllers; they reuse the same services,
 * immutable {@code record} DTOs, {@code SecretMasker}/{@code expose-values} handling, and self-data
 * filtering, so the agent sees exactly the sanitized shape the browser UI sees. Each tool is bound
 * to a {@link BootUiPanels} id so {@link BootUiMcpService} can enforce per-panel enable/read-only
 * toggles.
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
            ObjectProvider<CracController> crac,
            int maxResults) {
        // Resolve each (lazy) controller bean; conditionally-registered controllers (e.g. Hibernate,
        // Spring Security) may be absent depending on the host app's classpath, so the matching tool is
        // simply not advertised rather than failing the whole server.
        OverviewController overviewBean = overview.getIfAvailable();
        HealthController healthBean = health.getIfAvailable();
        ConfigController configBean = config.getIfAvailable();
        BeansController beansBean = beans.getIfAvailable();
        MappingsController mappingsBean = mappings.getIfAvailable();
        ExceptionsController exceptionsBean = exceptions.getIfAvailable();
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
        if (securityBean != null) {
            registry.add(action(
                    "security_scan",
                    "Run the Security advisor and return application security findings to fix.",
                    BootUiPanels.SECURITY,
                    args -> securityBean.scan()));
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

        // --- Diagnostics / runtime read tools (panel reads; allowed when the panel is enabled) ---
        if (exceptionsBean != null) {
            registry.add(read(
                    "get_exceptions",
                    "List recent unhandled exceptions captured at runtime (most recent first).",
                    BootUiPanels.EXCEPTIONS,
                    args -> exceptionsBean.list()));
        }
        if (securityLogsBean != null) {
            registry.add(limitRead(
                    "get_security_logs",
                    "List recent security audit events (authentication, authorization, etc.).",
                    BootUiPanels.SECURITY_LOGS,
                    args -> securityLogsBean.logs(null, null, null, null, limit(args, maxResults))));
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
                    args -> tracesBean.list(limit(args, maxResults))));
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
                    args -> httpExchangesBean.exchanges(null, null, null, null, limit(args, maxResults))));
        }

        // --- Core context read tools ---
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
                    args -> configBean.list(query(args), null, false, null, limit(args, maxResults))));
        }
        if (beansBean != null) {
            registry.add(searchRead(
                    "get_beans",
                    "List Spring beans. Optional 'query' filters by bean name or type.",
                    BootUiPanels.BEANS,
                    args -> beansBean.beans(query(args), null, null, limit(args, maxResults))));
        }
        if (mappingsBean != null) {
            registry.add(searchRead(
                    "get_mappings",
                    "List request mappings (URL patterns to handlers). Optional 'query' filters them.",
                    BootUiPanels.MAPPINGS,
                    args -> mappingsBean.flatMappings(query(args), null, limit(args, maxResults))));
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

    private static McpTool action(String name, String description, String panelId, Function<JsonNode, Object> handler) {
        return new McpTool(name, description, emptyObjectSchema(), panelId, true, handler);
    }

    private static McpTool read(String name, String description, String panelId, Function<JsonNode, Object> handler) {
        return new McpTool(name, description, emptyObjectSchema(), panelId, false, handler);
    }

    private static McpTool limitRead(
            String name, String description, String panelId, Function<JsonNode, Object> handler) {
        return new McpTool(name, description, limitSchema(), panelId, false, handler);
    }

    private static McpTool searchRead(
            String name, String description, String panelId, Function<JsonNode, Object> handler) {
        return new McpTool(name, description, querySchema(), panelId, false, handler);
    }

    private static ObjectNode emptyObjectSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.set("properties", JsonNodeFactory.instance.objectNode());
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode limitSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = JsonNodeFactory.instance.objectNode();
        properties.set("limit", limitProperty());
        schema.set("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode querySchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ObjectNode properties = JsonNodeFactory.instance.objectNode();
        ObjectNode query = JsonNodeFactory.instance.objectNode();
        query.put("type", "string");
        query.put("description", "Optional case-insensitive filter applied to the results.");
        properties.set("query", query);
        properties.set("limit", limitProperty());
        schema.set("properties", properties);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static ObjectNode limitProperty() {
        ObjectNode limit = JsonNodeFactory.instance.objectNode();
        limit.put("type", "integer");
        limit.put("minimum", 1);
        limit.put(
                "description",
                "Optional maximum number of items to return. Capped by the bootui.mcp.max-results server limit.");
        return limit;
    }

    private static String query(JsonNode args) {
        if (args == null || !args.has("query") || args.get("query").isNull()) {
            return null;
        }
        String value = args.get("query").asString().trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * Resolve the effective page size for a read tool: honor an optional client-supplied positive
     * {@code limit} argument, but never exceed the configured {@code bootui.mcp.max-results} cap.
     */
    private static Integer limit(JsonNode args, int maxResults) {
        if (args != null && args.has("limit") && args.get("limit").isIntegralNumber()) {
            int requested = args.get("limit").asInt();
            if (requested >= 1) {
                return Math.min(requested, maxResults);
            }
        }
        return maxResults;
    }
}
