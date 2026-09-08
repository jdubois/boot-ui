package io.github.jdubois.bootui.engine.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HeapClassHistogramEntryDto;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryEvaluationEvidenceTests {
    @Test
    void nonG1CollectorsAreInapplicableRatherThanMissingG1Evidence() {
        for (var names : List.of(
                List.of("Copy", "MarkSweepCompact"),
                List.of("PS Scavenge", "PS MarkSweep"),
                List.of("ZGC Minor Cycles", "ZGC Major Cycles"))) {
            var memory = new MemoryContext.MemoryData(
                    0, 0, 0, 0, 0, 0, List.of(), 0, 0, 0, -1, List.of(), names, null, null);
            var evaluation =
                    new G1FullGcFrequencyRule().evaluateWithEvidence(new MemoryContext(memory, null, null, null, null));
            assertThat(evaluation.result().status()).isEqualTo("SKIPPED");
            assertThat(evaluation.usable()).isFalse();
            assertThat(evaluation.requiredUnknown()).isFalse();
        }
        var missing = new G1FullGcFrequencyRule().evaluateWithEvidence(context(null));
        assertThat(missing.usable()).isFalse();
        assertThat(missing.requiredUnknown()).isTrue();
    }

    @Test
    void emptyAndIrrelevantHistogramTargetsNeverTurnPassIntoCompletedEvidence() {
        MemoryContext empty = context(new MemoryContext.HeapContentData(true, List.of(), 0, 0));
        MemoryEvaluation noObjects = new BigObjectsRule().evaluateWithEvidence(empty);
        assertThat(noObjects.result().status()).isEqualTo("PASS");
        assertThat(noObjects.usable()).isFalse();

        MemoryContext scalar = context(new MemoryContext.HeapContentData(
                true, List.of(new HeapClassHistogramEntryDto(1, "java.lang.String", 10, 100)), 10, 100));
        MemoryEvaluation noCollections = new CollectionBloatRule().evaluateWithEvidence(scalar);
        assertThat(noCollections.result().status()).isEqualTo("PASS");
        assertThat(noCollections.usable()).isFalse();
        assertThat(new BigObjectsRule().evaluateWithEvidence(scalar).usable()).isTrue();
    }

    @Test
    void realInfoIsRetainedAlongsideMissingObservationNoticesBeforeReportFiltering() {
        MemoryContext context = context(MemoryContext.HeapContentData.unavailable());
        MemoryEvaluation info = new ExcessiveLoadedClassesRule().evaluateWithEvidence(context);
        MemoryEvaluation missing = new BigObjectsRule().evaluateWithEvidence(context);
        assertThat(info.result().severity()).isEqualTo("INFO");
        assertThat(info.result().status()).isEqualTo("VIOLATION");
        assertThat(info.usable()).isTrue();
        assertThat(missing.result().status()).isEqualTo("SKIPPED");
        assertThat(missing.requiredUnknown()).isTrue();
        assertThat(missing.usable()).isFalse();
        var evidence = MemoryScanner.evidence(List.of(info, missing));
        assertThat(evidence.usable()).isTrue();
        assertThat(evidence.coverageComplete()).isFalse();
    }

    @Test
    void intentionalAbsenceIsDifferentFromMissingScalarEvidence() {
        MemoryContext context = context(MemoryContext.HeapContentData.unavailable());
        MemoryEvaluation notContainer = new MissingHeapSizingInContainerRule().evaluateWithEvidence(context);
        assertThat(notContainer.usable()).isFalse();
        assertThat(notContainer.requiredUnknown()).isFalse();
        MemoryEvaluation unknownHeap = new CompressedOopsCliffRule().evaluateWithEvidence(context);
        assertThat(unknownHeap.result().status()).isEqualTo("PASS");
        assertThat(unknownHeap.usable()).isFalse();
        assertThat(unknownHeap.requiredUnknown()).isTrue();
    }

    private static MemoryContext context(MemoryContext.HeapContentData histogram) {
        return new MemoryContext(null, null, histogram, new MemoryContext.ClassLoadingData(50_000, 50_000, 0), null);
    }
}
