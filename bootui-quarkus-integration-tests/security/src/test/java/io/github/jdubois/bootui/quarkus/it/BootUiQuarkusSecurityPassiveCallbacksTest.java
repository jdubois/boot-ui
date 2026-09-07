package io.github.jdubois.bootui.quarkus.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.quarkussecurity.QuarkusSecurityScanner;
import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.credentials.CredentialsProvider;
import io.quarkus.oidc.OidcRequestContext;
import io.quarkus.oidc.OidcTenantConfig;
import io.quarkus.oidc.TenantConfigResolver;
import io.quarkus.oidc.TenantResolver;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(BootUiQuarkusSecurityPassiveCallbacksTest.PassiveProfile.class)
class BootUiQuarkusSecurityPassiveCallbacksTest {
    @Inject
    QuarkusSecurityScanner scanner;

    @Inject
    Config config;

    @Inject
    Counters counters;

    @Inject
    CountingTenantResolver tenantResolver;

    @Inject
    CountingTenantConfigResolver tenantConfigResolver;

    @Inject
    CountingPolicy policy;

    @Inject
    CountingCredentials credentials;

    @Test
    void explicitScanNeverExecutesRegisteredNativeSecurityCallbacks() {
        assertThat(config.getValue("bootui.internal.sec.custom-identity", Boolean.class))
                .isTrue();
        assertThat(config.getValue("bootui.internal.sec.custom-authorization", Boolean.class))
                .isTrue();

        // Bootstrap and injection have finished. Direct invocation avoids request authentication outside the scanner.
        List<Integer> before = counters.values();
        var report = scanner.scan();
        assertThat(counters.values()).containsExactlyElementsOf(before);
        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.results())
                .noneMatch(result ->
                        List.of("QS-AUTH-001", "QS-AUTHZ-001", "QS-AUTHZ-004").contains(result.id()));
        assertThat(report.results()).anyMatch(result -> result.id().equals("QS-AUTH-002"));
        assertThat(report.analysisErrors()).isEmpty();

        // Positive controls run after the scan boundary and use the same injected CDI instances.
        tenantResolver.resolve(null);
        tenantConfigResolver.resolve(null, null);
        policy.checkPermission(null, Uni.createFrom().nullItem(), null);
        credentials.getCredentials("fixture");
        credentials.getCredentialsAsync("fixture");
        assertThat(counters.values())
                .containsExactlyElementsOf(
                        before.stream().map(value -> value + 1).toList());
    }

    public static class PassiveProfile implements QuarkusTestProfile {
        static final String NAME = "advisor-passive-security";

        @Override
        public String getConfigProfile() {
            return NAME;
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.oidc.tenant-enabled", "false",
                    "quarkus.oidc.devservices.enabled", "false",
                    "quarkus.oidc.credentials.client-secret.provider.name", "advisor-passive",
                    "quarkus.oidc.credentials.client-secret.provider.key", "fixture-key",
                    "quarkus.http.auth.proactive", "false",
                    "quarkus.http.auth.permission.passive.paths", "/advisor-policy/callback-fixture",
                    "quarkus.http.auth.permission.passive.policy", "advisor-passive");
        }
    }

    @Singleton
    @IfBuildProfile(PassiveProfile.NAME)
    public static class Counters {
        final AtomicInteger tenant = new AtomicInteger();
        final AtomicInteger tenantConfig = new AtomicInteger();
        final AtomicInteger permission = new AtomicInteger();
        final AtomicInteger credential = new AtomicInteger();
        final AtomicInteger asyncCredential = new AtomicInteger();

        List<Integer> values() {
            return List.of(tenant.get(), tenantConfig.get(), permission.get(), credential.get(), asyncCredential.get());
        }
    }

    @Singleton
    @IfBuildProfile(PassiveProfile.NAME)
    public static class CountingTenantResolver implements TenantResolver {
        @Inject
        Counters counters;

        @Override
        public String resolve(RoutingContext context) {
            counters.tenant.incrementAndGet();
            return null;
        }
    }

    @Singleton
    @IfBuildProfile(PassiveProfile.NAME)
    public static class CountingTenantConfigResolver implements TenantConfigResolver {
        @Inject
        Counters counters;

        @Override
        public Uni<OidcTenantConfig> resolve(
                RoutingContext context, OidcRequestContext<OidcTenantConfig> requestContext) {
            counters.tenantConfig.incrementAndGet();
            return Uni.createFrom().nullItem();
        }
    }

    @Singleton
    @IfBuildProfile(PassiveProfile.NAME)
    public static class CountingPolicy implements HttpSecurityPolicy {
        @Inject
        Counters counters;

        @Override
        public String name() {
            return "advisor-passive";
        }

        @Override
        public Uni<CheckResult> checkPermission(
                RoutingContext context, Uni<SecurityIdentity> identity, AuthorizationRequestContext requestContext) {
            counters.permission.incrementAndGet();
            return Uni.createFrom().item(CheckResult.PERMIT);
        }
    }

    @Singleton
    @Named("advisor-passive")
    @IfBuildProfile(PassiveProfile.NAME)
    public static class CountingCredentials implements CredentialsProvider {
        @Inject
        Counters counters;

        @Override
        public Map<String, String> getCredentials(String name) {
            counters.credential.incrementAndGet();
            return Map.of();
        }

        @Override
        public Uni<Map<String, String>> getCredentialsAsync(String name) {
            counters.asyncCredential.incrementAndGet();
            return Uni.createFrom().item(Map.of());
        }
    }
}
