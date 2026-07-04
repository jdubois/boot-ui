package io.github.jdubois.bootui.engine.quarkusapp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.SpringReport;
import io.github.jdubois.bootui.core.dto.SpringRuleResultDto;
import io.github.jdubois.bootui.spi.QuarkusAppSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QuarkusAppScannerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1000), ZoneOffset.UTC);

    /**
     * Mutable builder whose defaults describe a clean Quarkus app that fires zero rules, so each test can flip
     * exactly the fields under test. Mirrors the 36-field {@link QuarkusAppSnapshot} positional record.
     */
    private static final class Snap {
        // CDI / beans (beanCount = applicationScoped + singleton + requestScoped + dependentScoped = 4)
        int applicationScoped = 3;
        int singleton = 1;
        int requestScoped = 0;
        int dependentScoped = 0;
        List<String> mutableAppScopedFields = List.of();
        List<String> publicResourceFields = List.of();
        List<String> mutableSingletonFields = List.of();
        // Config
        int configProperties = 2;
        int configMappings = 0;
        boolean legacySchemaGenerationPropertyUsed = false;
        // Endpoints / reactive
        int endpoints = 4;
        int defaultScopeResources = 0;
        int reactiveEndpoints = 0;
        int reactiveEndpointsWithoutBlocking = 0;
        int blockingAnnotations = 0;
        // Scheduling
        int scheduled = 0;
        boolean clusteredScheduler = false;
        // Profiles
        List<String> activeProfiles = List.of("prod");
        List<String> prodProfileKeys = List.of("%prod.x");
        boolean prodDevServices = false;
        boolean nativeBuild = false;
        // Datasource
        boolean jdbcDatasource = false;
        String prodSchemaGeneration = "";
        String prodDbKind = "";
        boolean prodJdbcUrlInMemory = false;
        boolean prodSqlLogging = false;
        boolean datasourceMaxSizeConfigured = true;
        // Logging
        boolean prodLogLevelVerbose = false;
        // Web
        boolean compressionEnabled = true;
        boolean shutdownTimeoutZeroed = false;
        boolean shutdownTimeoutConfigured = true;
        boolean restClientsRegistered = false;
        boolean restClientTimeoutZeroOrExcessive = false;
        // Performance / virtual threads (jdkMajorVersion=21 + 1 adopting endpoint is a clean modern baseline)
        int virtualThreadEndpoints = 1;
        int virtualThreadSynchronized = 0;
        int jdkMajorVersion = 21;

        QuarkusAppSnapshot build() {
            return new QuarkusAppSnapshot(
                    applicationScoped,
                    singleton,
                    requestScoped,
                    dependentScoped,
                    mutableAppScopedFields,
                    configProperties,
                    endpoints,
                    defaultScopeResources,
                    reactiveEndpoints,
                    reactiveEndpointsWithoutBlocking,
                    blockingAnnotations,
                    scheduled,
                    activeProfiles,
                    prodProfileKeys,
                    prodDevServices,
                    nativeBuild,
                    configMappings,
                    jdbcDatasource,
                    prodSchemaGeneration,
                    prodDbKind,
                    prodJdbcUrlInMemory,
                    prodSqlLogging,
                    clusteredScheduler,
                    publicResourceFields,
                    prodLogLevelVerbose,
                    compressionEnabled,
                    shutdownTimeoutZeroed,
                    restClientsRegistered,
                    restClientTimeoutZeroOrExcessive,
                    virtualThreadEndpoints,
                    virtualThreadSynchronized,
                    jdkMajorVersion,
                    mutableSingletonFields,
                    legacySchemaGenerationPropertyUsed,
                    shutdownTimeoutConfigured,
                    datasourceMaxSizeConfigured);
        }
    }

    private static SpringRuleResultDto find(SpringReport r, String id) {
        return r.results().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
    }

    private static SpringReport scan(Snap s) {
        return QuarkusAppScanner.usingSnapshot(s::build, CLOCK).scan();
    }

    @Test
    void cleanAppHasNoFindings() {
        SpringReport r = scan(new Snap());
        assertThat(r.violationsFound()).isZero();
        assertThat(r.componentsAnalyzed()).isEqualTo(4);
        assertThat(r.scan().status()).isEqualTo("SCANNED");
    }

    @Test
    void mutableAppScopedStateIsFlagged() {
        Snap s = new Snap();
        s.mutableAppScopedFields = List.of("Foo.count");
        SpringReport r = scan(s);
        assertThat(find(r, "QA-CDI-001").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void publicResourceFieldIsFlagged() {
        Snap s = new Snap();
        s.publicResourceFields = List.of("WidgetResource.cache");
        SpringReport r = scan(s);
        assertThat(find(r, "QA-CDI-002").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void mutableSingletonFieldIsFlagged() {
        // QA-CDI-003: a @Singleton bean shares the exact same single-shared-instance risk as
        // @ApplicationScoped (QA-CDI-001); the @RequestScoped exclusion for QA-CDI-002 itself is enforced
        // upstream in the Jandex scan (BootUiQuarkusProcessor), not observable at this snapshot level.
        Snap s = new Snap();
        s.mutableSingletonFields = List.of("CounterService.total");
        SpringReport r = scan(s);
        assertThat(find(r, "QA-CDI-003").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void noTypeSafeConfigIsFlagged() {
        Snap s = new Snap();
        s.configProperties = 0;
        s.configMappings = 0;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-CFG-001")).isNotNull();
    }

    @Test
    void configMappingSuppressesConfigPropertyFinding() {
        Snap s = new Snap();
        s.configProperties = 0;
        s.configMappings = 1;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-CFG-001")).isNull();
    }

    @Test
    void reactiveWithJdbcDatasourceIsFlagged() {
        Snap firing = new Snap();
        firing.reactiveEndpoints = 2;
        firing.reactiveEndpointsWithoutBlocking = 2;
        firing.jdbcDatasource = true;
        // QA-RX-001 was raised from INFO to HIGH: blocking the Vert.x event loop is one of the most severe,
        // most common Quarkus production footguns (throws BlockingOperationNotAllowedException at runtime).
        assertThat(find(scan(firing), "QA-RX-001").severity()).isEqualTo("HIGH");

        Snap noJdbc = new Snap();
        noJdbc.reactiveEndpoints = 2;
        noJdbc.reactiveEndpointsWithoutBlocking = 2;
        noJdbc.jdbcDatasource = false;
        assertThat(find(scan(noJdbc), "QA-RX-001")).isNull();
    }

    @Test
    void blockingGuardedReactiveEndpointsDoNotFlagRx001() {
        // Regression: the old app-wide blockingAnnotationCount==0 check false-negatived whenever ANY unrelated
        // @Blocking existed anywhere in the app; the fix correlates per-endpoint via the
        // reactiveEndpointsWithoutBlockingCount field, computed per reactive JAX-RS method. Note: whether
        // @Transactional also counts as a guard (alongside @Blocking) is computed upstream in
        // BootUiQuarkusProcessor's Jandex scan, before reactiveEndpointsWithoutBlockingCount reaches this
        // snapshot, so it is covered by a build-step-level test rather than here.
        Snap s = new Snap();
        s.reactiveEndpoints = 2;
        s.reactiveEndpointsWithoutBlocking = 0; // both reactive endpoints carry @Blocking/@Transactional themselves
        s.blockingAnnotations = 1;
        s.jdbcDatasource = true;
        assertThat(find(scan(s), "QA-RX-001")).isNull();
    }

    @Test
    void prodDevServicesIsLow() {
        // QA-PROD-001 was lowered from HIGH to LOW: Dev Services is build-time/augmentation-only and never
        // runs in a packaged LaunchMode.NORMAL production build regardless of this override, so it is a
        // config-hygiene signal, not an active production risk.
        Snap s = new Snap();
        s.prodDevServices = true;
        s.prodProfileKeys = List.of("%prod.x.devservices.enabled");
        SpringReport r = scan(s);
        assertThat(find(r, "QA-PROD-001").severity()).isEqualTo("LOW");
    }

    @Test
    void prodDestructiveCreateSchemaIsCritical() {
        // QA-PROD-002: drop-and-create/create/drop rebuild or drop the schema outright, aligned with the
        // sibling Hibernate advisor's HIB-CONFIG-002 CRITICAL severity for the same condition.
        Snap s = new Snap();
        s.prodSchemaGeneration = "drop-and-create";
        s.prodDbKind = "postgresql";
        SpringReport r = scan(s);
        assertThat(find(r, "QA-PROD-002").severity()).isEqualTo("CRITICAL");
    }

    @Test
    void prodUpdateSchemaIsHigh() {
        // QA-PROD-002: 'update' silently alters the schema in place rather than rebuilding it outright, so it
        // is a step down from CRITICAL but still HIGH (was previously not flagged as destructive at all).
        Snap s = new Snap();
        s.prodSchemaGeneration = "update";
        s.prodDbKind = "postgresql";
        SpringReport r = scan(s);
        assertThat(find(r, "QA-PROD-002").severity()).isEqualTo("HIGH");
    }

    @Test
    void prodValidateSchemaIsNotFlagged() {
        Snap s = new Snap();
        s.prodSchemaGeneration = "validate";
        s.prodDbKind = "postgresql";
        SpringReport r = scan(s);
        assertThat(find(r, "QA-PROD-002")).isNull();
    }

    @Test
    void prodInMemoryDatasourceIsFlagged() {
        Snap byKind = new Snap();
        byKind.prodDbKind = "h2";
        assertThat(find(scan(byKind), "QA-PROD-003").severity()).isEqualTo("MEDIUM");

        Snap byUrl = new Snap();
        byUrl.prodJdbcUrlInMemory = true;
        assertThat(find(scan(byUrl), "QA-PROD-003")).isNotNull();
    }

    @Test
    void datasourceWithoutMaxSizeIsFlagged() {
        Snap s = new Snap();
        s.jdbcDatasource = true;
        s.datasourceMaxSizeConfigured = false;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-DB-001").severity()).isEqualTo("LOW");
    }

    @Test
    void datasourceWithMaxSizeConfiguredDoesNotFlagDb001() {
        Snap s = new Snap();
        s.jdbcDatasource = true;
        s.datasourceMaxSizeConfigured = true;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-DB-001")).isNull();
    }

    @Test
    void noDatasourceDoesNotFlagDb001() {
        Snap s = new Snap();
        s.jdbcDatasource = false;
        s.datasourceMaxSizeConfigured = false;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-DB-001")).isNull();
    }

    @Test
    void prodSqlLoggingIsFlagged() {
        Snap s = new Snap();
        s.prodSqlLogging = true;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-CFG-002").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void prodVerboseLogLevelIsFlagged() {
        Snap s = new Snap();
        s.prodLogLevelVerbose = true;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-CFG-003").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void legacySchemaGenerationPropertyIsFlagged() {
        Snap s = new Snap();
        s.legacySchemaGenerationPropertyUsed = true;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-CFG-004").severity()).isEqualTo("LOW");
    }

    @Test
    void currentSchemaManagementPropertyDoesNotFlagCfg004() {
        Snap s = new Snap();
        s.legacySchemaGenerationPropertyUsed = false;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-CFG-004")).isNull();
    }

    @Test
    void scheduledWithoutClusteringIsFlagged() {
        Snap firing = new Snap();
        firing.scheduled = 1;
        assertThat(find(scan(firing), "QA-SCH-001").severity()).isEqualTo("LOW");

        Snap clustered = new Snap();
        clustered.scheduled = 1;
        clustered.clusteredScheduler = true;
        assertThat(find(scan(clustered), "QA-SCH-001")).isNull();
    }

    @Test
    void noProdOverridesIsFlagged() {
        // Regression: the old condition additionally required activeProfiles().isEmpty(), but
        // SmallRyeConfig.getProfiles() almost always returns a live profile (dev/test/prod) in a running app,
        // making the old rule a near-dead signal. The fix drops that requirement and fires on the absence of
        // %prod. overrides alone -- note activeProfiles is non-empty here (the Snap default, "prod"),
        // proving the fix.
        Snap s = new Snap();
        s.prodProfileKeys = List.of();
        SpringReport r = scan(s);
        assertThat(find(r, "QA-PROF-001").severity()).isEqualTo("INFO");
    }

    @Test
    void prodOverridesPresentDoesNotFlagProf001() {
        Snap s = new Snap(); // default Snap already carries a non-empty prodProfileKeys
        SpringReport r = scan(s);
        assertThat(find(r, "QA-PROF-001")).isNull();
    }

    @Test
    void compressionDisabledIsFlagged() {
        Snap s = new Snap();
        s.compressionEnabled = false;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-WEB-001").severity()).isEqualTo("INFO");
    }

    @Test
    void gracefulShutdownZeroedIsFlagged() {
        Snap s = new Snap();
        s.shutdownTimeoutZeroed = true;
        // Zeroing the timeout means it IS configured (to 0), so QA-WEB-004 ("never configured") is
        // mutually exclusive with QA-WEB-002 ("explicitly zeroed") by construction.
        s.shutdownTimeoutConfigured = true;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-WEB-002").severity()).isEqualTo("MEDIUM");
        assertThat(find(r, "QA-WEB-004")).isNull();
    }

    @Test
    void shutdownTimeoutNeverConfiguredIsFlagged() {
        Snap s = new Snap();
        s.shutdownTimeoutConfigured = false;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-WEB-004").severity()).isEqualTo("LOW");
        assertThat(find(r, "QA-WEB-002")).isNull();
    }

    @Test
    void shutdownTimeoutConfiguredDoesNotFlagWeb004() {
        Snap s = new Snap();
        s.shutdownTimeoutConfigured = true;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-WEB-004")).isNull();
    }

    @Test
    void restClientTimeoutZeroOrExcessiveIsFlagged() {
        // QA-WEB-003 was redesigned: it used to fire on the mere ABSENCE of a timeout override (a factually
        // wrong premise -- Quarkus REST clients already default to a 15s connect-timeout/30s read-timeout).
        // It now fires only on an explicit 0 (disabled) or excessive (>5m) override.
        Snap s = new Snap();
        s.restClientsRegistered = true;
        s.restClientTimeoutZeroOrExcessive = true;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-WEB-003").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void restClientWithoutOverrideDoesNotFlagWeb003() {
        // The Quarkus default (15s connect / 30s read) applies when nothing is overridden -- no longer flagged.
        Snap s = new Snap();
        s.restClientsRegistered = true;
        s.restClientTimeoutZeroOrExcessive = false;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-WEB-003")).isNull();
    }

    @Test
    void noRestClientsDoesNotFlagWeb003() {
        Snap s = new Snap();
        s.restClientsRegistered = false;
        s.restClientTimeoutZeroOrExcessive = false;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-WEB-003")).isNull();
    }

    @Test
    void noVirtualThreadAdoptionIsFlagged() {
        Snap s = new Snap();
        s.virtualThreadEndpoints = 0;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-PERF-001").severity()).isEqualTo("INFO");
    }

    @Test
    void virtualThreadAdoptionSuppressesPerf001() {
        Snap s = new Snap();
        s.virtualThreadEndpoints = 1;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-PERF-001")).isNull();
    }

    @Test
    void oldJdkDoesNotFlagPerf001() {
        // Virtual threads are not a mainstream concern before JDK 21; the rule intentionally does not fire.
        Snap s = new Snap();
        s.virtualThreadEndpoints = 0;
        s.jdkMajorVersion = 17;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-PERF-001")).isNull();
    }

    @Test
    void virtualThreadSynchronizedPinningIsFlagged() {
        Snap s = new Snap();
        s.virtualThreadSynchronized = 1;
        s.jdkMajorVersion = 21;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-PERF-002").severity()).isEqualTo("HIGH");
    }

    @Test
    void jdk24FixesVirtualThreadPinningPerf002() {
        // JEP 491 removes synchronized-block/method pinning of the carrier thread starting in JDK 24.
        Snap s = new Snap();
        s.virtualThreadSynchronized = 1;
        s.jdkMajorVersion = 24;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-PERF-002")).isNull();
    }

    @Test
    void noSynchronizedVirtualThreadMethodsDoesNotFlagPerf002() {
        Snap s = new Snap();
        s.virtualThreadSynchronized = 0;
        s.jdkMajorVersion = 21;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-PERF-002")).isNull();
    }

    @Test
    void rulesEvaluatedMatchesTotalRuleCount() {
        SpringReport r = scan(new Snap());
        assertThat(r.scan().rulesEvaluated()).isEqualTo(20);
    }

    @Test
    void dismissalsHideMatchingFindings() {
        Snap s = new Snap();
        s.configProperties = 0;
        s.configMappings = 0;
        QuarkusAppScanner scanner = QuarkusAppScanner.usingSnapshot(s::build, CLOCK);
        SpringReport scanned = scanner.scan();
        int before = scanned.violationsFound();
        SpringReport after = scanner.applyDismissals(scanned, Set.of("QA-CFG-001"));
        assertThat(after.violationsFound()).isEqualTo(before - 1);
    }

    @Test
    void initialReportIsNotScanned() {
        SpringReport r =
                QuarkusAppScanner.usingSnapshot(() -> new Snap().build(), CLOCK).initialReport();
        assertThat(r.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(r.violationsFound()).isZero();
    }
}
