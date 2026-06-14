package io.github.jdubois.bootui.core.dto;

/**
 * One exception correlated to a profiled request.
 *
 * <p>Built from the recorded exception occurrences whose request context (method + path) matches
 * the profiled request within its time window. Messages follow BootUI's exposure policy.</p>
 *
 * @param exceptionClassName the exception type
 * @param message the latest masked message, or {@code null}
 * @param location the originating application location, or {@code null}
 * @param timestamp epoch milliseconds of the occurrence
 * @param thread the thread that threw, or {@code null}
 * @param handler the handler that was executing, or {@code null}
 * @param source short capture-source label ({@code web} or {@code log})
 */
public record RequestProfileExceptionDto(
        String exceptionClassName,
        String message,
        String location,
        long timestamp,
        String thread,
        String handler,
        String source) {}
