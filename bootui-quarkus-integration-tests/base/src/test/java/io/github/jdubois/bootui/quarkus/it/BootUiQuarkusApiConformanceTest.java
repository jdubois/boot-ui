package io.github.jdubois.bootui.quarkus.it;

import io.github.jdubois.bootui.conformance.AbstractBootUiApiConformanceTest;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URL;

/**
 * Runs the shared, framework-neutral {@link AbstractBootUiApiConformanceTest} contract against the
 * Quarkus adapter, by booting this minimal (Docker-free) Quarkus app under {@code @QuarkusTest}.
 *
 * <p>This is the Quarkus mirror of the Spring sample app's {@code SpringApiConformanceTest}: both
 * adapters answer the exact same {@code /bootui/api/**} contract, so the shared Vue UI binds to one
 * stable shape. {@code @QuarkusTest} boots in {@code LaunchMode.TEST}, so the console's build steps are
 * registered (they are gated off only in production) and the engine-backed endpoints are live.</p>
 *
 * <p>Panel-access conformance properties are in {@code application.properties}:
 * {@code bootui.panels.copilot.enabled=false} enables
 * {@link AbstractBootUiApiConformanceTest#panelDisabledRequestIsRejectedWithCanonicalBody}; {@code
 * bootui.panels.heap-dump.read-only=true} enables
 * {@link AbstractBootUiApiConformanceTest#panelReadOnlyActionIsRejectedWithCanonicalBody}.</p>
 */
@QuarkusTest
class BootUiQuarkusApiConformanceTest extends AbstractBootUiApiConformanceTest {

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
