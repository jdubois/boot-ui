package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.mcp.McpRuntimeStats;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolSchema;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.progress.OperationProgress;
import io.github.jdubois.bootui.engine.progress.ProgressPhase;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The cross-stack contract that closing a request-scoped MCP stream cancels the call (MCP 2026-07-28).
 *
 * <p>Each adapter test replaces its MCP tools with {@link #tool()}, a deterministic {@code architecture_scan} that
 * reports one phase and then runs until it is cancelled, and calls {@link #closeAfterFirstEventCancels}. That opens
 * the stream over a raw socket, reads through the first event, and closes the connection. The tool must then stop, and
 * the call must be counted once as cancelled, never as a timeout, with its concurrency permit released.
 */
public final class McpStreamDisconnectContract {

    private static final ProgressPhase WAITING = ProgressPhase.of("Waiting for cancellation");

    private final CountDownLatch running = new CountDownLatch(1);
    private final CountDownLatch stopped = new CountDownLatch(1);

    /** The cancellable scan, under the catalog name of the one tool that streams progress. */
    public McpTool tool() {
        return new McpTool(
                "architecture_scan",
                "Runs until cancelled.",
                McpToolSchema.NONE,
                BootUiPanels.ARCHITECTURE,
                true,
                arguments -> runUntilCancelled());
    }

    private Object runUntilCancelled() {
        OperationProgress progress = OperationProgress.current();
        try {
            progress.report(WAITING, 1, 0);
            running.countDown();
            while (true) {
                progress.checkCancelled();
                Thread.sleep(20);
            }
        } catch (InterruptedException interrupted) {
            return "interrupted";
        } finally {
            stopped.countDown();
        }
    }

    /**
     * Opens the stream at {@code path} on {@code localhost:port}, closes it after the first event, and checks that the
     * call was cancelled.
     */
    public void closeAfterFirstEventCancels(int port, String path, Supplier<McpRuntimeStats.Snapshot> stats)
            throws Exception {
        String body =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"architecture_scan\","
                        + "\"arguments\":{},\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
                        + "\"io.modelcontextprotocol/clientCapabilities\":{},\"progressToken\":\"p\"}}}";
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String head;
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(15_000);
            OutputStream output = socket.getOutputStream();
            output.write(("POST " + path + " HTTP/1.1\r\n"
                            + "Host: localhost:" + port + "\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Accept: application/json, text/event-stream\r\n"
                            + "MCP-Protocol-Version: 2026-07-28\r\n"
                            + "Mcp-Method: tools/call\r\n"
                            + "Mcp-Name: architecture_scan\r\n"
                            + "Content-Length: " + payload.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.write(payload);
            output.flush();
            head = readThroughFirstEvent(socket.getInputStream());
        }

        assertThat(head).startsWith("HTTP/1.1 200");
        assertThat(head.toLowerCase(Locale.ROOT)).contains("content-type: text/event-stream", "x-accel-buffering: no");
        assertThat(head).contains("\"progressToken\":\"p\"", "\"message\":\"Waiting for cancellation\"");
        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(stopped.await(20, TimeUnit.SECONDS))
                .as("closing the stream stops the tool, within two keep-alives on blocking stacks")
                .isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while ((stats.get().callCount() == 0 || stats.get().cancellations() == 0) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        McpRuntimeStats.Snapshot snapshot = stats.get();
        assertThat(snapshot.cancellations()).isEqualTo(1);
        assertThat(snapshot.timeouts()).isZero();
        assertThat(snapshot.callCount())
                .as("the concurrency permit is released once")
                .isEqualTo(1);
    }

    private static String readThroughFirstEvent(InputStream input) throws Exception {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        byte[] buffer = new byte[512];
        while (true) {
            int read = input.read(buffer);
            if (read < 0) {
                break;
            }
            received.write(buffer, 0, read);
            String text = received.toString(StandardCharsets.UTF_8);
            int event = text.indexOf("data:");
            if (event >= 0 && text.indexOf("\n\n", event) > 0) {
                return text;
            }
        }
        return received.toString(StandardCharsets.UTF_8);
    }
}
