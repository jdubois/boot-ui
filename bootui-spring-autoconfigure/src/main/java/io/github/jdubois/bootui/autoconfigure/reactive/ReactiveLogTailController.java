package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.web.BootUiLogAppender;
import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import io.github.jdubois.bootui.engine.logtail.LogTailReader;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.concurrent.Queues;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reactive (WebFlux) sibling of {@code LogTailController}: the same {@link LogTailBuffer} ring
 * buffer fed by the shared Logback appender, streamed as server-sent events instead of a servlet
 * {@code SseEmitter}. Unlike the coalesced tick used by
 * {@link ReactiveBootUiChangeStream}-backed panels, each element here carries an actual captured
 * log line - the browser has no other endpoint to re-fetch full log content from. Snapshots and
 * streamed lines are read through the engine {@link LogTailReader}, which applies the live
 * value-exposure policy to each message as it is emitted.
 */
@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/log-tail")
@ConditionalOnClass(name = "ch.qos.logback.classic.LoggerContext")
public class ReactiveLogTailController implements DisposableBean {

    /** Upper bound on simultaneous log-tail streams; this is a local dev tool, not a fan-out hub. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    /** The same clean mapper BootUI's JSON responses use, so a streamed line matches the recent snapshot. */
    private static final JsonMapper JSON =
            JsonMapper.builder().findAndAddModules().build();

    private final BootUiLogAppender appender;
    private final LogTailReader reader;
    private final AtomicInteger subscriberCount = new AtomicInteger();

    /**
     * Dedicated delivery threads, so that a line logged while a stream line is exposed or serialized is never
     * captured and streamed back.
     */
    private final Scheduler delivery = Schedulers.newBoundedElastic(
            MAX_CONCURRENT_STREAMS,
            Integer.MAX_VALUE,
            LogTailBuffer.deliveryThreadFactory("bootui-log-tail-stream-reactive-"),
            60);

    public ReactiveLogTailController(BootUiProperties properties, BootUiExposure exposure) {
        this.appender = BootUiLogAppender.install(new LogTailBuffer(
                LogTailBuffer.DEFAULT_MAX_LINES, properties.getLogTail().getMaxBytes()));
        this.reader = new LogTailReader(appender.buffer(), exposure);
    }

    @GetMapping("/recent")
    public List<LogLineDto> recent() {
        return reader.recent();
    }

    /**
     * Streams every captured log line: the backlog first, then live lines as they are appended.
     * {@link LogTailBuffer#subscribeWithReplay} atomically snapshots the backlog and registers the
     * live subscriber under one lock, so no line is lost or duplicated between the two; the flux
     * subscription itself (not controller construction) is the side-effecting moment, matching
     * WebFlux's subscribe-once-per-request model. The logging thread only hands the captured line
     * over; {@code publishOn} moves delivery to a dedicated delivery thread, where each line is exposed
     * under the policy in force as it is sent, as the servlet stream's SSE worker does. Like the
     * servlet stream, a client that falls {@link LogTailReader#MAX_PENDING_LINES} lines behind is
     * disconnected rather than buffering without bound.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream() {
        return Flux.defer(() -> {
            if (subscriberCount.incrementAndGet() > MAX_CONCURRENT_STREAMS) {
                subscriberCount.decrementAndGet();
                return Flux.<ServerSentEvent<String>>error(
                        new IllegalStateException("Too many concurrent BootUI log-tail streams"));
            }
            return Flux.<LogLineDto>create(sink -> {
                        LogTailBuffer.Subscription subscription = reader.subscribeWithReplay(sink::next);
                        Runnable unsubscribe = subscription.unsubscribe();
                        sink.onDispose(unsubscribe::run);
                        for (LogLineDto line : subscription.backlog()) {
                            sink.next(line);
                        }
                    })
                    .onBackpressureBuffer(LogTailReader.MAX_PENDING_LINES)
                    // Without delayError, an overflow disconnects at once instead of waiting behind lines a
                    // stalled client will never drain.
                    .publishOn(delivery, false, Queues.SMALL_BUFFER_SIZE)
                    // Serialized here, on the delivery thread: Spring's SSE writer encodes an object later, often on
                    // the Netty event loop, where a debug "Encoding [...]" line would be captured and streamed back.
                    // String data is written as-is, so the wire stays "data:{json}".
                    .map(line -> ServerSentEvent.<String>builder(JSON.writeValueAsString(reader.expose(line)))
                            .event("log")
                            .build())
                    .doFinally(signalType -> subscriberCount.decrementAndGet());
        });
    }

    int activeStreamCount() {
        return subscriberCount.get();
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
        delivery.dispose();
    }

    /** Also disposes the delivery scheduler when the context is destroyed without closing, such as a failed refresh. */
    @Override
    public void destroy() {
        delivery.dispose();
    }
}
