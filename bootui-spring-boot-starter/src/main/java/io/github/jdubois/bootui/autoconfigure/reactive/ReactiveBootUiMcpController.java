package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.mcp.BootUiMcpService;
import io.github.jdubois.bootui.autoconfigure.mcp.McpServerState;
import io.github.jdubois.bootui.engine.mcp.McpDispatchOutcome;
import io.github.jdubois.bootui.engine.mcp.McpProgressToken;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpRequestHeaders;
import io.github.jdubois.bootui.engine.mcp.McpStreamSink;
import io.github.jdubois.bootui.engine.mcp.McpStreamingCall;
import io.github.jdubois.bootui.engine.progress.ProgressEvent;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reactive WebFlux transport for the BootUI MCP server: the same dual-era {@link BootUiMcpService#exchange} as the
 * servlet {@code BootUiMcpController}, over a bounded {@code DataBuffer} body.
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/mcp")
public class ReactiveBootUiMcpController {

    private static final String PAYLOAD_LIMIT_MESSAGE = "Request payload exceeds limit";
    /** Added to the execution timeout before a writer waiting for demand gives up, as on the blocking stacks. */
    private static final long BACKSTOP_GRACE_MILLIS = 10_000;
    /** How often a writer waiting for demand rechecks whether the client went away. */
    private static final long DEMAND_POLL_MILLIS = 100;

    private final BootUiMcpService service;
    private final McpServerState state;
    private final int maxPayloadBytes;
    private final long backstopMillis;

    public ReactiveBootUiMcpController(BootUiMcpService service, McpServerState state, BootUiProperties properties) {
        this.service = service;
        this.state = state;
        this.maxPayloadBytes = Math.max(1, properties.getMcp().getMaxPayloadBytes());
        this.backstopMillis =
                Math.max(1, properties.getMcp().getExecutionTimeout().toMillis()) + BACKSTOP_GRACE_MILLIS;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<?>> rpc(@RequestBody Flux<DataBuffer> requestBody, @RequestHeader HttpHeaders headers) {
        McpRequestHeaders mcpHeaders = BootUiMcpService.headers(headers);
        boolean acceptsEventStream = McpProtocol.acceptsEventStream(headers.get(HttpHeaders.ACCEPT));
        // A stream whose response is dropped before it is written (the client went away first) is cancelled here,
        // rather than holding its concurrency permit until the execution timeout.
        AtomicReference<McpStreamingCall> unstarted = new AtomicReference<>();
        return DataBufferUtils.join(requestBody, maxPayloadBytes)
                .publishOn(Schedulers.boundedElastic())
                .<ResponseEntity<?>>map(
                        buffer -> handle(readAndRelease(buffer), mcpHeaders, acceptsEventStream, unstarted))
                .switchIfEmpty(Mono.fromSupplier(() -> handle(new byte[0], mcpHeaders, acceptsEventStream, unstarted)))
                .onErrorResume(
                        DataBufferLimitException.class,
                        ex -> Mono.just(json(413, error(null, McpProtocol.PARSE_ERROR, PAYLOAD_LIMIT_MESSAGE))))
                .doOnCancel(() -> cancelUnstarted(unstarted))
                .doOnDiscard(ResponseEntity.class, dropped -> cancelUnstarted(unstarted));
    }

    private static void cancelUnstarted(AtomicReference<McpStreamingCall> unstarted) {
        McpStreamingCall call = unstarted.getAndSet(null);
        if (call != null) {
            call.cancel();
        }
    }

    @GetMapping
    public Mono<ResponseEntity<Void>> getStream() {
        return Mono.just(ResponseEntity.status(405).build());
    }

    private ResponseEntity<?> handle(
            byte[] requestBody,
            McpRequestHeaders headers,
            boolean acceptsEventStream,
            AtomicReference<McpStreamingCall> unstarted) {
        if (requestBody != null && requestBody.length > maxPayloadBytes) {
            return json(413, error(null, McpProtocol.PARSE_ERROR, PAYLOAD_LIMIT_MESSAGE));
        }
        JsonNode request;
        try {
            request = service.readTree(requestBody == null ? new byte[0] : requestBody);
        } catch (IllegalArgumentException ex) {
            return json(400, error(null, McpProtocol.PARSE_ERROR, ex.getMessage()));
        }
        BootUiMcpService.Reply reply = service.exchange(request, headers, state.isEnabled(), acceptsEventStream);
        if (reply.stream() != null) {
            unstarted.set(reply.stream().call());
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .header(McpProtocol.ACCEL_BUFFERING_HEADER, "no")
                    .body(events(reply.stream(), unstarted));
        }
        if (reply.body() == null) {
            return ResponseEntity.accepted().build();
        }
        return json(reply.status(), reply.body());
    }

    /**
     * The request-scoped event stream. The call starts when the response subscribes, so headers are committed first;
     * a cancelled subscription (the client went away) is reported to {@link McpStreamingCall#clientClosed()}, which cancels
     * a modern call and only stops writing a legacy one. Each JSON-RPC
     * message is an SSE {@code data:} line and a keep-alive is an empty comment, the same bytes the servlet and Quarkus
     * transports write.
     *
     * <p>The writer emits only against downstream demand, so a client that stops reading blocks the writer, which keeps
     * the call's concurrency permit as on the blocking stacks instead of buffering without bound. That wait has the
     * same backstop as theirs: past the execution timeout plus a grace period, the writer gives up as if the write had
     * failed.
     */
    private Flux<ServerSentEvent<String>> events(
            BootUiMcpService.Stream stream, AtomicReference<McpStreamingCall> unstarted) {
        McpStreamingCall call = stream.call();
        // A stream that is never subscribed is still released by the call's own execution timeout.
        return Flux.create(sink -> {
            // Subscribed: from now on the stream's own cancellation handles a client that goes away.
            unstarted.compareAndSet(call, null);
            Object demand = new Object();
            long giveUpAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(backstopMillis);
            sink.onRequest(requested -> {
                synchronized (demand) {
                    demand.notifyAll();
                }
            });
            sink.onCancel(call::clientClosed);
            sink.onDispose(call::clientClosed);
            call.start(new McpStreamSink() {
                @Override
                public void progress(McpProgressToken token, ProgressEvent event) throws IOException {
                    emit(ServerSentEvent.builder(McpProtocol.sseData(service.renderProgress(token, event)))
                            .build());
                }

                @Override
                public void heartbeat() throws IOException {
                    emit(ServerSentEvent.<String>builder().comment("").build());
                }

                @Override
                public void complete(McpDispatchOutcome outcome) throws IOException {
                    emit(ServerSentEvent.builder(
                                    McpProtocol.sseData(service.renderFinal(stream.id(), call.era(), outcome)))
                            .build());
                }

                private void emit(ServerSentEvent<String> event) throws IOException {
                    synchronized (demand) {
                        while (sink.requestedFromDownstream() <= 0) {
                            if (sink.isCancelled()) {
                                throw new IOException("The client closed the MCP event stream");
                            }
                            long remaining = giveUpAt - System.nanoTime();
                            if (remaining <= 0) {
                                throw new IOException("The client stopped reading the MCP event stream");
                            }
                            try {
                                demand.wait(Math.min(DEMAND_POLL_MILLIS, Math.max(1, remaining / 1_000_000)));
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new InterruptedIOException("Interrupted while waiting for demand");
                            }
                        }
                    }
                    sink.next(event);
                }

                @Override
                public void close() {
                    sink.complete();
                }
            });
        });
    }

    private static byte[] readAndRelease(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = JsonNodeFactory.instance.objectNode();
        response.put("jsonrpc", McpProtocol.JSONRPC_VERSION);
        response.set("id", id == null ? JsonNodeFactory.instance.nullNode() : id);
        ObjectNode error = JsonNodeFactory.instance.objectNode();
        error.put("code", code);
        error.put("message", message == null ? "Error" : message);
        response.set("error", error);
        return response;
    }

    private static ResponseEntity<String> json(int status, JsonNode body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString());
    }
}
