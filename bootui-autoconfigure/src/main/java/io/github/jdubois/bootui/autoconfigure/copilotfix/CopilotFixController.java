package io.github.jdubois.bootui.autoconfigure.copilotfix;

import io.github.jdubois.bootui.core.dto.CopilotFixAvailabilityDto;
import io.github.jdubois.bootui.core.dto.CopilotFixDescriptorDto;
import io.github.jdubois.bootui.core.dto.CopilotFixRunDto;
import io.github.jdubois.bootui.core.dto.CopilotFixRunRequestDto;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST endpoints backing the BootUI "Fix it with Copilot" panel.
 *
 * <p>State-changing endpoints (starting a run) use {@code POST} so the existing
 * {@code PanelAccessFilter}/{@code LocalhostOnlyFilter} CSRF and loopback defenses apply. Responses
 * never include the GitHub token; the agent's progress is streamed as sanitized events.
 */
@RestController
@RequestMapping("/bootui/api/copilot-fix")
public class CopilotFixController {

    /** Upper bound on simultaneous activity streams; this is a local dev tool, not a fan-out hub. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    private final Supplier<CopilotFixService> service;
    private final java.util.concurrent.atomic.AtomicInteger activeStreams =
            new java.util.concurrent.atomic.AtomicInteger();

    @Autowired
    public CopilotFixController(
            @Qualifier("bootUiCopilotFixService") ObjectProvider<CopilotFixService> serviceProvider) {
        this(serviceProvider::getObject);
    }

    CopilotFixController(CopilotFixService service) {
        this(() -> service);
    }

    private CopilotFixController(Supplier<CopilotFixService> service) {
        this.service = service;
    }

    @GetMapping("/status")
    public CopilotFixAvailabilityDto status() {
        return service().availability();
    }

    @PostMapping("/run")
    public ResponseEntity<CopilotFixRunDto> run(@RequestBody CopilotFixRunRequestDto request) {
        CopilotFixService service = service();
        if (!service.enabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The Fix it with Copilot capability is disabled");
        }
        CopilotFixDescriptorDto descriptor = request == null ? null : request.descriptor();
        if (descriptor == null
                || descriptor.findingId() == null
                || descriptor.findingId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A finding descriptor with an id is required");
        }
        CopilotFixRun run = service.start(descriptor);
        return ResponseEntity.accepted().body(run.snapshot());
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<CopilotFixRunDto> runStatus(@PathVariable String id) {
        CopilotFixRun run = service().getRun(id);
        if (run == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(run.snapshot());
    }

    @GetMapping(path = "/runs/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id) {
        CopilotFixRun run = service().getRun(id);
        SseEmitter emitter = new SseEmitter(0L);
        if (run == null) {
            emitter.completeWithError(new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown run"));
            return emitter;
        }
        if (activeStreams.incrementAndGet() > MAX_CONCURRENT_STREAMS) {
            activeStreams.decrementAndGet();
            emitter.completeWithError(new IllegalStateException("Too many concurrent BootUI Copilot fix streams"));
            return emitter;
        }

        AtomicReference<Runnable> unsubscribeRef = new AtomicReference<>(() -> {});
        Runnable cleanup = () -> {
            Runnable unsubscribe = unsubscribeRef.getAndSet(null);
            if (unsubscribe != null) {
                unsubscribe.run();
                activeStreams.decrementAndGet();
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());

        Runnable unsubscribe = run.subscribe(event -> {
            try {
                emitter.send(SseEmitter.event().name("event").data(event, MediaType.APPLICATION_JSON));
                if ("done".equals(event.type())) {
                    emitter.complete();
                }
            } catch (IOException ex) {
                emitter.completeWithError(ex);
            }
        });
        unsubscribeRef.set(unsubscribe);
        if (run.isFinished()) {
            emitter.complete();
        }
        return emitter;
    }

    private CopilotFixService service() {
        return service.get();
    }
}
