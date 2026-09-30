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
    protected Tracing tracing() {
        return Tracing.ON;
    }

    @Override
    protected List<Traffic> traffic() {
        return List.of(
                Traffic.anonymous("/it/sql", 8),
                Traffic.anonymous("/it/boom", 4),
                Traffic.anonymous("/it/raw-executor-sql", 2));
    }

    @Override
    protected Pattern requestThreadPattern() {
        return Pattern.compile("executor-thread-\\d+|vert\\.x-eventloop-thread-\\d+");
    }

    @Override
    protected Pattern unownedThreadPattern() {
        return Pattern.compile(RawExecutorProbeResource.RAW_EXECUTOR_THREADS);
    }

    /** SQL and exceptions nest under their request by request id, before the trace id, in every phase. */
    @Override
    protected Map<String, Double> minimumNestedShares(Phase phase) {
        return Map.of("SQL", 1.0, "EXCEPTION", 1.0);
    }
}
