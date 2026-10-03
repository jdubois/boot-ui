package io.github.jdubois.bootui.engine.insights;

import io.github.jdubois.bootui.engine.journal.GcPayload;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.web.CorrelationTier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code heap-growth-after-gc} ({@code docs/PLAN-v2.md} §5.11): old-generation occupancy after the collections that
 * reclaimed old-generation space, full or mixed ones, rising across the run. What such a collection leaves behind is
 * what the application still holds, so a level that keeps rising is worth a heap histogram; it is never called a leak,
 * since a warming cache rises too before it levels off.
 *
 * <p>It needs {@value #MIN_COLLECTIONS} such collections. It is observed when the last one left at least {@value
 * #MIN_GROWTH_PERCENT} % and {@value #MIN_GROWTH_MIB} MiB more than the first, and occupancy rose in at least two thirds
 * of the steps between them.</p>
 */
public final class HeapGrowthAfterGc implements Observation {

    public static final String KIND = "heap-growth-after-gc";

    /** The collections that reclaimed old-generation space a trend needs. */
    static final int MIN_COLLECTIONS = 3;

    /** The growth from the first collection to the last, in percent, below which the level is said to hold. */
    static final int MIN_GROWTH_PERCENT = 10;

    /** The growth, in mebibytes, below which the level is said to hold whatever its share. */
    static final int MIN_GROWTH_MIB = 4;

    private static final long MIB = 1024 * 1024;

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String title() {
        return "Heap growth after GC";
    }

    @Override
    public CorrelationTier minimumTier() {
        return CorrelationTier.REQUEST_ID;
    }

    @Override
    public Set<JournalSource> reads() {
        return Set.of(JournalSource.GC);
    }

    @Override
    public Set<ProjectedRequest.Kind> unitKinds() {
        return Set.of();
    }

    @Override
    public String notApplicable(InsightsSnapshot snapshot) {
        boolean any = false;
        for (RuntimeEvent event : snapshot.collections()) {
            GcPayload gc = (GcPayload) event.payload();
            if (gc.oldGenAfterBytes() >= 0) {
                return null;
            }
            any = true;
        }
        return any
                ? "This JVM's heap has no old generation BootUI recognizes, such as a single-generation ZGC or"
                        + " Shenandoah heap."
                : null;
    }

    @Override
    public Evaluation evaluate(InsightsSnapshot snapshot) {
        List<RuntimeEvent> reclaiming = new ArrayList<>();
        for (RuntimeEvent event : snapshot.collections()) {
            if (((GcPayload) event.payload()).reclaimedOldGeneration()) {
                reclaiming.add(event);
            }
        }
        if (reclaiming.isEmpty()) {
            return new Evaluation(0, List.of());
        }
        List<List<String>> rows = new ArrayList<>();
        for (int index = reclaiming.size() - 1; index >= 0; index--) {
            RuntimeEvent event = reclaiming.get(index);
            GcPayload gc = (GcPayload) event.payload();
            rows.add(List.of(
                    gc.collector() + " #" + gc.gcId(),
                    Instant.ofEpochMilli(event.epochMillis()).toString(),
                    gc.cause() == null ? "" : gc.cause(),
                    mebibytes(gc.oldGenAfterBytes()),
                    gc.heapAfterBytes() < 0 ? "" : mebibytes(gc.heapAfterBytes())));
        }
        List<String> columns = List.of("Collection", "Completed", "Cause", "Old gen after (MiB)", "Heap after (MiB)");
        List<String> limitations = List.of(
                "Counts the collections the journal still retains; older ones were evicted with their events.",
                "Young collections only promote into the old generation, so they are not counted.");
        if (reclaiming.size() < MIN_COLLECTIONS) {
            return new Evaluation(
                    0,
                    List.of(new Finding(
                            "heap",
                            "Heap",
                            false,
                            InsightText.counted(reclaiming.size(), "collection")
                                    + " reclaimed old-generation space in this run; a trend needs " + MIN_COLLECTIONS
                                    + ".",
                            0,
                            0,
                            List.of("Keep the application running under its usual load, then refresh."),
                            List.of(),
                            columns,
                            rows,
                            limitations)));
        }
        long first = ((GcPayload) reclaiming.get(0).payload()).oldGenAfterBytes();
        long last = ((GcPayload) reclaiming.get(reclaiming.size() - 1).payload()).oldGenAfterBytes();
        int steps = reclaiming.size() - 1;
        int rises = 0;
        for (int index = 1; index < reclaiming.size(); index++) {
            if (((GcPayload) reclaiming.get(index).payload()).oldGenAfterBytes()
                    > ((GcPayload) reclaiming.get(index - 1).payload()).oldGenAfterBytes()) {
                rises++;
            }
        }
        long growth = last - first;
        if (growth < MIN_GROWTH_MIB * MIB || growth * 100 < first * MIN_GROWTH_PERCENT || rises * 3 < steps * 2) {
            return new Evaluation(0, List.of());
        }
        String sentence = "Old-generation occupancy after the " + reclaiming.size()
                + " collections that reclaimed it rose from " + mebibytes(first) + " MiB to " + mebibytes(last)
                + " MiB" + (first > 0 ? " (+" + Math.round(100.0 * growth / first) + " %)" : "") + ", rising in "
                + rises + " of " + InsightText.counted(steps, "step") + ".";
        return new Evaluation(
                0,
                List.of(new Finding(
                        "heap",
                        "Heap",
                        true,
                        sentence,
                        0,
                        0,
                        List.of(
                                "Take two heap histograms in the Memory panel some time apart, and compare the classes"
                                        + " whose instances grew.",
                                "A cache or collection that only grows keeps rising; a warming cache levels off once"
                                        + " it is full, so check again after more load."),
                        List.of(),
                        columns,
                        rows,
                        limitations)));
    }

    private static String mebibytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (double) MIB);
    }
}
