package io.github.jdubois.bootui.core.dto;

/**
 * Summary of one grouped exception for the Exceptions panel list and live stream.
 *
 * <p>Exceptions are grouped by a stable {@code id} (a fingerprint of the exception type and the
 * top stack frames) so repeated failures collapse into a single row with an occurrence
 * {@code count}. The {@code last*} fields describe the most recent occurrence. {@code message} is
 * already masked according to the configured value-exposure policy.</p>
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
        String lastSource) {}
