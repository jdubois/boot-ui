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

    /** Full-control builder; every field that could fire a rule is explicit. beanCount = appScoped+singleton = 4. */
    private static QuarkusAppSnapshot app(
            List<String> mutable,
            int configProps,
            int configMapping,
            int reactive,
            int blocking,
            boolean jdbc,
            int scheduled,
            boolean clustered,
            List<String> activeProfiles,
            List<String> prodKeys,
            boolean prodDevServices,
            String prodSchema,
            String prodDbKind,
            boolean prodInMem,
            boolean prodSqlLog,
            List<String> publicFields) {
        return new QuarkusAppSnapshot(
                3,
                1,
                0,
                0,
                mutable,
                configProps,
                4,
                0,
                reactive,
                blocking,
                scheduled,
                activeProfiles,
                prodKeys,
                prodDevServices,
                false,
                configMapping,
                jdbc,
                prodSchema,
                prodDbKind,
                prodInMem,
                prodSqlLog,
                clustered,
                publicFields);
    }

    /** A snapshot that fires no rules: type-safe config present, an active profile, nothing destructive. */
    private static QuarkusAppSnapshot clean() {
        return app(
                List.of(),
                2,
                0,
                0,
                0,
                false,
                0,
                false,
                List.of("prod"),
                List.of("%prod.x"),
                false,
                "",
                "",
                false,
                false,
                List.of());
    }

    private static SpringRuleResultDto find(SpringReport r, String id) {
        return r.results().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
    }

    private static SpringReport scan(QuarkusAppSnapshot s) {
        return QuarkusAppScanner.usingSnapshot(() -> s, CLOCK).scan();
    }

    @Test
    void mutableAppScopedStateIsFlagged() {
        SpringReport r = scan(app(
                List.of("Foo.count"),
                2,
                0,
                0,
                0,
                false,
                0,
                false,
                List.of("prod"),
                List.of("%prod.x"),
                false,
                "",
                "",
                false,
                false,
                List.of()));
        assertThat(find(r, "QA-CDI-001").severity()).isEqualTo("MEDIUM");
        assertThat(r.scan().status()).isEqualTo("SCANNED");
    }

    @Test
    void publicResourceFieldIsFlagged() {
        SpringReport r = scan(app(
                List.of(),
                2,
                0,
                0,
                0,
                false,
                0,
                false,
                List.of("prod"),
                List.of("%prod.x"),
                false,
                "",
                "",
                false,
                false,
                List.of("WidgetResource.cache")));
        assertThat(find(r, "QA-CDI-002").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void noTypeSafeConfigIsFlagged() {
        SpringReport r = scan(app(
                List.of(),
                0,
                0,
                0,
                0,
                false,
                0,
                false,
                List.of("prod"),
                List.of("%prod.x"),
                false,
                "",
                "",
                false,
                false,
                List.of()));
        assertThat(find(r, "QA-CFG-001")).isNotNull();
    }

    @Test
    void configMappingSuppressesConfigPropertyFinding() {
        SpringReport r = scan(app(
                List.of(),
                0,
                1,
                0,
                0,
                false,
                0,
                false,
                List.of("prod"),
                List.of("%prod.x"),
                false,
                "",
                "",
                false,
                false,
                List.of()));
        assertThat(find(r, "QA-CFG-001")).isNull();
    }

    @Test
    void reactiveWithJdbcDatasourceIsFlagged() {
        SpringReport firing = scan(app(
                List.of(),
                2,
                0,
                2,
                0,
                true,
                0,
                false,
                List.of("prod"),
                List.of("%prod.x"),
                false,
                "",
                "",
                false,
                false,
                List.of()));
        assertThat(find(firing, "QA-RX-001")).isNotNull();

        SpringReport noJdbc = scan(app(
                List.of(),
                2,
                0,
                2,
                0,
                false,
                0,
                false,
                List.of("prod"),
                List.of("%prod.x"),
                false,
                "",
                "",
                false,
                false,
                List.of()));
        assertThat(find(noJdbc, "QA-RX-001")).isNull();
    }

    @Test
    void prodDevServicesIsHigh() {
        SpringReport r = scan(app(
                List.of(),
                2,
                0,
                0,
                0,
                false,
                0,
                false,
                List.of("prod"),
                List.of("%prod.x.devservices.enabled"),
                true,
                "",
                "",
                false,
                false,
                List.of()));
        assertThat(find(r, "QA-PROD-001").severity()).isEqualTo("HIGH");
    }

    @Test
    void prodDestructiveSchemaIsHigh() {
        SpringReport r = scan(app(
                List.of(),
                2,
                0,
                0,
                0,
                false,
                0,
                false,
                List.of("prod"),
                List.of("%prod.x"),
                false,
                "drop-and-create",
                "postgresql",
                false,
                false,
                List.of()));
        assertThat(find(r, "QA-PROD-002").severity()).isEqualTo("HIGH");
    }

    @Test
    void prodInMemoryDatasourceIsFlagged() {
        SpringReport byKind = scan(app(
                List.of(),
                2,
                0,
                0,
                0,
                false,
                0,
                false,
                List.of("prod"),
                List.of("%prod.x"),
                false,
                "",
                "h2",
                false,
                false,
                List.of()));
        assertThat(find(byKind, "QA-PROD-003").severity()).isEqualTo("MEDIUM");

        SpringReport byUrl = scan(app(
                List.of(),
                2,
                0,
                0,
                0,
                false,
                0,
                false,
                List.of("prod"),
                List.of("%prod.x"),
                false,
                "",
                "",
                true,
                false,
                List.of()));
        assertThat(find(byUrl, "QA-PROD-003")).isNotNull();
    }

    @Test
    void prodSqlLoggingIsFlagged() {
        SpringReport r = scan(app(
                List.of(),
                2,
                0,
                0,
                0,
                false,
                0,
                false,
                List.of("prod"),
                List.of("%prod.x"),
                false,
                "",
                "",
                false,
                true,
                List.of()));
        assertThat(find(r, "QA-CFG-002").severity()).isEqualTo("MEDIUM");
    }

    @Test
    void scheduledWithoutClusteringIsFlagged() {
        SpringReport firing = scan(app(
                List.of(),
                2,
                0,
                0,
                0,
                false,
                1,
                false,
                List.of("prod"),
                List.of("%prod.x"),
                false,
                "",
                "",
                false,
                false,
                List.of()));
        assertThat(find(firing, "QA-SCH-001").severity()).isEqualTo("LOW");

        SpringReport clustered = scan(app(
                List.of(),
                2,
                0,
                0,
                0,
                false,
                1,
                true,
                List.of("prod"),
                List.of("%prod.x"),
                false,
                "",
                "",
                false,
                false,
                List.of()));
        assertThat(find(clustered, "QA-SCH-001")).isNull();
    }

    @Test
    void noProfileConfigurationIsInfo() {
        SpringReport r = scan(app(
                List.of(), 2, 0, 0, 0, false, 0, false, List.of(), List.of(), false, "", "", false, false, List.of()));
        assertThat(find(r, "QA-PROF-001").severity()).isEqualTo("INFO");
    }

    @Test
    void retiredEndpointRuleIsNeverEmitted() {
        SpringReport r = scan(app(
                List.of(), 0, 0, 0, 0, false, 0, false, List.of(), List.of(), false, "", "", false, false, List.of()));
        assertThat(find(r, "QA-EP-001")).isNull();
        assertThat(r.scan().rulesEvaluated()).isEqualTo(10);
    }

    @Test
    void cleanAppHasNoFindings() {
        SpringReport r = scan(clean());
        assertThat(r.violationsFound()).isZero();
        assertThat(r.componentsAnalyzed()).isEqualTo(4);
    }

    @Test
    void dismissalsHideMatchingFindings() {
        QuarkusAppScanner scanner = QuarkusAppScanner.usingSnapshot(
                () -> app(
                        List.of(), 0, 0, 0, 0, false, 0, false, List.of(), List.of(), false, "", "", false, false,
                        List.of()),
                CLOCK);
        SpringReport scanned = scanner.scan();
        int before = scanned.violationsFound();
        SpringReport after = scanner.applyDismissals(scanned, Set.of("QA-CFG-001"));
        assertThat(after.violationsFound()).isEqualTo(before - 1);
    }

    @Test
    void initialReportIsNotScanned() {
        SpringReport r = QuarkusAppScanner.usingSnapshot(() -> clean(), CLOCK).initialReport();
        assertThat(r.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(r.violationsFound()).isZero();
    }
}
