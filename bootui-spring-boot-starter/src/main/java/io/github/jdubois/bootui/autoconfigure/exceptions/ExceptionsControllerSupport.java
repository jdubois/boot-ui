package io.github.jdubois.bootui.autoconfigure.exceptions;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.CaughtExceptionsReport;
import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.ExceptionStatusUpdateRequest;
import io.github.jdubois.bootui.core.dto.ExceptionsReport;
import io.github.jdubois.bootui.engine.exceptions.CaughtExceptionsReader;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionsService;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * Shared read/clear/triage business logic for the BootUI Exceptions panel, used by both the servlet
 * {@code ExceptionsController} and the WebFlux {@code ReactiveExceptionsController}. Both bindings
 * expose the identical REST contract over the same framework-neutral {@link ExceptionStore} /
 * {@link ExceptionsService}, and none of this logic touches a servlet or reactive request/response
 * type, so it is extracted once here rather than duplicated per transport. The transport-specific
 * pieces (the {@code @RestController} wiring itself, the SSE {@code /stream} endpoint, and
 * constructor/shutdown lifecycle) stay in each controller.
 */
public final class ExceptionsControllerSupport {

    private ExceptionsControllerSupport() {}

    public static ExceptionsReport list(
            ObjectProvider<ExceptionStore> storeProvider, BootUiProperties properties, ExceptionsService service) {
        return list(storeProvider, properties, service, null);
    }

    /**
     * The panel's report, with the caught-in-code summary when the agent's {@code caught-exceptions} sensor records
     * ({@code docs/PLAN-v2.md} M5-6).
     */
    public static ExceptionsReport list(
            ObjectProvider<ExceptionStore> storeProvider,
            BootUiProperties properties,
            ExceptionsService service,
            ObjectProvider<CaughtExceptionsReader> caught) {
        ExceptionStore store = storeProvider.getIfAvailable();
        ExceptionsReport report = store == null
                ? ExceptionsReport.unavailable(
                        "Exception capture is disabled",
                        properties.getExceptions().getMaxGroups())
                : service.report(store);
        CaughtExceptionsReader reader = caught == null ? null : caught.getIfAvailable();
        return reader == null ? report : report.withCaughtInCode(reader.summary(properties::isPanelEnabled));
    }

    /** The <b>Caught in application code</b> section ({@code docs/PLAN-v2.md} M5-6). */
    public static CaughtExceptionsReport caught(
            ObjectProvider<CaughtExceptionsReader> caught, BootUiProperties properties) {
        CaughtExceptionsReader reader = caught == null ? null : caught.getIfAvailable();
        return reader == null
                ? CaughtExceptionsReport.unavailable(
                        "The BootUI agent's caught-exceptions sensor is not wired in this application")
                : reader.report(properties::isPanelEnabled);
    }

    public static ExceptionDetailDto detail(
            ObjectProvider<ExceptionStore> storeProvider, ExceptionsService service, String id) {
        ExceptionStore store = storeProvider.getIfAvailable();
        ExceptionStore.GroupDetail detail = store == null ? null : store.find(id);
        if (detail == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "exception " + id + " not found");
        }
        return service.detail(detail);
    }

    public static void clear(ObjectProvider<ExceptionStore> storeProvider) {
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
    public static ExceptionGroupDto updateStatus(
            ObjectProvider<ExceptionStore> storeProvider,
            ExceptionsService service,
            String id,
            ExceptionStatusUpdateRequest request) {
        ExceptionStore store = storeProvider.getIfAvailable();
        ExceptionGroupDto updated = service.updateStatus(store, id, request == null ? null : request.status());
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "exception " + id + " not found");
        }
        return updated;
    }

    public static ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage() == null ? "Invalid request" : ex.getMessage()));
    }
}
