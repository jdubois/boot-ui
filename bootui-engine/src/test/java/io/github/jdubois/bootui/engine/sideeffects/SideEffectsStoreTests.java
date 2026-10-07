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
    void blockingRowsCountInterruptedCallsAsFailedAndAddUpHowLongTheyBlocked() {
        SideEffectsStore store = new SideEffectsStore(0L);
        int[][] calls = {
            {SideEffectsCatalog.KIND_SLEEP, SideEffectsCatalog.OUTCOME_RETURNED, 20},
            {SideEffectsCatalog.KIND_SLEEP, SideEffectsCatalog.OUTCOME_INTERRUPTED, 5},
            {SideEffectsCatalog.KIND_BLOCKING_NETWORK, SideEffectsCatalog.OUTCOME_ERROR, 7}
        };
        for (int[] call : calls) {
            store.add(new SideEffectsStore.Observation(
                    new SideEffectRecord(
                            SideEffectsCatalog.RECORD_BLOCKING,
                            call[0],
                            1L,
                            NOW,
                            NOW,
                            0L,
                            0L,
                            0L,
                            1,
                            call[1],
                            1,
                            0,
                            0,
                            0,
                            1L,
                            call[2] * 1_000_000L,
                            call[2] * 1_000_000L,
                            0,
                            0),
                    "blocking",
                    SideEffectsCatalog.kind(SideEffectsCatalog.RECORD_BLOCKING, call[0]),
                    "reactor-http-nio-{n}",
                    "com.example.Handler#handle",
                    null,
                    "reactor-http-nio-{n}"));
        }

        List<SideEffectsRowDto> rows = store.rows("blocking", true, true);

        assertThat(rows).extracting(SideEffectsRowDto::kind).containsExactlyInAnyOrder("sleep", "network");
        SideEffectsRowDto sleep = rows.stream()
                .filter(row -> row.kind().equals("sleep"))
                .findFirst()
                .orElseThrow();
        assertThat(sleep.count()).isEqualTo(2L);
        assertThat(sleep.failed()).as("the interrupted sleep").isEqualTo(1L);
        assertThat(sleep.completed()).as("never a process exit").isZero();
        assertThat(sleep.totalMillis()).isEqualTo(25L);
        assertThat(sleep.maxMillis()).isEqualTo(20L);
        SideEffectsRowDto network = rows.stream()
                .filter(row -> row.kind().equals("network"))
                .findFirst()
                .orElseThrow();
        assertThat(network.failed()).isEqualTo(1L);
        assertThat(SideEffectsCatalog.kind(SideEffectsCatalog.RECORD_BLOCKING, SideEffectsCatalog.KIND_BLOCKING_FILE))
                .isEqualTo("file");
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

        store.resolve(Map.of(), NOW + 500L + SideEffectsStore.CAPTURE_GRACE_MILLIS);

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
    void aNonBlockingConnectsFinishIsDecidedAsItsConnectWasIntoTheSameRow() {
        SideEffectsStore store = new SideEffectsStore(0L);
        boolean[] matched = {false};
        store.setCapture(capture(matched));
        store.add(network(
                SideEffectsCatalog.KIND_CONNECT,
                SideEffectsCatalog.OUTCOME_PENDING,
                0L,
                0L,
                SideEffectsStore.REST_WAITING));
        store.resolve(Map.of(), NOW + SideEffectsStore.UNOWNED_CAPTURE_MILLIS);
        // A call recorded once the connect was decided not captured never splits its finish into another row.
        matched[0] = true;
        store.add(network(
                SideEffectsCatalog.KIND_CONNECT_FINISH,
                SideEffectsCatalog.OUTCOME_CONNECTED,
                0L,
                4_000_000L,
                SideEffectsStore.REST_WAITING));
        store.resolve(Map.of(), NOW + SideEffectsStore.UNOWNED_CAPTURE_MILLIS + 1);

        assertThat(store.rows("network", true, true, captured()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.capture()).isEqualTo(SideEffectsRowDto.NOT_CAPTURED);
                    assertThat(row.count()).isEqualTo(1L);
                    assertThat(row.completed()).isEqualTo(1L);
                    assertThat(row.totalMillis()).isEqualTo(4L);
                });
    }

    @Test
    void anUnownedHttpClientsConnectWaitsLongerForItsCallToBeRecorded() {
        SideEffectsStore store = new SideEffectsStore(0L);
        store.setCapture(capture(new boolean[] {false}));
        store.add(network(
                SideEffectsCatalog.KIND_CONNECT,
                SideEffectsCatalog.OUTCOME_CONNECTED,
                0L,
                1L,
                SideEffectsStore.REST_WAITING_HTTP));

        store.resolve(Map.of(), NOW + SideEffectsStore.UNOWNED_CAPTURE_MILLIS);
        assertThat(store.pendingCount()).isEqualTo(1);
        store.resolve(Map.of(), NOW + SideEffectsStore.UNOWNED_HTTP_CAPTURE_MILLIS);

        assertThat(store.rows("network", true, true, captured()))
                .singleElement()
                .satisfies(row -> assertThat(row.capture()).isEqualTo(SideEffectsRowDto.NOT_CAPTURED));
    }

    @Test
    void anExecutionNamedByAnEarlierEventWaitsForItsRestCallRecordedSecondsAfterTheConnect() {
        SideEffectsStore store = new SideEffectsStore(0L);
        boolean[] matched = {false};
        store.setCapture(capture(matched));
        store.add(new SideEffectsStore.Observation(
                new SideEffectRecord(
                        SideEffectsCatalog.RECORD_NETWORK,
                        SideEffectsCatalog.KIND_CONNECT,
                        1L,
                        NOW,
                        NOW,
                        0L,
                        0xefL,
                        0L,
                        1,
                        SideEffectsCatalog.OUTCOME_CONNECTED,
                        1,
                        SideEffectRecord.EXECUTION_OWN,
                        0,
                        0,
                        1L,
                        1L,
                        1L,
                        0,
                        0),
                "network",
                "connect",
                "localhost:6379",
                "com.example.Job#run",
                null,
                "scheduling-{n}",
                null,
                SideEffectsStore.REST_WAITING,
                "localhost",
                6379));
        String key = SideEffectsStore.EXECUTION_KEY + "00000000000000ef";

        // Its earlier SQL event already names the run; its REST call is recorded only 5 s after the connect.
        store.resolve(Map.of(key, "scheduled ReportJob.run"), NOW + 500L);
        store.resolve(Map.of(), NOW + SideEffectsStore.CAPTURE_GRACE_MILLIS + 1_000L);
        assertThat(store.rows("network", true, true))
                .as("still waiting for its call")
                .isEmpty();
        matched[0] = true;
        store.resolve(Map.of(), NOW + 5_000L);

        assertThat(store.rows("network", true, true, captured()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.attribution()).isEqualTo("scheduled ReportJob.run");
                    assertThat(row.capture()).isEqualTo(SideEffectsRowDto.CAPTURED);
                });
    }

    @Test
    void aRequestsGraceRunsFromWhenItWasNamedNotFromItsConnect() {
        SideEffectsStore store = new SideEffectsStore(0L);
        boolean[] matched = {false};
        store.setCapture(capture(matched));
        store.add(network(
                SideEffectsCatalog.KIND_CONNECT,
                SideEffectsCatalog.OUTCOME_CONNECTED,
                0xabL,
                1L,
                SideEffectsStore.REST_WAITING));

        // A long request: named 20 s after its connect, its call recorded a second after that.
        store.resolve(Map.of("00000000000000ab", "GET /slow"), NOW + 20_000L);
        assertThat(store.rows("network", true, true)).isEmpty();
        matched[0] = true;
        store.resolve(Map.of(), NOW + 21_000L);

        assertThat(store.rows("network", true, true, captured()))
                .singleElement()
                .satisfies(row -> assertThat(row.capture()).isEqualTo(SideEffectsRowDto.CAPTURED));
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

    /**
     * M5-5e: a thread-activity follow-up lands on its creation's row, even when the creation, made by a request longer
     * than the store waits, counted under the unknown route, and the request's route is named by the time it ends.
     */
    @Test
    void aThreadActivityFollowUpLandsOnItsCreationsRowEvenWhenTheRouteWasNamedLater() {
        SideEffectsStore store = new SideEffectsStore(0L);
        long request = 0x42L;
        store.add(threads(SideEffectsCatalog.KIND_EXECUTOR_CREATE, request, NOW));
        store.resolve(Map.of(), NOW + SideEffectsStore.PENDING_MILLIS);
        store.resolve(Map.of(String.format("%016x", request), "GET /stream"), NOW + SideEffectsStore.PENDING_MILLIS);
        store.add(threads(SideEffectsCatalog.KIND_EXECUTOR_LEFT_RUNNING, request, NOW));
        store.add(threads(SideEffectsCatalog.KIND_EXECUTOR_SHUTDOWN, request, NOW));

        List<SideEffectsRowDto> rows = store.rows("thread-activity", true, true);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.attribution()).isEqualTo(SideEffectsStore.UNKNOWN_ROUTE);
            assertThat(row.count()).isEqualTo(1L);
            assertThat(row.requests()).isEqualTo(1L);
            assertThat(row.leftRunning()).isEqualTo(1L);
            assertThat(row.completed()).isEqualTo(1L);
        });
    }

    /**
     * M5-5g: a resource's reports land on one row: counted once, open after its request and closed after it apart from
     * reclaimed without close(), the only one a run comparison keys on.
     */
    @Test
    void aResourcesReportsLandOnOneRowCountedOnceAndOnlyAReclaimIsAKey() {
        SideEffectsStore store = new SideEffectsStore(0L);
        String request = String.format("%016x", 0x51L);
        store.resolve(Map.of(request, "GET /reports"), NOW);
        store.add(resources(SideEffectsCatalog.KIND_RESOURCE_LEFT_OPEN, 0x51L, true, "socket", "localhost:5432"));
        store.add(resources(SideEffectsCatalog.KIND_RESOURCE_CLOSED_LATE, 0x51L, false, "socket", "localhost:5432"));
        store.add(resources(
                SideEffectsCatalog.KIND_RESOURCE_RECLAIMED, 0x51L, true, "file input stream", "./reports/r-{n}.csv"));

        List<SideEffectsRowDto> rows = store.rows("resources", true, true);
        assertThat(rows).hasSize(2);
        assertThat(rows)
                .filteredOn(row -> row.kind().equals("socket"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.count()).isEqualTo(1L);
                    assertThat(row.requests()).isEqualTo(1L);
                    assertThat(row.leftRunning()).isEqualTo(1L);
                    assertThat(row.completed()).isEqualTo(1L);
                    assertThat(row.failed()).isZero();
                    assertThat(row.attribution()).isEqualTo("GET /reports");
                });
        assertThat(rows)
                .filteredOn(row -> row.kind().equals("file input stream"))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.count()).isEqualTo(1L);
                    assertThat(row.failed()).isEqualTo(1L);
                });
        assertThat(store.keys().keys()).singleElement().satisfies(key -> {
            assertThat(key.kind()).isEqualTo("file input stream");
            assertThat(key.count()).isEqualTo(1L);
        });
    }

    /** M5-5g: a resource's later report lands on its first report's row, as a thread-activity follow-up does. */
    @Test
    void aResourcesLaterReportLandsOnItsFirstReportsRowEvenWhenTheRouteWasNamedLater() {
        SideEffectsStore store = new SideEffectsStore(0L);
        long request = 0x52L;
        store.add(resources(SideEffectsCatalog.KIND_RESOURCE_LEFT_OPEN, request, true, "socket", "localhost:5432"));
        store.resolve(Map.of(), NOW + SideEffectsStore.PENDING_MILLIS);
        store.resolve(Map.of(String.format("%016x", request), "GET /stream"), NOW + SideEffectsStore.PENDING_MILLIS);
        store.add(resources(SideEffectsCatalog.KIND_RESOURCE_RECLAIMED, request, false, "socket", "localhost:5432"));

        assertThat(store.rows("resources", true, true)).singleElement().satisfies(row -> {
            assertThat(row.attribution()).isEqualTo(SideEffectsStore.UNKNOWN_ROUTE);
            assertThat(row.count()).isEqualTo(1L);
            assertThat(row.leftRunning()).isEqualTo(1L);
            assertThat(row.failed()).isEqualTo(1L);
        });
    }

    private static SideEffectsStore.Observation resources(
            int kind, long request, boolean first, String resource, String target) {
        int detail = SideEffectsCatalog.ORIGIN_APPLICATION | (first ? SideEffectsCatalog.DETAIL_FIRST_REPORT : 0);
        return new SideEffectsStore.Observation(
                new SideEffectRecord(
                        SideEffectsCatalog.RECORD_RESOURCES,
                        kind,
                        1L,
                        NOW,
                        NOW,
                        request,
                        0L,
                        0L,
                        1,
                        SideEffectsCatalog.OUTCOME_DONE,
                        1,
                        0,
                        0,
                        detail,
                        1L,
                        1_000_000L,
                        1_000_000L,
                        0,
                        0),
                "resources",
                resource,
                target,
                "com.example.Reports#read",
                null,
                "http-nio-{n}-exec-{n}",
                "application",
                null);
    }

    @Test
    void aThreadLocalResolvedAfterAGiveUpMovesItsRowsAndWaitingObservationsOrDropsThem() {
        SideEffectsStore store = new SideEffectsStore(0L);
        String marker = SideEffectsStore.unresolvedThreadLocal(3, 77);
        store.add(threadLocal(0xabL, "holder not resolved (java.lang.ThreadLocal)", marker));
        store.add(threadLocal(0xcdL, "holder not resolved (java.lang.ThreadLocal)", marker));
        store.add(threadLocal(0xabL, "com.example.Tenants.CURRENT", null));
        store.resolve(Map.of("00000000000000ab", "GET /x"), NOW);

        assertThat(store.rows("thread-locals", true, true))
                .extracting(SideEffectsRowDto::target, SideEffectsRowDto::count)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("holder not resolved (java.lang.ThreadLocal)", 1L),
                        org.assertj.core.groups.Tuple.tuple("com.example.Tenants.CURRENT", 1L));

        assertThat(store.resolveThreadLocal(marker, "left set", "com.example.Tenants.CURRENT", "application", false))
                .isZero();
        store.resolve(Map.of("00000000000000cd", "GET /y"), NOW);

        assertThat(store.rows("thread-locals", true, true))
                .extracting(SideEffectsRowDto::attribution, SideEffectsRowDto::target, SideEffectsRowDto::count)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("GET /x", "com.example.Tenants.CURRENT", 2L),
                        org.assertj.core.groups.Tuple.tuple("GET /y", "com.example.Tenants.CURRENT", 1L));
        assertThat(store.rowCount("thread-locals")).isEqualTo(2L);

        String other = SideEffectsStore.unresolvedThreadLocal(4, 88);
        store.add(threadLocal(0xabL, "holder not resolved (java.lang.ThreadLocal)", other));
        store.add(threadLocal(0xefL, "holder not resolved (java.lang.ThreadLocal)", other));
        assertThat(store.resolveThreadLocal(other, "left set", "org.slf4j.MDC.mdcAdapter", "library", true))
                .as("its row and its waiting observation")
                .isEqualTo(2L);
        store.resolve(Map.of("00000000000000ef", "GET /z"), NOW);
        assertThat(store.rows("thread-locals", true, true))
                .extracting(SideEffectsRowDto::target)
                .containsOnly("com.example.Tenants.CURRENT");
    }

    private static SideEffectsStore.Observation threadLocal(long request, String target, String marker) {
        return new SideEffectsStore.Observation(
                new SideEffectRecord(
                        SideEffectsCatalog.RECORD_THREAD_LOCALS,
                        SideEffectsCatalog.KIND_THREAD_LOCAL_LEFT_SET,
                        1L,
                        NOW,
                        NOW,
                        request,
                        0L,
                        0L,
                        1,
                        0,
                        1,
                        0,
                        0,
                        0,
                        1L,
                        0L,
                        0L,
                        0,
                        0),
                "thread-locals",
                "left set",
                target,
                null,
                null,
                "http-nio-{n}-exec-{n}",
                null,
                marker,
                null,
                -1,
                marker == null ? "application" : "unknown",
                null);
    }

    private static SideEffectsStore.Observation threads(int kind, long request, long millis) {
        return new SideEffectsStore.Observation(
                new SideEffectRecord(
                        SideEffectsCatalog.RECORD_THREADS,
                        kind,
                        1L,
                        millis,
                        millis,
                        request,
                        0L,
                        0L,
                        1,
                        SideEffectsCatalog.OUTCOME_STARTED,
                        1,
                        0,
                        0,
                        SideEffectsCatalog.ORIGIN_APPLICATION,
                        1L,
                        0L,
                        0L,
                        0,
                        0),
                "thread-activity",
                "executor",
                "java.util.concurrent.ThreadPoolExecutor",
                "com.example.Exports#export",
                null,
                "http-nio-{n}-exec-{n}",
                "application",
                null);
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
