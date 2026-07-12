package io.github.jdubois.bootui.quarkus.it;

import io.github.jdubois.bootui.conformance.AbstractMcpConformanceTest;
import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.Map;

@QuarkusTest
@TestProfile(BootUiQuarkusMcpConformanceTest.ConformanceProfile.class)
class BootUiQuarkusMcpConformanceTest extends AbstractMcpConformanceTest {

    public static class ConformanceProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "bootui.panels.copilot.enabled", "false",
                    "bootui.panels.heap-dump.read-only", "true",
                    "bootui.heap-dump.capture-enabled", "false",
                    "bootui.claude-code.enabled", "OFF");
        }
    }

    @TestHTTPResource
    URL baseUrl;

    @Override
    protected String baseUrl() {
        return baseUrl.toExternalForm();
    }

    @Override
    protected boolean enableMcp() {
        BootUiHttpProbe.Response response = new BootUiHttpProbe(baseUrl())
                .request(
                        "POST",
                        "/bootui/api/mcp-server/toggle",
                        Map.of("Content-Type", "application/json"),
                        "{\"enabled\":true}");
        return response.status() == 200 && response.json().path("enabled").asBoolean(false);
    }

    @Override
    protected void disableMcp() {
        new BootUiHttpProbe(baseUrl())
                .request(
                        "POST",
                        "/bootui/api/mcp-server/toggle",
                        Map.of("Content-Type", "application/json"),
                        "{\"enabled\":false}");
    }
}
