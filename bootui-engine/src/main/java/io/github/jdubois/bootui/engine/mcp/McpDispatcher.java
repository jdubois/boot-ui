package io.github.jdubois.bootui.engine.mcp;

import io.github.jdubois.bootui.engine.action.ActionBusyException;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationException;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.InitializeResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.NoResponse;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.PingResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.PromptGetResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.PromptsListResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ProtocolError;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallError;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolsListResult;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Framework- and JSON-free core of the BootUI MCP server: it routes an already-parsed
 * {@link McpRequest} to a typed {@link McpDispatchOutcome}, applying the same method routing,
 * notification handling, per-panel gating, tool lookup and {@code max-results} capping the browser UI
 * obeys.
 *
 * <p>Each adapter keeps a thin envelope codec that parses a request node into an {@link McpRequest},
 * calls {@link #dispatch(McpRequest)}, and renders the outcome back to JSON with its own
 * {@code ObjectMapper} (Jackson 3 on Spring Boot, Jackson 2 on Quarkus). The control flow here is a
 * one-to-one translation of the original Spring {@code BootUiMcpService} so both adapters answer
 * byte-identically: a refused panel gate is an in-band {@link ToolCallError} ({@code isError:true}), as
 * is a client error a tool raises about the request itself (an unknown resource id, a conflicting
 * state) once the adapter has translated its framework exception into an {@link McpToolClientException};
 * malformed tool calls are JSON-RPC {@link ProtocolError}s; an unexpected failure becomes the standard,
 * detail-free JSON-RPC internal error ({@code -32603}) and is sent to the server-side diagnostic
 * reporter. Serialization of a successful payload (the only remaining Jackson step) is performed and
 * error-handled by the adapter codec.
 */
public final class McpDispatcher {

    private static final Logger log = LoggerFactory.getLogger(McpDispatcher.class);
    private static final ExecutorService TOOL_EXECUTOR = Executors.newCachedThreadPool(new McpToolThreadFactory());

    private final Supplier<List<McpTool>> toolSupplier;
    private final List<McpPrompt> prompts;
    private final McpPanelPolicy policy;
    private final String serverVersion;
    private final String instructions;
    private final int maxResults;
    private final Semaphore toolCallSemaphore;
    private final McpFailureReporter failureReporter;
    private final long executionTimeoutMillis;
    private final McpRuntimeStats runtimeStats;

    /**
     * @param tools the advertised tool catalog, in order (each adapter wires its own controllers /
     *     resources)
     * @param prompts the advertised reusable prompt catalog
     * @param policy the per-panel enable / read-only gate behind {@code tools/call}
     * @param serverVersion the server version advertised in {@code initialize} ({@code null} → {@code "dev"})
     * @param instructions the framework-specific usage instructions advertised in {@code initialize}
     * @param maxResults the {@code bootui.mcp.max-results} cap applied to paged read tools (floored at 1)
     * @param maxConcurrentCalls the maximum concurrent {@code tools/call} invocations (floored at 1)
     */
    public McpDispatcher(
            List<McpTool> tools,
            List<McpPrompt> prompts,
            McpPanelPolicy policy,
            String serverVersion,
            String instructions,
            int maxResults,
            int maxConcurrentCalls) {
        this(
                tools,
                prompts,
                policy,
                serverVersion,
                instructions,
                maxResults,
                maxConcurrentCalls,
                McpProtocol.DEFAULT_EXECUTION_TIMEOUT_MILLIS,
                (operation, failure) -> log.error("BootUI MCP failure while {}", operation, failure));
    }

    /**
     * Creates a dispatcher with an adapter-owned diagnostic reporter.
     *
     * @param failureReporter receives each unexpected failure exactly once with its original stack trace
     */
    public McpDispatcher(
            List<McpTool> tools,
            List<McpPrompt> prompts,
            McpPanelPolicy policy,
            String serverVersion,
            String instructions,
            int maxResults,
            int maxConcurrentCalls,
            McpFailureReporter failureReporter) {
        this(
                tools,
                prompts,
                policy,
                serverVersion,
                instructions,
                maxResults,
                maxConcurrentCalls,
                McpProtocol.DEFAULT_EXECUTION_TIMEOUT_MILLIS,
                failureReporter);
    }

    public McpDispatcher(
            List<McpTool> tools,
            List<McpPrompt> prompts,
            McpPanelPolicy policy,
            String serverVersion,
            String instructions,
            int maxResults,
            int maxConcurrentCalls,
            long executionTimeoutMillis,
            McpFailureReporter failureReporter) {
        this(
                () -> tools,
                prompts,
                policy,
                serverVersion,
                instructions,
                maxResults,
                maxConcurrentCalls,
                executionTimeoutMillis,
                failureReporter);
    }

    public McpDispatcher(
            Supplier<List<McpTool>> toolSupplier,
            List<McpPrompt> prompts,
            McpPanelPolicy policy,
            String serverVersion,
            String instructions,
            int maxResults,
            int maxConcurrentCalls,
            long executionTimeoutMillis,
            McpFailureReporter failureReporter) {
        this.toolSupplier = Objects.requireNonNull(toolSupplier, "toolSupplier");
        this.prompts = List.copyOf(prompts);
        this.policy = Objects.requireNonNull(policy, "policy");
        this.serverVersion = serverVersion == null ? "dev" : serverVersion;
        this.instructions = instructions;
        this.maxResults = Math.max(1, maxResults);
        this.toolCallSemaphore = new Semaphore(Math.max(1, maxConcurrentCalls));
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
        this.executionTimeoutMillis = Math.max(1, executionTimeoutMillis);
        this.runtimeStats = new McpRuntimeStats();
    }

    /**
     * Backward-compatible constructor that uses the default concurrent call cap.
     */
    public McpDispatcher(
            List<McpTool> tools, McpPanelPolicy policy, String serverVersion, String instructions, int maxResults) {
        this(
                tools,
                List.of(),
                policy,
                serverVersion,
                instructions,
                maxResults,
                McpProtocol.DEFAULT_MAX_CONCURRENT_CALLS);
    }

    /** Backward-compatible constructor without prompt templates. */
    public McpDispatcher(
            List<McpTool> tools,
            McpPanelPolicy policy,
            String serverVersion,
            String instructions,
            int maxResults,
            int maxConcurrentCalls) {
        this(tools, List.of(), policy, serverVersion, instructions, maxResults, maxConcurrentCalls);
    }

    /** The advertised tool catalog, in order. */
    public List<McpTool> tools() {
        return List.copyOf(toolSupplier.get());
    }

    /** Operational counters exposed by the MCP Server panel. */
    public McpRuntimeStats runtimeStats() {
        return runtimeStats;
    }

    /**
     * Routes a single parsed JSON-RPC request to a typed outcome. Returns {@link NoResponse} for a
     * notification with no applicable response; the adapter then emits no body (HTTP 202).
     */
    public McpDispatchOutcome dispatch(McpRequest request) {
        try {
            return dispatchRequest(request);
        } catch (RuntimeException | Error failure) {
            failureReporter.report("dispatching a request", failure);
            return request != null && request.notification()
                    ? new NoResponse()
                    : new ProtocolError(McpProtocol.INTERNAL_ERROR, McpProtocol.INTERNAL_ERROR_MESSAGE);
        }
    }

    private McpDispatchOutcome dispatchRequest(McpRequest request) {
        String method = request.method();
        if (method == null || method.isEmpty()) {
            return request.notification()
                    ? new NoResponse()
                    : new ProtocolError(McpProtocol.INVALID_PARAMS, McpProtocol.MISSING_METHOD_MESSAGE);
        }
        McpDispatchOutcome outcome =
                switch (method) {
                    case "initialize" -> initialize(request);
                    case "ping" -> new PingResult();
                    case "tools/list" ->
                        new ToolsListResult(
                                tools().stream().map(McpTool::describe).toList());
                    case "tools/call" -> callTool(request);
                    case "prompts/list" -> new PromptsListResult(prompts);
                    case "prompts/get" -> getPrompt(request);
                    default -> new ProtocolError(McpProtocol.METHOD_NOT_FOUND, "Unknown method: " + method);
                };
        return request.notification() ? new NoResponse() : outcome;
    }

    private McpDispatchOutcome initialize(McpRequest request) {
        String requested = request.requestedProtocolVersion();
        String negotiated = (requested == null || requested.isEmpty())
                ? McpProtocol.DEFAULT_PROTOCOL_VERSION
                : McpProtocol.KNOWN_VERSIONS.contains(requested) ? requested : McpProtocol.DEFAULT_PROTOCOL_VERSION;
        return new InitializeResult(negotiated, McpProtocol.SERVER_NAME, serverVersion, instructions);
    }

    private McpDispatchOutcome callTool(McpRequest request) {
        String name = request.toolName();
        if (name == null || name.isEmpty()) {
            return new ProtocolError(McpProtocol.INVALID_PARAMS, McpProtocol.MISSING_TOOL_NAME_MESSAGE);
        }

        McpTool tool = findTool(name);
        if (tool == null) {
            return new ProtocolError(McpProtocol.INVALID_PARAMS, McpProtocol.unknownToolMessage(name));
        }
        if (request.argumentsError() != null) {
            return new ProtocolError(McpProtocol.INVALID_PARAMS, request.argumentsError());
        }
        TreeSet<String> unexpectedArguments = new TreeSet<>(request.argumentNames());
        unexpectedArguments.removeAll(tool.schema().argumentNames());
        if (!unexpectedArguments.isEmpty()) {
            return new ProtocolError(
                    McpProtocol.INVALID_PARAMS,
                    "Unexpected tool argument" + (unexpectedArguments.size() == 1 ? "" : "s") + ": "
                            + String.join(", ", unexpectedArguments));
        }
        if (!policy.isEnabled(tool.panelId())) {
            return new ToolCallError(
                    policy.disabledReason(tool.panelId()), McpDispatchOutcome.ToolErrorReason.PANEL_DISABLED);
        }
        if (tool.action() && policy.isReadOnly(tool.panelId())) {
            return new ToolCallError(
                    policy.readOnlyReason(tool.panelId()), McpDispatchOutcome.ToolErrorReason.PANEL_READ_ONLY);
        }
        boolean ruleViolations = tool.schema() == McpToolSchema.RULE_VIOLATIONS;
        if (ruleViolations && request.rawLimit() != null && request.rawLimit() < 1) {
            return new ProtocolError(McpProtocol.INVALID_PARAMS, McpProtocol.invalidArgumentMinimumMessage("limit", 1));
        }
        if (ruleViolations && request.rawOffset() != null && request.rawOffset() < 0) {
            return new ProtocolError(
                    McpProtocol.INVALID_PARAMS, McpProtocol.invalidArgumentMinimumMessage("offset", 0));
        }
        McpArguments arguments =
                McpArguments.normalize(request, tool.schema(), maxResults, McpToolCatalog.defaultLimit(tool.name()));
        if ((tool.schema() == McpToolSchema.ID || ruleViolations) && arguments.id() == null) {
            return new ProtocolError(McpProtocol.INVALID_PARAMS, McpProtocol.MISSING_ID_ARGUMENT_MESSAGE);
        }
        if (ruleViolations && arguments.scanId() == null) {
            return new ProtocolError(McpProtocol.INVALID_PARAMS, McpProtocol.MISSING_SCAN_ID_ARGUMENT_MESSAGE);
        }
        if (!toolCallSemaphore.tryAcquire()) {
            runtimeStats.recordCapacityRefusal();
            return new ProtocolError(McpProtocol.SERVER_AT_CAPACITY, McpProtocol.RATE_LIMITED_MESSAGE);
        }
        long startedAt = System.nanoTime();
        AtomicInteger invocationState = new AtomicInteger(0);
        Future<Object> invocation;
        try {
            invocation = TOOL_EXECUTOR.submit(() -> {
                if (!invocationState.compareAndSet(0, 1)) {
                    return null;
                }
                try {
                    return tool.invoke(arguments);
                } finally {
                    int previous = invocationState.getAndSet(3);
                    toolCallSemaphore.release();
                    if (previous == 1) {
                        runtimeStats.recordCall(System.nanoTime() - startedAt);
                    }
                }
            });
        } catch (RuntimeException | Error failure) {
            toolCallSemaphore.release();
            runtimeStats.recordCall(System.nanoTime() - startedAt);
            throw failure;
        }
        try {
            return new ToolCallResult(invocation.get(executionTimeoutMillis, TimeUnit.MILLISECONDS));
        } catch (TimeoutException ex) {
            runtimeStats.recordTimeout();
            int previous = invocationState.getAndUpdate(state -> state < 2 ? (state == 0 ? 3 : 2) : state);
            invocation.cancel(true);
            if (previous == 0) {
                toolCallSemaphore.release();
            }
            if (previous == 0 || previous == 1) {
                runtimeStats.recordCall(System.nanoTime() - startedAt);
            }
            return new ProtocolError(McpProtocol.TOOL_TIMEOUT, McpProtocol.TOOL_TIMEOUT_MESSAGE);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            int previous = invocationState.getAndUpdate(state -> state < 2 ? (state == 0 ? 3 : 2) : state);
            invocation.cancel(true);
            if (previous == 0) {
                toolCallSemaphore.release();
            }
            if (previous == 0 || previous == 1) {
                runtimeStats.recordCall(System.nanoTime() - startedAt);
            }
            throw new IllegalStateException("Interrupted while invoking MCP tool", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof ActionBusyException busy) {
                return new ToolCallError(busy.result().message(), McpDispatchOutcome.ToolErrorReason.ACTION_BUSY);
            }
            if (cause instanceof McpToolClientException clientError) {
                return new ToolCallError(clientError.getMessage(), clientError.status());
            }
            if (cause instanceof AdvisorViolationException clientError
                    && McpToolClientExceptions.isClientError(clientError.status())) {
                return new ToolCallError(clientError.getMessage(), clientError.status());
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("MCP tool invocation failed", cause);
        }
    }

    private McpDispatchOutcome getPrompt(McpRequest request) {
        String name = request.toolName();
        if (name == null || name.isEmpty()) {
            return new ProtocolError(McpProtocol.INVALID_PARAMS, McpProtocol.MISSING_PROMPT_NAME_MESSAGE);
        }
        return prompts.stream()
                .filter(prompt -> prompt.name().equals(name))
                .findFirst()
                .<McpDispatchOutcome>map(PromptGetResult::new)
                .orElseGet(() -> new ProtocolError(McpProtocol.INVALID_PARAMS, "Unknown prompt: " + name));
    }

    private McpTool findTool(String name) {
        return tools().stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private static final class McpToolThreadFactory implements ThreadFactory {

        private int sequence;

        @Override
        public synchronized Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "bootui-mcp-tool-" + ++sequence);
            thread.setDaemon(true);
            return thread;
        }
    }
}
