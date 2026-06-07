package io.github.jdubois.bootui.autoconfigure.restadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.RestApiAdvisorReport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class RestApiAdvisorControllerTests {

    private static final String FIXTURES = "io.github.jdubois.bootui.autoconfigure.restadvisor.fixtures";

    private RestApiAdvisorController controller() {
        RestApiAdvisorScanner scanner = new RestApiAdvisorScanner(
                () -> List.of(FIXTURES),
                new ClassFileRestApiAdvisorImporter(),
                () -> false,
                Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC));
        return new RestApiAdvisorController(scanner);
    }

    @Test
    void getReturnsNotScannedReportBeforeAnyScan() {
        RestApiAdvisorReport report = controller().restAdvisor();

        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.results()).isEmpty();
    }

    @Test
    void scanRunsRulesAndCachesTheResult() {
        RestApiAdvisorController controller = controller();

        RestApiAdvisorReport scanned = controller.scan();
        assertThat(scanned.scan().status()).isEqualTo("SCANNED");
        assertThat(scanned.violationsFound()).isPositive();

        // The cached report is returned on subsequent GETs.
        assertThat(controller.restAdvisor()).isSameAs(scanned);
    }
}
