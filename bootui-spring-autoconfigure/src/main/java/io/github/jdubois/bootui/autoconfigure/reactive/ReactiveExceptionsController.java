package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.exceptions.ExceptionsControllerSupport;
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
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Reactive (WebFlux) sibling of {@code ExceptionsController}: identical read/clear/triage API over
 * the same framework-neutral {@link ExceptionStore}, with the {@code /stream} endpoint rebuilt on
 * {@link ReactiveBootUiChangeStream}. Capture itself happens in {@link ReactiveBootUiExceptionHandler}
 * (the reactive twin of {@code BootUiExceptionHandlerResolver}).
 */
@RestController
@RequestMapping("/bootui/api/exceptions")
public class ReactiveExceptionsController {

    private final ObjectProvider<ExceptionStore> storeProvider;

    private final BootUiProperties properties;

    private final ExceptionsService service;

    private final ReactiveBootUiChangeStream changeStream;

    private Runnable storeUnsubscribe;

    @Autowired
    public ReactiveExceptionsController(
            ObjectProvider<ExceptionStore> storeProvider, BootUiProperties properties, BootUiExposure exposure) {
        this.storeProvider = storeProvider;
        this.properties = properties;
        this.service = new ExceptionsService(exposure);
        this.changeStream = new ReactiveBootUiChangeStream("exceptions");
        ExceptionStore store = storeProvider.getIfAvailable();
        if (store != null) {
            this.storeUnsubscribe = store.subscribe(changeStream::signal);
        }
    }

    /**
     * Completes any open SSE streams and detaches the store listener when the context starts
     * closing. See {@code ExceptionsController#shutdown} for why this runs on
     * {@link ContextClosedEvent} rather than a destroy callback.
     */
    @EventListener(ContextClosedEvent.class)
    void shutdown() {
        if (storeUnsubscribe != null) {
            storeUnsubscribe.run();
            storeUnsubscribe = null;
        }
        changeStream.close();
    }

    ReactiveExceptionsController(ObjectProvider<ExceptionStore> storeProvider, BootUiProperties properties) {
        this(storeProvider, properties, new BootUiExposure(properties));
    }

    @GetMapping
    public ExceptionsReport list() {
        return ExceptionsControllerSupport.list(storeProvider, properties, service);
    }

    @GetMapping("/{id}")
    public ExceptionDetailDto detail(@PathVariable String id) {
        return ExceptionsControllerSupport.detail(storeProvider, service, id);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear() {
        ExceptionsControllerSupport.clear(storeProvider);
    }

    @PostMapping("/{id}/status")
    public ExceptionGroupDto updateStatus(
            @PathVariable String id, @RequestBody(required = false) ExceptionStatusUpdateRequest request) {
        return ExceptionsControllerSupport.updateStatus(storeProvider, service, id, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ExceptionsControllerSupport.handleBadRequest(ex);
    }

    /**
     * Streams a coalesced {@code update} notification whenever a new exception is captured or the
     * panel is cleared, so the browser can refresh live without polling.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> stream() {
        return changeStream.open();
    }
}
