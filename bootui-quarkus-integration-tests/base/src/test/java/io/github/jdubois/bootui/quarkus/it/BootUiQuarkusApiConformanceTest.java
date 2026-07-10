package io.github.jdubois.bootui.quarkus.it;

import io.github.jdubois.bootui.conformance.AbstractBootUiApiConformanceTest;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.Map;

/**
 * Runs the shared, framework-neutral {@link AbstractBootUiApiConformanceTest} contract against the
 * Quarkus adapter, by booting this minimal (Docker-free) Quarkus app under {@code @QuarkusTest}.
 *
 * <p>This is the Quarkus mirror of the Spring sample app's {@code SpringApiConformanceTest}: both
 * adapters answer the exact same {@code /bootui/api/**} contract, so the shared Vue UI binds to one
 * stable shape. {@code @QuarkusTest} boots in {@code LaunchMode.TEST}, so the console's build steps are
 * registered (they are gated off only in production) and the engine-backed endpoints are live.</p>
 *
 * <p>The dedicated test profile sets {@code bootui.panels.copilot.enabled=false} to enable
 * {@link AbstractBootUiApiConformanceTest#panelDisabledRequestIsRejectedWithCanonicalBody}; {@code
 * bootui.panels.heap-dump.read-only=true} enables
 * {@link AbstractBootUiApiConformanceTest#panelReadOnlyActionIsRejectedWithCanonicalBody}. The profile
 * disables heap-dump capture and real Claude Code session discovery, and isolates conformance mutations
 * (logger level, cached scan report, telemetry clear) from the adapter's other {@code @QuarkusTest}
 * classes.</p>
 */
@QuarkusTest
@TestProfile(BootUiQuarkusApiConformanceTest.ConformanceProfile.class)
class BootUiQuarkusApiConformanceTest extends AbstractBootUiApiConformanceTest {

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
    protected String expectedPanelsResource() {
        return "/io/github/jdubois/bootui/conformance/expected-panels-quarkus.json";
    }
}
