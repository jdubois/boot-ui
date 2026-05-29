package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.BootUiDtos.CopilotDashboardDto;
import io.github.jdubois.bootui.core.BootUiDtos.CopilotEventListDto;
import io.github.jdubois.bootui.core.BootUiDtos.CopilotRawEventDto;
import io.github.jdubois.bootui.core.BootUiDtos.CopilotSessionDetail;
import io.github.jdubois.bootui.core.BootUiDtos.CopilotSessionListDto;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST endpoints backing the BootUI Copilot panel.
 *
 * <p>Read-only by design. The default response payload contains only allowlisted,
 * sanitized fields - never raw prompts, tool arguments, command output, or diffs.
 * The {@code /raw} endpoint is the single opt-in escape hatch for inspecting the
 * source JSON locally, and is gated by {@code bootui.copilot.allow-raw-reveal} and
 * the existing {@code bootui.expose-values} setting.</p>
 */
@RestController
@RequestMapping("/bootui/api/copilot")
public class CopilotController {

    private final Supplier<CopilotSessionStore> store;
    private final BootUiProperties properties;
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    /** Upper bound on simultaneous activity streams; this is a local dev tool, not a fan-out hub. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    @Autowired
    public CopilotController(
            @Qualifier("bootUiCopilotSessionStore") ObjectProvider<CopilotSessionStore> storeProvider,
            BootUiProperties properties) {
        this(storeProvider::getObject, properties);
    }

    CopilotController(CopilotSessionStore store, BootUiProperties properties) {
        this(() -> store, properties);
    }

    private CopilotController(Supplier<CopilotSessionStore> store, BootUiProperties properties) {
        this.store = store;
        this.properties = properties;
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
        CopilotSessionStore store = store();
        if (store.getSession(id) == null) {
            return ResponseEntity.notFound().build();
        }
        var events = store.listEvents(id, category, since, limit);
        int total = store.totalEvents(id, category, since);
        return ResponseEntity.ok(new CopilotEventListDto(id, total, events.size(), events));
    }

    @GetMapping("/sessions/{id}/events/{eventId}/raw")
    public ResponseEntity<CopilotRawEventDto> raw(@PathVariable String id, @PathVariable String eventId) {
        CopilotSessionStore store = store();
        if (!store.isRawRevealAllowed()) {
            return ResponseEntity.notFound().build();
        }
        if (properties.getExposeValues() == BootUiProperties.ValueExposure.METADATA_ONLY) {
            return ResponseEntity.notFound().build();
        }
        String json = store.getRawEventJson(id, eventId);
        if (json == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new CopilotRawEventDto(id, eventId, json));
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        CopilotSessionStore store = store();
        SseEmitter emitter = new SseEmitter(0L);
        if (emitters.size() >= MAX_CONCURRENT_STREAMS) {
            emitter.completeWithError(new IllegalStateException("Too many concurrent BootUI Copilot streams"));
            return emitter;
        }
        emitters.add(emitter);

        AtomicReference<Runnable> unsubscribeRef = new AtomicReference<>(() -> {});
        emitter.onCompletion(() -> cleanup(emitter, unsubscribeRef.get()));
        emitter.onTimeout(() -> cleanup(emitter, unsubscribeRef.get()));
        emitter.onError(error -> cleanup(emitter, unsubscribeRef.get()));

        try {
            emitter.send(SseEmitter.event().name("sessions").data(store.listSessions(), MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event().name("dashboard").data(store.dashboard(), MediaType.APPLICATION_JSON));
        } catch (IOException ex) {
            cleanup(emitter, unsubscribeRef.get());
            emitter.completeWithError(ex);
            return emitter;
        }

        Runnable unsubscribe = store.subscribe(refresh -> {
            try {
                emitter.send(
                        SseEmitter.event().name("sessions").data(store.listSessions(), MediaType.APPLICATION_JSON));
                emitter.send(SseEmitter.event().name("dashboard").data(store.dashboard(), MediaType.APPLICATION_JSON));
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
        if (unsubscribe != null) {
            unsubscribe.run();
        }
    }

    // exposed only for testing
    List<SseEmitter> emittersForTesting() {
        return emitters;
    }

    private CopilotSessionStore store() {
        return store.get();
    }
}
