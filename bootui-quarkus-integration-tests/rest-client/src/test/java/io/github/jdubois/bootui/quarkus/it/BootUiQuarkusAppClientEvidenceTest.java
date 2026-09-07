package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(BootUiQuarkusAppClientEvidenceTest.TimeoutProfile.class)
class BootUiQuarkusAppClientEvidenceTest {

    @TestHTTPResource
    URL baseUrl;

    @Test
    void scansNativeRegisteredClientTimersWithoutCallingTheClient() {
        BootUiHttpProbe probe = new BootUiHttpProbe(baseUrl.toExternalForm());
        int callsBefore = probe.get("/bootui/api/rest-client-trace")
                .json()
                .path("entries")
                .size();

        var response = probe.post("/bootui/api/spring/scan", Map.of("Content-Type", "application/json"));
        assertThat(response.status()).isEqualTo(200);
        List<JsonNode> timers = new ArrayList<>();
        response.json().path("results").forEach(result -> {
            if (result.path("id").asText().equals("QA-WEB-003")) {
                timers.add(result);
            }
        });
        assertThat(timers).hasSize(1);
        assertThat(timers.get(0).path("violationCount").asInt()).isEqualTo(1);
        assertThat(timers.get(0).path("sampleViolations").toString()).contains("read");
        assertThat(timers.get(0).toString()).doesNotContain("unregistered", "connect-zero");
        response.json()
                .path("analysisErrors")
                .forEach(error -> assertThat(error.path("id").asText()).isNotEqualTo("QA-WEB-003"));
        assertThat(probe.get("/bootui/api/rest-client-trace")
                        .json()
                        .path("entries")
                        .size())
                .isEqualTo(callsBefore);
    }

    public static class TimeoutProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.rest-client.connect-timeout", "0",
                    "quarkus.rest-client.\"org.acme.restclient.PingClient\".connect-timeout", "1000",
                    "ping-client/mp-rest/readTimeout", "0",
                    "quarkus.rest-client.unregistered.read-timeout", "0");
        }
    }
}
