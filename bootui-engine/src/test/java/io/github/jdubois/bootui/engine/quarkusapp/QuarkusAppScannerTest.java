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

    private static QuarkusAppSnapshot snapshot(
            List<String> mutable,
            int configProps,
            int endpoints,
            int defaultScopeResources,
            int reactive,
            int blocking,
            List<String> activeProfiles,
            List<String> prodKeys,
            boolean prodDevServices) {
        return new QuarkusAppSnapshot(
                3,
                1,
                0,
                0,
                mutable,
                configProps,
                endpoints,
                defaultScopeResources,
                reactive,
                blocking,
                1,
                activeProfiles,
                prodKeys,
                prodDevServices,
                false);
    }

    private static SpringRuleResultDto find(SpringReport r, String id) {
        return r.results().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
    }

    @Test
    void mutableAppScopedStateIsFlagged() {
        SpringReport r = QuarkusAppScanner.usingSnapshot(
                        () -> snapshot(List.of("Foo.count"), 2, 4, 0, 0, 0, List.of("prod"), List.of("%prod.x"), false),
                        CLOCK)
                .scan();
        assertThat(find(r, "QA-CDI-001").severity()).isEqualTo("MEDIUM");
        assertThat(r.scan().status()).isEqualTo("SCANNED");
    }

    @Test
    void noConfigPropertyIsFlagged() {
        SpringReport r = QuarkusAppScanner.usingSnapshot(
                        () -> snapshot(List.of(), 0, 4, 0, 0, 0, List.of("prod"), List.of("%prod.x"), false), CLOCK)
                .scan();
        assertThat(find(r, "QA-CFG-001")).isNotNull();
    }

    @Test
    void prodDevServicesIsHigh() {
        SpringReport r = QuarkusAppScanner.usingSnapshot(
                        () -> snapshot(
                                List.of(),
                                2,
                                4,
                                0,
                                0,
                                0,
                                List.of("prod"),
                                List.of("%prod.x.devservices.enabled"),
                                true),
                        CLOCK)
                .scan();
        assertThat(find(r, "QA-PROD-001").severity()).isEqualTo("HIGH");
    }

    @Test
    void cleanAppHasNoFindings() {
        SpringReport r = QuarkusAppScanner.usingSnapshot(
                        () -> snapshot(List.of(), 3, 4, 0, 0, 0, List.of("prod"), List.of("%prod.x"), false), CLOCK)
                .scan();
        assertThat(r.violationsFound()).isZero();
        assertThat(r.componentsAnalyzed()).isEqualTo(4);
    }

    @Test
    void dismissalsHideMatchingFindings() {
        QuarkusAppScanner scanner = QuarkusAppScanner.usingSnapshot(
                () -> snapshot(List.of(), 0, 4, 2, 0, 0, List.of(), List.of(), false), CLOCK);
        SpringReport scanned = scanner.scan();
        int before = scanned.violationsFound();
        SpringReport after = scanner.applyDismissals(scanned, Set.of("QA-CFG-001"));
        assertThat(after.violationsFound()).isEqualTo(before - 1);
    }

    @Test
    void initialReportIsNotScanned() {
        SpringReport r = QuarkusAppScanner.usingSnapshot(
                        () -> snapshot(List.of(), 2, 4, 0, 0, 0, List.of("prod"), List.of("%prod.x"), false), CLOCK)
                .initialReport();
        assertThat(r.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(r.violationsFound()).isZero();
    }
}
