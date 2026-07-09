package io.github.jdubois.bootui.autoconfigure.sqltrace;

import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.stream.BootUiChangeStream;
import io.github.jdubois.bootui.core.dto.SqlTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import javax.sql.DataSource;
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
 * Read-mostly endpoint backing the SQL Trace panel.
 *
 * <p>Returns the SQL captured by BootUI's hand-written JDBC tracing proxy and
 * exposes state-changing {@code clear} and {@code recording} (pause/resume)
 * actions (gated by the panel access filter when the panel is read-only).
 * Parameter bindings are only surfaced when capture is enabled and value
 * exposure is not metadata-only.</p>
 *
 * <p>The trace/clear/recording business logic lives in {@link SqlTraceControllerSupport}, shared
 * with the WebFlux sibling {@code ReactiveSqlTraceController} since none of it touches a servlet or
 * reactive request/response type. This class keeps only the {@code @RestController} wiring, the SSE
 * {@code /stream} endpoint, and the recorder-listener lifecycle.</p>
 */
@RestController
@RequestMapping("/bootui/api/sql-trace")
public class SqlTraceController {

    private final ObjectProvider<SqlTraceRecorder> recorderProvider;
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final BootUiExposure exposure;
    private final BootUiChangeStream changeStream;
    private Runnable recorderUnsubscribe;

    public SqlTraceController(
            ObjectProvider<SqlTraceRecorder> recorderProvider,
            ObjectProvider<DataSource> dataSourceProvider,
            BootUiExposure exposure) {
        this.recorderProvider = recorderProvider;
        this.dataSourceProvider = dataSourceProvider;
        this.exposure = exposure;
        this.changeStream = new BootUiChangeStream("sql-trace");
        SqlTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder != null) {
            this.recorderUnsubscribe = recorder.subscribe(changeStream::signal);
        }
    }

    /**
     * Completes any open SSE streams and detaches the recorder listener when the context starts closing.
     *
     * <p>Runs on {@link ContextClosedEvent} rather than {@code @PreDestroy}: the event is published
     * before the web server's graceful-shutdown lifecycle waits for in-flight requests, whereas
     * {@code @PreDestroy} runs during later bean destruction. An {@code SseEmitter(0L)} never completes
     * on its own, so cleaning up at destroy time would let graceful shutdown block until its timeout on
     * every stop. Doing it here also keeps a Spring Boot DevTools restart from leaking the
     * {@code bootui-sql-trace-stream} daemon thread (and the discarded context behind it).
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
     * Streams a coalesced {@code update} notification whenever a statement is captured, the buffer is
     * cleared, or recording is paused/resumed, so the browser can refresh live without polling.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return changeStream.open();
    }
}
