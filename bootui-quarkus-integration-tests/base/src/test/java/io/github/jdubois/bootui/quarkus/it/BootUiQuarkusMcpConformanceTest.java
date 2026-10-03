package io.github.jdubois.bootui.quarkus.it;

import io.github.jdubois.bootui.conformance.AbstractMcpConformanceTest;
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
                    "bootui.overrides-file", "target/mcp-conformance/application-bootui.properties",
                    "bootui.panels.copilot.enabled", "false",
                    "bootui.panels.heap-dump.read-only", "true",
                    "bootui.heap-dump.capture-enabled", "false",
                    "bootui.claude-code.enabled", "OFF",
                    "bootui.mcp.max-payload-bytes", "256");
        }
    }

    @TestHTTPResource
    URL baseUrl;

    @Override
    protected String baseUrl() {
        return baseUrl.toExternalForm();
    }

    @Override
    protected String requestProfileProbePath() {
        return "/it/correlation/worker";
    }
}
