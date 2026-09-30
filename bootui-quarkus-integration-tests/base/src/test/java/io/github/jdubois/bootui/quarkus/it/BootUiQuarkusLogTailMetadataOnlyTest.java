package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe.Response;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.Map;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Real-boot check that the Quarkus Log Tail resource reads the live {@code bootui.expose-values} setting: under
 * {@code METADATA_ONLY} a captured line keeps its timestamp, level, logger, and thread, but its message is omitted and
 * flagged as such.
 */
@QuarkusTest
@TestProfile(BootUiQuarkusLogTailMetadataOnlyTest.MetadataOnlyProfile.class)
class BootUiQuarkusLogTailMetadataOnlyTest {

    public static final class MetadataOnlyProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("bootui.expose-values", "metadata_only");
        }
    }

    @TestHTTPResource
    URL baseUrl;

    @Test
    void omitsMessagesButKeepsLineMetadata() {
        String logger = "com.example.quarkus.MetadataOnlyProbe" + System.nanoTime();
        Logger.getLogger(logger).warning("login password=hunter2");

        Response response = new BootUiHttpProbe(baseUrl.toExternalForm()).get("/bootui/api/log-tail/recent");
        assertThat(response.status()).isEqualTo(200);

        JsonNode line = null;
        for (JsonNode candidate : response.json()) {
            if (logger.equals(candidate.path("logger").asText())) {
                line = candidate;
            }
        }
        assertThat(line).as("the just-emitted line must be captured").isNotNull();
        assertThat(line.path("message").isNull()).as("$.message").isTrue();
        assertThat(line.path("messageOmitted").asBoolean())
                .as("$.messageOmitted")
                .isTrue();
        assertThat(line.path("level").asText()).isEqualTo("WARN");
        assertThat(line.path("thread").isTextual()).isTrue();
        assertThat(response.body()).doesNotContain("hunter2");
    }
}
