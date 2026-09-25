package io.github.jdubois.bootui.engine.cli;

import io.github.jdubois.bootui.core.dto.CliServerStatus;
import io.github.jdubois.bootui.core.dto.CliToolInfo;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome;
import io.github.jdubois.bootui.engine.mcp.McpDispatcher;
import io.github.jdubois.bootui.engine.mcp.McpFailureReporter;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpRequest;
import io.github.jdubois.bootui.engine.mcp.McpRuntimeStats;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The whole of the {@code /bootui/api/cli} facade, so the Spring MVC, WebFlux, and Quarkus resources are
 * only routing.
 *
 * <p>Tool invocation goes through an {@link McpDispatcher} built over the <em>same</em> tool registry the MCP
 * server uses, which is what makes the CLI a projection rather than a second implementation: unknown tool,
 * unexpected argument, disabled panel, action on a read-only panel, missing {@code id}, result capping,
 * concurrency, and execution timeout are all the existing code path, so none of them can drift.
 *
 * <p>The dispatcher is deliberately a <em>separate instance</em> from the MCP server's. Sharing one would mix
 * command-line traffic into the MCP Server panel's call counters and concurrency budget, making that panel
 * report something other than what agents did.
 */
public final class CliService {

    private final boolean enabled;
    private final Supplier<List<McpTool>> tools;
    private final McpPanelPolicy policy;
    private final McpDispatcher dispatcher;
    private final String serverVersion;
    private final String endpoint;
    private final int maxResults;

    /**
     * @param enabled the {@code bootui.cli.enabled} setting
     * @param tools the same tool supplier the MCP server is built from
     * @param policy the per-panel enable/read-only policy
     * @param serverVersion the BootUI version to report
     * @param endpoint the absolute request path this facade is mounted at, for self-description
     * @param maxResults the {@code bootui.cli.max-results} cap
     * @param maxConcurrentCalls the {@code bootui.cli.max-concurrent-calls} cap
     * @param executionTimeoutMillis the {@code bootui.cli.execution-timeout} budget
     * @param failureReporter the adapter's diagnostics sink for unexpected tool failures
     */
    public CliService(
            boolean enabled,
            Supplier<List<McpTool>> tools,
            McpPanelPolicy policy,
            String serverVersion,
            String endpoint,
            int maxResults,
            int maxConcurrentCalls,
            long executionTimeoutMillis,
            McpFailureReporter failureReporter) {
        this.enabled = enabled;
        this.tools = Objects.requireNonNull(tools, "tools");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.serverVersion = serverVersion == null ? "dev" : serverVersion;
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.maxResults = Math.max(1, maxResults);
        this.dispatcher = new McpDispatcher(
                tools,
                List.of(),
                policy,
                this.serverVersion,
                "",
                this.maxResults,
                maxConcurrentCalls,
                executionTimeoutMillis,
                failureReporter);
    }

    /** Whether tool invocation is currently accepted. */
    public boolean enabled() {
        return enabled;
    }

    /** Call counters for this facade, kept separate from the MCP server's. */
    public McpRuntimeStats runtimeStats() {
        return dispatcher.runtimeStats();
    }

    /**
     * Describes what this instance exposes: the advertised tools, the command each is reached by, their
     * argument shapes, how their panels are currently gated, and this facade's own call counters. Answered
     * even while disabled, so the CLI can explain itself rather than fail opaquely; the tool list is then
     * empty because nothing is invocable.
     */
    public CliServerStatus status() {
        List<CliToolInfo> infos = enabled
                ? tools.get().stream()
                        .map(tool -> new CliToolInfo(
                                tool.name(),
                                CliCommandPaths.commandFor(tool.name()),
                                tool.description(),
                                tool.panelId(),
                                tool.action(),
                                tool.schema().name(),
                                List.copyOf(tool.schema().argumentNames()),
                                policy.isEnabled(tool.panelId()),
                                policy.isReadOnly(tool.panelId())))
                        .toList()
                : List.of();
        McpRuntimeStats.Snapshot stats = runtimeStats().snapshot();
        return new CliServerStatus(
                enabled,
                McpProtocol.SERVER_NAME,
                serverVersion,
                endpoint,
                maxResults,
                stats.callCount(),
                stats.totalLatencyMillis(),
                stats.capacityRefusals(),
                stats.timeouts(),
                infos.size(),
                infos);
    }

    /**
     * Invokes one tool by name with the request body's arguments.
     *
     * <p>The adapter passes the decoded body <em>verbatim</em>, every property included, rather than a fixed
     * {@code (query, limit, id)} triple. That is what lets the dispatcher's unexpected-argument check apply to
     * the CLI too: a caller who misspells {@code query} as {@code q} gets the same refusal MCP gives, instead
     * of a {@code 200} carrying an unfiltered report that silently ignored the filter they asked for.
     *
     * @param name the tool name
     * @param arguments the decoded request body, or {@code null}/empty when none was sent
     */
    public CliToolResponse invoke(String name, Map<String, Object> arguments) {
        if (!enabled) {
            return CliOutcomes.disabled();
        }
        Map<String, Object> body = arguments == null ? Map.of() : arguments;
        ParsedArguments parsed = parseArguments(body);
        McpRequest request = new McpRequest(
                McpProtocol.JSONRPC_VERSION,
                "tools/call",
                false,
                null,
                name,
                parsed.query,
                parsed.limit,
                parsed.id,
                body.keySet(),
                parsed.error,
                parsed.scanId,
                parsed.offset);
        McpDispatchOutcome outcome = dispatcher.dispatch(request);
        return CliOutcomes.toResponse(outcome);
    }

    /**
     * Invokes one tool with already-typed arguments, for callers that are not decoding a request body.
     *
     * @param name the tool name
     * @param query the optional {@code query} filter
     * @param limit the optional {@code limit}, capped at {@code maxResults}
     * @param id the optional {@code id} for tools that require one
     */
    public CliToolResponse invoke(String name, String query, Integer limit, String id) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (query != null) {
            arguments.put("query", query);
        }
        if (limit != null) {
            arguments.put("limit", limit);
        }
        if (id != null) {
            arguments.put("id", id);
        }
        return invoke(name, arguments);
    }

    /**
     * Applies the same type checks the MCP envelope codecs apply to {@code params.arguments}, so a malformed
     * value is refused with the identical canonical message on both transports. An explicitly null value is a
     * type error rather than an absent argument, matching how a JSON null is treated there.
     */
    private static ParsedArguments parseArguments(Map<String, Object> arguments) {
        String query = null;
        Integer limit = null;
        String id = null;
        String scanId = null;
        Integer offset = null;
        if (arguments.containsKey("query")) {
            Object value = arguments.get("query");
            if (!(value instanceof String)) {
                return ParsedArguments.error(McpProtocol.invalidArgumentTypeMessage("query", "a string"));
            }
            query = (String) value;
        }
        if (arguments.containsKey("id")) {
            Object value = arguments.get("id");
            if (!(value instanceof String)) {
                return ParsedArguments.error(McpProtocol.invalidArgumentTypeMessage("id", "a string"));
            }
            id = (String) value;
        }
        if (arguments.containsKey("limit")) {
            Object value = arguments.get("limit");
            Long integral = asIntegral(value);
            if (integral == null || integral < Integer.MIN_VALUE || integral > Integer.MAX_VALUE) {
                return ParsedArguments.error(McpProtocol.invalidArgumentTypeMessage("limit", "an integer"));
            }
            if (integral < 1) {
                return ParsedArguments.error(McpProtocol.invalidArgumentMinimumMessage("limit", 1));
            }
            limit = integral.intValue();
        }
        if (arguments.containsKey("scanId")) {
            Object value = arguments.get("scanId");
            if (!(value instanceof String)) {
                return ParsedArguments.error(McpProtocol.invalidArgumentTypeMessage("scanId", "a string"));
            }
            scanId = (String) value;
        }
        if (arguments.containsKey("offset")) {
            Long integral = asIntegral(arguments.get("offset"));
            if (integral == null || integral < Integer.MIN_VALUE || integral > Integer.MAX_VALUE) {
                return ParsedArguments.error(McpProtocol.invalidArgumentTypeMessage("offset", "an integer"));
            }
            if (integral < 0) {
                return ParsedArguments.error(McpProtocol.invalidArgumentMinimumMessage("offset", 0));
            }
            offset = integral.intValue();
        }
        return new ParsedArguments(query, limit, id, null, scanId, offset);
    }

    /** The value as a whole number, or {@code null} when it is not an integral JSON number. */
    private static Long asIntegral(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger) {
            BigInteger number = (BigInteger) value;
            return number.bitLength() < 64 ? number.longValue() : null;
        }
        return null;
    }

    private static final class ParsedArguments {

        private final String query;
        private final Integer limit;
        private final String id;
        private final String error;
        private final String scanId;
        private final Integer offset;

        private ParsedArguments(String query, Integer limit, String id, String error, String scanId, Integer offset) {
            this.query = query;
            this.limit = limit;
            this.id = id;
            this.error = error;
            this.scanId = scanId;
            this.offset = offset;
        }

        private static ParsedArguments error(String error) {
            return new ParsedArguments(null, null, null, error, null, null);
        }
    }
}
