package io.github.jdubois.bootui.autoconfigure.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.github.jdubois.bootui.autoconfigure.config.BootUiActuatorDefaultsEnvironmentPostProcessor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

class ActuatorGuardScopeTests {

    @Test
    void defaultExposureAndHealthDescendantsAreIndependentlyProtected() {
        var defaults = scope(environment(), null);
        assertThat(defaults.guards("/actuator/env")).isTrue();
        assertThat(defaults.guards("/actuator/health/readiness")).isTrue();
        assertThat(defaults.guards("/actuator/info")).isTrue();
        assertThat(defaults.guards("/actuator/environment")).isFalse();
        assertThat(defaults.guards("/api/hello")).isFalse();
        var exposureOwned = scope(environment().withProperty("management.endpoint.health.show-details", "never"), null);
        assertThat(exposureOwned.guards("/actuator/env")).isTrue();
        assertThat(exposureOwned.guards("/actuator/health/readiness")).isFalse();
        var detailsOwned =
                scope(environment().withProperty("management.endpoints.web.exposure.include", "health,env"), null);
        assertThat(detailsOwned.guards("/actuator/env")).isFalse();
        assertThat(detailsOwned.guards("/actuator/health/readiness")).isTrue();
    }

    @Test
    void identicalHostDefaultsAndIndexedExposureDoNotBecomeBootUiOwned() {
        var host = environment();
        host.getPropertySources()
                .addLast(new MapPropertySource(
                        "defaultProperties",
                        Map.of(
                                "management.endpoint.health.show-details", "always",
                                "management.endpoints.web.exposure.include[0]", "health",
                                "management.endpoints.web.exposure.include[1]", "env")));
        var scope = scope(host, null);
        assertThat(scope.guards("/actuator/env")).isFalse();
        assertThat(scope.guards("/actuator/health")).isFalse();
    }

    @Test
    void mappedAndAdditionalHealthPathsAreGuardedWithoutClaimingOtherPaths() {
        var endpoints = mock(PathMappedEndpoints.class);
        when(endpoints.getPath(EndpointId.of("health"))).thenReturn("/admin/status");
        when(endpoints.getAdditionalPaths(WebServerNamespace.SERVER, EndpointId.of("health")))
                .thenReturn(List.of("/readyz"));
        var scope = scope(
                environment()
                        .withProperty("management.endpoints.web.base-path", "/admin")
                        .withProperty("management.endpoints.web.path-mapping.env", "environment"),
                endpoints);
        assertThat(scope.guards("/admin/environment")).isTrue();
        assertThat(scope.guards("/admin/status/readiness")).isTrue();
        assertThat(scope.guards("/readyz")).isTrue();
        assertThat(scope.guards("/readyz-other")).isFalse();
        assertThat(scope.guards("/actuator/env")).isFalse();
    }

    @Test
    void exposureOptOutAndLaterHostOverridesAreRespectedButRuntimeCannotDisarmSnapshot() {
        var optedOut = scope(environment().withProperty("bootui.actuator.expose-defaults", "false"), null);
        assertThat(optedOut.guards("/actuator/env")).isFalse();
        assertThat(optedOut.guards("/actuator/health")).isFalse();
        var environment = environment();
        var scope = scope(environment, null);
        environment.setProperty("management.endpoints.web.exposure.include", "*");
        assertThat(scope.guards("/actuator/env")).isTrue();
        var afterHostOverride = new ActuatorGuardScope(environment, () -> null);
        assertThat(afterHostOverride.guards("/actuator/env")).isFalse();
    }

    @Test
    void separateManagementListenerDoesNotClaimMainRoutesButStillProtectsMainHealthAliases() {
        var environment = environment();
        new BootUiActuatorDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());
        var endpoints = mock(PathMappedEndpoints.class);
        when(endpoints.getAdditionalPaths(WebServerNamespace.SERVER, EndpointId.of("health")))
                .thenReturn(List.of("/readyz"));
        var main = new ActuatorGuardScope(environment, () -> endpoints, false, WebServerNamespace.SERVER);
        assertThat(main.guards("/actuator/env")).isFalse();
        assertThat(main.guards("/readyz")).isTrue();
    }

    private MockEnvironment environment() {
        return new MockEnvironment().withProperty("bootui.enabled", "ON");
    }

    private ActuatorGuardScope scope(MockEnvironment environment, PathMappedEndpoints endpoints) {
        new BootUiActuatorDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());
        return new ActuatorGuardScope(environment, () -> endpoints);
    }
}
