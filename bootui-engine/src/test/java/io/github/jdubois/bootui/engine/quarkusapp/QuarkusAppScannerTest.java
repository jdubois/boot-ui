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
     * exactly the fields under test. Mirrors the 32-field {@link QuarkusAppSnapshot} positional record.
     */
    private static final class Snap {
        // CDI / beans (beanCount = applicationScoped + singleton + requestScoped + dependentScoped = 4)
        int applicationScoped = 3;
        int singleton = 1;
        int requestScoped = 0;
        int dependentScoped = 0;
        List<String> mutableAppScopedFields = List.of();
        List<String> publicResourceFields = List.of();
        // Config
        int configProperties = 2;
        int configMappings = 0;
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
        // Logging
        boolean prodLogLevelVerbose = false;
        // Web
        boolean compressionEnabled = true;
        boolean shutdownTimeoutZeroed = false;
        boolean restClientsRegistered = false;
        boolean restClientTimeoutConfigured = false;
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
                    restClientTimeoutConfigured,
                    virtualThreadEndpoints,
                    virtualThreadSynchronized,
                    jdkMajorVersion);
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
        assertThat(find(scan(firing), "QA-RX-001")).isNotNull();

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
        // reactiveEndpointsWithoutBlockingCount field, computed per reactive JAX-RS method.
        Snap s = new Snap();
        s.reactiveEndpoints = 2;
        s.reactiveEndpointsWithoutBlocking = 0; // both reactive endpoints carry @Blocking themselves
        s.blockingAnnotations = 1;
        s.jdbcDatasource = true;
        assertThat(find(scan(s), "QA-RX-001")).isNull();
    }

    @Test
    void prodDevServicesIsHigh() {
        Snap s = new Snap();
        s.prodDevServices = true;
        s.prodProfileKeys = List.of("%prod.x.devservices.enabled");
        SpringReport r = scan(s);
        assertThat(find(r, "QA-PROD-001").severity()).isEqualTo("HIGH");
    }

    @Test
    void prodDestructiveSchemaIsHigh() {
        Snap s = new Snap();
        s.prodSchemaGeneration = "drop-and-create";
        s.prodDbKind = "postgresql";
        SpringReport r = scan(s);
        assertThat(find(r, "QA-PROD-002").severity()).isEqualTo("HIGH");
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
    void noProfileConfigurationIsInfo() {
        Snap s = new Snap();
        s.activeProfiles = List.of();
        s.prodProfileKeys = List.of();
        SpringReport r = scan(s);
        assertThat(find(r, "QA-PROF-001").severity()).isEqualTo("INFO");
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
        SpringReport r = scan(s);
        assertThat(find(r, "QA-WEB-002").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void restClientWithoutTimeoutIsFlagged() {
        Snap s = new Snap();
        s.restClientsRegistered = true;
        s.restClientTimeoutConfigured = false;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-WEB-003").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void restClientWithTimeoutDoesNotFlagWeb003() {
        Snap s = new Snap();
        s.restClientsRegistered = true;
        s.restClientTimeoutConfigured = true;
        SpringReport r = scan(s);
        assertThat(find(r, "QA-WEB-003")).isNull();
    }

    @Test
    void noRestClientsDoesNotFlagWeb003() {
        Snap s = new Snap();
        s.restClientsRegistered = false;
        s.restClientTimeoutConfigured = false;
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
        assertThat(r.scan().rulesEvaluated()).isEqualTo(16);
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
