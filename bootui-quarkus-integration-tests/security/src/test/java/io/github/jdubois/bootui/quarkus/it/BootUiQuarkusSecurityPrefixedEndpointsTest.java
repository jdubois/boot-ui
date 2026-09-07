package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.conformance.BootUiHttpProbe;
import io.github.jdubois.bootui.engine.quarkussecurity.QuarkusSecurityScanner;
import io.github.jdubois.bootui.quarkus.security.QuarkusSecuritySnapshotProviderImpl;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.net.URL;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(BootUiQuarkusSecurityPrefixedEndpointsTest.PrefixedProfile.class)
class BootUiQuarkusSecurityPrefixedEndpointsTest {
    @TestHTTPResource
    URL baseUrl;

    @Inject
    Config config;

    @Inject
    QuarkusSecurityScanner scanner;

    @Test
    void realPrefixedRoutingAndPermissionDoNotBecomeRawUncoveredEndpoints() {
        var probe = new BootUiHttpProbe(baseUrl.toExternalForm());
        assertThat(probe.get("/api/advisor-policy/exact/child").status()).isEqualTo(200);
        assertThat(probe.get("/api/advisor-policy/method").status()).isIn(401, 403);
        var snapshot = new QuarkusSecuritySnapshotProviderImpl(config).snapshot();
        assertThat(snapshot.evidence().incomplete()).contains("non-default REST listener prefix");
        assertThat(snapshot.evidence().unknownRules()).contains("QS-AUTH-001", "QS-AUTHZ-001", "QS-AUTHZ-004");
        var report = scanner.scan();
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.results())
                .noneMatch(result ->
                        List.of("QS-AUTH-001", "QS-AUTHZ-001", "QS-AUTHZ-004").contains(result.id()));
        assertThat(report.analysisErrors()).isEmpty();
    }

    public static class PrefixedProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.rest.path", "/api",
                    "quarkus.http.auth.permission.prefixed.paths", "/api/advisor-policy/method",
                    "quarkus.http.auth.permission.prefixed.policy", "deny");
        }
    }
}
