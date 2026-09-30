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
 * Runs the {@code docs/PLAN-v2.md} §5.1 correlation coverage scenario on Quarkus with the OpenTelemetry SDK disabled,
 * so no request or statement carries a trace id. SQL still nests under its request in every phase, through BootUI's
 * own request id (M1-2 and the first part of M1-5), so the scenario enforces that floor.
 */
@QuarkusTest
@TestProfile(BootUiQuarkusCorrelationCoverageWithoutTracingTest.WithoutTracingProfile.class)
class BootUiQuarkusCorrelationCoverageWithoutTracingTest extends AbstractCorrelationCoverageTest {

    public static class WithoutTracingProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.otel.sdk.disabled", "true",
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
        return "quarkus-without-tracing";
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

    @Override
    protected Tracing tracing() {
        return Tracing.OFF;
    }

    @Override
    protected Map<String, Double> minimumNestedShares(Phase phase) {
        return Map.of("SQL", 1.0, "EXCEPTION", 1.0);
    }
}
