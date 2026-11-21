package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.support.UriMasking;
import java.util.Objects;

/**
 * A fail-closed disk view of one journal entry.
 *
 * <p>The durable journal format deliberately leaves write-time exposure to its caller. This boundary reconstructs
 * that caller's entry under {@link JournalTextExposure#masked()} while retaining the envelope and evidence Runtime
 * Insights needs. In-memory-only throwable identities are withheld. Unknown payload implementations are rejected
 * rather than passed through by reference, so a payload added to the journal cannot reach disk before this versioned
 * boundary has explicitly handled it.
 */
final class JournalEntryDiskView {

    private static final String AUTHORITY_PREFIX = "http://";
    private static final JournalTextExposure EXPOSURE = JournalTextExposure.masked();

    private JournalEntryDiskView() {}

    /** The reconstructed, masked form of {@code entry}; never the original entry or payload. */
    static JournalEntry of(JournalEntry entry) {
        if (entry == null || entry.event() == null) {
            throw new IllegalArgumentException("A journal entry and its event must not be null");
        }
        RuntimeEvent event = entry.event();
        RuntimeEvent safeEvent = new RuntimeEvent(
                event.source(),
                event.epochMillis(),
                event.durationNanos(),
                event.requestId(),
                event.executionId(),
                event.traceId(),
                event.thread(),
                event.threadKind(),
                event.failedOrSlow(),
                payload(event.payload()));
        return new JournalEntry(entry.sequence(), safeEvent, entry.estimatedBytes());
    }

    /**
     * Applies the same fail-closed disk exposure to a projection derived from {@code entry}.
     *
     * <p>The journal never invents principal evidence and the durable projection deliberately keeps that field empty.
     * The caller still owns structural validation; this method only normalizes durable text exposure.
     */
    static ActivityEntryDto activity(JournalEntry entry, ActivityEntryDto activity) {
        if (activity == null) {
            return null;
        }
        Objects.requireNonNull(entry.event(), "entry.event must not be null");
        String safePath = path(activity.path());
        String summary = JournalActivityFeed.TYPE_SQL.equals(activity.type())
                ? EXPOSURE.sql(activity.summary())
                : EXPOSURE.message(activity.summary());
        if (safePath != null && activity.path() != null) {
            summary = summary == null ? null : summary.replace(activity.path(), safePath);
        }
        String detail = activity.detail();
        if (detail != null) {
            detail = JournalActivityFeed.TYPE_REQUEST.equals(activity.type()) ? path(detail) : EXPOSURE.message(detail);
        }
        if (summary != null && summary.length() > DurableJournalStore.MAX_TEXT_PROJECTION_LENGTH) {
            summary = summary.substring(0, DurableJournalStore.MAX_TEXT_PROJECTION_LENGTH);
        }
        if (detail != null && detail.length() > DurableJournalStore.MAX_TEXT_PROJECTION_LENGTH) {
            detail = detail.substring(0, DurableJournalStore.MAX_TEXT_PROJECTION_LENGTH);
        }
        return new ActivityEntryDto(
                activity.id(),
                activity.type(),
                activity.timestamp(),
                activity.severity(),
                summary,
                detail,
                activity.durationMs(),
                activity.correlationId(),
                activity.method(),
                safePath,
                activity.status(),
                activity.thread(),
                activity.profileable(),
                activity.parentId(),
                null,
                activity.sqlNPlusOneSuspected(),
                activity.badges(),
                activity.exceptionGroupId());
    }

    private static RuntimeEventPayload payload(RuntimeEventPayload payload) {
        if (payload == null) {
            return null;
        }
        if (payload instanceof HttpPayload p) {
            return new HttpPayload(
                    p.method(),
                    path(p.path()),
                    path(p.routeTemplate()),
                    message(p.operation()),
                    p.status(),
                    p.resources(),
                    p.timing(),
                    p.asyncStarted());
        }
        if (payload instanceof SqlPayload p) {
            return new SqlPayload(
                    sql(p.sql()),
                    p.callSite(),
                    p.dataSource(),
                    p.failed(),
                    p.frames(),
                    p.phase(),
                    p.completedNanos(),
                    p.codePathStamp(),
                    p.provenance(),
                    p.batchSize(),
                    p.rowCount());
        }
        if (payload instanceof RestClientPayload p) {
            return new RestClientPayload(
                    p.method(),
                    authority(p.authority()),
                    path(p.path()),
                    p.status(),
                    p.clientType(),
                    p.failed(),
                    p.frames(),
                    p.completedNanos(),
                    p.codePathStamp());
        }
        if (payload instanceof ExceptionPayload p) {
            return new ExceptionPayload(p.groupId(), p.exceptionClass(), p.signature(), p.types(), null, p.logged());
        }
        if (payload instanceof SecurityPayload p) {
            return new SecurityPayload(p.type());
        }
        if (payload instanceof CachePayload p) {
            return new CachePayload(p.cacheName(), p.operation(), p.frames(), p.codePathStamp());
        }
        if (payload instanceof ScheduledPayload p) {
            return new ScheduledPayload(p.task(), p.exceptionClass());
        }
        if (payload instanceof MessagingPayload p) {
            return new MessagingPayload(p.broker(), p.sent(), message(p.destination()), p.failed(), p.linkedTraceId());
        }
        if (payload instanceof TransactionPayload p) {
            return new TransactionPayload(
                    p.method(),
                    p.rolledBack(),
                    p.nested(),
                    p.savepoint(),
                    p.startNanos(),
                    p.readOnly(),
                    p.isolation(),
                    p.rollbackOnly(),
                    p.failureClass(),
                    p.ormStatements(),
                    p.ormFlushes(),
                    p.ormDirtyEntities(),
                    p.begun(),
                    p.boundaryKnown());
        }
        if (payload instanceof ConnectionPayload p) {
            return new ConnectionPayload(p.dataSource(), p.waitNanos(), p.statements(), p.checkoutNanos());
        }
        if (payload instanceof MailPayload p) {
            return new MailPayload(p.recipients(), p.attachments(), p.sent());
        }
        if (payload instanceof FaultTolerancePayload p) {
            return new FaultTolerancePayload(
                    p.policy(),
                    p.policyType(),
                    p.target(),
                    p.outcome(),
                    p.attempt(),
                    p.state(),
                    p.failureCategory(),
                    p.failure(),
                    p.protective());
        }
        if (payload instanceof AiPayload p) {
            return new AiPayload(
                    p.operation(),
                    p.provider(),
                    p.model(),
                    p.inputTokens(),
                    p.outputTokens(),
                    p.finishReason(),
                    p.failed(),
                    p.spanId(),
                    p.completedNanos(),
                    p.codePathStamp());
        }
        if (payload instanceof AuthorizationPayload p) {
            return new AuthorizationPayload(
                    p.target(),
                    p.subject(),
                    authorizationRule(p.rule()),
                    p.authentication(),
                    p.granted(),
                    p.authorities());
        }
        if (payload instanceof OrmPayload p) {
            return new OrmPayload(
                    p.persistenceUnit(),
                    p.statements(),
                    p.statementNanos(),
                    p.connectionAcquisitions(),
                    p.acquisitionNanos(),
                    p.flushes(),
                    p.flushNanos(),
                    p.partialFlushes(),
                    p.partialFlushNanos(),
                    p.dirtyEntities(),
                    p.entitiesInContext(),
                    p.l2Hits(),
                    p.l2Misses(),
                    p.l2Puts(),
                    p.flushTimeline());
        }
        if (payload instanceof GcPayload p) {
            return new GcPayload(
                    p.collector(),
                    p.gcId(),
                    p.action(),
                    p.cause(),
                    p.pause(),
                    p.heapBeforeBytes(),
                    p.heapAfterBytes(),
                    p.oldGenBeforeBytes(),
                    p.oldGenAfterBytes());
        }
        if (payload instanceof LogPayload p) {
            return new LogPayload(p.logger(), p.level(), message(p.template()), p.exceptionClass(), null);
        }
        if (payload instanceof AppEventPayload p) {
            return new AppEventPayload(
                    p.kind(),
                    p.eventType(),
                    p.listener(),
                    p.phase(),
                    p.outcome(),
                    p.exceptionClass(),
                    p.listeners(),
                    p.startNanos());
        }
        if (payload instanceof LifecyclePayload p) {
            return new LifecyclePayload(p.kind(), p.target(), p.runStart());
        }
        if (payload instanceof WebSocketPayload p) {
            return new WebSocketPayload(
                    path(p.endpoint()),
                    p.kind(),
                    p.inbound(),
                    path(p.destination()),
                    p.payloadBytes(),
                    message(p.closeStatus()),
                    p.failed());
        }
        if (payload instanceof AsyncHandoffPayload p) {
            return new AsyncHandoffPayload(
                    p.executionId(),
                    p.parentExecutionId(),
                    p.taskClass(),
                    p.hook(),
                    p.submittedEpochMillis(),
                    p.queuedNanos(),
                    p.allocatedBytes(),
                    p.failed(),
                    p.exceptionClass(),
                    p.afterResponse(),
                    p.afterResponseMicros(),
                    p.capped(),
                    p.bodyAfterResponse(),
                    p.bodyAfterResponseMicros(),
                    p.responseAtMicros(),
                    p.failureAfterResponse());
        }
        if (payload instanceof CaughtExceptionPayload p) {
            return new CaughtExceptionPayload(
                    p.kind(),
                    p.siteClass(),
                    p.siteMethod(),
                    p.line(),
                    p.declaredTypes(),
                    p.siteFlags(),
                    p.exceptionClass(),
                    p.family(),
                    p.count(),
                    p.foundBy(),
                    p.foundAt(),
                    0);
        }
        throw new IllegalArgumentException(
                "Unsupported journal payload class: " + payload.getClass().getName());
    }

    private static String path(String value) {
        return EXPOSURE.path(value);
    }

    private static String authority(String value) {
        if (value == null) {
            return null;
        }
        return UriMasking.maskUserInfo(AUTHORITY_PREFIX + value).substring(AUTHORITY_PREFIX.length());
    }

    private static String sql(String value) {
        return value == null ? null : EXPOSURE.sql(value);
    }

    private static String message(String value) {
        return EXPOSURE.message(value);
    }

    private static String authorizationRule(String value) {
        return value == null || value.isBlank() ? value : EXPOSURE.authorizationRule(value);
    }
}
