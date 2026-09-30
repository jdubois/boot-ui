package io.github.jdubois.bootui.quarkus.it;

import io.github.jdubois.bootui.conformance.AbstractCorrelationCoverageTest;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Runs the {@code docs/PLAN-v2.md} §5.1 correlation coverage scenario against Quarkus, with OpenTelemetry and an
 * in-memory H2 datasource. The routes cover blocking JDBC on a worker thread ({@link SqlProbeResource}) and a
 * failing request ({@link ExceptionProbeResource}). This module has no security or cache, so the scenario has
 * no such traffic on this stack. A dedicated profile gives the scenario its own application instance.
 */
@QuarkusTest
@TestProfile(BootUiQuarkusCorrelationCoverageTest.CorrelationProfile.class)
class BootUiQuarkusCorrelationCoverageTest extends AbstractCorrelationCoverageTest {

    public static class CorrelationProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "bootui.overrides-file", "target/correlation-coverage/application-bootui.properties",
                    "bootui.http-exchanges.max-exchanges", "1000");
        }
    }

    @TestHTTPResource
    URL baseUrl;

    @Override
    protected String baseUrl() {
        String url = baseUrl.toExternalForm();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    protected String runtimeLabel() {
        return "quarkus";
    }

    @Override
    protected List<Traffic> traffic() {
        return List.of(Traffic.anonymous("/it/sql", 8), Traffic.anonymous("/it/boom", 4));
    }

    @Override
    protected Pattern requestThreadPattern() {
        return Pattern.compile("executor-thread-\\d+|vert\\.x-eventloop-thread-\\d+");
    }
}
