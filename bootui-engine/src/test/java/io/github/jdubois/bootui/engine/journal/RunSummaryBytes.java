package io.github.jdubois.bootui.engine.journal;

import io.github.jdubois.bootui.engine.correlation.RunIdentity;

/** Tests only: a run summary holding {@code sideEffects}, as the run history writes it to disk. */
public final class RunSummaryBytes {

    private RunSummaryBytes() {}

    public static byte[] encode(RunSideEffects sideEffects) {
        return RunSummaryCodec.encode(
                RunSummary.of(RunIdentity.start(), new JournalAggregates().snapshot(), null, sideEffects, 2),
                RunHistory.MAX_SUMMARY_BYTES);
    }
}
