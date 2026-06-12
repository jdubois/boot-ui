package io.github.jdubois.bootui.core.dto;

/**
 * A single recorded occurrence of a grouped exception.
 *
 * <p>Web occurrences carry request context ({@code requestMethod}, {@code requestPath},
 * {@code handler}); occurrences captured from logging leave those null. {@code source} is a short
 * label such as {@code "web"} or {@code "log"}. The request path never includes the query string,
 * which could contain secrets.</p>
 */
public record ExceptionOccurrenceDto(
        long timestamp, String thread, String requestMethod, String requestPath, String handler, String source) {}
