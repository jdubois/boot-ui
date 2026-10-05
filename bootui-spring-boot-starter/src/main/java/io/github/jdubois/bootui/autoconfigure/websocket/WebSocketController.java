package io.github.jdubois.bootui.autoconfigure.websocket;

import io.github.jdubois.bootui.autoconfigure.stream.BootUiChangeStream;
import io.github.jdubois.bootui.core.dto.WebSocketCaptureRequest;
import io.github.jdubois.bootui.core.dto.WebSocketReport;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder;
import io.github.jdubois.bootui.engine.websocket.WebSocketService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Read-mostly endpoint backing the WebSockets panel on the servlet stack.
 *
 * <p>Returns the declared WebSocket endpoints, the sessions and STOMP subscriptions BootUI has observed,
 * and a bounded buffer of frame <em>metadata</em>. Message payloads are never captured, so they can never
 * be served here. The state-changing {@code clear} and {@code capture} actions only affect BootUI's own
 * buffers and are gated by the panel access filter when the panel is read-only.</p>
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/websockets")
public class WebSocketController {

    private final ObjectProvider<WebSocketService> serviceProvider;
    private final ObjectProvider<WebSocketActivityRecorder> recorderProvider;
    private final BootUiChangeStream changeStream;
    private Runnable recorderUnsubscribe;

    public WebSocketController(
            ObjectProvider<WebSocketService> serviceProvider,
            ObjectProvider<WebSocketActivityRecorder> recorderProvider) {
        this.serviceProvider = serviceProvider;
        this.recorderProvider = recorderProvider;
        this.changeStream = new BootUiChangeStream("websockets");
        WebSocketActivityRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder != null) {
            this.recorderUnsubscribe = recorder.subscribe(changeStream::signal);
        }
    }

    /** Completes open SSE streams and detaches the recorder listener when the context starts closing. */
    @EventListener(ContextClosedEvent.class)
    void shutdown() {
        if (recorderUnsubscribe != null) {
            recorderUnsubscribe.run();
            recorderUnsubscribe = null;
        }
        changeStream.close();
    }

    @GetMapping
    public WebSocketReport report() {
        return WebSocketControllerSupport.report(serviceProvider);
    }

    @DeleteMapping
    public WebSocketReport clear() {
        return WebSocketControllerSupport.clear(serviceProvider);
    }

    @PostMapping("/capture")
    public WebSocketReport capture(@RequestBody(required = false) WebSocketCaptureRequest request) {
        return WebSocketControllerSupport.capture(serviceProvider, recorderProvider, request);
    }

    /** Streams a coalesced {@code update} notification whenever observed activity changes. */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return changeStream.open();
    }
}
