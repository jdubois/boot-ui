package io.github.jdubois.bootui.engine.mcp;

import io.github.jdubois.bootui.engine.action.ActionBusyException;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolationException;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.DiscoverResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.InitializeResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.NoResponse;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.PingResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.PromptGetResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.PromptsListResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ProtocolError;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallError;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolCallResult;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome.ToolsListResult;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.progress.OperationCancelledException;
import io.github.jdubois.bootui.engine.progress.OperationProgress;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
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
    private final int maxConcurrentCalls;
    private final McpFailureReporter failureReporter;
    private final long executionTimeoutMillis;
    private final McpRuntimeStats runtimeStats;
    private final McpInFlightCalls inFlight = new McpInFlightCalls();
    private final Function<String, String> panelUnavailableReason;

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
        this(
                toolSupplier,
                prompts,
                policy,
                serverVersion,
                instructions,
                maxResults,
                maxConcurrentCalls,
                executionTimeoutMillis,
                failureReporter,
                panelId -> null);
    }

    /**
     * Creates a dispatcher that can say why a catalog tool is not advertised.
     *
     * @param panelUnavailableReason the reason a panel is unavailable in this application, or {@code null} when it
     *     is available or the adapter cannot tell; a call to a catalog tool this server does not advertise reports it
     */
    public McpDispatcher(
            Supplier<List<McpTool>> toolSupplier,
            List<McpPrompt> prompts,
            McpPanelPolicy policy,
            String serverVersion,
            String instructions,
            int maxResults,
            int maxConcurrentCalls,
            long executionTimeoutMillis,
            McpFailureReporter failureReporter,
            Function<String, String> panelUnavailableReason) {
        this.panelUnavailableReason = Objects.requireNonNull(panelUnavailableReason, "panelUnavailableReason");
        this.toolSupplier = Objects.requireNonNull(toolSupplier, "toolSupplier");
        this.prompts = List.copyOf(prompts);
        this.policy = Objects.requireNonNull(policy, "policy");
        this.serverVersion = serverVersion == null ? "dev" : serverVersion;
        this.instructions = instructions;
        this.maxResults = Math.max(1, maxResults);
        this.maxConcurrentCalls = Math.max(1, maxConcurrentCalls);
        this.toolCallSemaphore = new Semaphore(this.maxConcurrentCalls);
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

    /** The server version advertised in {@code initialize}, {@code server/discover}, and modern result metadata. */
    public String serverVersion() {
        return serverVersion;
    }

    /**
     * Concurrency permits not held by a running or pending tool call; {@link #maxConcurrentCalls()} when the server is
     * idle. Exposed for tests that prove a permit is released exactly once.
     */
    public int availableCallPermits() {
        return toolCallSemaphore.availablePermits();
    }

    /** The {@code bootui.mcp.max-concurrent-calls} bound, floored at 1. */
    public int maxConcurrentCalls() {
        return maxConcurrentCalls;
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
        return dispatch(request, new McpCancellation());
    }

    /**
     * Like {@link #dispatch(McpRequest)}, with a handle through which another thread can cancel a {@code tools/call}
     * while it runs; the call then answers {@link McpDispatchOutcome.Cancelled}.
     */
    public McpDispatchOutcome dispatch(McpRequest request, McpCancellation cancellation) {
        Objects.requireNonNull(cancellation, "cancellation");
        try {
            return dispatchRequest(request, cancellation);
        } catch (RuntimeException | Error failure) {
            failureReporter.report("dispatching a request", failure);
            return request != null && request.notification()
                    ? new NoResponse()
                    : new ProtocolError(McpProtocol.INTERNAL_ERROR, McpProtocol.INTERNAL_ERROR_MESSAGE);
        }
    }

    private McpDispatchOutcome dispatchRequest(McpRequest request, McpCancellation cancellation) {
        String method = request.method();
        if (method == null || method.isEmpty()) {
            return request.notification()
                    ? new NoResponse()
                    : new ProtocolError(McpProtocol.INVALID_PARAMS, McpProtocol.MISSING_METHOD_MESSAGE);
        }
        McpDispatchOutcome outcome = request.era() == McpEra.MODERN
                ? dispatchModern(request, method, cancellation)
                : dispatchLegacy(request, method, cancellation);
        return request.notification() ? new NoResponse() : outcome;
    }

    /** MCP 2025-06-18: the {@code initialize} handshake and {@code ping}, as in BootUI 1.x. */
    private McpDispatchOutcome dispatchLegacy(McpRequest request, String method, McpCancellation cancellation) {
        return switch (method) {
            case "initialize" -> initialize(request);
            case "ping" -> new PingResult();
            case "notifications/cancelled" -> cancelInFlight(request);
            default -> dispatchShared(request, method, cancellation);
        };
    }

    /** MCP 2026-07-28: no handshake and no {@code ping}; {@code server/discover} advertises the server instead. */
    private McpDispatchOutcome dispatchModern(McpRequest request, String method, McpCancellation cancellation) {
        if ("server/discover".equals(method)) {
            return new DiscoverResult(
                    McpProtocol.SUPPORTED_VERSIONS, McpProtocol.SERVER_NAME, serverVersion, instructions);
        }
        return dispatchShared(request, method, cancellation);
    }

    private McpDispatchOutcome dispatchShared(McpRequest request, String method, McpCancellation cancellation) {
        return switch (method) {
            case "tools/list" ->
                new ToolsListResult(
                        tools().stream().map(tool -> tool.describe(maxResults)).toList());
            case "tools/call" -> callTool(request, cancellation);
            case "prompts/list" -> new PromptsListResult(prompts);
            case "prompts/get" -> getPrompt(request);
            default -> new ProtocolError(McpProtocol.METHOD_NOT_FOUND, "Unknown method: " + method);
        };
    }

    private McpDispatchOutcome initialize(McpRequest request) {
        String requested = request.requestedProtocolVersion();
        String negotiated = (requested == null || requested.isEmpty())
                ? McpProtocol.DEFAULT_PROTOCOL_VERSION
                : McpProtocol.KNOWN_VERSIONS.contains(requested) ? requested : McpProtocol.DEFAULT_PROTOCOL_VERSION;
        return new InitializeResult(negotiated, McpProtocol.SERVER_NAME, serverVersion, instructions);
    }

    /**
     * Starts a {@code tools/call} that may answer on a request-scoped event stream. Only a call, in either era, with a progress
     * token, to a tool that {@linkplain McpTool#reportsProgress() reports progress}, from a client that accepts
     * {@code text/event-stream}, and that passes every validation and policy gate and gets a concurrency permit, is
     * {@link McpCallStart.Stream streamed}. Everything else, including every refusal, is the same {@link
     * McpCallStart.Immediate immediate} outcome {@link #dispatch(McpRequest)} returns.
     *
     * <p>A returned stream holds a permit and has its absolute execution timeout already scheduled: the adapter must
     * {@link McpStreamingCall#start start} it or {@link McpStreamingCall#cancel cancel} it, and the timeout still
     * releases the permit if it does neither.
     */
    public McpCallStart start(McpRequest request, boolean acceptsEventStream) {
        return start(request, acceptsEventStream, new McpCancellation());
    }

    /**
     * Like {@link #start(McpRequest, boolean)}, with a handle through which another thread can cancel an immediate
     * {@code tools/call} while it runs (a stream is cancelled through {@link McpStreamingCall#cancel()}).
     */
    public McpCallStart start(McpRequest request, boolean acceptsEventStream, McpCancellation cancellation) {
        if (request == null
                || request.progressToken() == null
                || request.notification()
                || !acceptsEventStream
                || !"tools/call".equals(request.method())) {
            return new McpCallStart.Immediate(dispatch(request, cancellation));
        }
        try {
            McpTool tool = request.toolName() == null ? null : findTool(request.toolName());
            if (tool == null || !tool.reportsProgress()) {
                return new McpCallStart.Immediate(dispatch(request, cancellation));
            }
            Object prepared = prepareCall(request);
            if (prepared instanceof McpDispatchOutcome refusal) {
                return new McpCallStart.Immediate(refusal);
            }
            PreparedCall call = (PreparedCall) prepared;
            if (!toolCallSemaphore.tryAcquire()) {
                runtimeStats.recordCapacityRefusal();
                return new McpCallStart.Immediate(
                        new ProtocolError(McpProtocol.SERVER_AT_CAPACITY, McpProtocol.RATE_LIMITED_MESSAGE));
            }
            AtomicReference<McpInFlightCalls.Registration> registration = new AtomicReference<>();
            Runnable unregister = () -> {
                McpInFlightCalls.Registration registered = registration.getAndSet(null);
                if (registered != null) {
                    registered.close();
                }
            };
            McpStreamingCall streaming = new McpStreamingCall(
                    call.tool(),
                    call.arguments(),
                    request.progressToken(),
                    request.era(),
                    unregister,
                    executionTimeoutMillis,
                    toolCallSemaphore,
                    runtimeStats,
                    failureReporter,
                    TOOL_EXECUTOR);
            if (tracked(request)) {
                registration.set(inFlight.register(request.requestKey(), streaming::cancel));
                if (streaming.finished()) {
                    unregister.run();
                }
            }
            return new McpCallStart.Stream(streaming);
        } catch (RuntimeException | Error failure) {
            failureReporter.report("dispatching a request", failure);
            return new McpCallStart.Immediate(
                    new ProtocolError(McpProtocol.INTERNAL_ERROR, McpProtocol.INTERNAL_ERROR_MESSAGE));
        }
    }

    /** A validated tool call that may run. */
    private record PreparedCall(McpTool tool, McpArguments arguments) {}

    private McpDispatchOutcome callTool(McpRequest request, McpCancellation cancellation) {
        Object prepared = prepareCall(request);
        if (prepared instanceof McpDispatchOutcome refusal) {
            return refusal;
        }
        if (!toolCallSemaphore.tryAcquire()) {
            runtimeStats.recordCapacityRefusal();
            return new ProtocolError(McpProtocol.SERVER_AT_CAPACITY, McpProtocol.RATE_LIMITED_MESSAGE);
        }
        McpTool tool = ((PreparedCall) prepared).tool();
        McpArguments arguments = ((PreparedCall) prepared).arguments();
        if (tracked(request)) {
            try (McpInFlightCalls.Registration registered =
                    inFlight.register(request.requestKey(), cancellation::cancel)) {
                return invokeBlocking(tool, arguments, cancellation);
            }
        }
        return invokeBlocking(tool, arguments, cancellation);
    }

    /**
     * {@code true} for a legacy {@code tools/call} with an id: MCP 2025-06-18 cancels one with {@code
     * notifications/cancelled}, so it is registered by id while it holds a permit.
     */
    private static boolean tracked(McpRequest request) {
        return request.era() == McpEra.LEGACY && request.requestKey() != null && "tools/call".equals(request.method());
    }

    /**
     * A legacy {@code notifications/cancelled}: cancels the one in-flight {@code tools/call} with that id. MCP 2025-06-18
     * lets a receiver ignore an unknown or finished id, and so does an id two callers share. BootUI has no sessions,
     * so any local caller that passes the endpoint's checks can cancel a call by its id. Sent with an id, it is not a
     * notification and is answered as an unknown method.
     */
    private McpDispatchOutcome cancelInFlight(McpRequest request) {
        if (!request.notification()) {
            return new ProtocolError(McpProtocol.METHOD_NOT_FOUND, "Unknown method: " + request.method());
        }
        inFlight.cancel(request.cancelledRequestKey());
        return new NoResponse();
    }

    /** The legacy calls registered for cancellation right now. */
    int inFlightCalls() {
        return inFlight.size();
    }

    /**
     * Validates a {@code tools/call} without running it: a {@link PreparedCall}, or the refusal {@link
     * McpDispatchOutcome} (unknown tool, malformed arguments, panel policy).
     */
    private Object prepareCall(McpRequest request) {
        String name = request.toolName();
        if (name == null || name.isEmpty()) {
            return new ProtocolError(McpProtocol.INVALID_PARAMS, McpProtocol.MISSING_TOOL_NAME_MESSAGE);
        }

        McpTool tool = findTool(name);
        if (tool == null) {
            return McpToolCatalog.byName(name)
                    .map(this::unavailableTool)
                    .orElseGet(
                            () -> new ProtocolError(McpProtocol.INVALID_PARAMS, McpProtocol.unknownToolMessage(name)));
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
            return new ProtocolError(
                    McpProtocol.INVALID_PARAMS,
                    McpProtocol.missingArgumentMessage(McpProtocol.MISSING_ID_ARGUMENT_MESSAGE, tool.name()));
        }
        if (ruleViolations && arguments.scanId() == null) {
            return new ProtocolError(
                    McpProtocol.INVALID_PARAMS,
                    McpProtocol.missingArgumentMessage(McpProtocol.MISSING_SCAN_ID_ARGUMENT_MESSAGE, tool.name()));
        }
        return new PreparedCall(tool, arguments);
    }

    /**
     * Runs one tool call on the tool executor and waits for it, at most the execution timeout. Every call gets its own
     * {@link OperationProgress} bound to the tool thread, without a listener, so a timeout or a {@link McpCancellation}
     * stops a tool that checks for cancellation as well as interrupting it.
     *
     * <p>{@code invocationState} counts the call and releases its permit exactly once: {@code 0} queued, {@code 1}
     * running, {@code 2} abandoned while running (the tool thread releases when it returns), {@code 3} done or abandoned
     * before it started (released by whoever moved it there).
     */
    private McpDispatchOutcome invokeBlocking(McpTool tool, McpArguments arguments, McpCancellation cancellation) {
        long startedAt = System.nanoTime();
        AtomicInteger invocationState = new AtomicInteger(0);
        OperationProgress progress = new OperationProgress(null);
        Future<Object> invocation;
        try {
            invocation = TOOL_EXECUTOR.submit(() -> {
                if (!invocationState.compareAndSet(0, 1)) {
                    return null;
                }
                try {
                    return OperationProgress.runWith(progress, () -> tool.invoke(arguments));
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
        Runnable stop = () -> {
            progress.cancel();
            invocation.cancel(true);
        };
        if (!cancellation.attach(stop)) {
            stop.run();
        }
        try {
            return new ToolCallResult(invocation.get(executionTimeoutMillis, TimeUnit.MILLISECONDS));
        } catch (TimeoutException ex) {
            runtimeStats.recordTimeout();
            abandon(invocationState, invocation, progress, startedAt);
            return new ProtocolError(McpProtocol.TOOL_TIMEOUT, McpProtocol.TOOL_TIMEOUT_MESSAGE);
        } catch (CancellationException ex) {
            runtimeStats.recordCancellation();
            abandon(invocationState, invocation, progress, startedAt);
            return new McpDispatchOutcome.Cancelled();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            abandon(invocationState, invocation, progress, startedAt);
            throw new IllegalStateException("Interrupted while invoking MCP tool", ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            McpDispatchOutcome expected = expectedToolFailure(cause);
            if (expected != null) {
                if (expected instanceof McpDispatchOutcome.Cancelled) {
                    runtimeStats.recordCancellation();
                }
                return expected;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("MCP tool invocation failed", cause);
        } finally {
            cancellation.detach();
        }
    }

    /** Gives up waiting for a call: stops the tool and settles its permit and call count (see {@link #invokeBlocking}). */
    private void abandon(
            AtomicInteger invocationState, Future<Object> invocation, OperationProgress progress, long startedAt) {
        progress.cancel();
        int previous = invocationState.getAndUpdate(state -> state < 2 ? (state == 0 ? 3 : 2) : state);
        invocation.cancel(true);
        if (previous == 0) {
            toolCallSemaphore.release();
        }
        if (previous == 0 || previous == 1) {
            runtimeStats.recordCall(System.nanoTime() - startedAt);
        }
    }

    /**
     * The outcome of a tool failure that is the request's or the caller's doing rather than a server fault, or {@code
     * null} for a server fault the caller must report: a busy single-flight action, a client error the tool raised,
     * and a tool that stopped at a cancellation checkpoint.
     */
    static McpDispatchOutcome expectedToolFailure(Throwable cause) {
        if (cause instanceof OperationCancelledException) {
            // The tool stopped at a cancellation checkpoint: an expected outcome, never reported as a server fault.
            return new McpDispatchOutcome.Cancelled();
        }
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
        return null;
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

    /**
     * A catalog tool this server does not advertise: still not callable, but the caller learns why, from the panel
     * that backs it, rather than a bare "unknown tool" that reads like a typo.
     */
    private McpDispatchOutcome unavailableTool(McpToolCatalog.Entry entry) {
        // Some adapters register a tool only while its panel is enabled, so a disabled panel's tool can be missing
        // here: it gets the same refusal as an advertised one, not an availability reason that would be wrong.
        if (!policy.isEnabled(entry.panelId())) {
            return new ToolCallError(
                    policy.disabledReason(entry.panelId()), McpDispatchOutcome.ToolErrorReason.PANEL_DISABLED);
        }
        String reason = null;
        try {
            reason = panelUnavailableReason.apply(entry.panelId());
        } catch (RuntimeException failure) {
            failureReporter.report("reading why a panel is unavailable", failure);
        }
        String panelTitle = BootUiPanels.byId(entry.panelId())
                .map(BootUiPanels.Panel::title)
                .orElse(entry.panelId());
        Map<String, String> data = new LinkedHashMap<>();
        data.put("tool", entry.name());
        data.put("panel", entry.panelId());
        if (reason != null && !reason.isBlank()) {
            data.put("reason", reason.trim());
        }
        return new ProtocolError(
                McpProtocol.INVALID_PARAMS,
                McpProtocol.unavailableToolMessage(entry.name(), panelTitle, reason, entry.stacks()),
                data);
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
