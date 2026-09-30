package io.github.jdubois.bootui.engine.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.AdvisorViolationDetailsDto;
import io.github.jdubois.bootui.core.dto.PageMetadata;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class AdvisorScanStateTests {

    @Test
    void everyPageCarriesLocationsAlignedWithItsViolationsAndTheMetadataCarriesTheNotes() {
        AdvisorScanState<Report> state = state();
        AdvisorViolationCollector collector = state.collector();
        List<AdvisorViolation> findings = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            findings.add(
                    index % 3 == 1
                            ? AdvisorViolation.of("finding-" + index)
                            : new AdvisorViolation(
                                    "finding-" + index,
                                    new io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto(
                                            "a.Type" + index, null, null, "Type" + index + ".java", index + 1, null)));
        }
        collector.record("rule", 7, findings, UnaryOperator.identity(), 0);
        collector.record("plain", 2, List.of("x", "y"), UnaryOperator.identity());
        collector.addLocationNotes(List.of("2 class(es) were loaded from an archive."));
        Report published = state.publish(new Report("scan", 1, null), collector);
        String scanId = published.violationDetails().scanId();
        assertThat(published.violationDetails().locationNotes())
                .containsExactly("2 class(es) were loaded from an archive.");

        for (int offset = 0; offset < 7; offset += 3) {
            AdvisorRuleViolationsDto page = state.ruleViolations("rule", scanId, offset, 3);
            assertThat(page.locations()).hasSameSizeAs(page.violations());
            for (int index = 0; index < page.violations().size(); index++) {
                int number = offset + index;
                assertThat(page.violations().get(index)).isEqualTo("finding-" + number);
                if (number % 3 == 1) assertThat(page.locations().get(index)).isNull();
                else assertThat(page.locations().get(index).className()).isEqualTo("a.Type" + number);
            }
        }
        // A page on which no violation is located carries no location data at all.
        assertThat(state.ruleViolations("rule", scanId, 1, 1).locations()).isEmpty();
        assertThat(state.ruleViolations("plain", scanId, 0, 10).locations()).isEmpty();
    }

    @Test
    void initialReportIsStableAndDoesNotEstablishADetailSnapshot() {
        AdvisorScanState<Report> state = state();
        AtomicInteger reads = new AtomicInteger();
        Report initial = state.currentReport(() -> {
            reads.incrementAndGet();
            return new Report("initial", 1, AdvisorViolationDetailsDto.unknown());
        });

        assertThat(state.currentReport(() -> {
                    throw new AssertionError("Initial report must only be created once.");
                }))
                .isSameAs(initial);
        assertThat(reads).hasValue(1);
        assertFailure(
                state,
                "rule",
                "not-completed",
                null,
                null,
                409,
                "No completed advisor scan is available. Reread the cached report before requesting details.");
    }

    @Test
    void concurrentInitialReadersShareOneStableReport() throws Exception {
        AdvisorScanState<Report> state = state();
        AtomicInteger reads = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Report>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    await(start);
                    return state.currentReport(() -> new Report("initial", reads.incrementAndGet(), null));
                }));
            }
            start.countDown();
            Report initial = futures.get(0).get(5, TimeUnit.SECONDS);
            for (Future<Report> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isSameAs(initial);
            }
            assertThat(reads).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 10, 11, 9_999, 10_000, 10_001})
    void metadataAndEveryPageKeepTheTrueCountsAndExactRetainedSequence(int count) {
        AdvisorScanState<Report> state = state();
        List<String> findings = details(count);
        Report report = publish(state, findings, count);
        AdvisorViolationDetailsDto metadata = report.violationDetails();
        int retained = Math.min(count, 10_000);

        assertThat(metadata.scanId()).isNotBlank();
        assertThat(UUID.fromString(metadata.scanId()).toString()).isEqualTo(metadata.scanId());
        assertThat(metadata.total()).isEqualTo(count);
        assertThat(metadata.retained()).isEqualTo(retained);
        assertThat(metadata.retentionLimit()).isEqualTo(10_000);
        assertThat(metadata.truncated()).isEqualTo(count > retained);
        if (count == 0) {
            assertFailure(
                    state,
                    "rule",
                    metadata.scanId(),
                    null,
                    null,
                    404,
                    "Advisor rule has no findings in the current scan.");
            return;
        }

        List<String> retrieved = new ArrayList<>();
        for (int offset = 0; ; offset += 1_000) {
            AdvisorRuleViolationsDto page = state.ruleViolations("rule", metadata.scanId(), offset, 1_000);
            assertThat(page.violationCount()).isEqualTo(count);
            assertThat(page.retainedCount()).isEqualTo(retained);
            assertThat(page.truncated()).isEqualTo(count > retained);
            assertThat(page.page().total()).isEqualTo(retained);
            assertThat(page.page().matched()).isEqualTo(retained);
            assertThat(page.page().offset()).isEqualTo(offset);
            assertThat(page.page().returned()).isEqualTo(page.violations().size());
            retrieved.addAll(page.violations());
            if (!page.page().hasMore()) {
                break;
            }
        }
        assertThat(retrieved).containsExactlyElementsOf(findings.subList(0, retained));
    }

    @Test
    void pagingDefaultsAndMaximumUseRetainedNotOriginalTotals() {
        AdvisorScanState<Report> state = state();
        state.setRetentionLimit(() -> 1_234);
        Report report = publish(state, details(1_250), 1_250);
        String scanId = report.violationDetails().scanId();

        AdvisorRuleViolationsDto defaultPage = state.ruleViolations("rule", scanId, null, null);
        assertThat(defaultPage.page()).isEqualTo(new PageMetadata(1_234, 1_234, 0, 100, 100, true));
        assertThat(defaultPage.violations()).containsExactlyElementsOf(details(100));
        assertThat(defaultPage.violationCount()).isEqualTo(1_250);
        assertThat(defaultPage.retainedCount()).isEqualTo(1_234);
        assertThat(defaultPage.truncated()).isTrue();

        for (int limit : new int[] {1_000, 1_001, Integer.MAX_VALUE}) {
            AdvisorRuleViolationsDto capped = state.ruleViolations("rule", scanId, 100, limit);
            assertThat(capped.page()).isEqualTo(new PageMetadata(1_234, 1_234, 100, 1_000, 1_000, true));
            assertThat(capped.violations())
                    .containsExactlyElementsOf(details(1_100).subList(100, 1_100));
        }
        AdvisorRuleViolationsDto tail = state.ruleViolations("rule", scanId, 1_200, 100);
        assertThat(tail.page()).isEqualTo(new PageMetadata(1_234, 1_234, 1_200, 100, 34, false));
        assertThat(tail.truncated()).isTrue();
        assertThat(state.ruleViolations("rule", scanId, 1, 1).page())
                .isEqualTo(new PageMetadata(1_234, 1_234, 1, 1, 1, true));

        for (int offset : new int[] {1_234, 1_235, Integer.MAX_VALUE}) {
            AdvisorRuleViolationsDto end = state.ruleViolations("rule", scanId, offset, 100);
            assertThat(end.violations()).isEmpty();
            assertThat(end.page()).isEqualTo(new PageMetadata(1_234, 1_234, 1_234, 100, 0, false));
            assertThat(end.truncated()).isTrue();
        }
    }

    @Test
    void noRetainedDetailsAndIncompleteUpstreamDataRemainFindingPages() {
        AdvisorScanState<Report> state = state();
        state.setRetentionLimit(() -> 2);
        AdvisorViolationCollector collector = state.collector();
        collector.record("first", 3, List.of("a", "a"), UnaryOperator.identity());
        collector.record("second", 4, List.of("b", "c", "d", "e"), UnaryOperator.identity());
        collector.record("passed", 0, List.of(), UnaryOperator.identity());
        Report report = state.publish(new Report("report", 1, null), collector);
        String scanId = report.violationDetails().scanId();

        assertThat(report.violationDetails()).isEqualTo(new AdvisorViolationDetailsDto(scanId, 7, 2, 2, true));
        assertThat(state.ruleViolations("first", scanId, 0, 100).violations()).containsExactly("a", "a");
        AdvisorRuleViolationsDto empty = state.ruleViolations("second", scanId, null, null);
        assertThat(empty)
                .isEqualTo(new AdvisorRuleViolationsDto(
                        scanId, "second", 4, 0, true, List.of(), new PageMetadata(0, 0, 0, 100, 0, false)));
        assertFailure(state, "passed", scanId, null, null, 404, "Advisor rule has no findings in the current scan.");

        AdvisorScanState<Report> incomplete = state();
        Report partial = publish(incomplete, List.of("only-known-detail"), 4);
        assertThat(partial.violationDetails().truncated()).isTrue();
        assertThat(partial.violationDetails().retained()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsMalformedInputsBeforeLookingForASnapshot(
            String ruleId, String scanId, Integer offset, Integer limit, String message) {
        AdvisorScanState<Report> state = state();
        assertFailure(state, ruleId, scanId, offset, limit, 400, message);
        publish(state, List.of("finding"), 1);
        assertFailure(state, ruleId, scanId, offset, limit, 400, message);
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of(null, "scan", 0, 1, "Advisor rule ID must not be blank."),
                Arguments.of("", "scan", 0, 1, "Advisor rule ID must not be blank."),
                Arguments.of(" \t\n", "scan", 0, 1, "Advisor rule ID must not be blank."),
                Arguments.of("rule", null, 0, 1, "Advisor scan ID must not be blank."),
                Arguments.of("rule", "", 0, 1, "Advisor scan ID must not be blank."),
                Arguments.of("rule", "\t \n", 0, 1, "Advisor scan ID must not be blank."),
                Arguments.of("rule", "scan", -1, 1, "Advisor violation offset must not be negative."),
                Arguments.of("rule", "scan", Integer.MIN_VALUE, 1, "Advisor violation offset must not be negative."),
                Arguments.of("rule", "scan", 0, 0, "Advisor violation limit must be positive."),
                Arguments.of("rule", "scan", 0, -1, "Advisor violation limit must be positive."),
                Arguments.of("rule", "scan", 0, Integer.MIN_VALUE, "Advisor violation limit must be positive."));
    }

    @Test
    void noSnapshotStaleSnapshotAndUnknownRuleAreDistinctSafeFailures() {
        AdvisorScanState<Report> state = state();
        assertFailure(
                state,
                "password=not-echoed",
                "credential=not-echoed",
                0,
                100,
                409,
                "No completed advisor scan is available. Reread the cached report before requesting details.");
        Report report = publish(state, List.of("finding"), 1);
        assertFailure(
                state,
                "password=not-echoed",
                report.violationDetails().scanId(),
                0,
                100,
                404,
                "Advisor rule has no findings in the current scan.");
        assertFailure(
                state,
                "rule",
                "credential=not-echoed",
                0,
                100,
                409,
                "Advisor scan has been replaced. Reread the cached report before requesting details.");
    }

    @Test
    void readsTheLiveRetentionLimitOnceAtScanStartAndFreezesIt() {
        AdvisorScanState<Report> state = state();
        AtomicInteger limit = new AtomicInteger(2);
        AtomicInteger policyReads = new AtomicInteger();
        state.setRetentionLimit(() -> {
            policyReads.incrementAndGet();
            return limit.get();
        });
        assertThat(policyReads).hasValue(1);
        AdvisorViolationCollector collector = state.collector();
        assertThat(policyReads).hasValue(2);
        limit.set(4);
        collector.record("rule", 5, details(5), UnaryOperator.identity());
        Report first = state.publish(new Report("first", 1, null), collector);

        assertThat(first.violationDetails().retentionLimit()).isEqualTo(2);
        assertThat(first.violationDetails().retained()).isEqualTo(2);
        assertThat(state.currentReport(() -> null)).isSameAs(first);
        assertThat(state.ruleViolations("rule", first.violationDetails().scanId(), null, null)
                        .retainedCount())
                .isEqualTo(2);
        assertThat(policyReads).hasValue(2);

        Report second = publish(state, details(5), 5);
        assertThat(second.violationDetails().retentionLimit()).isEqualTo(4);
        assertThat(second.violationDetails().retained()).isEqualTo(4);
        assertThat(policyReads).hasValue(3);
        limit.set(0);
        assertThatThrownBy(state::collector)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Advisor violation retention limit must be positive.");
        assertThat(state.currentReport(() -> null)).isSameAs(second);
        limit.set(-1);
        assertThatThrownBy(state::collector).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidPolicyImmediatelyWithoutReplacingThePreviousOne() {
        AdvisorScanState<Report> state = state();
        state.setRetentionLimit(() -> 2);
        assertThatThrownBy(() -> state.setRetentionLimit(() -> 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> state.setRetentionLimit(() -> -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> state.setRetentionLimit(null)).isInstanceOf(NullPointerException.class);
        assertThat(publish(state, details(3), 3).violationDetails().retentionLimit())
                .isEqualTo(2);
    }

    @Test
    void eachCompletedSnapshotGetsANewIdentifierEvenAtTheSameClockTime() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-13T10:00:00Z"), ZoneOffset.UTC);
        AdvisorScanState<Report> state = state();
        AdvisorViolationCollector firstCollector = state.collector();
        firstCollector.record("rule", 1, List.of("first"), UnaryOperator.identity());
        Report first = state.publish(new Report("first", clock.millis(), null), firstCollector);
        AdvisorViolationCollector secondCollector = state.collector();
        secondCollector.record("rule", 1, List.of("second"), UnaryOperator.identity());
        Report second = state.publish(new Report("second", clock.millis(), null), secondCollector);

        assertThat(first.scannedAt()).isEqualTo(second.scannedAt());
        assertThat(first.violationDetails().scanId())
                .isNotEqualTo(second.violationDetails().scanId());
        assertFailure(
                state,
                "rule",
                first.violationDetails().scanId(),
                0,
                100,
                409,
                "Advisor scan has been replaced. Reread the cached report before requesting details.");
        assertThat(state.ruleViolations("rule", second.violationDetails().scanId(), 0, 100)
                        .violations())
                .containsExactly("second");
    }

    @Test
    void publishesReportMetadataAndIndexTogetherWhileReadsKeepThePreviousSnapshot() throws Exception {
        CountDownLatch projecting = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AdvisorScanState<Report> state = new AdvisorScanState<>((report, metadata) -> {
            if ("second".equals(report.label())) {
                projecting.countDown();
                await(finish);
            }
            return report.withViolationDetails(metadata);
        });
        Report first = publish(state, List.of("first"), 1);
        AdvisorViolationCollector next = state.collector();
        next.record("rule", 2, List.of("second-a", "second-b"), UnaryOperator.identity());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Report> publication = executor.submit(() -> state.publish(new Report("second", 1, null), next));
            await(projecting);
            assertThat(state.currentReport(() -> null)).isSameAs(first);
            assertThat(state.ruleViolations("rule", first.violationDetails().scanId(), 0, 100)
                            .violations())
                    .containsExactly("first");
            finish.countDown();

            Report second = publication.get(5, TimeUnit.SECONDS);
            assertThat(state.currentReport(() -> null)).isSameAs(second);
            assertThat(second.violationDetails().total()).isEqualTo(2);
            AdvisorRuleViolationsDto page =
                    state.ruleViolations("rule", second.violationDetails().scanId(), 0, 100);
            assertThat(page.violationCount()).isEqualTo(2);
            assertThat(page.violations()).containsExactly("second-a", "second-b");
            assertFailure(
                    state,
                    "rule",
                    first.violationDetails().scanId(),
                    0,
                    100,
                    409,
                    "Advisor scan has been replaced. Reread the cached report before requesting details.");
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentPublicationsNeverMixReportCountsAndDetailIndexes() throws Exception {
        AdvisorScanState<Report> state = state();
        publish(state, details(1), 1);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> firstWriter = executor.submit(() -> publishRepeatedly(state, start, 2));
            Future<?> secondWriter = executor.submit(() -> publishRepeatedly(state, start, 3));
            Future<?> reader = executor.submit(() -> {
                await(start);
                for (int i = 0; i < 200; i++) {
                    Report report = state.currentReport(() -> null);
                    AdvisorViolationDetailsDto metadata = report.violationDetails();
                    try {
                        AdvisorRuleViolationsDto page = state.ruleViolations("rule", metadata.scanId(), 0, 100);
                        assertThat(page.violationCount()).isEqualTo(metadata.total());
                        assertThat(page.retainedCount()).isEqualTo(metadata.retained());
                        assertThat(page.violations()).containsExactlyElementsOf(details(metadata.total()));
                    } catch (AdvisorViolationException exception) {
                        assertThat(exception.status()).isEqualTo(409);
                    }
                }
            });
            start.countDown();
            firstWriter.get(5, TimeUnit.SECONDS);
            secondWriter.get(5, TimeUnit.SECONDS);
            reader.get(5, TimeUnit.SECONDS);
            Report report = state.currentReport(() -> null);
            assertThat(state.ruleViolations("rule", report.violationDetails().scanId(), 0, 100)
                            .violations())
                    .containsExactlyElementsOf(details(report.violationDetails().total()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedProjectionDoesNotReplaceTheCompletedSnapshot() {
        AdvisorScanState<Report> state = new AdvisorScanState<>((report, metadata) -> {
            if ("failure".equals(report.label())) {
                throw new IllegalStateException("Projection failed");
            }
            return report.withViolationDetails(metadata);
        });
        Report first = publish(state, List.of("first"), 1);
        assertThatThrownBy(() -> state.publish(new Report("failure", 2, null), state.collector()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Projection failed");
        assertThat(state.currentReport(() -> null)).isSameAs(first);
        assertThat(state.ruleViolations("rule", first.violationDetails().scanId(), 0, 100)
                        .violations())
                .containsExactly("first");
    }

    @Test
    void returnedEmptyReportsReplacePreviousFindingsAndDetachedPagesStayImmutable() {
        AdvisorScanState<Report> state = state();
        AdvisorViolationCollector collector = state.collector();
        collector.record("rule", 1, List.of("first"), UnaryOperator.identity());
        Report first = state.publish(new Report("first", 1, null), collector);
        AdvisorRuleViolationsDto firstPage =
                state.ruleViolations("rule", first.violationDetails().scanId(), 0, 100);
        collector.record("rule", 1, List.of("late"), UnaryOperator.identity());
        assertThat(state.ruleViolations("rule", first.violationDetails().scanId(), 0, 100))
                .isEqualTo(firstPage);

        Report disabled = state.publish(new Report("DISABLED", 2, null), state.collector());
        assertThat(state.currentReport(() -> null)).isSameAs(disabled);
        assertThat(disabled.violationDetails().total()).isZero();
        assertThat(disabled.violationDetails().truncated()).isFalse();
        assertFailure(
                state,
                "rule",
                first.violationDetails().scanId(),
                0,
                100,
                409,
                "Advisor scan has been replaced. Reread the cached report before requesting details.");
        assertFailure(
                state,
                "rule",
                disabled.violationDetails().scanId(),
                0,
                100,
                404,
                "Advisor rule has no findings in the current scan.");
        assertThat(firstPage.violations()).containsExactly("first");
        assertThatThrownBy(() -> firstPage.violations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    private static AdvisorScanState<Report> state() {
        return new AdvisorScanState<>(Report::withViolationDetails);
    }

    private static List<String> details(int count) {
        return IntStream.range(0, count).mapToObj(i -> "finding-" + i).toList();
    }

    private static Report publish(AdvisorScanState<Report> state, List<String> details, int count) {
        AdvisorViolationCollector collector = state.collector();
        collector.record("rule", count, details, UnaryOperator.identity());
        return state.publish(new Report("report", 1, null), collector);
    }

    private static void publishRepeatedly(AdvisorScanState<Report> state, CountDownLatch start, int count) {
        await(start);
        for (int i = 0; i < 100; i++) {
            publish(state, details(count), count);
        }
    }

    private static void assertFailure(
            AdvisorScanState<Report> state,
            String ruleId,
            String scanId,
            Integer offset,
            Integer limit,
            int status,
            String message) {
        assertThatThrownBy(() -> state.ruleViolations(ruleId, scanId, offset, limit))
                .isInstanceOfSatisfying(AdvisorViolationException.class, failure -> {
                    assertThat(failure.status()).isEqualTo(status);
                    assertThat(failure.getMessage()).isEqualTo(message);
                });
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private record Report(String label, long scannedAt, AdvisorViolationDetailsDto violationDetails) {
        Report withViolationDetails(AdvisorViolationDetailsDto metadata) {
            return new Report(label, scannedAt, metadata);
        }
    }
}
