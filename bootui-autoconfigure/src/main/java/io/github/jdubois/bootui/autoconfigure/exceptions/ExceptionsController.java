package io.github.jdubois.bootui.autoconfigure.exceptions;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties.ValueExposure;
import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.autoconfigure.stream.BootUiChangeStream;
import io.github.jdubois.bootui.core.dto.ExceptionCauseDto;
import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.ExceptionFrameDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.ExceptionOccurrenceDto;
import io.github.jdubois.bootui.core.dto.ExceptionsReport;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)([\"']?(?:password|passwd|pwd|secret|token|api[-_]?key|apikey|authorization|credential|"
                    + "access[-_]?key|client[-_]?secret|private[-_]?key)[\"']?\\s*[=:]\\s*[\"']?)([^\\s\"',;&)]+)");

    private final ObjectProvider<ExceptionStore> storeProvider;

    private final BootUiProperties properties;

    private final BootUiExposure exposure;

    private final BootUiChangeStream changeStream;

    private Runnable storeUnsubscribe;

    @Autowired
    public ExceptionsController(
            ObjectProvider<ExceptionStore> storeProvider, BootUiProperties properties, BootUiExposure exposure) {
        this.storeProvider = storeProvider;
        this.properties = properties;
        this.exposure = exposure;
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
        List<ExceptionGroupDto> groups =
                store.groups().stream().map(this::toGroupDto).toList();
        return new ExceptionsReport(true, null, store.maxGroups(), store.totalExceptions(), groups);
    }

    @GetMapping("/{id}")
    public ExceptionDetailDto detail(@PathVariable String id) {
        ExceptionStore store = storeProvider.getIfAvailable();
        ExceptionStore.GroupDetail detail = store == null ? null : store.find(id);
        if (detail == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "exception " + id + " not found");
        }
        return toDetailDto(detail);
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
     * Streams a coalesced {@code update} notification whenever a new exception is captured or the
     * panel is cleared, so the browser can refresh live without polling.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return changeStream.open();
    }

    private ExceptionGroupDto toGroupDto(ExceptionStore.GroupSummary summary) {
        ExceptionStore.Occurrence last = summary.last();
        return new ExceptionGroupDto(
                summary.fingerprint(),
                summary.exceptionClassName(),
                displayMessage(summary.message()),
                summary.count(),
                summary.firstSeen(),
                summary.lastSeen(),
                summary.location(),
                summary.applicationException(),
                last == null ? null : last.thread(),
                last == null ? null : last.requestMethod(),
                last == null ? null : last.requestPath(),
                last == null ? null : last.handler(),
                last == null ? null : last.source());
    }

    private ExceptionDetailDto toDetailDto(ExceptionStore.GroupDetail detail) {
        return new ExceptionDetailDto(
                toGroupDto(detail.summary()),
                detail.frames().stream().map(this::toFrameDto).toList(),
                detail.causes().stream().map(this::toCauseDto).toList(),
                detail.occurrences().stream().map(this::toOccurrenceDto).toList());
    }

    private ExceptionFrameDto toFrameDto(ExceptionStore.Frame frame) {
        return new ExceptionFrameDto(
                frame.declaringClass(),
                frame.methodName(),
                frame.fileName(),
                frame.lineNumber() >= 0 ? frame.lineNumber() : null,
                frame.applicationFrame());
    }

    private ExceptionCauseDto toCauseDto(ExceptionStore.Cause cause) {
        return new ExceptionCauseDto(
                cause.exceptionClassName(),
                displayMessage(cause.message()),
                cause.frames().stream().map(this::toFrameDto).toList(),
                cause.commonFrames());
    }

    private ExceptionOccurrenceDto toOccurrenceDto(ExceptionStore.Occurrence occurrence) {
        return new ExceptionOccurrenceDto(
                occurrence.timestamp(),
                occurrence.thread(),
                occurrence.requestMethod(),
                occurrence.requestPath(),
                occurrence.handler(),
                occurrence.source());
    }

    private String displayMessage(String message) {
        ValueExposure valueExposure = exposure.valueExposure();
        if (valueExposure == ValueExposure.METADATA_ONLY || message == null) {
            return null;
        }
        if (valueExposure == ValueExposure.MASKED && exposure.maskSecrets()) {
            return SECRET_ASSIGNMENT.matcher(message).replaceAll(result -> result.group(1) + "******");
        }
        return message;
    }
}
