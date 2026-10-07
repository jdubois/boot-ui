package io.github.jdubois.bootui.quarkus.it;

import io.github.jdubois.bootui.conformance.AbstractCliConformanceTest;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.Map;

@QuarkusTest
@TestProfile(BootUiQuarkusCliConformanceTest.ConformanceProfile.class)
class BootUiQuarkusCliConformanceTest extends AbstractCliConformanceTest {

    public static class ConformanceProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "bootui.overrides-file", "target/cli-conformance/application-bootui.properties",
                    "bootui.panels.memory.enabled", "false",
                    "bootui.panels.heap-dump.read-only", "true",
                    "bootui.heap-dump.capture-enabled", "false",
                    "bootui.claude-code.enabled", "OFF",
                    // AbstractCliConformanceTest.CLI_TIMEOUT: a loaded runner can stretch architecture_scan past 30 s.
                    "bootui.cli.execution-timeout", "60s");
        }
    }

    @TestHTTPResource
    URL baseUrl;

    @Override
    protected String baseUrl() {
        return baseUrl.toExternalForm();
    }
}
