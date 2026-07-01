package io.github.jdubois.bootui.core.dto;

/**
 * Summary of one grouped exception for the Exceptions panel list and live stream.
 *
 * <p>Exceptions are grouped by a stable {@code id} (a fingerprint of the exception type and the
 * top stack frames) so repeated failures collapse into a single row with an occurrence
 * {@code count}. The {@code last*} fields describe the most recent occurrence. {@code message} is
 * already masked according to the configured value-exposure policy.</p>
 *
 * <p>{@code lastTraceId} is the distributed-trace id of the most recent occurrence, or {@code null} when
 * none was captured. It lets the Live Activity timeline nest this exception under the HTTP request that
 * shares the same trace id (used by the Quarkus adapter; {@code null} on Spring, which correlates by
 * serving thread instead).</p>
 */
public record ExceptionGroupDto(
        String id,
        String exceptionClassName,
        String message,
        long count,
        long firstSeen,
        long lastSeen,
        String location,
        boolean applicationException,
        String lastThread,
        String lastRequestMethod,
        String lastRequestPath,
        String lastHandler,
        String lastSource,
        String lastTraceId) {}
