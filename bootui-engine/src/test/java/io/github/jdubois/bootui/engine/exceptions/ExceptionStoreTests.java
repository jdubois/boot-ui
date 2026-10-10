package io.github.jdubois.bootui.engine.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.correlation.BootUiCorrelation;
import io.github.jdubois.bootui.spi.CorrelationContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExceptionStoreTests {

    @Test
    void theCrossRunSignatureIgnoresLineNumbersButNotMethods() {
        List<ExceptionStore.Frame> before = List.of(
                new ExceptionStore.Frame("com.example.OrderService", "place", "OrderService.java", 40, true),
                new ExceptionStore.Frame("com.example.OrderController", "post", "OrderController.java", 12, true));
        List<ExceptionStore.Frame> shifted = List.of(
                new ExceptionStore.Frame("com.example.OrderService", "place", "OrderService.java", 47, true),
                new ExceptionStore.Frame("com.example.OrderController", "post", "OrderController.java", 15, true));
        List<ExceptionStore.Frame> otherMethod = List.of(
                new ExceptionStore.Frame("com.example.OrderService", "cancel", "OrderService.java", 40, true),
                new ExceptionStore.Frame("com.example.OrderController", "post", "OrderController.java", 12, true));

        String signature = ExceptionStore.signature("java.lang.IllegalStateException", before);

        assertThat(ExceptionStore.signature("java.lang.IllegalStateException", shifted))
                .isEqualTo(signature);
        assertThat(ExceptionStore.signature("java.lang.IllegalStateException", otherMethod))
                .isNotEqualTo(signature);
        assertThat(ExceptionStore.signature("java.lang.IllegalArgumentException", before))
                .isNotEqualTo(signature);
    }

    @Test
    void theAgentBridgesFramesNeverChangeAFailuresGroup() {
        ExceptionStore.Frame job = new ExceptionStore.Frame("com.example.Job", "run", "Job.java", 10, true);
        ExceptionStore.Frame worker = new ExceptionStore.Frame(
                "java.util.concurrent.ThreadPoolExecutor", "runWorker", "ThreadPoolExecutor.java", 1144, false);
        ExceptionStore.Frame bridge = new ExceptionStore.Frame(
                "io.github.jdubois.bootui.agent.bridge.TaskPropagation", "runTask", "TaskPropagation.java", 230, false);

        assertThat(ExceptionStore.fingerprintFrames(List.of(job, bridge, worker)))
                .containsExactly(job, worker);
        assertThat(ExceptionStore.signature("java.lang.IllegalStateException", List.of(job, bridge, worker)))
                .isEqualTo(ExceptionStore.signature("java.lang.IllegalStateException", List.of(job, worker)));
    }

    @Test
    void groupsRepeatedFailuresWithIdenticalStacks() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        for (Throwable throwable : sameOrigin(3)) {
            store.record(throwable, "main", null, null, null, "log");
        }

        List<ExceptionStore.GroupSummary> groups = store.groups();
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).count()).isEqualTo(3);
        assertThat(store.totalExceptions()).isEqualTo(3);
    }

    @Test
    void stampsEachOccurrenceWithTheRequestIdCurrentWhenItWasRecorded() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        List<Throwable> failures = sameOrigin(3);

        try (BootUiCorrelation.Scope ignored =
                BootUiCorrelation.open(CorrelationContext.forRequest("0123456789abcdef"))) {
            store.record(failures.get(0), "worker-1", "GET", "/orders", "Handler#x", "web");
        }
        store.setCorrelationContextProvider(() -> CorrelationContext.forRequest("fedcba9876543210"));
        store.record(failures.get(1), "log-thread", null, null, null, "log");
        store.setCorrelationContextProvider(() -> {
            throw new IllegalStateException("no request context");
        });
        store.record(failures.get(2), "main", null, null, null, "log");

        ExceptionStore.GroupDetail detail = store.find(store.groups().get(0).fingerprint());
        assertThat(detail.occurrences())
                .extracting(ExceptionStore.Occurrence::requestId)
                .containsExactlyInAnyOrder("0123456789abcdef", "fedcba9876543210", null);
        assertThat(store.groups().get(0).last().requestId()).isNull();
    }

    @Test
    void deduplicatesTheSameThrowableInstance() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        IllegalStateException ex = new IllegalStateException("boom");

        store.record(ex, "main", "GET", "/x", "Handler#x", "web");
        store.record(ex, "main", null, null, null, "log");

        assertThat(store.totalExceptions()).isEqualTo(1);
        assertThat(store.groups()).hasSize(1);
        assertThat(store.groups().get(0).count()).isEqualTo(1);
    }

    @Test
    void distinctValueEqualThrowablesAreSeparateOccurrences() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        List<io.github.jdubois.bootui.engine.journal.RuntimeEvent> events = new ArrayList<>();
        store.setRuntimeEventSink(event -> {
            events.add(event);
            return true;
        });
        List<ValueEqualFailure> failures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            failures.add(new ValueEqualFailure());
        }
        for (ValueEqualFailure failure : failures) {
            store.record(failure, "main", null, null, null, "log");
            store.record(new RuntimeException("wrapper", failure), "main", null, null, null, "web");
        }

        assertThat(store.totalExceptions()).isEqualTo(2);
        assertThat(store.groups())
                .singleElement()
                .satisfies(group -> assertThat(group.count()).isEqualTo(2));
        assertThat(events).hasSize(2);
    }

    private static final class ValueEqualFailure extends RuntimeException {
        ValueEqualFailure() {
            super("equal failure");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ValueEqualFailure;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    @Test
    void deduplicatesAcrossCauseChainSoAFrameworkWrapperOfASeenCauseCountsOnce() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        IllegalStateException root = new IllegalStateException("boom");

        // First feeder (e.g. Vert.x failure handler) records the raw cause.
        store.record(root, "main", "GET", "/x", "Handler#x", "web");
        // Second feeder (e.g. JUL log hook) records a fresh wrapper around the same cause.
        store.record(new RuntimeException("wrapped", root), "main", null, null, null, "log");

        assertThat(store.totalExceptions()).isEqualTo(1);
    }

    @Test
    void appliesIgnorePredicate() {
        ExceptionStore store = new ExceptionStore(100, 25, 50, t -> t instanceof IllegalArgumentException);

        store.record(new IllegalArgumentException("dropped"), "main", null, null, null, "log");
        store.record(new IllegalStateException("kept"), "main", null, null, null, "log");

        assertThat(store.groups()).hasSize(1);
        assertThat(store.groups().get(0).exceptionClassName()).isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    void separatesDistinctExceptionTypes() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(new IllegalStateException("a"), "main", null, null, null, "log");
        store.record(new IllegalArgumentException("b"), "main", null, null, null, "log");

        assertThat(store.groups()).hasSize(2);
    }

    @Test
    void evictsTheLeastRecentlySeenGroupWhenFull() throws InterruptedException {
        ExceptionStore store = new ExceptionStore(2, 25, 50);
        store.record(new IllegalStateException("first"), "main", null, null, null, "log");
        Thread.sleep(2);
        store.record(new IllegalArgumentException("second"), "main", null, null, null, "log");
        Thread.sleep(2);
        store.record(new NullPointerException("third"), "main", null, null, null, "log");

        List<String> classes = store.groups().stream()
                .map(ExceptionStore.GroupSummary::exceptionClassName)
                .toList();
        assertThat(classes).hasSize(2);
        assertThat(classes).doesNotContain("java.lang.IllegalStateException");
        assertThat(classes).contains("java.lang.NullPointerException", "java.lang.IllegalArgumentException");
    }

    @Test
    void boundsRetainedOccurrencesPerGroup() {
        ExceptionStore store = new ExceptionStore(100, 3, 50);
        for (Throwable throwable : sameOrigin(5)) {
            store.record(throwable, "main", null, null, null, "log");
        }

        ExceptionStore.GroupSummary summary = store.groups().get(0);
        ExceptionStore.GroupDetail detail = store.find(summary.fingerprint());
        assertThat(summary.count()).isEqualTo(5);
        assertThat(detail.occurrences()).hasSize(3);
    }

    @Test
    void capturesCauseChainWithCommonFrameCount() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        Throwable root = new NumberFormatException("bad number");
        Throwable wrapper = new IllegalStateException("wrapped", root);

        store.record(wrapper, "main", null, null, null, "log");

        ExceptionStore.GroupDetail detail = store.find(store.groups().get(0).fingerprint());
        assertThat(detail.causes()).isNotEmpty();
        assertThat(detail.causes().get(0).exceptionClassName()).isEqualTo("java.lang.NumberFormatException");
    }

    @Test
    void flagsApplicationFramesFromConfiguredPackages() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.setApplicationPackages(List.of(getClass().getPackageName()));

        store.record(new IllegalStateException("boom"), "main", null, null, null, "log");

        assertThat(store.groups().get(0).applicationException()).isTrue();
    }

    @Test
    void clearsState() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);

        store.record(new IllegalStateException("boom"), "main", null, null, null, "log");
        assertThat(store.groups()).hasSize(1);

        store.clear();
        assertThat(store.groups()).isEmpty();
        assertThat(store.totalExceptions()).isZero();
    }

    @Test
    void notifiesSubscribersOnRecordAndClearAndStopsAfterUnsubscribe() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        java.util.concurrent.atomic.AtomicInteger notifications = new java.util.concurrent.atomic.AtomicInteger();
        Runnable handle = store.subscribe(notifications::incrementAndGet);

        store.record(new IllegalStateException("boom"), "main", null, null, null, "log");
        assertThat(notifications.get()).isEqualTo(1);

        store.clear();
        assertThat(notifications.get()).isEqualTo(2);

        handle.run();
        store.record(new IllegalStateException("after"), "main", null, null, null, "log");
        assertThat(notifications.get()).isEqualTo(2);
    }

    @Test
    void isolatesSubscriberFailuresFromCapture() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.subscribe(() -> {
            throw new RuntimeException("bad subscriber");
        });

        store.record(new IllegalStateException("boom"), "main", null, null, null, "log");

        assertThat(store.totalExceptions()).isEqualTo(1);
    }

    @Test
    void newGroupsDefaultToOpenStatusWithNoRegressions() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(new IllegalStateException("boom"), "main", null, null, null, "log");

        ExceptionStore.GroupSummary summary = store.groups().get(0);
        assertThat(summary.status()).isEqualTo(ExceptionStore.Status.OPEN);
        assertThat(summary.regressionCount()).isZero();
    }

    @Test
    void setStatusUpdatesAKnownGroupAndReturnsTheUpdatedSummary() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(new IllegalStateException("boom"), "main", null, null, null, "log");
        String fingerprint = store.groups().get(0).fingerprint();

        ExceptionStore.GroupSummary updated = store.setStatus(fingerprint, ExceptionStore.Status.ACKNOWLEDGED);

        assertThat(updated).isNotNull();
        assertThat(updated.status()).isEqualTo(ExceptionStore.Status.ACKNOWLEDGED);
        assertThat(store.groups().get(0).status()).isEqualTo(ExceptionStore.Status.ACKNOWLEDGED);
    }

    @Test
    void setStatusReturnsNullForAnUnknownFingerprint() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);

        assertThat(store.setStatus("does-not-exist", ExceptionStore.Status.RESOLVED))
                .isNull();
    }

    @Test
    void acknowledgedGroupsDoNotAutoTransitionOnNewOccurrences() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        // Both throwables must share the exact same call site so they fingerprint into one group -
        // the stack trace (and therefore the fingerprint) is captured at construction time, so
        // generating them from a single loop line (like sameOrigin does) is required here.
        List<Throwable> throwables = sameOrigin(2);
        store.record(throwables.get(0), "main", null, null, null, "log");
        String fingerprint = store.groups().get(0).fingerprint();
        store.setStatus(fingerprint, ExceptionStore.Status.ACKNOWLEDGED);

        store.record(throwables.get(1), "main", null, null, null, "log");

        ExceptionStore.GroupSummary summary = store.groups().get(0);
        assertThat(summary.status()).isEqualTo(ExceptionStore.Status.ACKNOWLEDGED);
        assertThat(summary.regressionCount()).isZero();
        assertThat(summary.count()).isEqualTo(2);
    }

    @Test
    void resolvedGroupsAutoReopenAndIncrementRegressionCountOnANewOccurrence() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        List<Throwable> throwables = sameOrigin(3);
        store.record(throwables.get(0), "main", null, null, null, "log");
        String fingerprint = store.groups().get(0).fingerprint();
        store.setStatus(fingerprint, ExceptionStore.Status.RESOLVED);

        store.record(throwables.get(1), "main", null, null, null, "log");

        ExceptionStore.GroupSummary firstRegression = store.groups().get(0);
        assertThat(firstRegression.status()).isEqualTo(ExceptionStore.Status.OPEN);
        assertThat(firstRegression.regressionCount()).isEqualTo(1);

        // Resolve again, and confirm a second regression increments the counter further.
        store.setStatus(fingerprint, ExceptionStore.Status.RESOLVED);
        store.record(throwables.get(2), "main", null, null, null, "log");

        ExceptionStore.GroupSummary secondRegression = store.groups().get(0);
        assertThat(secondRegression.status()).isEqualTo(ExceptionStore.Status.OPEN);
        assertThat(secondRegression.regressionCount()).isEqualTo(2);
    }

    @Test
    void manualSetStatusNeverChangesRegressionCount() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(makeException(), "main", null, null, null, "log");
        String fingerprint = store.groups().get(0).fingerprint();

        store.setStatus(fingerprint, ExceptionStore.Status.ACKNOWLEDGED);
        store.setStatus(fingerprint, ExceptionStore.Status.RESOLVED);
        store.setStatus(fingerprint, ExceptionStore.Status.OPEN);

        assertThat(store.groups().get(0).regressionCount()).isZero();
    }

    @Test
    void setStatusNotifiesSubscribers() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        store.record(new IllegalStateException("boom"), "main", null, null, null, "log");
        String fingerprint = store.groups().get(0).fingerprint();

        java.util.concurrent.atomic.AtomicInteger notifications = new java.util.concurrent.atomic.AtomicInteger();
        store.subscribe(notifications::incrementAndGet);

        store.setStatus(fingerprint, ExceptionStore.Status.ACKNOWLEDGED);

        assertThat(notifications.get()).isEqualTo(1);
    }

    private static List<Throwable> sameOrigin(int count) {
        List<Throwable> throwables = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            throwables.add(makeException());
        }
        return throwables;
    }

    private static Throwable makeException() {
        return new IllegalStateException("repeated failure");
    }

    @Test
    void notifiesTheSpanEnricherWithTheExceptionType() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        List<String> enriched = new ArrayList<>();
        store.setSpanEnricher(new io.github.jdubois.bootui.engine.telemetry.SpanEnricher() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public void onException(String exceptionType) {
                enriched.add(exceptionType);
            }
        });

        store.record(new IllegalStateException("boom"), "main", "GET", "/x", "Handler#x", "web");

        assertThat(enriched).containsExactly("java.lang.IllegalStateException");
    }

    @Test
    void doesNotNotifyEnricherForDuplicateThrowable() {
        ExceptionStore store = new ExceptionStore(100, 25, 50);
        List<String> enriched = new ArrayList<>();
        store.setSpanEnricher(new io.github.jdubois.bootui.engine.telemetry.SpanEnricher() {
            @Override
            public void onException(String exceptionType) {
                enriched.add(exceptionType);
            }
        });
        IllegalStateException ex = new IllegalStateException("boom");

        store.record(ex, "main", "GET", "/x", "Handler#x", "web");
        store.record(ex, "main", null, null, null, "log");

        assertThat(enriched).containsExactly("java.lang.IllegalStateException");
    }
}
