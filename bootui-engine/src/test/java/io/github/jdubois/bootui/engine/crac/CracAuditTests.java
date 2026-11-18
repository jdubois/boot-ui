package io.github.jdubois.bootui.engine.crac;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.jdubois.bootui.core.dto.CracFindingDto;
import io.github.jdubois.bootui.engine.archunit.ArchUnitBytecodeImports;
import io.github.jdubois.bootui.engine.crac.CracReadinessScanner.CracScanResult;
import java.io.Closeable;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.http.HttpClient;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import org.crac.Context;
import org.crac.Core;
import org.crac.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Schedules;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

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
        assertThat(result.checksRun()).isEqualTo(6);
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
            assertThat(result.checksRun()).isEqualTo(6);
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
            assertThat(result.checksRun()).isEqualTo(12);
            assertThat(finding(result, "CRAC-LIFECYCLE-002").status()).isEqualTo("SKIPPED");
            assertThat(finding(result, "CRAC-SCHED-001").status()).isEqualTo("SKIPPED");
            assertThat(scanner.latestRuntimeInventory().available()).isFalse();
            assertThat(result.warnings()).isNotEmpty().noneMatch(text -> text.contains("do-not-expose"));
        }
    }

    @Test
    void sameTypeCleanupDoesNotCreditTheOtherFieldEvenWhenTheOwnerSelfRegisters() {
        CracFindingDto result = finding(scanCaptured(TwoDirectories.class), "CRAC-RES-001");
        assertThat(result.status()).isEqualTo("REVIEW");
        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.occurrenceCount()).isEqualTo(2);
        assertThat(result.sampleOccurrences())
                .anyMatch(text -> text.contains(".first")
                        && text.contains("receiver-specific cleanup and constructor self-registration"))
                .anyMatch(text ->
                        text.contains(".second") && text.contains("no supported receiver-specific lifecycle cleanup"));
    }

    @Test
    void readingBothFieldsBesideAnUnrelatedCloseDoesNotProvideReceiverEvidence() {
        CracFindingDto result = finding(scanCaptured(UnrelatedClose.class), "CRAC-RES-001");
        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.occurrenceCount()).isEqualTo(2);
        assertThat(result.sampleOccurrences())
                .allMatch(text -> text.contains("no supported receiver-specific lifecycle cleanup"));
    }

    @Test
    void onlySelfRegistrationAndSupportedCleanupOfEveryFieldReduceTheReview() {
        for (Class<?> fixture : List.of(RegisteredDirectories.class, RegisteredTransportClients.class)) {
            String id = fixture == RegisteredDirectories.class ? "CRAC-RES-001" : "CRAC-POOL-002";
            CracFindingDto result = finding(scanCaptured(fixture), id);
            assertThat(result.status()).isEqualTo("REVIEW");
            assertThat(result.severity()).isEqualTo("MEDIUM");
            assertThat(result.occurrenceCount()).isEqualTo(2);
            assertThat(result.sampleOccurrences())
                    .allMatch(text -> text.contains("receiver-specific cleanup and constructor self-registration"))
                    .allSatisfy(text -> assertThat(text)
                            .startsWith("UNVERIFIED; no runtime/checkpoint guarantee; ")
                            .hasSizeLessThanOrEqualTo(240));
        }
    }

    @Test
    void transportCleanupDoesNotCreditAnotherSameTypedClient() {
        CracFindingDto result = finding(scanCaptured(PartiallyClosedTransportClients.class), "CRAC-POOL-002");
        assertThat(result.status()).isEqualTo("REVIEW");
        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.occurrenceCount()).isEqualTo(2);
        assertThat(result.sampleOccurrences())
                .anyMatch(text -> text.contains(".first")
                        && text.contains("receiver-specific cleanup and constructor self-registration"))
                .anyMatch(text ->
                        text.contains(".second") && text.contains("no supported receiver-specific lifecycle cleanup"));
    }

    @Test
    void importedCallbackOverridesInvalidateDeclaringOwnerCreditIncludingInheritedOverrides() {
        for (Class<?> subtype : List.of(
                OverridesDirectoryCleanup.class,
                InheritsOverriddenDirectoryCleanup.class,
                OverridesTransportCleanup.class)) {
            boolean transport = subtype == OverridesTransportCleanup.class;
            CracFindingDto result = finding(
                    scanCaptured(transport ? RegisteredTransportClients.class : RegisteredDirectories.class, subtype),
                    transport ? "CRAC-POOL-002" : "CRAC-RES-001");
            assertThat(result.status()).isEqualTo("REVIEW");
            assertThat(result.severity()).isEqualTo("HIGH");
            assertThat(result.occurrenceCount()).isEqualTo(2);
            assertThat(result.sampleOccurrences())
                    .allMatch(text -> text.contains("imported subtype overrides cleanup callback"));
        }
    }

    @Test
    void finalOwnersAndUnchangedInheritedCallbacksRetainOnlyBoundedReviewCredit() {
        for (CracScanResult scan : List.of(
                scanCaptured(RegisteredDirectories.class, InheritsDirectoryCleanup.class),
                scanCaptured(RegisteredDirectories.class, OverloadsDirectoryCleanup.class),
                scanCaptured(FinalCallbackDirectoryOwner.class, InheritsFinalDirectoryCleanup.class),
                scanCaptured(FinalDirectoryOwner.class))) {
            CracFindingDto result = finding(scan, "CRAC-RES-001");
            assertThat(result.status()).isEqualTo("REVIEW");
            assertThat(result.severity()).isEqualTo("MEDIUM");
            assertThat(result.sampleOccurrences())
                    .isNotEmpty()
                    .allSatisfy(text -> assertThat(text)
                            .contains("constructor self-registration observed")
                            .hasSizeLessThanOrEqualTo(240));
        }
    }

    @Test
    void absentOtherConditionalOrIncompleteConstructorRegistrationRemainHigh() {
        for (Class<?> fixture : List.of(
                UnregisteredDirectory.class,
                OtherResourceRegistration.class,
                AliasedSelfRegistration.class,
                ConditionalRegistration.class,
                OnlyOneConstructorRegisters.class)) {
            CracFindingDto result = finding(scanCaptured(fixture), "CRAC-RES-001");
            assertThat(result.status()).isEqualTo("REVIEW");
            assertThat(result.severity()).isEqualTo("HIGH");
            assertThat(result.sampleOccurrences())
                    .allMatch(
                            text -> text.contains("receiver-specific cleanup observed; owner registration unverified"));
        }
    }

    @Test
    void unsupportedAliasesAndHelpersOnAnotherInstanceRemainHigh() {
        for (Class<?> fixture : List.of(AliasedCleanup.class, OtherInstanceHelper.class)) {
            CracFindingDto result = finding(scanCaptured(fixture), "CRAC-RES-001");
            assertThat(result.severity()).isEqualTo("HIGH");
            assertThat(result.sampleOccurrences())
                    .allMatch(text -> text.contains("no supported receiver-specific lifecycle cleanup"));
        }
    }

    @Test
    void conditionalCallbackOrPrivateHelperCleanupCannotLowerTheReview() {
        for (Class<?> fixture : List.of(ConditionalCleanup.class, ConditionalHelperCleanup.class)) {
            CracFindingDto result = finding(scanCaptured(fixture), "CRAC-RES-001");
            assertThat(result.severity()).isEqualTo("HIGH");
            assertThat(result.sampleOccurrences())
                    .allMatch(text -> text.contains("callback/helper control flow unsupported"));
        }
    }

    @Test
    void missingCapturedBytecodeCannotLowerAReviewDespiteCleanupAndRegistration() {
        for (Class<?> fixture : List.of(RegisteredDirectories.class, RegisteredTransportClients.class)) {
            String id = fixture == RegisteredDirectories.class ? "CRAC-RES-001" : "CRAC-POOL-002";
            CracFindingDto result = finding(scan(CracRuntimeInventory.empty(), fixture), id);
            assertThat(result.status()).isEqualTo("REVIEW");
            assertThat(result.severity()).isEqualTo("HIGH");
            assertThat(result.sampleOccurrences())
                    .allMatch(text -> text.contains("import-time bytecode was not captured"));
        }
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
    void programmaticFixedRateSchedulingIsFoundButRestoreReschedulingAndFixedDelayAreNot() {
        CracFindingDto result = finding(
                scan(
                        CracRuntimeInventory.empty(),
                        ProgrammaticFixedRate.class,
                        RestoreRescheduling.class,
                        RegistrarFixedRate.class),
                "CRAC-SCHED-001");
        assertThat(result.status()).isEqualTo("REVIEW");
        assertThat(result.occurrenceCount()).isEqualTo(5);
        assertThat(result.sampleOccurrences())
                .anyMatch(text -> text.contains("executorRate") && text.contains("ScheduledExecutorService"))
                .anyMatch(text -> text.contains("poolRate") && text.contains("ScheduledExecutorService"))
                .anyMatch(text -> text.contains("timerRate") && text.contains("Timer"))
                .anyMatch(text -> text.contains("springRate") && text.contains("TaskScheduler"))
                .anyMatch(text -> text.contains("configureTasks") && text.contains("addFixedRateTask"))
                .noneMatch(text -> text.contains("RestoreRescheduling") || text.contains("delayOnly"));
    }

    @Test
    void preStartOnRefreshExcludesDeclarativeButNotProgrammaticFixedRateScheduling() {
        CracRuntimeInventory preStart = new CracRuntimeInventory(
                List.of(), List.of(), List.of(), List.of(), true, true, false, false, List.of(), true, List.of());

        CracFindingDto declarative =
                finding(scan(preStart, SchedulesFixture.class, RegistrarFixedRate.class), "CRAC-SCHED-001");
        assertThat(declarative.status()).isEqualTo("SKIPPED");

        CracFindingDto programmatic =
                finding(scan(preStart, SchedulesFixture.class, ProgrammaticFixedRate.class), "CRAC-SCHED-001");
        assertThat(programmatic.status()).isEqualTo("REVIEW");
        assertThat(programmatic.occurrenceCount()).isEqualTo(4);
        assertThat(programmatic.sampleOccurrences()).noneMatch(text -> text.contains("SchedulesFixture"));
    }

    @Test
    void staticNetworkIdentityLookupsAreFoundButInstanceAndLoopbackLookupsAreNot() {
        CracFindingDto result = finding(
                scan(CracRuntimeInventory.empty(), StaticHostIdentity.class, LazyHostIdentity.class), "CRAC-NET-002");
        assertThat(result.status()).isEqualTo("REVIEW");
        assertThat(result.severity()).isEqualTo("LOW");
        assertThat(result.occurrenceCount()).isEqualTo(3);
        assertThat(result.sampleOccurrences())
                .allMatch(text -> text.contains("StaticHostIdentity"))
                .anyMatch(text -> text.contains("getLocalHost"))
                .anyMatch(text -> text.contains("getHostName"))
                .anyMatch(text -> text.contains("getNetworkInterfaces"))
                .noneMatch(text -> text.contains("getLoopbackAddress"));
        assertThat(StaticNetworkIdentityCheck.isNetworkIdentityLookup("java.net.InetAddress", "getByAddress"))
                .isFalse();
        assertThat(StaticNetworkIdentityCheck.isNetworkIdentityLookup("java.net.InetAddress", "getAllByName"))
                .isTrue();
    }

    @Test
    void generatorFieldsAloneAreMediumButExplicitSecureRandomSeedingIsHigh() {
        CracFindingDto fields = finding(scan(CracRuntimeInventory.empty(), GeneratorFields.class), "CRAC-RANDOM-001");
        assertThat(fields.status()).isEqualTo("REVIEW");
        assertThat(fields.severity()).isEqualTo("MEDIUM");
        assertThat(fields.occurrenceCount()).isEqualTo(3);
        assertThat(fields.sampleOccurrences())
                .anyMatch(text -> text.contains("splittable"))
                .anyMatch(text -> text.contains("threadLocal"))
                .noneMatch(text -> text.contains("secure") || text.contains("generator"));

        CracFindingDto seeded = finding(
                scan(CracRuntimeInventory.empty(), GeneratorFields.class, ExplicitSeed.class), "CRAC-RANDOM-001");
        assertThat(seeded.severity()).isEqualTo("HIGH");
        assertThat(seeded.occurrenceCount()).isEqualTo(4);
    }

    @Test
    void transportOwningClientTypesIncludeMessagingClientsButNotFacadesOrGenericInterfaces() {
        for (String owner : List.of(
                "org.apache.kafka.clients.producer.KafkaProducer",
                "org.apache.kafka.clients.consumer.KafkaConsumer",
                "io.lettuce.core.AbstractRedisClient",
                "redis.clients.jedis.JedisPool",
                "redis.clients.jedis.UnifiedJedis",
                "io.netty.channel.EventLoopGroup",
                "java.net.http.HttpClient")) {
            assertThat(UnmanagedHttpClientFieldCheck.isKnownTransportOwner(owner))
                    .as(owner)
                    .isTrue();
        }
        for (String facade : List.of(
                "org.apache.kafka.clients.producer.Producer",
                "org.springframework.kafka.core.KafkaTemplate",
                "org.springframework.data.redis.core.RedisTemplate",
                "org.springframework.web.client.RestClient",
                "reactor.netty.http.client.HttpClient")) {
            assertThat(UnmanagedHttpClientFieldCheck.isKnownTransportOwner(facade))
                    .as(facade)
                    .isFalse();
        }
    }

    @Test
    void springLifecycleCleanupIsFieldSpecificButNotProofOfSpringBeanOwnership() {
        var imported = ArchUnitBytecodeImports.importClasses(GracefulLoopOwner.class, GracefulLoop.class);
        JavaClass owner = imported.classes().get(GracefulLoopOwner.class);
        assertThat(CracFieldCleanupEvidence.read(owner, imported.bytecode())
                        .cleans(owner.getMethod("stop"), owner.getField("loops")))
                .isTrue();
        CracFindingDto result = finding(scanCaptured(SpringDirectoryOwner.class), "CRAC-RES-001");
        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.sampleOccurrences())
                .allMatch(text -> text.contains("Spring bean lifecycle ownership and running state unverified"));
    }

    @Test
    void persistenceRowDataPasswordsAreNotReportedButKeysAndStaticSecretsAre() {
        CracFindingDto result = finding(
                scan(CracRuntimeInventory.empty(), UserEntity.class, AddressEmbeddable.class, ServiceSecrets.class),
                "CRAC-SECRET-001");
        assertThat(result.status()).isEqualTo("REVIEW");
        assertThat(result.occurrenceCount()).isEqualTo(4);
        assertThat(result.sampleOccurrences())
                .anyMatch(text -> text.contains("UserEntity.signingKey"))
                .anyMatch(text -> text.contains("UserEntity.DEFAULT_PASSWORD"))
                .anyMatch(text -> text.contains("ServiceSecrets.apiToken"))
                .anyMatch(text -> text.contains("ServiceSecrets.password"))
                .noneMatch(text -> text.contains("UserEntity.password") || text.contains("AddressEmbeddable"));
    }

    @Test
    void startupDatabaseAccessIsReviewedOnlyWithCheckpointIntent() {
        List<String> access =
                List.of("Flyway migration initializer flywayInitializer : example.FlywayMigrationInitializer;"
                        + " Hikari pool(s) without an in-memory JDBC URL: dataSource");
        for (boolean api : List.of(true, false)) {
            for (boolean onRefresh : List.of(true, false)) {
                CracRuntimeInventory runtime = new CracRuntimeInventory(
                        List.of(), List.of(), List.of(), List.of(), api, onRefresh, false, true, List.of(), true,
                        List.of(), access);
                CracFindingDto result = finding(scan(runtime, SuppliedDate.class), "CRAC-POOL-005");
                if (api || onRefresh) {
                    assertThat(result.status()).isEqualTo("REVIEW");
                    assertThat(result.severity()).isEqualTo("MEDIUM");
                    assertThat(result.occurrenceCount()).isEqualTo(1);
                    assertThat(result.sampleOccurrences()).anyMatch(text -> text.contains("flywayInitializer"));
                } else {
                    assertThat(result.status()).isEqualTo("SKIPPED");
                }
            }
        }
        assertThat(finding(scan(CracRuntimeInventory.empty(), SuppliedDate.class), "CRAC-POOL-005")
                        .status())
                .isEqualTo("OK");
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

    @Test
    void datasourceMetadataRemainsAResourceReviewWithoutClaimingAPoolOrGlobalCoverage() {
        String observation =
                "embedded : example.EmbeddedDatabase - embedded DataSource; " + "checkpoint handling is unverified";
        CracRuntimeInventory runtime = new CracRuntimeInventory(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                true,
                false,
                false,
                true,
                List.of("managedJms"),
                true,
                List.of(),
                List.of(),
                List.of(observation));

        CracFindingDto result = finding(scan(runtime, SuppliedDate.class), "CRAC-POOL-001");

        assertThat(result.status()).isEqualTo("REVIEW");
        assertThat(result.severity()).isEqualTo("HIGH");
        assertThat(result.occurrenceCount()).isEqualTo(1);
        assertThat(result.sampleOccurrences()).containsExactly(observation);
        assertThat(result.name()).contains("resources");
    }

    private static CracScanResult scan(CracRuntimeInventory runtime, Class<?>... classes) {
        return new CracReadinessScanner(
                        () -> List.of("example"),
                        packages -> new ClassFileImporter().importClasses(classes),
                        Clock.systemUTC(),
                        () -> runtime)
                .scan();
    }

    private static CracScanResult scanCaptured(Class<?>... classes) {
        return new CracReadinessScanner(
                        () -> List.of("example"),
                        new CracClassImporter() {
                            @Override
                            public JavaClasses importPackages(List<String> packages) {
                                throw new AssertionError("Scanner must request import-time bytecode");
                            }

                            @Override
                            public ArchUnitBytecodeImports.Result importWithBytecode(List<String> packages) {
                                return ArchUnitBytecodeImports.importClasses(classes);
                            }
                        },
                        Clock.systemUTC())
                .scan();
    }

    private static CracFindingDto finding(CracScanResult result, String id) {
        return result.findings().stream()
                .filter(finding -> id.equals(finding.id()))
                .findFirst()
                .orElseThrow();
    }

    static class ProgrammaticFixedRate {
        void executorRate(ScheduledExecutorService executor) {
            executor.scheduleAtFixedRate(() -> {}, 0, 1, TimeUnit.SECONDS);
        }

        void poolRate(ScheduledThreadPoolExecutor executor) {
            executor.scheduleAtFixedRate(() -> {}, 0, 1, TimeUnit.SECONDS);
        }

        void timerRate(Timer timer, TimerTask task) {
            timer.scheduleAtFixedRate(task, 0, 1000);
        }

        void springRate(TaskScheduler scheduler) {
            scheduler.scheduleAtFixedRate(() -> {}, Duration.ofSeconds(1));
        }

        void delayOnly(ScheduledExecutorService executor, Timer timer, TimerTask task) {
            executor.scheduleWithFixedDelay(() -> {}, 0, 1, TimeUnit.SECONDS);
            timer.schedule(task, 0, 1000);
        }
    }

    static class RestoreRescheduling implements Resource {
        ScheduledExecutorService executor;

        @Override
        public void beforeCheckpoint(Context<? extends Resource> context) {}

        @Override
        public void afterRestore(Context<? extends Resource> context) {
            executor.scheduleAtFixedRate(() -> {}, 0, 1, TimeUnit.SECONDS);
        }
    }

    static class RegistrarFixedRate implements SchedulingConfigurer {
        @Override
        public void configureTasks(ScheduledTaskRegistrar registrar) {
            registrar.addFixedRateTask(() -> {}, Duration.ofSeconds(1));
        }
    }

    static class StaticHostIdentity {
        static final String HOST;
        static final Object INTERFACES;
        static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

        static {
            try {
                HOST = InetAddress.getLocalHost().getHostName();
                INTERFACES = NetworkInterface.getNetworkInterfaces();
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    static class LazyHostIdentity {
        String host() throws IOException {
            return InetAddress.getLocalHost().getHostName();
        }
    }

    static class GeneratorFields {
        Random random;
        SplittableRandom splittable;
        RandomGenerator generator;
        ThreadLocalRandom threadLocal;
        SecureRandom secure;
    }

    static class GracefulLoop {
        void shutdownGracefully() {}
    }

    static class GracefulLoopOwner implements SmartLifecycle {
        GracefulLoop loops;

        @Override
        public void start() {}

        @Override
        public void stop() {
            loops.shutdownGracefully();
        }

        @Override
        public boolean isRunning() {
            return true;
        }
    }

    @jakarta.persistence.Entity
    static class UserEntity {
        static String DEFAULT_PASSWORD = "";
        String password;
        java.security.PrivateKey signingKey;
    }

    @jakarta.persistence.Embeddable
    static class AddressEmbeddable {
        String accessToken;
    }

    static class ServiceSecrets {
        String apiToken;
        char[] password;
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

        TwoDirectories() {
            Core.getGlobalContext().register(this);
        }

        @Override
        public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
            first.close();
        }

        @Override
        public void afterRestore(Context<? extends Resource> context) {}
    }

    static class RegisteredDirectories implements Resource {
        DirectoryStream<Path> first;
        DirectoryStream<Path> second;

        RegisteredDirectories() {
            Core.getGlobalContext().register(this);
        }

        @Override
        public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
            first.close();
            releaseSecond();
        }

        private void releaseSecond() throws IOException {
            nestedRelease();
        }

        private void nestedRelease() throws IOException {
            second.close();
        }

        @Override
        public void afterRestore(Context<? extends Resource> context) {}
    }

    static class OverridesDirectoryCleanup extends RegisteredDirectories {
        @Override
        public void beforeCheckpoint(Context<? extends Resource> context) {}
    }

    static class InheritsOverriddenDirectoryCleanup extends OverridesDirectoryCleanup {}

    static final class InheritsDirectoryCleanup extends RegisteredDirectories {}

    static final class OverloadsDirectoryCleanup extends RegisteredDirectories {
        public void beforeCheckpoint(String unrelated) {}
    }

    static class FinalCallbackDirectoryOwner implements Resource {
        DirectoryStream<Path> directory;

        FinalCallbackDirectoryOwner() {
            Core.getGlobalContext().register(this);
        }

        @Override
        public final void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
            directory.close();
        }

        @Override
        public void afterRestore(Context<? extends Resource> context) {}
    }

    static final class InheritsFinalDirectoryCleanup extends FinalCallbackDirectoryOwner {}

    static final class FinalDirectoryOwner implements Resource {
        DirectoryStream<Path> directory;

        FinalDirectoryOwner() {
            Core.getGlobalContext().register(this);
        }

        @Override
        public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
            directory.close();
        }

        @Override
        public void afterRestore(Context<? extends Resource> context) {}
    }

    abstract static class CloseableTransport extends HttpClient implements Closeable {}

    static class RegisteredTransportClients implements Resource {
        CloseableTransport first;
        CloseableTransport second;

        RegisteredTransportClients() {
            Core.getGlobalContext().register(this);
        }

        @Override
        public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
            first.close();
            releaseSecond();
        }

        private void releaseSecond() throws IOException {
            second.close();
        }

        @Override
        public void afterRestore(Context<? extends Resource> context) {}
    }

    static class OverridesTransportCleanup extends RegisteredTransportClients {
        @Override
        public void beforeCheckpoint(Context<? extends Resource> context) {}
    }

    static class PartiallyClosedTransportClients implements Resource {
        CloseableTransport first;
        CloseableTransport second;

        PartiallyClosedTransportClients() {
            Core.getGlobalContext().register(this);
        }

        @Override
        public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
            first.close();
        }

        @Override
        public void afterRestore(Context<? extends Resource> context) {}
    }

    static class UnrelatedClose implements Resource {
        DirectoryStream<Path> first;
        DirectoryStream<Path> second;

        UnrelatedClose() {
            Core.getGlobalContext().register(this);
        }

        @Override
        public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
            System.out.println(first);
            System.out.println(second);
            Files.newDirectoryStream(Path.of(".")).close();
        }

        @Override
        public void afterRestore(Context<? extends Resource> context) {}
    }

    static class UnregisteredDirectory implements Resource {
        DirectoryStream<Path> directory;

        @Override
        public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
            directory.close();
        }

        @Override
        public void afterRestore(Context<? extends Resource> context) {}
    }

    static class OtherResourceRegistration implements Resource {
        DirectoryStream<Path> directory;

        OtherResourceRegistration(Resource other) {
            Core.getGlobalContext().register(other);
        }

        @Override
        public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
            directory.close();
        }

        @Override
        public void afterRestore(Context<? extends Resource> context) {}
    }

    static class ConditionalRegistration implements Resource {
        DirectoryStream<Path> directory;

        ConditionalRegistration(boolean register) {
            if (register) Core.getGlobalContext().register(this);
        }

        @Override
        public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
            directory.close();
        }

        @Override
        public void afterRestore(Context<? extends Resource> context) {}
    }

    static class AliasedSelfRegistration implements Resource {
        DirectoryStream<Path> directory;

        AliasedSelfRegistration() {
            Resource alias = this;
            Core.getGlobalContext().register(alias);
        }

        @Override
        public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
            directory.close();
        }

        @Override
        public void afterRestore(Context<? extends Resource> context) {}
    }

    static class ConditionalCleanup implements Resource {
        DirectoryStream<Path> directory;
        boolean shouldClose;

        ConditionalCleanup() {
            Core.getGlobalContext().register(this);
        }

        @Override
        public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
            if (shouldClose) directory.close();
        }

        @Override
        public void afterRestore(Context<? extends Resource> context) {}
    }

    static class ConditionalHelperCleanup implements Resource {
        DirectoryStream<Path> directory;
        boolean shouldClose;

        ConditionalHelperCleanup() {
            Core.getGlobalContext().register(this);
        }

        @Override
        public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
            release();
        }

        private void release() throws IOException {
            if (shouldClose) directory.close();
        }

        @Override
        public void afterRestore(Context<? extends Resource> context) {}
    }

    static class OnlyOneConstructorRegisters implements Resource {
        DirectoryStream<Path> directory;

        OnlyOneConstructorRegisters() {
            Core.getGlobalContext().register(this);
        }

        OnlyOneConstructorRegisters(DirectoryStream<Path> directory) {
            this.directory = directory;
        }

        @Override
        public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
            directory.close();
        }

        @Override
        public void afterRestore(Context<? extends Resource> context) {}
    }

    static class AliasedCleanup implements Resource {
        DirectoryStream<Path> directory;

        AliasedCleanup() {
            Core.getGlobalContext().register(this);
        }

        @Override
        public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
            DirectoryStream<Path> alias = directory;
            alias.close();
        }

        @Override
        public void afterRestore(Context<? extends Resource> context) {}
    }

    static class OtherInstanceHelper implements Resource {
        DirectoryStream<Path> directory;
        OtherInstanceHelper peer;

        OtherInstanceHelper() {
            Core.getGlobalContext().register(this);
        }

        @Override
        public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
            peer.release();
        }

        private void release() throws IOException {
            directory.close();
        }

        @Override
        public void afterRestore(Context<? extends Resource> context) {}
    }

    static class SpringDirectoryOwner implements SmartLifecycle {
        DirectoryStream<Path> directory;

        @Override
        public void start() {}

        @Override
        public void stop() {
            try {
                directory.close();
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public boolean isRunning() {
            return true;
        }
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
