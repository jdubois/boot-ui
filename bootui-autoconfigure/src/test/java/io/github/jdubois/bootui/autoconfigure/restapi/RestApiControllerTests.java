package io.github.jdubois.bootui.autoconfigure.restapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.web.DismissedRulesStore;
import io.github.jdubois.bootui.core.dto.RestApiReport;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class RestApiControllerTests {

    private static final String FIXTURES = "io.github.jdubois.bootui.autoconfigure.restapi.fixtures";

    private RestApiController controller() {
        RestApiScanner scanner = new RestApiScanner(
                () -> List.of(FIXTURES),
                new ClassFileRestApiImporter(),
                () -> false,
                Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC));
        return new RestApiController(scanner, new DismissedRulesStore(Path.of("target", "no-such-dir", "boot-ui.yml")));
    }

    @Test
    void getReturnsNotScannedReportBeforeAnyScan() {
        RestApiReport report = controller().restApi();

        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.results()).isEmpty();
    }

    @Test
    void scanRunsRulesAndCachesTheResult() {
        RestApiController controller = controller();

        RestApiReport scanned = controller.scan();
        assertThat(scanned.scan().status()).isEqualTo("SCANNED");
        assertThat(scanned.violationsFound()).isPositive();

        // The cached report is returned on subsequent GETs.
        assertThat(controller.restApi()).isSameAs(scanned);
    }
}
