package io.github.jdubois.bootui.engine.journal;

import java.util.List;
import java.util.Objects;

/**
 * Versioned, caller-masked auxiliary detail associated with one journal event.
 *
 * <p>The shape is intentionally allow-listed. It has no fields for exception messages, raw principals, HTTP headers,
 * query strings, sessions, email content/recipients, or arbitrary attributes. {@code frames}, {@code types}, and
 * {@code causes} contain only bounded class/frame identifiers, and callers must apply the active exposure policy before
 * constructing request details.
 */
public record DurableJournalDetail(
        DurableJournalScope scope,
        long journalSequence,
        DurableJournalDetailKind kind,
        int version,
        String exceptionGroupId,
        String exceptionClass,
        String signature,
        List<String> types,
        List<String> applicationFrames,
        List<String> causes,
        long occurrences,
        String requestMethod,
        String routeTemplate,
        String operation,
        Integer status) {

    public static final int CURRENT_VERSION = 1;
    public static final int MAX_TEXT_LENGTH = 512;
    public static final int MAX_ITEMS = 32;

    public DurableJournalDetail {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        if (journalSequence < 0) {
            throw new IllegalArgumentException("journalSequence must not be negative");
        }
        if (version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported durable journal detail version: " + version);
        }
        exceptionGroupId = bounded("exceptionGroupId", exceptionGroupId);
        exceptionClass = bounded("exceptionClass", exceptionClass);
        signature = bounded("signature", signature);
        types = strings("types", types);
        applicationFrames = strings("applicationFrames", applicationFrames);
        causes = strings("causes", causes);
        requestMethod = bounded("requestMethod", requestMethod);
        routeTemplate = bounded("routeTemplate", routeTemplate);
        operation = bounded("operation", operation);
        if (kind == DurableJournalDetailKind.EXCEPTION) {
            if (exceptionClass == null || occurrences < 1) {
                throw new IllegalArgumentException(
                        "Exception detail requires exceptionClass and a positive occurrences value");
            }
            requestMethod = requireNull(requestMethod, "requestMethod");
            routeTemplate = requireNull(routeTemplate, "routeTemplate");
            operation = requireNull(operation, "operation");
            if (status != null) {
                throw new IllegalArgumentException("status is not part of exception detail");
            }
        } else {
            if (occurrences != 0
                    || exceptionGroupId != null
                    || exceptionClass != null
                    || signature != null
                    || !types.isEmpty()
                    || !applicationFrames.isEmpty()
                    || !causes.isEmpty()) {
                throw new IllegalArgumentException("Exception fields are not part of request detail");
            }
        }
    }

    public static DurableJournalDetail exception(
            DurableJournalScope scope,
            long journalSequence,
            String groupId,
            String exceptionClass,
            String signature,
            List<String> types,
            List<String> applicationFrames,
            List<String> causes,
            long occurrences) {
        return new DurableJournalDetail(
                scope,
                journalSequence,
                DurableJournalDetailKind.EXCEPTION,
                CURRENT_VERSION,
                groupId,
                exceptionClass,
                signature,
                types,
                applicationFrames,
                causes,
                occurrences,
                null,
                null,
                null,
                null);
    }

    public static DurableJournalDetail request(
            DurableJournalScope scope,
            long journalSequence,
            String method,
            String routeTemplate,
            String operation,
            Integer status) {
        return new DurableJournalDetail(
                scope,
                journalSequence,
                DurableJournalDetailKind.REQUEST,
                CURRENT_VERSION,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                0,
                method,
                routeTemplate,
                operation,
                status);
    }

    private static List<String> strings(String field, List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (values.size() > MAX_ITEMS) {
            throw new IllegalArgumentException(field + " has more than " + MAX_ITEMS + " items");
        }
        return List.copyOf(values.stream().map(value -> bounded(field, value)).toList());
    }

    private static String bounded(String field, String value) {
        if (value != null && value.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException(field + " exceeds " + MAX_TEXT_LENGTH + " characters");
        }
        return value;
    }

    private static String requireNull(String value, String field) {
        if (value != null) {
            throw new IllegalArgumentException(field + " is not part of exception detail");
        }
        return null;
    }
}
