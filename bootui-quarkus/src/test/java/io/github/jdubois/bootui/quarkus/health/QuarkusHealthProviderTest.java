package io.github.jdubois.bootui.quarkus.health;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HealthNodeDto;
import io.smallrye.health.SmallRyeHealth;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test of {@link QuarkusHealthProvider#map(SmallRyeHealth)} against hand-built
 * {@link SmallRyeHealth} payloads — no CDI container and no running backend. It lives in the provider's own
 * package (and in the provider's own module) so it can drive the package-private {@code map} seam directly
 * without a split package across archives. SmallRye's {@code jakarta.json} API and the Parsson implementation
 * are on the test classpath via this module's {@code provided}-scoped {@code smallrye-health} dependency.
 * It pins the MicroProfile-Health-to-neutral-DTO mapping the {@code BootUiHealthProducer}
 * relies on, including the defensive paths the design's critic round called out (sparse payload, absent checks,
 * absent data, typed data values).
 */
class QuarkusHealthProviderTest {

    private static SmallRyeHealth health(JsonObject payload) {
        return new SmallRyeHealth(payload);
    }

    @Test
    void mapsAnUpReportWithChecksAndData() {
        JsonObject payload = Json.createObjectBuilder()
                .add("status", "UP")
                .add(
                        "checks",
                        Json.createArrayBuilder()
                                .add(Json.createObjectBuilder()
                                        .add("name", "database")
                                        .add("status", "UP")
                                        .add(
                                                "data",
                                                Json.createObjectBuilder()
                                                        .add("connection", "ok")
                                                        .add("poolSize", 10)
                                                        .add("ssl", true)))
                                .add(Json.createObjectBuilder()
                                        .add("name", "disk")
                                        .add("status", "UP")))
                .build();

        HealthNodeDto root = QuarkusHealthProvider.map(health(payload));

        assertThat(root.name()).isEqualTo("application");
        assertThat(root.status()).isEqualTo("UP");
        assertThat(root.details())
                .as("the root carries no details, only its checks as components")
                .isNull();
        assertThat(root.components()).hasSize(2);

        HealthNodeDto database = root.components().get(0);
        assertThat(database.name()).isEqualTo("database");
        assertThat(database.status()).isEqualTo("UP");
        assertThat(database.components())
                .as("MicroProfile Health checks never nest")
                .isEmpty();
        assertThat(database.details()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) database.details();
        assertThat(data)
                .containsEntry("connection", "ok")
                .containsEntry("poolSize", 10L)
                .containsEntry("ssl", Boolean.TRUE);

        HealthNodeDto disk = root.components().get(1);
        assertThat(disk.name()).isEqualTo("disk");
        assertThat(disk.details())
                .as("a check without a data object maps to null details")
                .isNull();
    }

    @Test
    void mapsADownReport() {
        JsonObject payload = Json.createObjectBuilder()
                .add("status", "DOWN")
                .add(
                        "checks",
                        Json.createArrayBuilder()
                                .add(Json.createObjectBuilder()
                                        .add("name", "database")
                                        .add("status", "DOWN")
                                        .add("data", Json.createObjectBuilder().add("reason", "connection refused"))))
                .build();

        HealthNodeDto root = QuarkusHealthProvider.map(health(payload));

        assertThat(root.status()).isEqualTo("DOWN");
        assertThat(root.components()).hasSize(1);
        assertThat(root.components().get(0).status()).isEqualTo("DOWN");
    }

    @Test
    void mapsAReportWithAnEmptyChecksArray() {
        JsonObject payload = Json.createObjectBuilder()
                .add("status", "UP")
                .add("checks", Json.createArrayBuilder())
                .build();

        HealthNodeDto root = QuarkusHealthProvider.map(health(payload));

        assertThat(root.status()).isEqualTo("UP");
        assertThat(root.components()).isEmpty();
    }

    @Test
    void defaultsDefensivelyWhenStatusAndChecksAreAbsent() {
        // A minimal/sparse payload must never throw — a well-formed-but-empty report should render UNKNOWN,
        // not 500 the panel.
        HealthNodeDto root =
                QuarkusHealthProvider.map(health(Json.createObjectBuilder().build()));

        assertThat(root.name()).isEqualTo("application");
        assertThat(root.status()).isEqualTo("UNKNOWN");
        assertThat(root.components()).isEmpty();
    }

    @Test
    void mapsFractionalAndStringDataValues() {
        JsonObject payload = Json.createObjectBuilder()
                .add("status", "UP")
                .add(
                        "checks",
                        Json.createArrayBuilder()
                                .add(Json.createObjectBuilder()
                                        .add("name", "load")
                                        .add("status", "UP")
                                        .add(
                                                "data",
                                                Json.createObjectBuilder()
                                                        .add("ratio", 0.75)
                                                        .add("region", "eu-west-1"))))
                .build();

        HealthNodeDto root = QuarkusHealthProvider.map(health(payload));

        @SuppressWarnings("unchecked")
        Map<String, Object> data =
                (Map<String, Object>) root.components().get(0).details();
        assertThat(data).containsEntry("ratio", 0.75).containsEntry("region", "eu-west-1");
    }
}
