package io.github.jdubois.bootui.autoconfigure.exceptions;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.stream.BootUiChangeStream;
import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.ExceptionStatusUpdateRequest;
import io.github.jdubois.bootui.core.dto.ExceptionsReport;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionsService;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Read/clear API for the BootUI Exceptions panel.
 *
 * <p>Exception messages are surfaced according to the configured value-exposure policy: omitted for
 * {@code METADATA_ONLY}, scrubbed of obvious secret-like assignments for the default {@code MASKED}
 * mode, and shown verbatim only for {@code FULL}. Stack frames carry only class/method/file/line
 * information.</p>
 */
@RestController
@RequestMapping("/bootui/api/exceptions")
public class ExceptionsController {

    private final ObjectProvider<ExceptionStore> storeProvider;

    private final BootUiProperties properties;

    private final ExceptionsService service;

    private final BootUiChangeStream changeStream;

    private Runnable storeUnsubscribe;

    @Autowired
    public ExceptionsController(
            ObjectProvider<ExceptionStore> storeProvider, BootUiProperties properties, BootUiExposure exposure) {
        this.storeProvider = storeProvider;
        this.properties = properties;
        this.service = new ExceptionsService(exposure);
        this.changeStream = new BootUiChangeStream("exceptions");
        ExceptionStore store = storeProvider.getIfAvailable();
        if (store != null) {
            this.storeUnsubscribe = store.subscribe(changeStream::signal);
        }
    }

    /**
     * Completes any open SSE streams and detaches the store listener when the context starts closing.
     *
     * <p>Runs on {@link ContextClosedEvent} rather than {@code @PreDestroy}: the event is published
     * before the web server's graceful-shutdown lifecycle waits for in-flight requests, whereas
     * {@code @PreDestroy} runs during later bean destruction. An {@code SseEmitter(0L)} never completes
     * on its own, so cleaning up at destroy time would let graceful shutdown block until its timeout on
     * every stop. Doing it here also keeps a Spring Boot DevTools restart from leaking the
     * {@code bootui-exceptions-stream} daemon thread (and the discarded context behind it).
     */
    @EventListener(ContextClosedEvent.class)
    void shutdown() {
        if (storeUnsubscribe != null) {
            storeUnsubscribe.run();
            storeUnsubscribe = null;
        }
        changeStream.close();
    }

    ExceptionsController(ObjectProvider<ExceptionStore> storeProvider, BootUiProperties properties) {
        this(storeProvider, properties, new BootUiExposure(properties));
    }

    @GetMapping
    public ExceptionsReport list() {
        ExceptionStore store = storeProvider.getIfAvailable();
        if (store == null) {
            return ExceptionsReport.unavailable(
                    "Exception capture is disabled", properties.getExceptions().getMaxGroups());
        }
        return service.report(store);
    }

    @GetMapping("/{id}")
    public ExceptionDetailDto detail(@PathVariable String id) {
        ExceptionStore store = storeProvider.getIfAvailable();
        ExceptionStore.GroupDetail detail = store == null ? null : store.find(id);
        if (detail == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "exception " + id + " not found");
        }
        return service.detail(detail);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear() {
        ExceptionStore store = storeProvider.getIfAvailable();
        if (store != null) {
            store.clear();
        }
    }

    /**
     * Changes the triage status of one exception group ({@code OPEN}/{@code ACKNOWLEDGED}/
     * {@code RESOLVED}). See {@link ExceptionsService#updateStatus} for validation and regression
     * semantics.
     */
    @PostMapping("/{id}/status")
    public ExceptionGroupDto updateStatus(
            @PathVariable String id, @RequestBody(required = false) ExceptionStatusUpdateRequest request) {
        ExceptionStore store = storeProvider.getIfAvailable();
        ExceptionGroupDto updated = service.updateStatus(store, id, request == null ? null : request.status());
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "exception " + id + " not found");
        }
        return updated;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage() == null ? "Invalid request" : ex.getMessage()));
    }

    /**
     * Streams a coalesced {@code update} notification whenever a new exception is captured or the
     * panel is cleared, so the browser can refresh live without polling.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return changeStream.open();
    }
}
