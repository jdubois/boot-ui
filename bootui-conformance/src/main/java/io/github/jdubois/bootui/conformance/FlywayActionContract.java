package io.github.jdubois.bootui.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import java.util.Map;

/** Exact confirmed unknown-target contract, reusable by capability-specific integration fixtures. */
public final class FlywayActionContract {

    private FlywayActionContract() {}

    public static void unavailableActionTargetReturnsCanonicalNotFound(
            BootUiHttpProbe probe, String apiPath, Map<String, String> headers) {
        Response response = probe.request(
                "POST",
                apiPath + "/flyway/migrate",
                headers,
                "{\"beanName\":\"conformance-missing-flyway\",\"confirm\":true}");

        assertThat(response.status()).as("unknown Flyway target status").isEqualTo(404);
        assertThat(response.isJson()).as("unknown Flyway target content type").isTrue();
        assertThat(response.json().path("status").asText()).isEqualTo("unavailable");
        assertThat(response.json().path("message").asText())
                .isEqualTo("No Flyway bean matched the requested datasource.");
    }
}
