package io.github.jdubois.bootui.engine.crac;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.CracFindingDto;
import io.github.jdubois.bootui.core.dto.CracReadinessReport;
import io.github.jdubois.bootui.core.dto.CracRuntimeStatusDto;
import io.github.jdubois.bootui.engine.crac.CracReadinessScanner.CracScanResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class CracReadinessScannerTests {

    private static final String FIXTURES = "io.github.jdubois.bootui.engine.crac.fixtures";
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);
    private static final CracRuntimeStatusDto RUNTIME =
            new CracRuntimeStatusDto(false, false, "Test JVM", false, null, null, List.of(), "summary", List.of());

    private CracReadinessScanner scanner(List<String> basePackages) {
        return new CracReadinessScanner(() -> basePackages, new ClassFileCracImporter(), CLOCK);
    }

    private CracReadinessScanner scanner(List<String> basePackages, CracRuntimeInventory inventory) {
        return new CracReadinessScanner(() -> basePackages, new ClassFileCracImporter(), CLOCK, () -> inventory);
    }

    @Test
    void initialResultIsNotScanned() {
        CracReadinessScanner scanner = scanner(List.of(FIXTURES));
        CracReadinessReport report = scanner.report(scanner.initialResult(), RUNTIME);

        assertThat(report.localOnly()).isTrue();
        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.scan().scannedAt()).isNull();
        assertThat(report.checksRun()).isZero();
        assertThat(report.classesAnalyzed()).isZero();
        assertThat(report.findings()).isEmpty();
        assertThat(report.basePackages()).containsExactly(FIXTURES);
        assertThat(report.runtime()).isEqualTo(RUNTIME);
        assertThat(report.warnings()).isEmpty();
    }

    @Test
    void scanDetectsEveryReadinessFinding() {
        CracReadinessScanner scanner = scanner(List.of(FIXTURES));
        CracScanResult result = scanner.scan();
        CracReadinessReport report = scanner.report(result, RUNTIME);

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.scan().scannedAt()).isEqualTo(CLOCK.millis());
        assertThat(report.checksRun())
                .isEqualTo(CracCheckRegistry.activeChecks().size());
        assertThat(report.classesAnalyzed()).isPositive();
        assertThat(report.findings())
                .allSatisfy(finding -> assertThat(finding.status()).isEqualTo("REVIEW"));
        // CRAC-LIFECYCLE-001 is intentionally absent here: ManagedResourceLifecycle and
        // ManagedClassWithUnrelatedLeak both implement org.crac.Resource, so the "no implementations
        // found" check now reports OK. See resourceRegistrationCheckIsOkWhenAnImplementerExists().
        assertThat(report.findings())
                .extracting(CracFindingDto::id)
                .contains(
                        "CRAC-RES-001",
                        "CRAC-FILE-001",
                        "CRAC-NET-001",
                        "CRAC-THREAD-001",
                        "CRAC-TIME-001",
                        "CRAC-CONFIG-001",
                        "CRAC-RANDOM-001",
                        "CRAC-SECRET-001",
                        "CRAC-SCHED-001",
                        "CRAC-POOL-002");
        assertThat(report.findings().stream().map(CracFindingDto::severity).toList())
                .isSortedAccordingTo(Comparator.comparingInt(CracReadinessScannerTests::severityRank));
        assertThat(report.severityCounts())
                .extracting("severity")
                .containsExactly("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
        assertThat(report.findingsFound()).isEqualTo(report.findings().size());
    }

    @Test
    void resourceRegistrationCheckIsOkWhenAnImplementerExists() {
        CracReadinessScanner scanner = scanner(List.of(FIXTURES));
        CracReadinessReport report = scanner.report(scanner.scan(), RUNTIME);

        // ManagedResourceLifecycle and ManagedClassWithUnrelatedLeak both implement org.crac.Resource,
        // so CRAC-LIFECYCLE-001 ("no implementations found") reports OK and is filtered out of findings().
        assertThat(report.findings()).extracting(CracFindingDto::id).doesNotContain("CRAC-LIFECYCLE-001");
    }

    @Test
    void managedLifecycleReopeningResourcesInCallbacksIsExempt() {
        CracReadinessScanner scanner = scanner(List.of(FIXTURES));
        CracReadinessReport report = scanner.report(scanner.scan(), RUNTIME);

        // ManagedResourceLifecycle implements org.crac.Resource and re-acquires its socket, file,
        // executor pool, and HTTP client from afterRestore() - the exact pattern each check's own
        // recommendation text suggests. None of these should be (re-)flagged.
        assertThat(findingSamples(report, "CRAC-NET-001"))
                .noneMatch(sample -> sample.contains("ManagedResourceLifecycle"));
        assertThat(findingSamples(report, "CRAC-FILE-001"))
                .noneMatch(sample -> sample.contains("ManagedResourceLifecycle"));
        assertThat(findingSamples(report, "CRAC-THREAD-001"))
                .noneMatch(sample -> sample.contains("ManagedResourceLifecycle"));
        assertThat(findingSamples(report, "CRAC-RES-001"))
                .noneMatch(sample -> sample.contains("ManagedResourceLifecycle"));
        assertThat(findingSamples(report, "CRAC-POOL-002"))
                .noneMatch(sample -> sample.contains("ManagedResourceLifecycle"));
    }

    @Test
    void unmanagedLeakInAManagedClassIsStillFlagged() {
        CracReadinessScanner scanner = scanner(List.of(FIXTURES));
        CracReadinessReport report = scanner.report(scanner.scan(), RUNTIME);

        // ManagedClassWithUnrelatedLeak implements org.crac.Resource, but opens its socket from an
        // unrelated method, not from beforeCheckpoint()/afterRestore(); the call-site-scoped exemption
        // must not let this leak slip through just because the enclosing class is otherwise managed.
        assertThat(findingSamples(report, "CRAC-NET-001"))
                .anyMatch(sample -> sample.contains("ManagedClassWithUnrelatedLeak"));
    }

    @Test
    void scanFlagsInstanceFieldRandomAndSecretHolders() {
        CracReadinessScanner scanner = scanner(List.of(FIXTURES));
        CracReadinessReport report = scanner.report(scanner.scan(), RUNTIME);

        // CRAC-RANDOM-001/CRAC-SECRET-001 used to filter on the STATIC modifier; these fixtures use
        // ordinary singleton-bean instance fields, the dominant real-world Spring pattern, and must
        // still be flagged now that the static-only filter has been removed.
        assertThat(findingSamples(report, "CRAC-RANDOM-001"))
                .anyMatch(sample -> sample.contains("InstanceRandomHolder"));
        assertThat(findingSamples(report, "CRAC-SECRET-001"))
                .anyMatch(sample -> sample.contains("InstanceSecretHolder"));
    }

    @Test
    void scanFlagsNioChannelOpenCall() {
        CracReadinessScanner scanner = scanner(List.of(FIXTURES));
        CracReadinessReport report = scanner.report(scanner.scan(), RUNTIME);

        // NioChannelOpener uses SocketChannel.open(), the idiomatic NIO factory method, rather than a
        // constructor; CRAC-NET-001 must detect this alongside the constructor-based fixtures.
        assertThat(findingSamples(report, "CRAC-NET-001")).anyMatch(sample -> sample.contains("NioChannelOpener"));
    }

    @Test
    void scanFlagsFixedRateScheduledMethodButNotFixedDelayOrCron() {
        CracReadinessScanner scanner = scanner(List.of(FIXTURES));
        CracReadinessReport report = scanner.report(scanner.scan(), RUNTIME);

        CracFindingDto sched = report.findings().stream()
                .filter(finding -> "CRAC-SCHED-001".equals(finding.id()))
                .findFirst()
                .orElseThrow();
        assertThat(sched.severity()).isEqualTo("MEDIUM");
        assertThat(sched.status()).isEqualTo("REVIEW");
        assertThat(sched.occurrenceCount()).isEqualTo(1);
        assertThat(sched.sampleOccurrences()).anyMatch(sample -> sample.contains("pollEveryFiveSeconds"));
        assertThat(sched.sampleOccurrences())
                .noneMatch(sample -> sample.contains("runFiveSecondsAfterCompletion") || sample.contains("hourlyJob"));
    }

    @Test
    void scanFlagsUnmanagedHttpClientField() {
        CracReadinessScanner scanner = scanner(List.of(FIXTURES));
        CracReadinessReport report = scanner.report(scanner.scan(), RUNTIME);

        assertThat(findingSamples(report, "CRAC-POOL-002")).anyMatch(sample -> sample.contains("HttpClientHolder"));
    }

    @Test
    void scanFlagsMissingCracDependencyWhenInventoryReportsAbsent() {
        CracRuntimeInventory inventory = new CracRuntimeInventory(List.of(), List.of(), false);
        CracReadinessScanner scanner = scanner(List.of(FIXTURES), inventory);

        CracReadinessReport report = scanner.report(scanner.scan(), RUNTIME);

        CracFindingDto dependency = report.findings().stream()
                .filter(finding -> "CRAC-LIFECYCLE-002".equals(finding.id()))
                .findFirst()
                .orElseThrow();
        assertThat(dependency.severity()).isEqualTo("MEDIUM");
        assertThat(dependency.status()).isEqualTo("REVIEW");
        assertThat(dependency.occurrenceCount()).isEqualTo(1);
    }

    @Test
    void scanDoesNotFlagCracDependencyByDefault() {
        CracReadinessScanner scanner = scanner(List.of(FIXTURES));
        CracReadinessReport report = scanner.report(scanner.scan(), RUNTIME);

        // The default CracRuntimeInventory (built by CracRuntimeInventory.empty()) defaults
        // cracApiPresent to true, so CRAC-LIFECYCLE-002 must not appear absent evidence to the contrary.
        assertThat(report.findings()).extracting(CracFindingDto::id).doesNotContain("CRAC-LIFECYCLE-002");
    }

    @Test
    void scanWithoutBasePackagesReportsNoClasses() {
        CracReadinessScanner scanner = scanner(List.of());
        CracReadinessReport report = scanner.report(scanner.scan(), RUNTIME);

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.classesAnalyzed()).isZero();
        assertThat(report.findings()).isEmpty();
    }

    @Test
    void basePackageDetectionFailureDegradesToWarning() {
        CracReadinessScanner scanner = new CracReadinessScanner(
                () -> {
                    throw new IllegalStateException("boom");
                },
                new ClassFileCracImporter(),
                CLOCK);

        CracReadinessReport report = scanner.report(scanner.scan(), RUNTIME);

        assertThat(report.scan().status()).isEqualTo("SCANNED");
        assertThat(report.warnings()).anyMatch(warning -> warning.contains("base packages could not be detected"));
    }

    @Test
    void scanWithoutConnectionPoolsDoesNotFlagPoolCheck() {
        CracReadinessScanner scanner = scanner(List.of(FIXTURES));
        CracReadinessReport report = scanner.report(scanner.scan(), RUNTIME);

        assertThat(report.findings()).extracting(CracFindingDto::id).doesNotContain("CRAC-POOL-001");
    }

    @Test
    void scanFlagsConnectionPoolWhenInventoryReportsOne() {
        CracRuntimeInventory inventory =
                new CracRuntimeInventory(List.of("dataSource : com.zaxxer.hikari.HikariDataSource"));
        CracReadinessScanner scanner = scanner(List.of(FIXTURES), inventory);

        CracReadinessReport report = scanner.report(scanner.scan(), RUNTIME);

        CracFindingDto pool = report.findings().stream()
                .filter(finding -> "CRAC-POOL-001".equals(finding.id()))
                .findFirst()
                .orElseThrow();
        assertThat(pool.severity()).isEqualTo("HIGH");
        assertThat(pool.status()).isEqualTo("REVIEW");
        assertThat(pool.occurrenceCount()).isEqualTo(1);
        assertThat(pool.sampleOccurrences()).anyMatch(sample -> sample.contains("HikariDataSource"));
    }

    @Test
    void scanFlagsCacheManagerWhenInventoryReportsOne() {
        CracRuntimeInventory inventory = new CracRuntimeInventory(
                List.of(), List.of("cacheManager : org.springframework.cache.concurrent.ConcurrentMapCacheManager"));
        CracReadinessScanner scanner = scanner(List.of(FIXTURES), inventory);

        CracReadinessReport report = scanner.report(scanner.scan(), RUNTIME);

        CracFindingDto cache = report.findings().stream()
                .filter(finding -> "CRAC-CACHE-001".equals(finding.id()))
                .findFirst()
                .orElseThrow();
        assertThat(cache.severity()).isEqualTo("LOW");
        assertThat(cache.status()).isEqualTo("REVIEW");
        assertThat(cache.occurrenceCount()).isEqualTo(1);
        assertThat(cache.sampleOccurrences()).anyMatch(sample -> sample.contains("ConcurrentMapCacheManager"));
    }

    private static int severityRank(String severity) {
        return List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO").indexOf(severity);
    }

    private static List<String> findingSamples(CracReadinessReport report, String id) {
        return report.findings().stream()
                .filter(finding -> id.equals(finding.id()))
                .findFirst()
                .map(CracFindingDto::sampleOccurrences)
                .orElse(List.of());
    }
}
