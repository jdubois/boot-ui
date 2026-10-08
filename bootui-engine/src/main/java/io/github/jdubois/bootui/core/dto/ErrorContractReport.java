package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Top-level report for the REST API panel's error-contract catalogue.
 *
 * <p>{@code available} is {@code false} when no error-contract backend is wired for the running stack;
 * {@code unavailableReason} then carries the framework-correct explanation. An available backend that
 * simply found no {@code @ControllerAdvice}, {@code @ExceptionHandler}, or {@code ExceptionMapper}
 * declaration reports {@code available=true} with an empty catalogue, which is the honest "this
 * application declares no error contract" state.</p>
 *
 * <p>{@code entries} is the sorted, queried and paged window described by {@code page};
 * {@code total} counts every catalogued entry before paging. {@code truncated} is {@code true} when the
 * discovered declarations exceeded {@code maxEntries} and the catalogue was bounded.</p>
 */
public record ErrorContractReport(
        boolean available,
        String unavailableReason,
        int total,
        int handlerCount,
        int componentCount,
        int exceptionTypeCount,
        boolean truncated,
        int maxEntries,
        List<ErrorContractEntryDto> entries,
        PageMetadata page) {

    public ErrorContractReport {
        entries = DtoCollections.immutableCopy(entries);
    }

    /** The unavailable report for a stack with no error-contract backend. */
    public static ErrorContractReport unavailable(String reason, int maxEntries) {
        return new ErrorContractReport(
                false, reason, 0, 0, 0, 0, false, maxEntries, List.of(), new PageMetadata(0, 0, 0, 0, 0, false));
    }
}
