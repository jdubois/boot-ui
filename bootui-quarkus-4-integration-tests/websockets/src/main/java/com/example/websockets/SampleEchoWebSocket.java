package com.example.websockets;

import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;

/**
 * A WebSockets Next endpoint exercised by the WebSockets integration test.
 *
 * <p>It lives under {@code com.example.websockets} (a realistic host-application package), deliberately
 * <em>not</em> under {@code io.github.jdubois.bootui.*}, so the engine self-filter — which hides BootUI's own
 * endpoints from the panel — does not drop it.</p>
 *
 * <p>The callbacks echo the received text back to the caller; what matters for the panel is that the
 * deployment processor's build-time Jandex scan discovers the {@code @WebSocket} path and the callback
 * annotations and records that metadata, so {@code GET /bootui/api/websockets} can report them. BootUI never
 * reads or stores the message contents these callbacks handle.</p>
 */
@WebSocket(path = "/it/echo")
public class SampleEchoWebSocket {

    @OnOpen
    public String opened() {
        return "ready";
    }

    @OnTextMessage
    public String echo(String message) {
        return message;
    }

    @OnClose
    public void closed() {
        // no-op: only the callback metadata matters to the panel
    }
}
