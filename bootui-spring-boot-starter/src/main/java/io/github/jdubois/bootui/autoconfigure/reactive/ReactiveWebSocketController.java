package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.websocket.WebSocketControllerSupport;
import io.github.jdubois.bootui.core.dto.WebSocketCaptureRequest;
import io.github.jdubois.bootui.core.dto.WebSocketReport;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder;
import io.github.jdubois.bootui.engine.websocket.WebSocketService;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Reactive (WebFlux) sibling of {@code WebSocketController}: the same JSON contract over the same
 * framework-neutral {@link WebSocketService}, with {@code /stream} rebuilt on
 * {@link ReactiveBootUiChangeStream} instead of the servlet-only {@code SseEmitter}.
 *
 * <p>On WebFlux the report lists endpoints only and reports {@code frameCaptureSupported=false} with a
 * reason; see {@link ReactiveWebSocketMetadataProvider}. The actions remain available so the contract
 * stays uniform across stacks, and they still clear BootUI's own (empty) buffers rather than failing.</p>
 */
@RestController
@RequestMapping("${bootui.api-path:/bootui/api}/websockets")
public class ReactiveWebSocketController {

    private final ObjectProvider<WebSocketService> serviceProvider;
    private final ObjectProvider<WebSocketActivityRecorder> recorderProvider;
    private final ReactiveBootUiChangeStream changeStream;
    private Runnable recorderUnsubscribe;

    public ReactiveWebSocketController(
            ObjectProvider<WebSocketService> serviceProvider,
            ObjectProvider<WebSocketActivityRecorder> recorderProvider) {
        this.serviceProvider = serviceProvider;
        this.recorderProvider = recorderProvider;
        this.changeStream = new ReactiveBootUiChangeStream("websockets");
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
    public Flux<ServerSentEvent<Map<String, Object>>> stream() {
        return changeStream.open();
    }
}
