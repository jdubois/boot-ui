package io.github.jdubois.bootui.quarkus4.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Whether BootUI's JBoss LogManager handler sees every WARN+ log, for the caught-exception outcomes (docs/PLAN-v2.md
 * M5-6): attached, not replaced during the window, and no logger keeping its records from the root.
 */
class QuarkusLogCoverageTest {

    private final Logger root = Logger.getLogger("");
    private final QuarkusLogCoverage coverage = new QuarkusLogCoverage();
    private QuarkusLogTailHandler installed;
    private Logger bypassing;
    private Handler own;

    @AfterEach
    void restore() {
        if (installed != null) {
            root.removeHandler(installed);
        }
        coverage.handlerChanged(null, null);
        if (bypassing != null) {
            bypassing.removeHandler(own);
            bypassing.setUseParentHandlers(true);
            bypassing.setLevel(null);
        }
    }

    @Test
    void aGapWhileDetachedReplacedDuringTheWindowOrBypassed() throws Exception {
        assertThat(coverage.gap(0, Long.MAX_VALUE)).contains("not attached");

        installed = new QuarkusLogTailHandler(new LogTailBuffer(), new InternalPackageMatcher(List.of()));
        root.addHandler(installed);
        coverage.handlerChanged(root, installed);
        long changed = System.currentTimeMillis();
        assertThat(coverage.gap(changed - 1_000, Long.MAX_VALUE)).contains("replaced");
        Thread.sleep(5);
        assertThat(coverage.gap(System.currentTimeMillis(), Long.MAX_VALUE)).isNull();

        bypassing = Logger.getLogger("com.example.audit.quarkus");
        own = new ConsoleHandler();
        bypassing.addHandler(own);
        bypassing.setUseParentHandlers(false);
        bypassing.setLevel(Level.SEVERE);
        QuarkusLogCoverage freshCoverage = new QuarkusLogCoverage();
        freshCoverage.handlerChanged(root, installed);
        assertThat(freshCoverage.bypassingLoggers()).contains("com.example.audit.quarkus");
    }

    @Test
    void actualHandlerPauseAndCustomFiltersWithholdCoverageWithoutInvokingPolicy() {
        LogTailBuffer buffer = new LogTailBuffer();
        installed = new QuarkusLogTailHandler(buffer, new InternalPackageMatcher(List.of()));
        root.addHandler(installed);
        coverage.handlerChanged(root, installed);
        buffer.suspendForLowFootprint();
        assertThat(coverage.gap(Long.MAX_VALUE, Long.MAX_VALUE)).contains("paused");
        buffer.resumeFromLowFootprint();
        AtomicInteger callbacks = new AtomicInteger();
        installed.setFilter(record -> {
            callbacks.incrementAndGet();
            throw new AssertionError("handler filters must not run on a coverage read");
        });
        assertThat(coverage.gap(Long.MAX_VALUE, Long.MAX_VALUE)).contains("filtering");
        assertThat(callbacks.get()).isZero();
    }

    @Test
    void nativeInventoryReadsUseTheHeldContextNotAnApplicationContextSelector() {
        installed = new QuarkusLogTailHandler(new LogTailBuffer(), new InternalPackageMatcher(List.of()));
        root.addHandler(installed);
        coverage.handlerChanged(root, installed);
        org.jboss.logmanager.LogContextSelector previous = org.jboss.logmanager.LogContext.getLogContextSelector();
        AtomicInteger callbacks = new AtomicInteger();
        org.jboss.logmanager.LogContext.setLogContextSelector(() -> {
            callbacks.incrementAndGet();
            throw new AssertionError("an application context selector must not run on a coverage read");
        });
        try {
            assertThat(coverage.gap(Long.MAX_VALUE, Long.MAX_VALUE)).contains("routing");
            assertThat(coverage.bypassingLoggers()).anyMatch(reason -> reason.contains("routing"));
            assertThat(callbacks.get()).isZero();
        } finally {
            org.jboss.logmanager.LogContext.setLogContextSelector(previous);
        }
    }

    @Test
    void loggingRoutedToAnotherNativeContextCannotBeProvedCoveredByTheHeldRoot() throws Exception {
        installed = new QuarkusLogTailHandler(new LogTailBuffer(), new InternalPackageMatcher(List.of()));
        root.addHandler(installed);
        coverage.handlerChanged(root, installed);
        assertThat(coverage.gap(Long.MAX_VALUE, Long.MAX_VALUE)).isNull();
        assertThat(coverage.bypassingLoggers()).isEmpty();
        org.jboss.logmanager.LogContextSelector previous = org.jboss.logmanager.LogContext.getLogContextSelector();
        try (org.jboss.logmanager.LogContext other = org.jboss.logmanager.LogContext.create()) {
            Logger otherRoot = other.getLogger("");
            AtomicInteger emitted = new AtomicInteger();
            Handler applicationHandler = new Handler() {
                @Override
                public void publish(java.util.logging.LogRecord record) {
                    emitted.incrementAndGet();
                }

                @Override
                public void flush() {}

                @Override
                public void close() {}
            };
            otherRoot.addHandler(applicationHandler);
            AtomicInteger selectorCallbacks = new AtomicInteger();
            org.jboss.logmanager.LogContext.setLogContextSelector(() -> {
                selectorCallbacks.incrementAndGet();
                return other;
            });
            try {
                Logger selected = Logger.getLogger("com.example.alternate-context");
                selected.severe("controlled alternate-context counter");
                assertThat(emitted.get()).isEqualTo(1);
                assertThat(otherRoot.getHandlers()).noneMatch(QuarkusLogTailHandler.class::isInstance);
                selectorCallbacks.set(0);

                String gap = coverage.gap(Long.MAX_VALUE, Long.MAX_VALUE);
                List<String> bypassing = coverage.bypassingLoggers();

                org.junit.jupiter.api.Assertions.assertAll(
                        () -> assertThat(gap).contains("routing"),
                        () -> assertThat(bypassing).anyMatch(reason -> reason.contains("routing")),
                        () -> assertThat(selectorCallbacks.get()).isZero());
            } finally {
                org.jboss.logmanager.LogContext.setLogContextSelector(previous);
                otherRoot.removeHandler(applicationHandler);
            }
        }
    }

    @Test
    void anotherApplicationCannotSupplyOrClearThisApplicationsCoverage() {
        installed = new QuarkusLogTailHandler(new LogTailBuffer(), new InternalPackageMatcher(List.of()));
        root.addHandler(installed);
        coverage.handlerChanged(root, installed);
        QuarkusLogCoverage other = new QuarkusLogCoverage();
        assertThat(other.gap(Long.MAX_VALUE, Long.MAX_VALUE)).contains("not attached");
        other.handlerChanged(null, null);
        assertThat(coverage.gap(Long.MAX_VALUE, Long.MAX_VALUE)).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void knownLifecycleReplacementPreservesItsTimeButExternalReplacementRemainsUncertain(boolean known)
            throws InterruptedException {
        installed = new QuarkusLogTailHandler(new LogTailBuffer(), new InternalPackageMatcher(List.of()));
        root.addHandler(installed);
        coverage.handlerChanged(root, installed);
        assertThat(coverage.gap(Long.MAX_VALUE, Long.MAX_VALUE)).isNull();
        root.removeHandler(installed);
        installed = new QuarkusLogTailHandler(new LogTailBuffer(), new InternalPackageMatcher(List.of()));
        root.addHandler(installed);
        if (known) {
            coverage.handlerChanged(root, installed);
        }
        Thread.sleep(5);
        long requestStarted = System.currentTimeMillis();
        Thread.sleep(5);

        String gap = coverage.gap(requestStarted, System.currentTimeMillis());

        if (known) {
            assertThat(gap)
                    .as("a read after a known reload must not move its timestamp past the new request")
                    .isNull();
        } else {
            assertThat(gap).contains("replaced");
        }
    }

    @Test
    void lifecycleBindingStillDetectsAnExternalReplacementBeforeItsFirstRead() throws InterruptedException {
        installed = new QuarkusLogTailHandler(new LogTailBuffer(), new InternalPackageMatcher(List.of()));
        root.addHandler(installed);
        coverage.handlerChanged(root, installed);
        assertThat(coverage.gap(Long.MAX_VALUE, Long.MAX_VALUE)).isNull();
        root.removeHandler(installed);
        installed = new QuarkusLogTailHandler(new LogTailBuffer(), new InternalPackageMatcher(List.of()));
        root.addHandler(installed);
        coverage.handlerChanged(root, installed);
        root.removeHandler(installed);
        installed = new QuarkusLogTailHandler(new LogTailBuffer(), new InternalPackageMatcher(List.of()));
        root.addHandler(installed);
        Thread.sleep(5);
        long requestStarted = System.currentTimeMillis();
        Thread.sleep(5);

        assertThat(coverage.gap(requestStarted, System.currentTimeMillis())).contains("replaced");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stoppingCaptureUsesItsHeldRootEvenWhenTheCurrentSelectorChangesOrThrows(boolean throwsOnSelection)
            throws Exception {
        QuarkusLogCoverage shared = new QuarkusLogCoverage();
        QuarkusLogTailCapture capture = new QuarkusLogTailCapture(new LogTailBuffer(), null, shared);
        capture.onStart(new io.quarkus.runtime.StartupEvent());
        Handler captured = java.util.Arrays.stream(root.getHandlers())
                .filter(QuarkusLogTailHandler.class::isInstance)
                .findFirst()
                .orElseThrow();
        org.jboss.logmanager.LogContextSelector previous = org.jboss.logmanager.LogContext.getLogContextSelector();
        try (org.jboss.logmanager.LogContext other = org.jboss.logmanager.LogContext.create()) {
            AtomicInteger selectorCallbacks = new AtomicInteger();
            org.jboss.logmanager.LogContext.setLogContextSelector(() -> {
                selectorCallbacks.incrementAndGet();
                if (throwsOnSelection) {
                    throw new IllegalStateException("controlled shutdown selector failure");
                }
                return other;
            });
            try {
                Throwable failure = catchThrowable(() -> capture.onStop(new io.quarkus.runtime.ShutdownEvent()));
                int callbacks = selectorCallbacks.get();
                Handler[] remaining = root.getHandlers();
                String gap = shared.gap(Long.MAX_VALUE, Long.MAX_VALUE);
                org.jboss.logmanager.LogContext.setLogContextSelector(previous);
                assertAll(
                        () -> assertThat(failure).isNull(),
                        () -> assertThat(callbacks).isZero(),
                        () -> assertThat(remaining).doesNotContain(captured),
                        () -> assertThat(gap).contains("not attached"));
            } finally {
                org.jboss.logmanager.LogContext.setLogContextSelector(previous);
                root.removeHandler(captured);
                shared.handlerChanged(null, null);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {4096, 4097})
    void inventoryVisitsAreBoundedEvenWhenEveryLoggerForwardsNormally(int inventorySize) {
        org.jboss.logmanager.LogContext nativeContext = mock(org.jboss.logmanager.LogContext.class);
        org.jboss.logmanager.Logger nativeRoot = mock(org.jboss.logmanager.Logger.class);
        when(nativeRoot.getLogContext()).thenReturn(nativeContext);
        org.jboss.logmanager.Logger forwarding =
                org.jboss.logmanager.LogContext.getSystemLogContext().getLogger("com.example.normal-inventory");
        assertThat(forwarding.getUseParentHandlers()).isTrue();
        when(nativeContext.getLoggerIfExists(anyString())).thenReturn(forwarding);
        AtomicInteger visited = new AtomicInteger();
        when(nativeContext.getLoggerNames()).thenReturn(new Enumeration<String>() {
            @Override
            public boolean hasMoreElements() {
                return visited.get() < inventorySize;
            }

            @Override
            public String nextElement() {
                return "normal.additive." + visited.getAndIncrement();
            }
        });
        org.jboss.logmanager.LogContextSelector defaultSelector =
                org.jboss.logmanager.LogContext.DEFAULT_LOG_CONTEXT_SELECTOR;
        try (var nativeApi = mockStatic(org.jboss.logmanager.LogContext.class, CALLS_REAL_METHODS)) {
            nativeApi
                    .when(org.jboss.logmanager.LogContext::getLogContextSelector)
                    .thenReturn(defaultSelector);
            nativeApi.when(org.jboss.logmanager.LogContext::getSystemLogContext).thenReturn(nativeContext);
            coverage.handlerChanged(nativeRoot, null);

            List<String> reasons = coverage.bypassingLoggers();

            assertAll(() -> assertThat(visited.get()).isLessThanOrEqualTo(4096), () -> {
                if (inventorySize > 4096) {
                    assertThat(reasons).anyMatch(reason -> reason.contains("4096"));
                } else {
                    assertThat(reasons).isEmpty();
                    assertThat(visited.get()).isEqualTo(inventorySize);
                }
            });
        }
    }

    @Test
    void unsupportedManagerInventoryIsCachedInsteadOfAllocatedOnEveryRead() {
        List<String> first = coverage.bypassingLoggers();
        assertThat(first).anyMatch(reason -> reason.contains("custom log manager"));
        assertThat(coverage.bypassingLoggers()).isSameAs(first);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeStartupMarkersDoNotPoisonPostInstallUnitsOrLaunderPreInstallUnits(boolean includePreInstall)
            throws Exception {
        try (var journal = new io.github.jdubois.bootui.engine.journal.RuntimeJournal(
                new io.github.jdubois.bootui.engine.journal.RuntimeJournalSettings(
                        true, 100, 1_000_000, 100, 10, 10, io.github.jdubois.bootui.engine.journal.JournalSource.all()),
                io.github.jdubois.bootui.engine.correlation.RunIdentity.start())) {
            long before = System.currentTimeMillis() - 100;
            io.github.jdubois.bootui.engine.journal.RunStartEvents.publish(
                    journal, before, 50_000_000L, List.of(), List.of(), java.util.Map.of(), null, false);
            if (includePreInstall) {
                journal.offer(io.github.jdubois.bootui.engine.journal.RuntimeEvent.of(
                        io.github.jdubois.bootui.engine.journal.JournalSource.HTTP,
                        before,
                        0,
                        io.github.jdubois.bootui.spi.CorrelationContext.forRequest("pre-install"),
                        "http-before",
                        null,
                        false,
                        new io.github.jdubois.bootui.engine.journal.HttpPayload(
                                "GET", "/before", "/before", null, 200)));
            }
            QuarkusLogCoverage shared = new QuarkusLogCoverage();
            QuarkusLogTailCapture capture = new QuarkusLogTailCapture(new LogTailBuffer(), journal, shared);
            capture.onStart(new io.quarkus.runtime.StartupEvent());
            try {
                Thread.sleep(5);
                long after = System.currentTimeMillis();
                journal.offer(io.github.jdubois.bootui.engine.journal.RuntimeEvent.of(
                        io.github.jdubois.bootui.engine.journal.JournalSource.HTTP,
                        after,
                        0,
                        io.github.jdubois.bootui.spi.CorrelationContext.forRequest("post-install"),
                        "http-after",
                        null,
                        false,
                        new io.github.jdubois.bootui.engine.journal.HttpPayload(
                                "POST", "/after", "/after", null, 204)));
                assertThat(journal.awaitDrained(java.time.Duration.ofSeconds(5)))
                        .isTrue();
                assertThat(shared.gap(after, System.currentTimeMillis())).isNull();
                if (includePreInstall) {
                    assertThat(shared.gap(before, System.currentTimeMillis())).contains("replaced");
                }
                var service = new io.github.jdubois.bootui.engine.insights.RuntimeInsightsService(
                        journal,
                        null,
                        panel -> true,
                        io.github.jdubois.bootui.engine.insights.InsightsStack.QUARKUS,
                        List::of);
                service.setLogCoverage(() -> shared);

                var check = service.report().checks().stream()
                        .filter(row -> row.kind()
                                .equals(io.github.jdubois.bootui.engine.insights.FrameworkWarningsByRoute.KIND))
                        .findFirst()
                        .orElseThrow();

                assertAll(
                        () -> assertThat(check.eligibleRequests()).isEqualTo(1),
                        () -> assertThat(check.status()).isEqualTo("EVALUATED"));
                assertThat(service.report().checks()).contains(check);
            } finally {
                capture.onStop(new io.quarkus.runtime.ShutdownEvent());
            }
        }
    }

    @Test
    void captureLifecycleBindsAndReleasesItsProducerOwnedNativeCoverage() {
        QuarkusLogCoverage shared = new io.github.jdubois.bootui.quarkus4.BootUiEngineProducer().logCoverage();
        QuarkusLogTailCapture capture = new QuarkusLogTailCapture(new LogTailBuffer(), null, shared);
        assertThat(shared.gap(Long.MAX_VALUE, Long.MAX_VALUE)).contains("not attached");
        capture.onStart(new io.quarkus.runtime.StartupEvent());
        try {
            assertThat(shared.gap(Long.MAX_VALUE, Long.MAX_VALUE)).isNull();
        } finally {
            capture.onStop(new io.quarkus.runtime.ShutdownEvent());
        }
        assertThat(shared.gap(Long.MAX_VALUE, Long.MAX_VALUE)).contains("not attached");
    }
}
