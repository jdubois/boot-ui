package io.github.jdubois.bootui.engine.journal;

/**
 * A backend-neutral Live Activity query over one durable run or the global archive.
 *
 * <p>Null scope dimensions mean all values. A run query names all three scope dimensions; a global query may still
 * filter by namespace, application, run, or instance.
 */
public record DurableJournalFeedQuery(
        String namespace,
        String application,
        String run,
        String instanceId,
        String source,
        String type,
        String severity,
        String text,
        String route,
        String requestId,
        Long since,
        Long until,
        boolean noRequest,
        DurableJournalGlobalCursor cursor,
        int pageSize) {

    public DurableJournalFeedQuery {
        namespace = optional("namespace", namespace, DurableJournalScope.MAX_NAMESPACE_LENGTH);
        application = optional("application", application, DurableJournalScope.MAX_APPLICATION_LENGTH);
        run = optional("run", run, DurableJournalScope.MAX_RUN_LENGTH);
        instanceId = optional("instanceId", instanceId, DurableJournalRun.MAX_INSTANCE_ID_LENGTH);
        source = optional("source", source, DurableJournalStore.MAX_PROJECTION_LENGTH);
        type = optional("type", type, DurableJournalStore.MAX_PROJECTION_LENGTH);
        severity = optional("severity", severity, DurableJournalStore.MAX_PROJECTION_LENGTH);
        text = optional("text", text, DurableJournalStore.MAX_TEXT_PROJECTION_LENGTH);
        route = optional("route", route, DurableJournalStore.MAX_TEXT_PROJECTION_LENGTH);
        requestId = optional("requestId", requestId, DurableJournalStore.MAX_PROJECTION_LENGTH);
        pageSize = pageSize <= 0
                ? DurableJournalQuery.DEFAULT_PAGE_SIZE
                : Math.min(pageSize, DurableJournalQuery.MAX_PAGE_SIZE);
    }

    public DurableJournalFeedQuery(
            String namespace,
            String application,
            String run,
            String instanceId,
            String source,
            String type,
            String severity,
            String text,
            String route,
            String requestId,
            DurableJournalGlobalCursor cursor,
            int pageSize) {
        this(
                namespace,
                application,
                run,
                instanceId,
                source,
                type,
                severity,
                text,
                route,
                requestId,
                null,
                null,
                false,
                cursor,
                pageSize);
    }

    public DurableJournalFeedQuery(String namespace, String application, String run, int pageSize) {
        this(namespace, application, run, null, null, null, null, null, null, null, null, pageSize);
    }

    public static DurableJournalFeedQuery firstPage(DurableJournalScope scope) {
        return firstPage(scope, DurableJournalQuery.DEFAULT_PAGE_SIZE);
    }

    public static DurableJournalFeedQuery firstPage(DurableJournalScope scope, int pageSize) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        return new DurableJournalFeedQuery(scope.namespace(), scope.application(), scope.run(), pageSize);
    }

    public static DurableJournalFeedQuery globalFirstPage() {
        return globalFirstPage(DurableJournalQuery.DEFAULT_PAGE_SIZE);
    }

    public static DurableJournalFeedQuery globalFirstPage(int pageSize) {
        return new DurableJournalFeedQuery(null, null, null, pageSize);
    }

    public DurableJournalFeedQuery withCursor(DurableJournalGlobalCursor newCursor) {
        return new DurableJournalFeedQuery(
                namespace,
                application,
                run,
                instanceId,
                source,
                type,
                severity,
                text,
                route,
                requestId,
                since,
                until,
                noRequest,
                newCursor,
                pageSize);
    }

    public DurableJournalFeedQuery withCursor(String encodedCursor) {
        return withCursor(DurableJournalGlobalCursor.decode(encodedCursor));
    }

    private static String optional(String field, String value, int maximum) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds " + maximum + " characters");
        }
        return value;
    }
}
