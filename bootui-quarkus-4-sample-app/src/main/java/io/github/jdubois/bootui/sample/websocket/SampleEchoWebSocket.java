package io.github.jdubois.bootui.sample.websocket;

import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;

/**
 * A minimal WebSockets Next echo endpoint, so the BootUI WebSockets panel has real build-time topology and real
 * live connections to report on Quarkus.
 *
 * <p>WebSockets Next exposes no message-interception SPI, so BootUI reports this endpoint and its open
 * connections but states that frame capture is unsupported rather than inventing an empty frame log. Nothing
 * BootUI does reads the messages this endpoint exchanges.</p>
 */
@WebSocket(path = "/ws/echo")
public class SampleEchoWebSocket {

    @OnOpen
    public String onOpen() {
        return "connected";
    }

    @OnTextMessage
    public String onMessage(String message) {
        return "echo: " + message;
    }

    @OnClose
    public void onClose() {
        // Nothing to release; the sample endpoint is stateless.
    }
}
