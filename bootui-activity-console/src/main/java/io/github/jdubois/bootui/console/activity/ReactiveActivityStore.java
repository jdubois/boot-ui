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
     * Finds up to {@code limit} entries sharing {@code correlationId}, across every instance the console
     * has received data for (deliberately not scoped to a single {@code instanceId}), newest first. This
     * is what lets the console show one microservice-to-microservice call as a single correlated trace.
     * Returns an empty list for a blank/{@code null} {@code correlationId} or when nothing matches.
     */
    Mono<List<StoredActivityEntry>> queryByCorrelationId(String correlationId, int limit);

    /** Deletes {@code instanceId}'s own rows older than {@code olderThanEpochMillis}. */
    Mono<Void> prune(String instanceId, long olderThanEpochMillis);
}
