package io.github.jdubois.bootui.autoconfigure.restclienttrace;

import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.stream.BootUiChangeStream;
import io.github.jdubois.bootui.core.dto.RestClientTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.RestClientTraceReport;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Read-mostly endpoint backing the REST Client panel.
 *
 * <p>Returns the outbound {@code RestClient}/{@code RestTemplate}/{@code WebClient} calls captured by
 * BootUI's instrumentation and exposes state-changing {@code clear} and {@code recording} (pause/resume)
 * actions (gated by the panel access filter when the panel is read-only). Query parameter and header
 * values are displayed per the live {@code bootui.mask-secrets}/{@code bootui.expose-values} policy (see
 * {@link RestClientTraceRecorder#report(boolean, io.github.jdubois.bootui.core.ValueExposure)}).</p>
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/rest-client-trace")
public class RestClientTraceController {

    private final ObjectProvider<RestClientTraceRecorder> recorderProvider;
    private final BootUiExposure exposure;
    private final BootUiChangeStream changeStream;
    private Runnable recorderUnsubscribe;

    public RestClientTraceController(
            ObjectProvider<RestClientTraceRecorder> recorderProvider, BootUiExposure exposure) {
        this.recorderProvider = recorderProvider;
        this.exposure = exposure;
        this.changeStream = new BootUiChangeStream("rest-client-trace");
        RestClientTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder != null) {
            this.recorderUnsubscribe = recorder.subscribe(changeStream::signal);
        }
    }

    /**
     * Completes any open SSE streams and detaches the recorder listener when the context starts closing.
     *
     * <p>Runs on {@link ContextClosedEvent} rather than {@code @PreDestroy}; see {@code SqlTraceController}
     * for the full rationale (graceful-shutdown ordering, DevTools restart leaks).</p>
     */
    @EventListener(ContextClosedEvent.class)
    void shutdown() {
        if (recorderUnsubscribe != null) {
            recorderUnsubscribe.run();
            recorderUnsubscribe = null;
        }
        changeStream.close();
    }

    @GetMapping
    public RestClientTraceReport trace() {
        return RestClientTraceControllerSupport.trace(recorderProvider, exposure);
    }

    @PostMapping("/clear")
    public RestClientTraceReport clear() {
        return RestClientTraceControllerSupport.clear(recorderProvider, exposure);
    }

    @PostMapping("/recording")
    public RestClientTraceReport recording(@RequestBody(required = false) RestClientTraceRecordingRequest request) {
        return RestClientTraceControllerSupport.recording(recorderProvider, exposure, request);
    }

    /**
     * Streams a coalesced {@code update} notification whenever a call is captured, the buffer is cleared,
     * or recording is paused/resumed, so the browser can refresh live without polling.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return changeStream.open();
    }
}
