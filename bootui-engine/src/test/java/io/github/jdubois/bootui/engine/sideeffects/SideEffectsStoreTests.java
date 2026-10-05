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

    @Test
    void aBlockingConnectAndANonBlockingConnectsFinishCountAttemptsEstablishedAndTimeInOneRow() {
        SideEffectsStore store = new SideEffectsStore(0L);
        store.add(network(SideEffectsCatalog.KIND_CONNECT, SideEffectsCatalog.OUTCOME_CONNECTED, 0L, 3_000_000L, null));
        store.add(network(SideEffectsCatalog.KIND_CONNECT, SideEffectsCatalog.OUTCOME_PENDING, 0L, 0L, null));
        store.add(network(
                SideEffectsCatalog.KIND_CONNECT_FINISH, SideEffectsCatalog.OUTCOME_CONNECTED, 0L, 5_000_000L, null));
        store.add(network(SideEffectsCatalog.KIND_CONNECT, SideEffectsCatalog.OUTCOME_IO_ERROR, 0L, 1_000_000L, null));

        SideEffectsRowDto row = store.rows("network", true, true).get(0);

        assertThat(row.kind()).isEqualTo("connect");
        assertThat(row.count()).isEqualTo(3L);
        assertThat(row.completed()).isEqualTo(2L);
        assertThat(row.failed()).isEqualTo(1L);
        assertThat(row.totalMillis()).isEqualTo(9L);
        assertThat(row.maxMillis()).isEqualTo(5L);
        assertThat(row.client()).isEqualTo("Lettuce");
    }

    @Test
    void anUnownedConnectWaitsForARestClientCallAndIsCapturedWhenOneNamesIt() {
        SideEffectsStore store = new SideEffectsStore(0L);
        boolean[] matched = {false};
        store.setCapture(capture(matched));
        store.add(network(
                SideEffectsCatalog.KIND_CONNECT,
                SideEffectsCatalog.OUTCOME_CONNECTED,
                0L,
                1L,
                SideEffectsStore.REST_WAITING));

        store.resolve(Map.of(), NOW + 1_000L);
        assertThat(store.rows("network", true, true)).isEmpty();
        assertThat(store.pendingCount()).isEqualTo(1);

        matched[0] = true;
        store.resolve(Map.of(), NOW + 2_000L);

        assertThat(store.rows("network", true, true, captured()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.scope()).isEqualTo(SideEffectsRowDto.THREAD);
                    assertThat(row.capture()).isEqualTo(SideEffectsRowDto.CAPTURED);
                    assertThat(row.capturedBy()).isEqualTo("rest-client-trace");
                });
    }

    @Test
    void anUnownedConnectNoCallNamesIsNotCapturedOnceItWaitedLongEnough() {
        SideEffectsStore store = new SideEffectsStore(0L);
        store.setCapture(capture(new boolean[] {false}));
        store.add(network(
                SideEffectsCatalog.KIND_CONNECT,
                SideEffectsCatalog.OUTCOME_CONNECTED,
                0L,
                1L,
                SideEffectsStore.REST_WAITING));

        store.resolve(Map.of(), NOW + SideEffectsStore.UNOWNED_CAPTURE_MILLIS);

        assertThat(store.rows("network", true, true, captured()))
                .singleElement()
                .satisfies(row -> assertThat(row.capture()).isEqualTo(SideEffectsRowDto.NOT_CAPTURED));
    }

    @Test
    void aRequestsConnectWaitsForItsRouteThenItsGraceBeforeItIsDecided() {
        SideEffectsStore store = new SideEffectsStore(0L);
        boolean[] matched = {false};
        store.setCapture(capture(matched));
        store.add(network(
                SideEffectsCatalog.KIND_CONNECT,
                SideEffectsCatalog.OUTCOME_CONNECTED,
                0xabL,
                1L,
                SideEffectsStore.REST_WAITING));

        store.resolve(Map.of("00000000000000ab", "GET /sdk"), NOW + 500L);
        assertThat(store.rows("network", true, true)).as("within its grace").isEmpty();

        store.resolve(Map.of(), NOW + SideEffectsStore.CAPTURE_GRACE_MILLIS);

        assertThat(store.rows("network", true, true, captured()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.attribution()).isEqualTo("GET /sdk");
                    assertThat(row.capture()).isEqualTo(SideEffectsRowDto.NOT_CAPTURED);
                    assertThat(row.exemplarRequestIds()).containsExactly("00000000000000ab");
                });
        assertThat(store.opened("network", true)).singleElement().satisfies(opened -> {
            assertThat(opened.attribution()).isEqualTo("GET /sdk");
            assertThat(opened.target()).isEqualTo("localhost:6379");
        });
        assertThat(store.opened("network", false))
                .as("route rows hidden with HTTP Exchanges")
                .isEmpty();
    }

    @Test
    void aSqlClientsConnectIsKeyedByItsCategoryAndDecidedOnRead() {
        SideEffectsStore store = new SideEffectsStore(0L);
        store.add(network(
                SideEffectsCatalog.KIND_CONNECT,
                SideEffectsCatalog.OUTCOME_CONNECTED,
                0L,
                1L,
                SideEffectsStore.CAPTURE_SQL));

        assertThat(store.rows("network", true, true, key -> new String[] {"captured", "sql-trace"}))
                .singleElement()
                .satisfies(row -> assertThat(row.capturedBy()).isEqualTo("sql-trace"));
        assertThat(store.rows("network", true, true, key -> new String[] {"not-captured", null}))
                .singleElement()
                .satisfies(row -> assertThat(row.capture()).isEqualTo("not-captured"));
    }

    private static NetworkCapture capture(boolean[] matched) {
        return new NetworkCapture() {
            @Override
            public void refresh() {}

            @Override
            public boolean restClient(
                    String host, int port, String requestId, String executionId, long first, long last) {
                return matched[0] && "localhost".equals(host) && port == 6379;
            }

            @Override
            public boolean sql() {
                return false;
            }

            @Override
            public boolean messaging(String broker) {
                return false;
            }

            @Override
            public boolean mail() {
                return false;
            }
        };
    }

    private static java.util.function.Function<String, String[]> captured() {
        return key -> SideEffectsStore.REST_CAPTURED.equals(key)
                ? new String[] {SideEffectsRowDto.CAPTURED, "rest-client-trace"}
                : new String[] {SideEffectsRowDto.NOT_CAPTURED, null};
    }

    private static SideEffectsStore.Observation network(
            int kind, int outcome, long request, long nanos, String captureKey) {
        return new SideEffectsStore.Observation(
                new SideEffectRecord(
                        SideEffectsCatalog.RECORD_NETWORK,
                        kind,
                        1L,
                        NOW,
                        NOW,
                        request,
                        0L,
                        0L,
                        1,
                        outcome,
                        1,
                        0,
                        0,
                        0,
                        1L,
                        nanos,
                        nanos,
                        0,
                        0),
                "network",
                SideEffectsCatalog.kind(SideEffectsCatalog.RECORD_NETWORK, kind),
                "localhost:6379",
                "com.example.Cache#get",
                null,
                "lettuce-nioEventLoop-{n}-{n}",
                "Lettuce",
                captureKey,
                "localhost",
                6379);
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
