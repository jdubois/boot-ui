package io.github.jdubois.bootui.quarkus.devservices;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DevServiceDto;
import io.github.jdubois.bootui.quarkus.QuarkusExposurePolicy;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test of {@link QuarkusDevServicesProvider}'s mapping from build-time-captured
 * {@link RawDevService} entries to the neutral {@link DevServiceDto}. In particular, it pins that the
 * service {@code type} is classified via the shared
 * {@code io.github.jdubois.bootui.engine.devservices.DevServiceTypeInference} engine helper, so a Postgres
 * (or other well-known) Quarkus Dev Service is typed the same way the Spring adapter types an identical
 * name/description/config, instead of the previous generic {@code "Dev Service"} for every entry.
 */
class QuarkusDevServicesProviderTest {

    @Test
    void classifiesWellKnownDevServicesByType() {
        SmallRyeConfig config = new SmallRyeConfigBuilder().build();
        QuarkusDevServices captured = new QuarkusDevServices(List.of(
                new RawDevService(
                        "default",
                        "postgres:16",
                        "abc123",
                        Map.of("quarkus.datasource.jdbc.url", "jdbc:postgresql://localhost:5432/app")),
                new RawDevService("cache", "redis:7", "def456", Map.of()),
                new RawDevService("unknown", "custom-image:1", "", Map.of())));
        QuarkusDevServicesProvider provider =
                new QuarkusDevServicesProvider(() -> Optional.of(captured), new QuarkusExposurePolicy(config));

        List<String> types =
                provider.services().stream().map(DevServiceDto::type).toList();

        assertThat(types).containsExactly("PostgreSQL", "Redis", "Service");
    }

    @Test
    void returnsEmptyListWhenNoDevServicesWereCaptured() {
        SmallRyeConfig config = new SmallRyeConfigBuilder().build();
        QuarkusDevServicesProvider provider =
                new QuarkusDevServicesProvider(Optional::empty, new QuarkusExposurePolicy(config));

        assertThat(provider.services()).isEmpty();
        assertThat(provider.dockerComposePresent()).isFalse();
        assertThat(provider.testcontainersPresent()).isFalse();
    }
}
