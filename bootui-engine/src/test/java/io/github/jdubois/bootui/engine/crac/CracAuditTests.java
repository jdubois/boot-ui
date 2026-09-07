package io.github.jdubois.bootui.engine.crac;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.CracFindingDto;
import io.github.jdubois.bootui.engine.crac.CracReadinessScanner.CracScanResult;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.crac.Context;
import org.crac.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;

class CracAuditTests {

    @Test
    void passiveInitialStateDoesNotCollectResources() {
        AtomicInteger collections = new AtomicInteger();
        CracReadinessScanner scanner = new CracReadinessScanner(
                List::of,
                packages -> {
                    throw new AssertionError("must not import");
                },
                Clock.systemUTC(),
                () -> {
                    collections.incrementAndGet();
                    return CracRuntimeInventory.empty();
                });
        assertThat(scanner.initialResult().status()).isEqualTo("NOT_SCANNED");
        assertThat(scanner.latestRuntimeInventory().available()).isFalse();
        assertThat(collections).hasValue(0);
        scanner.scan();
        assertThat(scanner.latestRuntimeInventory().available()).isTrue();
        assertThat(collections).hasValue(1);
    }

    @Test
    void packageDetectionFailurePreservesErrorAndIndependentChecks() {
        CracReadinessScanner scanner = new CracReadinessScanner(
                () -> {
                    throw new IllegalStateException("private-detail-sentinel");
                },
                packages -> {
                    throw new AssertionError("must not import");
                },
                Clock.systemUTC(),
                CracRuntimeInventory::empty);
        CracScanResult result = scanner.scan();
        assertThat(result.status()).isEqualTo("ERROR");
        assertThat(result.checksRun()).isEqualTo(5);
        assertThat(result.warnings()).noneMatch(text -> text.contains("private-detail-sentinel"));
    }

    @Test
    void runtimeChecksRunWithoutPackagesOrClassesAndAfterImportFailure() {
        CracRuntimeInventory runtime = new CracRuntimeInventory(List.of("unknownPool"), List.of(), false);
        CracReadinessScanner noPackages = new CracReadinessScanner(
                List::of,
                packages -> {
                    throw new AssertionError("must not import");
                },
                Clock.systemUTC(),
                () -> runtime);
        CracReadinessScanner noClasses = new CracReadinessScanner(
                () -> List.of("example"),
                packages -> new ClassFileImporter().importClasses(),
                Clock.systemUTC(),
                () -> runtime);
        CracReadinessScanner brokenImport = new CracReadinessScanner(
                () -> List.of("example"),
                packages -> {
                    throw new IllegalStateException("password=do-not-expose");
                },
                Clock.systemUTC(),
                () -> runtime);

        for (CracReadinessScanner scanner : List.of(noPackages, noClasses, brokenImport)) {
            CracScanResult result = scanner.scan();
            assertThat(result.classesAnalyzed()).isZero();
            assertThat(result.checksRun()).isEqualTo(5);
            assertThat(finding(result, "CRAC-POOL-001").status()).isEqualTo("REVIEW");
            assertThat(finding(result, "CRAC-LIFECYCLE-002").status()).isEqualTo("REVIEW");
            assertThat(finding(result, "CRAC-FILE-001").status()).isEqualTo("SKIPPED");
            assertThat(result.warnings()).isNotEmpty().noneMatch(text -> text.contains("do-not-expose"));
        }
        assertThat(brokenImport.scan().status()).isEqualTo("ERROR");
    }

    @Test
    void missingRuntimeEvidenceDoesNotBecomeCleanResults() {
        for (boolean returnsNull : List.of(true, false)) {
            CracReadinessScanner scanner = new CracReadinessScanner(
                    () -> List.of("example"),
                    packages -> new ClassFileImporter().importClasses(SuppliedDate.class),
                    Clock.systemUTC(),
                    () -> {
                        if (returnsNull) {
                            return null;
                        }
                        throw new IllegalStateException("password=do-not-expose");
                    });
            CracScanResult result = scanner.scan();
            assertThat(result.status()).isEqualTo("ERROR");
            assertThat(result.checksRun()).isEqualTo(11);
            assertThat(finding(result, "CRAC-LIFECYCLE-002").status()).isEqualTo("SKIPPED");
            assertThat(finding(result, "CRAC-SCHED-001").status()).isEqualTo("SKIPPED");
            assertThat(scanner.latestRuntimeInventory().available()).isFalse();
            assertThat(result.warnings()).isNotEmpty().noneMatch(text -> text.contains("do-not-expose"));
        }
    }

    @Test
    void sameTypeCleanupDoesNotCertifyOtherFieldOrEvenRegistration() {
        CracFindingDto result = finding(scan(CracRuntimeInventory.empty(), TwoDirectories.class), "CRAC-RES-001");
        assertThat(result.status()).isEqualTo("REVIEW");
        assertThat(result.severity()).isEqualTo("MEDIUM");
        assertThat(result.occurrenceCount()).isEqualTo(2);
        assertThat(result.sampleOccurrences())
                .anyMatch(text -> text.contains("first"))
                .anyMatch(text -> text.contains("second"))
                .allMatch(text -> text.contains("registration unverified"));
    }

    @Test
    void suppliedDateDoesNotReadCurrentTime() {
        assertThat(finding(scan(CracRuntimeInventory.empty(), SuppliedDate.class), "CRAC-TIME-001")
                        .status())
                .isEqualTo("OK");
        assertThat(finding(scan(CracRuntimeInventory.empty(), CurrentDate.class), "CRAC-TIME-001")
                        .status())
                .isEqualTo("REVIEW");
    }

    @Test
    void providerConstructorIsNotAnExplicitRandomSeed() {
        assertThat(finding(scan(CracRuntimeInventory.empty(), ProviderConstruction.class), "CRAC-RANDOM-001")
                        .status())
                .isEqualTo("OK");
        assertThat(finding(scan(CracRuntimeInventory.empty(), ExplicitSeed.class), "CRAC-RANDOM-001")
                        .status())
                .isEqualTo("REVIEW");
    }

    @Test
    void fileStreamFactoriesAreAcquisitionsButEagerReadsAreNot() {
        CracFindingDto result = finding(scan(CracRuntimeInventory.empty(), FileStreams.class), "CRAC-FILE-001");
        assertThat(result.occurrenceCount()).isEqualTo(5);
        assertThat(result.sampleOccurrences())
                .anyMatch(text -> text.contains("list"))
                .anyMatch(text -> text.contains("walk"))
                .anyMatch(text -> text.contains("find"))
                .anyMatch(text -> text.contains("lines"))
                .anyMatch(text -> text.contains("newDirectoryStream"))
                .noneMatch(text -> text.contains("readString") || text.contains("readAllLines"));
    }

    @Test
    void newerThreadApiMatchingDoesNotRequireLoadingThoseJdkTypes() {
        for (String owner : List.of(
                "java.lang.Thread$Builder",
                "java.lang.Thread$Builder$OfVirtual",
                "java.lang.Thread$Builder$OfPlatform")) {
            assertThat(UnmanagedThreadCheck.isThreadCreation(owner, "start")).isTrue();
            assertThat(UnmanagedThreadCheck.isThreadCreation(owner, "unstarted"))
                    .isFalse();
            assertThat(UnmanagedThreadCheck.isThreadCreation(owner, "factory")).isFalse();
        }
        assertThat(UnmanagedThreadCheck.isThreadCreation("java.util.concurrent.Executors", "newThreadPerTaskExecutor"))
                .isTrue();
        assertThat(UnmanagedThreadCheck.isThreadCreation(
                        "java.util.concurrent.Executors", "newVirtualThreadPerTaskExecutor"))
                .isTrue();
        assertThat(UnmanagedThreadCheck.isThreadCreation("example.Executors", "newThreadPerTaskExecutor"))
                .isFalse();
        assertThat(UnmanagedThreadCheck.isThreadCreation("java.lang.Thread", "<init>"))
                .isFalse();
    }

    @Test
    void schedulingFindsRepeatedAndComposedRatesButNotExplicitDefaults() {
        CracFindingDto result = finding(scan(CracRuntimeInventory.empty(), SchedulesFixture.class), "CRAC-SCHED-001");
        assertThat(result.occurrenceCount()).isEqualTo(3);
        assertThat(result.sampleOccurrences())
                .anyMatch(text -> text.contains("repeated"))
                .anyMatch(text -> text.contains("composed"))
                .anyMatch(text -> text.contains("metaComposed"))
                .noneMatch(text -> text.contains("disabled") || text.contains("empty") || text.contains("unrelated"));
    }

    @Test
    void runningContextIsNotExemptBecauseOriginalOnRefreshPropertyRemains() {
        CracRuntimeInventory runtime = new CracRuntimeInventory(
                List.of(), List.of(), List.of(), List.of(), true, true, false, true, List.of(), true, List.of());
        assertThat(finding(scan(runtime, SchedulesFixture.class), "CRAC-SCHED-001")
                        .status())
                .isEqualTo("REVIEW");
    }

    @Test
    void knownManagedClientsAreCreditedOnlyWithRunningLifecycleAndApi() {
        for (boolean running : List.of(true, false)) {
            for (boolean api : List.of(true, false)) {
                CracRuntimeInventory runtime = new CracRuntimeInventory(
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        api,
                        false,
                        false,
                        running,
                        List.of("managedFactory"),
                        true,
                        List.of());
                CracFindingDto result = finding(scan(runtime, SuppliedDate.class), "CRAC-POOL-001");
                assertThat(result.status()).isEqualTo(running && api ? "OK" : "REVIEW");
                if (!running || !api) {
                    assertThat(result.severity()).isEqualTo("MEDIUM");
                }
            }
        }
    }

    private static CracScanResult scan(CracRuntimeInventory runtime, Class<?>... classes) {
        return new CracReadinessScanner(
                        () -> List.of("example"),
                        packages -> new ClassFileImporter().importClasses(classes),
                        Clock.systemUTC(),
                        () -> runtime)
                .scan();
    }

    private static CracFindingDto finding(CracScanResult result, String id) {
        return result.findings().stream()
                .filter(finding -> id.equals(finding.id()))
                .findFirst()
                .orElseThrow();
    }

    static class SuppliedDate {
        static final Date DATE = new Date(0);
    }

    static class CurrentDate {
        static final Date DATE = new Date();
    }

    static class ProviderConstruction extends SecureRandom {
        ProviderConstruction() {
            super(null, null);
        }
    }

    static class ExplicitSeed {
        SecureRandom create() {
            return new SecureRandom(new byte[16]);
        }
    }

    static class TwoDirectories implements Resource {
        DirectoryStream<Path> first;
        DirectoryStream<Path> second;

        @Override
        public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
            if (first != null) {
                first.close();
            }
        }

        @Override
        public void afterRestore(Context<? extends Resource> context) {}
    }

    static class FileStreams {
        void acquire(Path path) throws IOException {
            Files.list(path);
            Files.walk(path);
            Files.find(path, 1, (entry, attributes) -> true);
            Files.lines(path);
            Files.newDirectoryStream(path);
            Files.readAllLines(path);
            Files.readString(path);
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Scheduled(fixedRate = 1000)
    @interface Rate {}

    @Retention(RetentionPolicy.RUNTIME)
    @Rate
    @interface MetaRate {}

    @Retention(RetentionPolicy.RUNTIME)
    @interface Unrelated {}

    @Retention(RetentionPolicy.RUNTIME)
    @Cycle
    @interface Cycle {}

    static class SchedulesFixture {
        @Scheduled(fixedDelay = 1000)
        @Scheduled(fixedRate = 2000)
        void repeated() {}

        @Rate
        void composed() {}

        @MetaRate
        void metaComposed() {}

        @Scheduled(fixedRate = -1, fixedRateString = "", fixedDelay = 1000)
        void disabled() {}

        @Schedules({})
        void empty() {}

        @Unrelated
        void unrelated() {}

        @Cycle
        void cyclic() {}
    }
}
