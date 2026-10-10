package io.github.jdubois.bootui.engine.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.core.dto.ActivitySwitchRequest;
import io.github.jdubois.bootui.engine.correlation.RunIdentity;
import io.github.jdubois.bootui.engine.journal.HttpPayload;
import io.github.jdubois.bootui.engine.journal.JournalActivityCapture;
import io.github.jdubois.bootui.engine.journal.JournalActivityFeed;
import io.github.jdubois.bootui.engine.journal.JournalSource;
import io.github.jdubois.bootui.engine.journal.RuntimeEvent;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ActivitySwitchServiceTests {

    private static final AtomicInteger DB_COUNTER = new AtomicInteger();

    private final ActivitySwitchService service = new ActivitySwitchService();
    private final RuntimeJournal journal = new RuntimeJournal(RuntimeJournalSettings.defaults(), RunIdentity.start());
    private final List<ActivityCapture> captures = new ArrayList<>();

    @AfterEach
    void closeCaptures() {
        captures.forEach(ActivityCapture::close);
        journal.close();
    }

    private ActivityCapture capture(ActivityStore target, ActivityPersistenceSettings settings) {
        ActivityCapture capture = JournalActivityCapture.start(
                target, settings, entry -> false, journal, new JournalActivityFeed(0, 5, null), panel -> true);
        captures.add(capture);
        return capture;
    }

    private ActivitySwitchResponse useExistingDataSource(
            SwitchableActivityStore store,
            ActivityPersistenceSettings settings,
            DataSource dataSource,
            ActivitySwitchRequest request) {
        return service.useExistingDataSource(store, settings, dataSource, request, journal, this::capture);
    }

    private static DataSource newDataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:switch-service-" + DB_COUNTER.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static ActivityPersistenceSettings disabledSettings() {
        return new ActivityPersistenceSettings(
                false,
                ActivityPersistenceSettings.DataSourceMode.SHARED,
                null,
                null,
                null,
                null,
                "bootui_activity",
                Duration.ofSeconds(5),
                200,
                Duration.ofDays(7),
                "app-1");
    }

    @Test
    void journalUnavailableRejectsBeforeResolvingADatabaseConnectionOrStartingCapture() {
        DataSource dataSource = mock(DataSource.class);
        SwitchableActivityStore store = new SwitchableActivityStore(new InMemoryActivityStore(200));
        try (RuntimeJournal disabled = new RuntimeJournal(RuntimeJournalSettings.disabled(), RunIdentity.start())) {
            for (RuntimeJournal unavailable : Arrays.asList(null, disabled)) {
                ActivitySwitchResponse response = service.useExistingDataSource(
                        store,
                        disabledSettings(),
                        dataSource,
                        new ActivitySwitchRequest(true),
                        unavailable,
                        (target, settings) -> {
                            throw new AssertionError("capture must not start");
                        });
                assertThat(response.status()).isEqualTo(409);
                assertThat(response.body().status()).isEqualTo("unavailable");
                assertThat(response.capture()).isNull();
                assertThat(store.persistent()).isFalse();
            }
            verifyNoInteractions(dataSource);
        } finally {
            store.close();
        }
    }

    @Test
    void closedJournalRejectsTheRuntimeActionBeforeDatabaseMutation() throws Exception {
        DataSource dataSource = newDataSource();
        SwitchableActivityStore store = new SwitchableActivityStore(new InMemoryActivityStore(200));
        journal.close();
        try {
            ActivitySwitchResponse response =
                    useExistingDataSource(store, disabledSettings(), dataSource, new ActivitySwitchRequest(true));
            try (var connection = dataSource.getConnection();
                    var tables = connection.getMetaData().getTables(null, null, "BOOTUI_ACTIVITY", null)) {
                assertThat(tables.next()).isFalse();
            }
            assertThat(response.status()).isEqualTo(409);
            assertThat(response.capture()).isNull();
            assertThat(store.persistent()).isFalse();
        } finally {
            store.close();
        }
    }

    @Test
    void closeDuringCaptureRegistrationCannotPublishAStoreThatWillNeverCapture() throws Exception {
        DataSource dataSource = newDataSource();
        SwitchableActivityStore store = new SwitchableActivityStore(new InMemoryActivityStore(200));
        AtomicReference<ActivityStore> candidate = new AtomicReference<>();
        try {
            ActivitySwitchResponse response = service.useExistingDataSource(
                    store,
                    disabledSettings(),
                    dataSource,
                    new ActivitySwitchRequest(true),
                    journal,
                    (target, settings) -> {
                        candidate.set(target);
                        ActivityCapture capture = capture(target, settings);
                        journal.close();
                        return capture;
                    });
            assertThat(response.status()).isEqualTo(500);
            assertThat(response.body().status()).isEqualTo("failed");
            assertThat(response.body().message()).contains("table may already have been created");
            assertThat(response.capture()).isNull();
            assertThat(store.persistent()).isFalse();
            var scheduler = BufferedActivityStore.class.getDeclaredField("scheduler");
            scheduler.setAccessible(true);
            assertThat(((ScheduledExecutorService) scheduler.get(candidate.get())).isShutdown())
                    .isTrue();
        } finally {
            store.close();
        }
    }

    @Test
    void concurrentJournalCloseCompletesDuringRegistrationAndPreventsPublication() throws Exception {
        DataSource dataSource = newDataSource();
        SwitchableActivityStore store = new SwitchableActivityStore(new InMemoryActivityStore(200));
        var closer = Executors.newSingleThreadExecutor();
        CountDownLatch closed = new CountDownLatch(1);
        try {
            ActivitySwitchResponse response = service.useExistingDataSource(
                    store,
                    disabledSettings(),
                    dataSource,
                    new ActivitySwitchRequest(true),
                    journal,
                    (target, settings) -> {
                        ActivityCapture capture = capture(target, settings);
                        closer.submit(() -> {
                            try {
                                journal.close();
                            } finally {
                                closed.countDown();
                            }
                        });
                        try {
                            assertThat(closed.await(5, TimeUnit.SECONDS))
                                    .as("journal teardown must not wait for the capture callback")
                                    .isTrue();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(ex);
                        }
                        return capture;
                    });
            assertThat(response.status()).isEqualTo(500);
            assertThat(response.body().status()).isEqualTo("failed");
            assertThat(response.capture()).isNull();
            assertThat(store.persistent()).isFalse();
        } finally {
            closer.shutdownNow();
            assertThat(closer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            store.close();
        }
    }

    @Test
    void batchDispatchedDuringCaptureRegistrationWritesToTheCandidateBeforeItIsPublished() throws Exception {
        InMemoryActivityStore original = new InMemoryActivityStore(200);
        SwitchableActivityStore store = new SwitchableActivityStore(original);
        DataSource dataSource = newDataSource();
        try {
            ActivitySwitchResponse response = service.useExistingDataSource(
                    store,
                    disabledSettings(),
                    dataSource,
                    new ActivitySwitchRequest(true),
                    journal,
                    (target, settings) -> {
                        assertThat(store.persistent()).isFalse();
                        assertThat(target).isInstanceOf(BufferedActivityStore.class);
                        ActivityCapture capture = capture(target, settings);
                        journal.offer(RuntimeEvent.of(
                                JournalSource.HTTP,
                                1_000,
                                2_000_000,
                                CorrelationContext.forRequest("during-switch"),
                                "t",
                                null,
                                false,
                                new HttpPayload("GET", "/during", "/during", null, 200)));
                        try {
                            assertThat(journal.awaitDrained(Duration.ofSeconds(5)))
                                    .isTrue();
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(ex);
                        }
                        assertThat(target.query(ActivityQuery.firstPage("app-1"))
                                        .entryDtos())
                                .hasSize(1);
                        assertThat(original.query(ActivityQuery.firstPage("app-1"))
                                        .entries())
                                .isEmpty();
                        return capture;
                    });
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.capture()).isNotNull();
            assertThat(store.persistent()).isTrue();
            response.capture().close();
        } finally {
            store.close();
        }
        assertThat(new JdbcActivityStore(dataSource, "bootui_activity")
                        .query(ActivityQuery.firstPage("app-1"))
                        .entries())
                .hasSize(1);
    }

    @Test
    void concurrentConfirmedAttemptsPublishOneStoreAndOwnOnlyOneCapture() throws Exception {
        SwitchableActivityStore store = new SwitchableActivityStore(new InMemoryActivityStore(200));
        DataSource dataSource = newDataSource();
        CountDownLatch startingCapture = new CountDownLatch(1);
        CountDownLatch secondRequested = new CountDownLatch(1);
        CountDownLatch publish = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> service.useExistingDataSource(
                    store,
                    disabledSettings(),
                    dataSource,
                    new ActivitySwitchRequest(true),
                    journal,
                    (target, settings) -> {
                        started.incrementAndGet();
                        ActivityCapture capture = capture(target, settings);
                        startingCapture.countDown();
                        try {
                            if (!publish.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test did not permit publication");
                            }
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(ex);
                        }
                        return capture;
                    }));
            assertThat(startingCapture.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                secondRequested.countDown();
                return service.useExistingDataSource(
                        store,
                        disabledSettings(),
                        dataSource,
                        new ActivitySwitchRequest(true),
                        journal,
                        (target, settings) -> {
                            started.incrementAndGet();
                            return capture(target, settings);
                        });
            });
            assertThat(secondRequested.await(5, TimeUnit.SECONDS)).isTrue();
            publish.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).body().status()).isEqualTo("success");
            ActivitySwitchResponse repeat = second.get(5, TimeUnit.SECONDS);
            assertThat(repeat.body().status()).isEqualTo("already-active");
            assertThat(repeat.capture()).isNull();
            assertThat(started).hasValue(1);
        } finally {
            publish.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            captures.forEach(ActivityCapture::close);
            store.close();
        }
    }

    @Test
    void failedOrNullCaptureLeavesInMemoryActiveAndClosesTheCandidatesScheduler() throws Exception {
        for (boolean throwsFailure : List.of(true, false)) {
            SwitchableActivityStore store = new SwitchableActivityStore(new InMemoryActivityStore(200));
            DataSource dataSource = newDataSource();
            AtomicReference<BufferedActivityStore> candidate = new AtomicReference<>();
            try {
                ActivitySwitchResponse response = service.useExistingDataSource(
                        store,
                        disabledSettings(),
                        dataSource,
                        new ActivitySwitchRequest(true),
                        journal,
                        (target, settings) -> {
                            candidate.set((BufferedActivityStore) target);
                            if (throwsFailure) {
                                throw new IllegalStateException("capture unavailable");
                            }
                            return null;
                        });
                assertThat(response.status()).isEqualTo(500);
                assertThat(response.body().status()).isEqualTo("failed");
                assertThat(response.body().message()).contains("table may already have been created", "in-memory");
                assertThat(response.capture()).isNull();
                assertThat(store.persistent()).isFalse();
                var scheduler = BufferedActivityStore.class.getDeclaredField("scheduler");
                scheduler.setAccessible(true);
                assertThat(((ScheduledExecutorService) scheduler.get(candidate.get())).isShutdown())
                        .isTrue();
                try (var connection = dataSource.getConnection();
                        var tables = connection.getMetaData().getTables(null, null, "BOOTUI_ACTIVITY", null)) {
                    assertThat(tables.next())
                            .as("do not roll back a host-owned table")
                            .isTrue();
                }
            } finally {
                store.close();
            }
        }
    }

    @Test
    void alreadyPersistentIsIdempotentAndReportsSuccessWithoutRequiringADataSource() {
        DataSource backing = newDataSource();
        SwitchableActivityStore store =
                ActivityStoreFactory.create(disabledSettings().withEnabledSharedMode(), () -> backing);
        try {
            ActivitySwitchResponse response =
                    useExistingDataSource(store, disabledSettings(), null, new ActivitySwitchRequest(true));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body().status()).isEqualTo("already-active");
            assertThat(response.newSettings()).isNull();
        } finally {
            store.close();
        }
    }

    @Test
    void noDataSourceIsRejectedWith404AndLeavesTheStoreUnchanged() {
        SwitchableActivityStore store = new SwitchableActivityStore(new InMemoryActivityStore(200));

        ActivitySwitchResponse response =
                useExistingDataSource(store, disabledSettings(), null, new ActivitySwitchRequest(true));

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().status()).isEqualTo("unavailable");
        assertThat(response.newSettings()).isNull();
        assertThat(store.persistent()).isFalse();
    }

    @Test
    void missingOrDeclinedConfirmationIsRejectedWith400AndLeavesTheStoreUnchanged() {
        SwitchableActivityStore store = new SwitchableActivityStore(new InMemoryActivityStore(200));
        DataSource dataSource = newDataSource();

        for (ActivitySwitchRequest request : Arrays.asList(null, new ActivitySwitchRequest(false))) {
            ActivitySwitchResponse response = useExistingDataSource(store, disabledSettings(), dataSource, request);

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body().status()).isEqualTo("blocked");
            assertThat(response.newSettings()).isNull();
        }
        assertThat(store.persistent()).isFalse();
    }

    @Test
    void confirmedSwitchCreatesTheTableAndMakesTheStorePersistent() {
        SwitchableActivityStore store = new SwitchableActivityStore(new InMemoryActivityStore(200));
        DataSource dataSource = newDataSource();
        try {
            ActivitySwitchResponse response =
                    useExistingDataSource(store, disabledSettings(), dataSource, new ActivitySwitchRequest(true));

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body().status()).isEqualTo("success");
            assertThat(response.body().tableName()).isEqualTo("bootui_activity");
            // The success message must honestly disclose the runtime-only, not-restart-durable caveat.
            assertThat(response.body().message()).contains("bootui_activity").contains("restart");
            assertThat(response.newSettings()).isNotNull();
            assertThat(response.newSettings().enabled()).isTrue();
            assertThat(response.newSettings().dataSourceMode())
                    .isEqualTo(ActivityPersistenceSettings.DataSourceMode.SHARED);
            assertThat(store.persistent()).isTrue();

            // The table was really created against the supplied DataSource, independent of `store` -
            // querying it through a second, unrelated JdbcActivityStore instance proves this.
            JdbcActivityStore direct = new JdbcActivityStore(dataSource, "bootui_activity");
            assertThat(direct.query(ActivityQuery.firstPage("app-1"))).isEqualTo(ActivityPage.EMPTY);
        } finally {
            store.close();
        }
    }

    @Test
    void schemaVerificationFailureIsReportedAsAnErrorWithoutSwitchingTheStore() {
        SwitchableActivityStore store = new SwitchableActivityStore(new InMemoryActivityStore(200));
        DataSource broken = (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        throw new SQLException("simulated connection failure");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });

        ActivitySwitchResponse response =
                useExistingDataSource(store, disabledSettings(), broken, new ActivitySwitchRequest(true));

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.body().status()).isEqualTo("failed");
        assertThat(response.newSettings()).isNull();
        assertThat(store.persistent()).isFalse();
    }

    @Test
    void readPathRejectionIsReportedAsAnErrorWithoutSwitchingTheStore() {
        // Before issue #1142 was fixed, a MySQL datasource created the table fine, the switch reported success, and
        // every later Live Activity read failed. The switch now proves the read path before switching.
        SwitchableActivityStore store = new SwitchableActivityStore(new InMemoryActivityStore(200));
        DataSource rejectingReads = new RecordingDataSource(newDataSource(), "H2", true).dataSource();

        ActivitySwitchResponse response =
                useExistingDataSource(store, disabledSettings(), rejectingReads, new ActivitySwitchRequest(true));

        assertThat(response.status()).isEqualTo(500);
        assertThat(response.body().status()).isEqualTo("failed");
        assertThat(response.body().message()).contains("read the activity table");
        assertThat(response.newSettings()).isNull();
        assertThat(store.persistent()).isFalse();
    }

    @Test
    void raceLossAgainstAConcurrentSwitchReportsAlreadyActiveAndClosesTheUnusedDurableStore() throws Exception {
        // Simulates two concurrent "Use the existing datasource" requests: this attempt sees a
        // not-yet-persistent store when it checks (so it proceeds to build a durable store), but loses
        // the race when it tries to actually install it, because another attempt won in between.
        SwitchableActivityStore store = mock(SwitchableActivityStore.class);
        when(store.persistent()).thenReturn(false);
        when(store.attemptSwitchToPersistent(any())).thenReturn(false);
        DataSource dataSource = newDataSource();
        ActivityCapture unusedCapture = mock(ActivityCapture.class);
        AtomicReference<ActivityStore> unusedStore = new AtomicReference<>();

        ActivitySwitchResponse response = service.useExistingDataSource(
                store, disabledSettings(), dataSource, new ActivitySwitchRequest(true), journal, (target, settings) -> {
                    unusedStore.set(target);
                    return unusedCapture;
                });

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().status()).isEqualTo("already-active");
        assertThat(response.newSettings()).isNull();
        verify(store).attemptSwitchToPersistent(any());
        verify(unusedCapture).close();
        var scheduler = BufferedActivityStore.class.getDeclaredField("scheduler");
        scheduler.setAccessible(true);
        assertThat(((ScheduledExecutorService) scheduler.get(unusedStore.get())).isShutdown())
                .isTrue();
    }
}
