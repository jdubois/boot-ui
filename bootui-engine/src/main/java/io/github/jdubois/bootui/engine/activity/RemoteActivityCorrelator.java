package io.github.jdubois.bootui.engine.activity;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.RemoteActivityEntryDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Looks up a request's Live Activity signals captured by <strong>other</strong> BootUI instances
 * sharing the same durable {@link ActivityStore} (the opt-in "Use the existing datasource" persistence
 * option, pointed at one physically shared table), correlated by the same W3C distributed-trace id
 * every {@link ActivityEntryDto#correlationId()} already carries.
 *
 * <p>This is deliberately a narrow, single-key lookup: it only ever runs for one already-resolved
 * request's own trace id, from that request's per-request profile drill-down (an explicit user action
 * opening one request's detail) — never a general cross-instance browse of the shared table. Because of
 * that narrow scope, a shared trace id is trusted at face value here: it is effectively globally unique
 * per distributed request, unlike {@code TraceCorrelationIndex}'s deliberately conservative "ambiguous
 * if shared" guard, which exists to protect a <em>list-level</em> feed of many locally captured
 * exchanges (some of which may legitimately reuse a trace id, e.g. retries) from misattribution.</p>
 */
public final class RemoteActivityCorrelator {

    /**
     * Upper bound on remote entries returned for one request, so a pathological shared table (e.g. a
     * reused/collided trace id) can't balloon a single profile response.
     */
    static final int MAX_REMOTE_ENTRIES = 100;

    private RemoteActivityCorrelator() {}

    /**
     * @param store the (possibly {@link SwitchableActivityStore}-wrapped) activity store to query, or
     *     {@code null} to skip the lookup entirely (e.g. persistence disabled)
     * @param correlationId the request's own distributed-trace id, or {@code null}/blank to skip
     * @param ownInstanceId this instance's own {@code instanceId} (see {@link
     *     ActivityPersistenceSettings#instanceId()}), excluded from the result since its entries are
     *     already surfaced through this instance's own local correlation
     * @return entries other instances captured under the same trace id, oldest-first, each with its
     *     originating {@code parentId} cleared (it refers to that instance's own local request id,
     *     meaningless here); empty when {@code store} or {@code correlationId} is absent, or no other
     *     instance recorded anything for this trace id
     */
    public static List<RemoteActivityEntryDto> forRequest(
            ActivityStore store, String correlationId, String ownInstanceId) {
        if (store == null || correlationId == null || correlationId.isBlank()) {
            return List.of();
        }
        List<StoredActivityEntry> rows = store.queryByCorrelationId(correlationId, MAX_REMOTE_ENTRIES);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<RemoteActivityEntryDto> result = new ArrayList<>();
        for (StoredActivityEntry stored : rows) {
            if (ownInstanceId != null && ownInstanceId.equals(stored.instanceId())) {
                continue;
            }
            result.add(new RemoteActivityEntryDto(stored.instanceId(), withoutParent(stored.entry())));
        }
        result.sort(Comparator.comparingLong(remote -> remote.entry().timestamp()));
        return result;
    }

    private static ActivityEntryDto withoutParent(ActivityEntryDto entry) {
        return new ActivityEntryDto(
                entry.id(),
                entry.type(),
                entry.timestamp(),
                entry.severity(),
                entry.summary(),
                entry.detail(),
                entry.durationMs(),
                entry.correlationId(),
                entry.method(),
                entry.path(),
                entry.status(),
                entry.thread(),
                entry.profileable(),
                null,
                entry.securedPrincipal(),
                entry.sqlNPlusOneSuspected());
    }
}
