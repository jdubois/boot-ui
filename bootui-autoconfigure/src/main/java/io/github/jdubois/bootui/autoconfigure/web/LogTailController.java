package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.LogLineDto;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/bootui/api/log-tail")
@ConditionalOnClass(name = "ch.qos.logback.classic.LoggerContext")
public class LogTailController {

    private final BootUiLogAppender appender;
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** Upper bound on simultaneous log-tail streams; this is a local dev tool, not a fan-out hub. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    public LogTailController() {
        this.appender = BootUiLogAppender.install();
    }

    private static LogLineDto toDto(BootUiLogAppender.LogLineDto line) {
        return new LogLineDto(line.timestamp(), line.level(), line.logger(), line.message(), line.thread());
    }

    @GetMapping("/recent")
    public List<LogLineDto> recent() {
        return appender.getRecentLines().stream().map(LogTailController::toDto).toList();
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L);
        if (emitters.size() >= MAX_CONCURRENT_STREAMS) {
            emitter.completeWithError(new IllegalStateException("Too many concurrent BootUI log-tail streams"));
            return emitter;
        }
        emitters.add(emitter);

        AtomicReference<Runnable> unsubscribeRef = new AtomicReference<>(() -> {});
        emitter.onCompletion(() -> cleanup(emitter, unsubscribeRef.get()));
        emitter.onTimeout(() -> cleanup(emitter, unsubscribeRef.get()));
        emitter.onError(error -> cleanup(emitter, unsubscribeRef.get()));

        try {
            for (BootUiLogAppender.LogLineDto line : appender.getRecentLines()) {
                sendLog(emitter, toDto(line));
            }
        } catch (IOException ex) {
            cleanup(emitter, unsubscribeRef.get());
            emitter.completeWithError(ex);
            return emitter;
        }

        Runnable unsubscribe = appender.subscribe(line -> {
            try {
                sendLog(emitter, toDto(line));
            } catch (IOException ex) {
                cleanup(emitter, unsubscribeRef.get());
                emitter.completeWithError(ex);
            }
        });
        unsubscribeRef.set(unsubscribe);

        return emitter;
    }

    private void cleanup(SseEmitter emitter, Runnable unsubscribe) {
        emitters.remove(emitter);
        unsubscribe.run();
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
        for (SseEmitter emitter : emitters) {
            emitter.complete();
        }
        emitters.clear();
        appender.uninstall();
    }

    private void sendLog(SseEmitter emitter, LogLineDto line) throws IOException {
        emitter.send(SseEmitter.event().name("log").data(line, MediaType.APPLICATION_JSON));
    }
}
