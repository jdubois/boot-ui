package io.github.jdubois.bootui.engine.model;

import io.github.jdubois.bootui.engine.journal.JournalStatus;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The current run's {@link RuntimeModel} ({@code docs/PLAN-v2.md} §5.4), shared by every adapter. It reads the
 * application's structure once per run, so a model never joins one run's events with another run's routes or beans,
 * and projects the model on read, cached until the journal records more.
 */
public final class RuntimeModelService {

    private final RuntimeJournal journal;
    private final Supplier<RouteTemplateResolver> routes;
    private final Function<String, StructureSnapshot> structure;
    private StructureSnapshot snapshot;
    private String snapshotRun;
    private RuntimeModel cached;
    private long cachedWatermark = Long.MIN_VALUE;
    private long cachedEvicted = Long.MIN_VALUE;
    private long cachedClears = Long.MIN_VALUE;

    /**
     * @param journal the journal, or {@code null} when the adapter created none
     * @param routes the application's declared routes, or {@code null}
     * @param structure reads the application's structure for a run id, or {@code null} for none
     */
    public RuntimeModelService(
            RuntimeJournal journal,
            Supplier<RouteTemplateResolver> routes,
            Function<String, StructureSnapshot> structure) {
        this.journal = journal;
        this.routes = routes == null ? RouteTemplateResolver::empty : routes;
        this.structure = structure == null ? StructureSnapshot::empty : structure;
    }

    /** The current run's model, or an empty partial model when the journal is disabled. */
    public synchronized RuntimeModel model() {
        if (journal == null || !journal.settings().enabled()) {
            return new RuntimeModelBuilder()
                    .build(null, List.of("The runtime journal is disabled: set bootui.runtime-journal.enabled=true."));
        }
        JournalStatus status = journal.status();
        long evicted = status.evictedByCount() + status.evictedByBytes();
        if (cached != null
                && cachedWatermark == status.lastSequence()
                && cachedEvicted == evicted
                && cachedClears == status.clears()) {
            return cached;
        }
        // An empty snapshot is read again, as providers can become available after the first read.
        if (snapshot == null
                || !status.runId().equals(snapshotRun)
                || (snapshot.routes().isEmpty() && snapshot.beans().isEmpty())) {
            snapshot = read(status.runId());
            snapshotRun = status.runId();
        }
        RouteTemplateResolver resolver;
        try {
            resolver = routes.get();
        } catch (RuntimeException ex) {
            resolver = RouteTemplateResolver.empty();
        }
        cached = RuntimeModelProjection.project(
                journal.entries(),
                resolver,
                snapshot,
                evicted,
                System::nanoTime,
                RuntimeModelProjection.READ_BUDGET_NANOS);
        cachedWatermark = status.lastSequence();
        cachedEvicted = evicted;
        cachedClears = status.clears();
        return cached;
    }

    /** The structure the current model was projected with, or an empty one before the first {@link #model()}. */
    public synchronized StructureSnapshot structure() {
        return snapshot == null ? StructureSnapshot.empty(null) : snapshot;
    }

    private StructureSnapshot read(String runId) {
        try {
            StructureSnapshot read = structure.apply(runId);
            return read == null ? StructureSnapshot.empty(runId) : read;
        } catch (RuntimeException ex) {
            return StructureSnapshot.empty(runId);
        }
    }
}
