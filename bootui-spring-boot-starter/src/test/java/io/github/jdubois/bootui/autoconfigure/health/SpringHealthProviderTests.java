package io.github.jdubois.bootui.autoconfigure.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.github.jdubois.bootui.core.dto.HealthNodeDto;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.health.contributor.Status;

/**
 * Tests for the Actuator {@code HealthDescriptor} -&gt; {@link HealthNodeDto} mapping that
 * {@link SpringHealthProvider} concentrates.
 *
 * <p>{@link IndicatedHealthDescriptor} is {@code final} and uses {@link MockMakers#INLINE};
 * {@link CompositeHealthDescriptor} is non-final and uses the default mock maker. The structural
 * "only default contributors" / DISABLED-root shaping is covered by the engine {@code HealthServiceTests};
 * this test only pins the mapping and the null-endpoint contract.</p>
 */
class SpringHealthProviderTests {

    @Test
    void readRootReturnsNullWhenEndpointIsAbsent() {
        SpringHealthProvider provider = new SpringHealthProvider(() -> null);

        assertThat(provider.readRoot()).isNull();
    }

    @Test
    void readRootMapsAnIndicatedUpStatusWithDetails() {
        IndicatedHealthDescriptor indicated =
                mock(IndicatedHealthDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(indicated.getStatus()).thenReturn(Status.UP);
        when(indicated.getDetails()).thenReturn(Map.of("ping", "pong"));

        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.health()).thenReturn(indicated);

        HealthNodeDto root = new SpringHealthProvider(() -> endpoint).readRoot();

        assertThat(root.name()).isEqualTo("application");
        assertThat(root.status()).isEqualTo("UP");
        assertThat(root.details()).isEqualTo(Map.of("ping", "pong"));
        assertThat(root.components()).isEmpty();
        assertThat(root.available()).isTrue();
    }

    @Test
    void readRootMapsADownStatus() {
        IndicatedHealthDescriptor down =
                mock(IndicatedHealthDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(down.getStatus()).thenReturn(Status.DOWN);
        when(down.getDetails()).thenReturn(Map.of("error", "Connection refused"));

        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.health()).thenReturn(down);

        assertThat(new SpringHealthProvider(() -> endpoint).readRoot().status()).isEqualTo("DOWN");
    }

    @Test
    void readRootMapsACompositeWithComponents() {
        IndicatedHealthDescriptor db =
                mock(IndicatedHealthDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(db.getStatus()).thenReturn(Status.UP);
        when(db.getDetails()).thenReturn(Map.of("database", "H2"));

        IndicatedHealthDescriptor disk =
                mock(IndicatedHealthDescriptor.class, withSettings().mockMaker(MockMakers.INLINE));
        when(disk.getStatus()).thenReturn(new Status("DOWN"));
        when(disk.getDetails()).thenReturn(Map.of("free", 1024L));

        Map<String, HealthDescriptor> components = Map.of("db", db, "diskSpace", disk);
        CompositeHealthDescriptor composite = mock(CompositeHealthDescriptor.class);
        when(composite.getStatus()).thenReturn(new Status("DOWN"));
        when(composite.getComponents()).thenReturn(components);

        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.health()).thenReturn(composite);

        HealthNodeDto root = new SpringHealthProvider(() -> endpoint).readRoot();

        assertThat(root.name()).isEqualTo("application");
        assertThat(root.status()).isEqualTo("DOWN");
        assertThat(root.components()).hasSize(2);
        assertThat(root.components().stream()
                        .filter(c -> c.name().equals("db"))
                        .findFirst()
                        .orElseThrow()
                        .status())
                .isEqualTo("UP");
        assertThat(root.components().stream()
                        .filter(c -> c.name().equals("diskSpace"))
                        .findFirst()
                        .orElseThrow()
                        .status())
                .isEqualTo("DOWN");
    }

    @Test
    void readRootMapsNullHealthToAnUnknownNodeNotADisabledRoot() {
        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.health()).thenReturn(null);

        HealthNodeDto root = new SpringHealthProvider(() -> endpoint).readRoot();

        assertThat(root).isNotNull();
        assertThat(root.name()).isEqualTo("application");
        assertThat(root.status()).isEqualTo("UNKNOWN");
        assertThat(root.available()).isTrue();
        assertThat(root.components()).isEmpty();
    }
}
