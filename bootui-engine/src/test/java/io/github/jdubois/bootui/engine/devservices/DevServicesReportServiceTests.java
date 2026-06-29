package io.github.jdubois.bootui.engine.devservices;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DevServiceDto;
import io.github.jdubois.bootui.core.dto.DevServicesReport;
import io.github.jdubois.bootui.spi.DevServicesProvider;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DevServicesReportServiceTests {

    private static DevServiceDto svc(String id, String name, String source) {
        return new DevServiceDto(id, name, "db", source, "img", "RUNNING", "h", List.of(), Map.of(), false, false, "");
    }

    @Test
    void sortsBySourceThenNameAndCounts() {
        DevServicesProvider provider = provider(
                List.of(
                        svc("c", "zeta", "Testcontainers"),
                        svc("a", "alpha", "Docker Compose"),
                        svc("b", "beta", "Docker Compose")),
                List.of("w1"));
        DevServicesReport report = new DevServicesReportService().report(provider);
        assertThat(report.total()).isEqualTo(3);
        assertThat(report.services()).extracting(DevServiceDto::name).containsExactly("alpha", "beta", "zeta");
        assertThat(report.dockerComposePresent()).isTrue();
        assertThat(report.testcontainersPresent()).isTrue();
        assertThat(report.snapshotTimestamp()).isEqualTo(42L);
        assertThat(report.warnings()).containsExactly("w1");
    }

    private static DevServicesProvider provider(List<DevServiceDto> services, List<String> warnings) {
        return new DevServicesProvider() {
            public boolean dockerComposePresent() {
                return true;
            }

            public boolean testcontainersPresent() {
                return true;
            }

            public long snapshotTimestamp() {
                return 42L;
            }

            public List<DevServiceDto> services() {
                return services;
            }

            public List<String> warnings() {
                return warnings;
            }
        };
    }
}
