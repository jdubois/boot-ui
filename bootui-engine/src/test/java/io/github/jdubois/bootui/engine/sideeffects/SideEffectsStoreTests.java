package io.github.jdubois.bootui.engine.sideeffects;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.SideEffectsRowDto;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SideEffectsStoreTests {

    private static final long NOW = 1_000_000L;

    @Test
    void aSensorPastItsCapCountsTheRestInItsOtherRowWhichSortsLast() {
        SideEffectsStore store = new SideEffectsStore(0L);
        for (int i = 0; i < SideEffectsStore.MAX_ROWS_PER_SENSOR + 10; i++) {
            store.add(observation(0L, "tool-" + i, 1));
        }

        List<SideEffectsRowDto> rows = store.rows("processes", true, true);

        assertThat(rows).hasSize(SideEffectsStore.MAX_ROWS_PER_SENSOR + 1);
        assertThat(rows.get(rows.size() - 1).scope()).isEqualTo(SideEffectsRowDto.OTHER);
        assertThat(rows.get(rows.size() - 1).count()).isEqualTo(10L);
        assertThat(store.folded()).isEqualTo(10L);
        assertThat(store.rowCount("processes")).isEqualTo(SideEffectsStore.MAX_ROWS_PER_SENSOR + 1L);
    }

    @Test
    void anExitAddsToItsStartsRowWithItsStatusAndLifetime() {
        SideEffectsStore store = new SideEffectsStore(0L);
        store.add(observation(0L, "git", 1));
        store.add(new SideEffectsStore.Observation(
                record(SideEffectsCatalog.KIND_PROCESS_EXIT, SideEffectsCatalog.OUTCOME_EXITED, 0L, 3, 5_000_000L),
                "processes",
                "process",
                "git",
                "com.example.Reports#export",
                null,
                "main"));

        SideEffectsRowDto row = store.rows("processes", true, true).get(0);

        assertThat(row.count()).isEqualTo(1L);
        assertThat(row.completed()).isEqualTo(1L);
        assertThat(row.nonZeroExits()).isEqualTo(1L);
        assertThat(row.lastExitStatus()).isEqualTo(3);
        assertThat(row.totalMillis()).isEqualTo(5L);
    }

    @Test
    void waitingObservationsAreBoundedAndDroppedPastTheBound() {
        SideEffectsStore store = new SideEffectsStore(0L);
        for (int i = 0; i < SideEffectsStore.MAX_PENDING + 3; i++) {
            store.add(observation(0xabL, "tool", 1));
        }

        assertThat(store.pendingCount()).isEqualTo(SideEffectsStore.MAX_PENDING);
        assertThat(store.dropped("processes")).isEqualTo(3L);

        store.resolve(Map.of("00000000000000ab", "GET /x"), NOW);

        assertThat(store.pendingCount()).isZero();
        assertThat(store.rows("processes", true, true)).singleElement().satisfies(row -> {
            assertThat(row.count()).isEqualTo(SideEffectsStore.MAX_PENDING);
            assertThat(row.exemplarRequestIds()).containsExactly("00000000000000ab");
        });
    }

    @Test
    void clearRecordingDropsRowsAndWaitingObservations() {
        SideEffectsStore store = new SideEffectsStore(0L);
        store.add(observation(0L, "git", 1));
        store.add(observation(0xabL, "git", 1));

        assertThat(store.rowCount()).isEqualTo(1);
        assertThat(store.pendingCount()).isEqualTo(1);
        assertThat(store.retainedBytes()).isPositive().isLessThanOrEqualTo(store.maxBytes());
        store.clear();

        assertThat(store.rowCount()).isZero();
        assertThat(store.pendingCount()).isZero();
        assertThat(store.retainedBytes()).isZero();
        assertThat(store.rows("processes", true, true)).isEmpty();
    }

    @Test
    void whileCodePathsIsHiddenRowsLoseTheirBeanMethodAndMergeWithoutItAndTheStoreKeepsIt() {
        SideEffectsStore store = new SideEffectsStore(0L);
        for (String inside : new String[] {"Reports.export", "Reports.preview"}) {
            store.add(new SideEffectsStore.Observation(
                    new SideEffectRecord(1, 1, 1L, NOW, NOW, 0L, 0L, 5L, 1, 1, 1, 0, 0, 0, 1L, 10L, 10L, 0, 0),
                    "processes",
                    "process",
                    "git",
                    "com.example.Reports#run",
                    inside,
                    "main"));
        }

        assertThat(store.rows("processes", true, false)).singleElement().satisfies(row -> {
            assertThat(row.insideMethod()).isNull();
            assertThat(row.count()).isEqualTo(2L);
        });
        assertThat(store.rows("processes", true, true)).hasSize(2);
    }

    private static SideEffectsStore.Observation observation(long request, String target, long count) {
        return new SideEffectsStore.Observation(
                new SideEffectRecord(1, 1, 1L, NOW, NOW, request, 0L, 0L, 1, 2, 1, 0, 0, 0, count, 10L, 10L, 0, 0),
                "processes",
                "process",
                target,
                "com.example.Reports#export",
                null,
                "main");
    }

    private static SideEffectRecord record(int kind, int outcome, long request, int exit, long nanos) {
        return new SideEffectRecord(
                1, kind, 1L, NOW, NOW, request, 0L, 0L, 1, outcome, 1, 0, 0, exit, 1L, nanos, nanos, 0, 0);
    }
}
