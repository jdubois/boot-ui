package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * A single recorded occurrence of a grouped exception.
 *
 * <p>Web occurrences carry request context ({@code requestMethod}, {@code requestPath},
 * {@code handler}); occurrences captured from logging leave those null. {@code source} is a short
 * label such as {@code "web"} or {@code "log"}. The request path never includes the query string,
 * which could contain secrets.</p>
 *
 * @param traceId the distributed-trace id active when this occurrence was captured, or {@code null}
 *     when none was active (or on Spring, which correlates by serving thread instead); used by the
 *     Quarkus adapter's per-request profile drill-down to correlate this occurrence to the request
 *     that produced it
 * @param bindingDiagnostics bounded metadata-only validation and binding diagnostics extracted for
 *     this occurrence (never carrying rejected values)
 * @param bindingDiagnosticsTruncated {@code true} when additional diagnostics existed beyond the
 *     per-occurrence cap
 */
public record ExceptionOccurrenceDto(
        long timestamp,
        String thread,
        String requestMethod,
        String requestPath,
        String handler,
        String source,
        String traceId,
        String requestId,
        String executionId,
        List<BindingDiagnosticDto> bindingDiagnostics,
        boolean bindingDiagnosticsTruncated) {

    public ExceptionOccurrenceDto {
        bindingDiagnostics = DtoCollections.immutableCopy(bindingDiagnostics);
    }

    /** Without binding diagnostics. */
    public ExceptionOccurrenceDto(
            long timestamp,
            String thread,
            String requestMethod,
            String requestPath,
            String handler,
            String source,
            String traceId,
            String requestId,
            String executionId) {
        this(
                timestamp,
                thread,
                requestMethod,
                requestPath,
                handler,
                source,
                traceId,
                requestId,
                executionId,
                List.of(),
                false);
    }

    /** Without BootUI's execution identity. */
    public ExceptionOccurrenceDto(
            long timestamp,
            String thread,
            String requestMethod,
            String requestPath,
            String handler,
            String source,
            String traceId,
            String requestId) {
        this(timestamp, thread, requestMethod, requestPath, handler, source, traceId, requestId, null);
    }

    /** Without BootUI's request identity. */
    public ExceptionOccurrenceDto(
            long timestamp,
            String thread,
            String requestMethod,
            String requestPath,
            String handler,
            String source,
            String traceId) {
        this(timestamp, thread, requestMethod, requestPath, handler, source, traceId, null);
    }
}
