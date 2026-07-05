package io.github.jdubois.bootui.console.activity;

import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.ActivityStoreException;
import io.github.jdubois.bootui.engine.activity.StoredActivityEntry;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Non-blocking counterpart to {@code io.github.jdubois.bootui.engine.activity.ActivityStore}, for the
 * BootUI Activity Console (a Spring WebFlux application): every method returns a cold {@link Mono} that
 * completes without ever blocking the calling thread, so it is safe to subscribe to from a Netty
 * event-loop thread.
 *
 * <p>This lives outside {@code bootui-engine} on purpose: the engine is framework-neutral (no Spring, no
 * blocking-vs-reactive opinion at all), and the console's reactive database access is genuinely
 * Spring-specific (Spring Data R2DBC's {@code DatabaseClient}), not a neutral SPI the engine could depend
 * on. The console reuses the engine's plain-data records ({@link StoredActivityEntry}, {@link
 * ActivityQuery}, {@link ActivityPage}, {@link ActivityStoreException}) unchanged, since those carry no
 * blocking/reactive behavior of their own and are exactly the shapes the console needs.
 *
 * <p>Method semantics mirror {@code ActivityStore} exactly (same filter rules, same pagination cursor
 * contract, same {@code instanceId} multi-tenant scoping); only the return type differs. Every
 * implementation must fail the returned {@link Mono} with an {@link ActivityStoreException} on error,
 * matching {@code ActivityStore}'s unchecked-throw contract.
 */
public interface ReactiveActivityStore {

    /**
     * Durably appends {@code entries}, or completes immediately (without touching storage) when {@code
     * entries} is {@code null} or empty. Entries are console-scoped by whichever {@code instanceId} each
     * one already carries (typically the sender that forwarded them), not a single console-wide id.
     */
    Mono<Void> appendBatch(List<StoredActivityEntry> entries);

    /** Runs {@code query} and returns the matching page (see {@link ActivityQuery} for filter semantics). */
    Mono<ActivityPage> query(ActivityQuery query);

    /**
     * Cross-instance variant of {@link #query(ActivityQuery)}: returns the merged, newest-first page
     * across <strong>every</strong> instance the console has received data for, applying every other
     * filter in {@code query} (type/severity/text/since/until/cursor/pageSize) but ignoring {@code
     * query.instanceId()} entirely (the field means nothing here; callers may pass any value, including
     * blank).
     *
     * <p>This is the console's primary read: unlike a host application's own panel (always scoped to its
     * one {@code instanceId}), the whole point of the Activity Console is a single aggregated feed when
     * several microservices call each other, so its main dashboard query has no instance to scope to.
     */
    Mono<ActivityPage> queryAllInstances(ActivityQuery query);

    /**
     * Finds up to {@code limit} entries sharing {@code correlationId}, across every instance the console
     * has received data for (deliberately not scoped to a single {@code instanceId}), newest first. This
     * is what lets the console show one microservice-to-microservice call as a single correlated trace.
     * Returns an empty list for a blank/{@code null} {@code correlationId} or when nothing matches.
     */
    Mono<List<StoredActivityEntry>> queryByCorrelationId(String correlationId, int limit);

    /**
     * Finds the single newest entry carrying {@code entryId} as {@link
     * io.github.jdubois.bootui.core.dto.ActivityEntryDto#id()}, across every instance the console has
     * received data for. Backs the console's per-request drill-down ({@code GET
     * /bootui/api/activity/request/{id}}): the shared Vue UI always requests a profile by the clicked
     * entry's own {@code id} (never its {@code correlationId}), so the drill-down endpoint must resolve
     * one back into the other before it can call {@link #queryByCorrelationId}.
     *
     * <p>An {@code entry_id} is only guaranteed unique <em>within</em> the instance that produced it
     * (typically a per-JVM counter or the underlying framework's own exchange id) &mdash; across two
     * independent instances forwarding to the same console, a collision is possible in principle. When
     * more than one row matches, this deliberately returns only the single newest one rather than
     * failing or disambiguating further: for the console's target scenario (a handful of local demo
     * instances), this is a documented, acceptable trade-off, not a correctness bug.
     *
     * <p>Returns an empty {@link Mono} (not an error) for a blank/{@code null} {@code entryId} or when
     * nothing matches.
     */
    Mono<StoredActivityEntry> findByEntryId(String entryId);

    /** Deletes {@code instanceId}'s own rows older than {@code olderThanEpochMillis}. */
    Mono<Void> prune(String instanceId, long olderThanEpochMillis);
}
