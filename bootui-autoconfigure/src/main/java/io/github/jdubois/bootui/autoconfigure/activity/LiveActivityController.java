package io.github.jdubois.bootui.autoconfigure.activity;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionStore;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionsController;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceController;
import io.github.jdubois.bootui.autoconfigure.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.autoconfigure.stream.BootUiChangeStream;
import io.github.jdubois.bootui.autoconfigure.web.HealthController;
import io.github.jdubois.bootui.autoconfigure.web.HttpExchangesController;
import io.github.jdubois.bootui.autoconfigure.web.SecurityLogsController;
import io.github.jdubois.bootui.autoconfigure.web.TracesController;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.audit.listener.AuditApplicationEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.ServletRequestHandledEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Read-only Live Activity endpoints: a merged, reverse-chronological stream of recent application
 * activity plus a Symfony-style per-request profile. Both reuse BootUI's existing signal controllers
 * so masking, self-filtering and buffer bounds are inherited; this controller adds no instrumentation.
 *
 * <p>Because the merged feed is genuinely event-driven, the panel refreshes over Server-Sent Events
 * instead of fixed-interval polling: {@link #stream()} pushes a tiny coalesced {@code update} tick
 * whenever any underlying source changes, and the browser re-fetches {@link #activity} so all
 * masking, filtering and bounds still apply. The four sources are wired in as signals — SQL trace and
 * exceptions through their in-process subscribe hooks, security and HTTP requests through Spring
 * application events — and a single {@link BootUiChangeStream} coalesces a burst into one push.
 */
@RestController
@RequestMapping("/bootui/api/activity")
public class LiveActivityController {

    private final LiveActivityService service;
    private final LiveActivityCorrelator correlator;
    private final BootUiChangeStream changeStream;
    private final String selfPath;

    public LiveActivityController(
            ObjectProvider<HttpExchangesController> httpExchanges,
            ObjectProvider<SqlTraceController> sqlTrace,
            ObjectProvider<ExceptionsController> exceptions,
            ObjectProvider<SecurityLogsController> securityLogs,
            ObjectProvider<TracesController> traces,
            ObjectProvider<HealthController> health,
            ObjectProvider<SqlTraceRecorder> sqlTraceRecorder,
            ObjectProvider<ExceptionStore> exceptionStore,
            BootUiProperties properties) {
        this.service = new LiveActivityService(httpExchanges, sqlTrace, exceptions, securityLogs, health, properties);
        this.correlator = new LiveActivityCorrelator(httpExchanges, sqlTrace, exceptions, traces, properties);
        this.changeStream = new BootUiChangeStream("activity");
        this.selfPath = properties.getPath();
        SqlTraceRecorder recorder = sqlTraceRecorder.getIfAvailable();
        if (recorder != null) {
            recorder.subscribe(changeStream::signal);
        }
        ExceptionStore store = exceptionStore.getIfAvailable();
        if (store != null) {
            store.subscribe(changeStream::signal);
        }
    }

    @GetMapping
    public LiveActivityReport activity(
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "severity", required = false) String severity,
            @RequestParam(name = "since", required = false, defaultValue = "0") long since,
            @RequestParam(name = "limit", required = false, defaultValue = "0") int limit) {
        return service.report(type, severity, since, limit);
    }

    @GetMapping("/request/{id}")
    public RequestProfileDto request(@PathVariable("id") String id) {
        return correlator.profile(id);
    }

    /**
     * Streams a coalesced {@code update} notification whenever any merged source changes, so the
     * browser can refresh the feed live without polling. The push is a tiny tick; the browser
     * re-fetches {@link #activity} on each tick, preserving all filtering, bounds and masking.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return changeStream.open();
    }

    /**
     * Signals on each completed host request so new {@code REQUEST} rows appear live. BootUI's own
     * traffic (including the panel's re-fetches and this SSE connection) is excluded so the stream
     * cannot trigger itself in a refresh loop.
     */
    @EventListener
    public void onRequestHandled(ServletRequestHandledEvent event) {
        if (isHostRequest(event.getRequestUrl(), selfPath)) {
            changeStream.signal();
        }
    }

    /** Signals whenever a security audit event is recorded so {@code SECURITY} rows appear live. */
    @EventListener
    public void onAuditEvent(AuditApplicationEvent event) {
        changeStream.signal();
    }

    /**
     * A request counts as host (non-BootUI) traffic worth signalling unless its URL targets BootUI's
     * own base path. The panel's own re-fetches and SSE connection always carry a {@code /bootui/...}
     * URL, so excluding that path is what breaks the self-refresh loop; a rare {@code null} URL is
     * treated as host traffic since an occasional extra tick is harmless.
     */
    static boolean isHostRequest(String requestUrl, String selfPath) {
        return requestUrl == null || !requestUrl.contains(selfPath);
    }
}
