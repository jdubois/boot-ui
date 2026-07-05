package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.CopilotDashboardDto;
import io.github.jdubois.bootui.core.dto.CopilotEventListDto;
import io.github.jdubois.bootui.core.dto.CopilotRawEventDto;
import io.github.jdubois.bootui.core.dto.CopilotSessionDetail;
import io.github.jdubois.bootui.core.dto.CopilotSessionListDto;
import io.github.jdubois.bootui.engine.agent.AgentSessionStore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Flux;

/**
 * Reactive (WebFlux) sibling of {@code AgentSessionController}: the same read-only session/event/
 * stream shape over an {@link AgentSessionStore}, with {@code /stream} rebuilt as a
 * {@code Flux<ServerSentEvent<Object>>} instead of a servlet {@code SseEmitter}. Concrete
 * subclasses only add the {@code @RestController}/{@code @RequestMapping} wiring, the backing
 * store, and a human-readable {@code streamLabel} used in stream-limit messages - identical
 * division of responsibility to the servlet original.
 *
 * <p>Read-only by design. The default response payload contains only allowlisted, sanitized
 * fields - never raw prompts, tool arguments, command output, or diffs. The {@code /raw} endpoint
 * is the single opt-in escape hatch for inspecting the source JSON locally, and is gated by the
 * per-agent {@code allow-raw-reveal} flag and the existing {@code bootui.expose-values} setting.
 */
public abstract class ReactiveAgentSessionController {

    /** Upper bound on simultaneous activity streams; this is a local dev tool, not a fan-out hub. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    private final Supplier<? extends AgentSessionStore> store;
    private final BootUiExposure exposure;
    private final String streamLabel;
    private final AtomicInteger subscriberCount = new AtomicInteger();

    protected ReactiveAgentSessionController(
            Supplier<? extends AgentSessionStore> store, BootUiExposure exposure, String streamLabel) {
        this.store = store;
        this.exposure = exposure;
        this.streamLabel = streamLabel;
    }

    @GetMapping("/sessions")
    public CopilotSessionListDto sessions(
            @RequestParam(name = "since", required = false) Long since,
            @RequestParam(name = "until", required = false) Long until) {
        return store().listSessions(since, until);
    }

    @GetMapping("/dashboard")
    public CopilotDashboardDto dashboard() {
        return store().dashboard();
    }

    @GetMapping("/sessions/{id}")
    public ResponseEntity<CopilotSessionDetail> session(@PathVariable String id) {
        CopilotSessionDetail detail = store().getSession(id);
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
    }

    @GetMapping("/sessions/{id}/events")
    public ResponseEntity<CopilotEventListDto> events(
            @PathVariable String id,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "since", required = false) Long since,
            @RequestParam(name = "limit", required = false, defaultValue = "200") int limit) {
        AgentSessionStore store = store();
        if (store.getSession(id) == null) {
            return ResponseEntity.notFound().build();
        }
        var events = store.listEvents(id, category, since, limit);
        int total = store.totalEvents(id, category, since);
        return ResponseEntity.ok(new CopilotEventListDto(id, total, events.size(), events));
    }

    @GetMapping("/sessions/{id}/events/{eventId}/raw")
    public ResponseEntity<CopilotRawEventDto> raw(@PathVariable String id, @PathVariable String eventId) {
        AgentSessionStore store = store();
        if (!store.isRawRevealAllowed()) {
            return ResponseEntity.notFound().build();
        }
        if (exposure.valueExposure() == ValueExposure.METADATA_ONLY) {
            return ResponseEntity.notFound().build();
        }
        String json = store.getRawEventJson(id, eventId);
        if (json == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new CopilotRawEventDto(id, eventId, json));
    }

    /**
     * Streams the current sessions + dashboard snapshot immediately, then again on every refresh
     * event, so the browser can update live without polling. Two named event types are pushed each
     * time ({@code sessions} then {@code dashboard}), matching the servlet original's payload shape.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream() {
        return Flux.defer(() -> {
            if (subscriberCount.incrementAndGet() > MAX_CONCURRENT_STREAMS) {
                subscriberCount.decrementAndGet();
                return Flux.<ServerSentEvent<Object>>error(
                        new IllegalStateException("Too many concurrent BootUI " + streamLabel + " streams"));
            }
            AgentSessionStore agentStore = store();
            return Flux.<ServerSentEvent<Object>>create(sink -> {
                        sink.next(sessionsEvent(agentStore));
                        sink.next(dashboardEvent(agentStore));
                        Runnable unsubscribe = agentStore.subscribe(refresh -> {
                            sink.next(sessionsEvent(agentStore));
                            sink.next(dashboardEvent(agentStore));
                        });
                        sink.onDispose(unsubscribe::run);
                    })
                    .doFinally(signalType -> subscriberCount.decrementAndGet());
        });
    }

    private static ServerSentEvent<Object> sessionsEvent(AgentSessionStore store) {
        return ServerSentEvent.builder((Object) store.listSessions())
                .event("sessions")
                .build();
    }

    private static ServerSentEvent<Object> dashboardEvent(AgentSessionStore store) {
        return ServerSentEvent.builder((Object) store.dashboard())
                .event("dashboard")
                .build();
    }

    // Unlike the servlet original, there is no process-wide shutdown() hook here: the servlet
    // controller tracks a shared CopyOnWriteArrayList<SseEmitter> that it completes explicitly on
    // ContextClosedEvent (an SseEmitter(0L) never completes on its own). Under WebFlux, the server's
    // own graceful shutdown cancels every in-flight subscription, which runs that subscription's
    // sink.onDispose callback and unsubscribes it - no shared collection to track or drain here.

    private AgentSessionStore store() {
        return store.get();
    }
}
