package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.web.BootUiLogAppender;
import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Reactive (WebFlux) sibling of {@code LogTailController}: the same {@link LogTailBuffer} ring
 * buffer fed by the shared Logback appender, streamed as {@code Flux<ServerSentEvent<LogLineDto>>}
 * instead of a servlet {@code SseEmitter}. Unlike the coalesced tick used by
 * {@link ReactiveBootUiChangeStream}-backed panels, each element here carries an actual captured
 * log line - the browser has no other endpoint to re-fetch full log content from.
 */
@RestController
@RequestMapping("/bootui/api/log-tail")
@ConditionalOnClass(name = "ch.qos.logback.classic.LoggerContext")
public class ReactiveLogTailController {

    /** Upper bound on simultaneous log-tail streams; this is a local dev tool, not a fan-out hub. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    private final BootUiLogAppender appender;
    private final LogTailBuffer buffer;
    private final AtomicInteger subscriberCount = new AtomicInteger();

    public ReactiveLogTailController(BootUiProperties properties) {
        this.appender = BootUiLogAppender.install(new LogTailBuffer(
                LogTailBuffer.DEFAULT_MAX_LINES, properties.getLogTail().getMaxBytes()));
        this.buffer = appender.buffer();
    }

    @GetMapping("/recent")
    public List<LogLineDto> recent() {
        return buffer.recent();
    }

    /**
     * Streams every captured log line: the backlog first, then live lines as they are appended.
     * {@link LogTailBuffer#subscribeWithReplay} atomically snapshots the backlog and registers the
     * live subscriber under one lock, so no line is lost or duplicated between the two; the flux
     * subscription itself (not controller construction) is the side-effecting moment, matching
     * WebFlux's subscribe-once-per-request model.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<LogLineDto>> stream() {
        return Flux.defer(() -> {
                    if (subscriberCount.incrementAndGet() > MAX_CONCURRENT_STREAMS) {
                        subscriberCount.decrementAndGet();
                        return Flux.<LogLineDto>error(
                                new IllegalStateException("Too many concurrent BootUI log-tail streams"));
                    }
                    return Flux.<LogLineDto>create(sink -> {
                                LogTailBuffer.Subscription subscription = buffer.subscribeWithReplay(sink::next);
                                Runnable unsubscribe = subscription.unsubscribe();
                                sink.onDispose(unsubscribe::run);
                                for (LogLineDto line : subscription.backlog()) {
                                    sink.next(line);
                                }
                            })
                            .doFinally(signalType -> subscriberCount.decrementAndGet());
                })
                .map(line ->
                        ServerSentEvent.<LogLineDto>builder(line).event("log").build());
    }

    /**
     * Detaches the shared Logback appender when the context starts closing, matching the servlet
     * original's rationale for using {@link ContextClosedEvent} over a destroy callback (keeps a
     * Spring Boot DevTools restart from leaving the old {@code LoggerContext} pinned by a dangling
     * appender). Open streams complete naturally: WebFlux's own graceful shutdown cancels in-flight
     * subscriptions, and cancellation runs each subscription's {@code sink.onDispose} to unsubscribe.
     */
    @EventListener(ContextClosedEvent.class)
    void shutdown() {
        appender.uninstall();
    }
}
