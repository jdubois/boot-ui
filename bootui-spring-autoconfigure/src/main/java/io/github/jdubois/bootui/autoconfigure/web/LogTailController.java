package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import io.github.jdubois.bootui.engine.logtail.LogTailReader;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Spring MVC transport for the Log Tail panel. Snapshots and streamed lines are read through the engine
 * {@link LogTailReader}, which applies the live value-exposure policy to each message at read time.
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/log-tail")
@ConditionalOnClass(name = "ch.qos.logback.classic.LoggerContext")
public class LogTailController {

    /** Stream workers are delivery threads: a line they log while sending is never captured and streamed back. */
    private static final ThreadFactory STREAM_THREADS = LogTailBuffer.deliveryThreadFactory("bootui-log-tail-stream-");

    private final BootUiLogAppender appender;
    private final LogTailReader reader;
    private final Supplier<SseEmitter> emitterFactory;
    private final int pendingEventCapacity;
    private final LogTailSseSession.EventSender eventSender;
    private final ExecutorService streamExecutor;
    private final CopyOnWriteArrayList<LogTailSseSession> sessions = new CopyOnWriteArrayList<>();

    /** Upper bound on simultaneous log-tail streams; this is a local dev tool, not a fan-out hub. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    /**
     * Per-client pending event bound. It holds the full 500-line replay plus a bounded live burst; a
     * client that cannot drain it is disconnected rather than consuming memory indefinitely.
     */
    static final int MAX_PENDING_EVENTS = LogTailReader.MAX_PENDING_LINES;

    @Autowired
    public LogTailController(BootUiProperties properties, BootUiExposure exposure) {
        this(
                BootUiLogAppender.install(new LogTailBuffer(
                        LogTailBuffer.DEFAULT_MAX_LINES, properties.getLogTail().getMaxBytes())),
                exposure,
                () -> new SseEmitter(0L),
                MAX_PENDING_EVENTS,
                LogTailController::sendLog,
                createStreamExecutor());
    }

    LogTailController(
            BootUiLogAppender appender,
            Supplier<SseEmitter> emitterFactory,
            int pendingEventCapacity,
            LogTailSseSession.EventSender eventSender) {
        this(appender, new BootUiExposure(new BootUiProperties()), emitterFactory, pendingEventCapacity, eventSender);
    }

    LogTailController(
            BootUiLogAppender appender,
            ExposurePolicy exposure,
            Supplier<SseEmitter> emitterFactory,
            int pendingEventCapacity,
            LogTailSseSession.EventSender eventSender) {
        this(appender, exposure, emitterFactory, pendingEventCapacity, eventSender, createStreamExecutor());
    }

    private LogTailController(
            BootUiLogAppender appender,
            ExposurePolicy exposure,
            Supplier<SseEmitter> emitterFactory,
            int pendingEventCapacity,
            LogTailSseSession.EventSender eventSender,
            ExecutorService streamExecutor) {
        this.appender = appender;
        this.reader = new LogTailReader(appender.buffer(), exposure);
        this.emitterFactory = emitterFactory;
        this.pendingEventCapacity = pendingEventCapacity;
        this.eventSender = eventSender;
        this.streamExecutor = streamExecutor;
    }

    @GetMapping("/recent")
    public List<LogLineDto> recent() {
        return reader.recent();
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = emitterFactory.get();
        LogTailSseSession session = new LogTailSseSession(
                emitter, reader, pendingEventCapacity, streamExecutor, eventSender, () -> removeSession(emitter));
        synchronized (sessions) {
            if (sessions.size() >= MAX_CONCURRENT_STREAMS) {
                emitter.completeWithError(new IllegalStateException("Too many concurrent BootUI log-tail streams"));
                return emitter;
            }
            sessions.add(session);
        }

        emitter.onCompletion(session::close);
        emitter.onTimeout(session::close);
        emitter.onError(error -> session.close());
        session.start();
        return emitter;
    }

    private void removeSession(SseEmitter emitter) {
        sessions.removeIf(session -> sessionMatches(session, emitter));
    }

    private boolean sessionMatches(LogTailSseSession session, SseEmitter emitter) {
        return session.emitter() == emitter;
    }

    /**
     * Completes any open log-tail streams and detaches the shared Logback appender when the context
     * starts closing.
     *
     * <p>Runs on {@link ContextClosedEvent} rather than {@code @PreDestroy}: the event is published
     * before the web server's graceful-shutdown lifecycle waits for in-flight requests, whereas
     * {@code @PreDestroy} runs during later bean destruction. An {@code SseEmitter(0L)} never completes
     * on its own, so cleaning up at destroy time would let graceful shutdown block until its timeout on
     * every stop. Doing it here also keeps a Spring Boot DevTools restart from leaving dead SSE
     * subscribers attached to the surviving {@code LoggerContext} (and the old context pinned behind
     * them) on every live reload.
     */
    @EventListener(ContextClosedEvent.class)
    void shutdown() {
        for (LogTailSseSession session : sessions) {
            session.complete();
        }
        sessions.clear();
        streamExecutor.shutdownNow();
        appender.uninstall();
    }

    int activeStreamCount() {
        return sessions.size();
    }

    private static void sendLog(SseEmitter emitter, LogLineDto line) throws IOException {
        emitter.send(SseEmitter.event().name("log").data(line, MediaType.APPLICATION_JSON));
    }

    private static ExecutorService createStreamExecutor() {
        return new ThreadPoolExecutor(
                0,
                MAX_CONCURRENT_STREAMS,
                100L,
                TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(),
                STREAM_THREADS,
                new ThreadPoolExecutor.AbortPolicy());
    }
}
