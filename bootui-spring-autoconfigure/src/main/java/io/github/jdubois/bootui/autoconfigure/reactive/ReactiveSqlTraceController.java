package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceControllerSupport;
import io.github.jdubois.bootui.core.dto.SqlTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Reactive (WebFlux) sibling of {@code SqlTraceController}: identical read/clear/recording
 * semantics over the same framework-neutral {@link SqlTraceRecorder}, with the {@code /stream}
 * endpoint rebuilt on {@link ReactiveBootUiChangeStream} instead of a servlet {@code SseEmitter}.
 */
@RestController
@RequestMapping("/bootui/api/sql-trace")
public class ReactiveSqlTraceController {

    private final ObjectProvider<SqlTraceRecorder> recorderProvider;
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final BootUiExposure exposure;
    private final ReactiveBootUiChangeStream changeStream;
    private Runnable recorderUnsubscribe;

    public ReactiveSqlTraceController(
            ObjectProvider<SqlTraceRecorder> recorderProvider,
            ObjectProvider<DataSource> dataSourceProvider,
            BootUiExposure exposure) {
        this.recorderProvider = recorderProvider;
        this.dataSourceProvider = dataSourceProvider;
        this.exposure = exposure;
        this.changeStream = new ReactiveBootUiChangeStream("sql-trace");
        SqlTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder != null) {
            this.recorderUnsubscribe = recorder.subscribe(changeStream::signal);
        }
    }

    /**
     * Completes any open SSE streams and detaches the recorder listener when the context starts
     * closing. See {@code SqlTraceController#shutdown} for why this runs on
     * {@link ContextClosedEvent} rather than a destroy callback.
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
    public SqlTraceReport trace() {
        return SqlTraceControllerSupport.trace(recorderProvider, exposure, dataSourceProvider);
    }

    @PostMapping("/clear")
    public SqlTraceReport clear() {
        return SqlTraceControllerSupport.clear(recorderProvider, exposure, dataSourceProvider);
    }

    @PostMapping("/recording")
    public SqlTraceReport recording(@RequestBody(required = false) SqlTraceRecordingRequest request) {
        return SqlTraceControllerSupport.recording(recorderProvider, exposure, dataSourceProvider, request);
    }

    /**
     * Streams a coalesced {@code update} notification whenever a statement is captured, the buffer
     * is cleared, or recording is paused/resumed, so the browser can refresh live without polling.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> stream() {
        return changeStream.open();
    }
}
