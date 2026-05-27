package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

/**
 * Verifies {@link BootUiProperties} default values and binding from environment
 * properties using {@link Binder}.
 */
class BootUiPropertiesTests {

    // -------------------------------------------------------------------------
    // Defaults (no properties set)
    // -------------------------------------------------------------------------

    private static BootUiProperties bind(MockEnvironment env) {
        return Binder.get(env).bind("bootui", BootUiProperties.class).get();
    }

    @Test
    void defaultEnabledModeIsAuto() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getEnabled()).isEqualTo(BootUiProperties.Mode.AUTO);
    }

    @Test
    void defaultPathIsBootui() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getPath()).isEqualTo("/bootui");
    }

    @Test
    void defaultApiPathIsBooutiApi() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getApiPath()).isEqualTo("/bootui/api");
    }

    @Test
    void defaultLocalhostOnlyIsTrue() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.isLocalhostOnly()).isTrue();
    }

    @Test
    void defaultAllowNonLocalhostIsFalse() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.isAllowNonLocalhost()).isFalse();
    }

    @Test
    void defaultMaskSecretsIsTrue() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.isMaskSecrets()).isTrue();
    }

    @Test
    void defaultExposeValuesIsMasked() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getExposeValues()).isEqualTo(BootUiProperties.ValueExposure.MASKED);
    }

    @Test
    void defaultShowBannerIsTrue() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.isShowBanner()).isTrue();
    }

    @Test
    void defaultEnabledProfilesAreDevAndLocal() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getEnabledProfiles()).containsExactly("dev", "local");
    }

    @Test
    void defaultDisabledProfilesAreProdAndProduction() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getDisabledProfiles()).containsExactly("prod", "production");
    }

    @Test
    void defaultOverridesFileIsRelativePath() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getOverridesFile()).isEqualTo(".bootui/application-bootui.properties");
    }

    @Test
    void defaultEndpointTimeoutIsFiveSeconds() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getEndpointTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void defaultDevServicesRestartEnabledIsFalse() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getDevServices().isRestartEnabled()).isFalse();
    }

    @Test
    void defaultDevServicesLogTailBytesIs64k() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getDevServices().getLogTailBytes()).isEqualTo(64 * 1024);
    }

    @Test
    void defaultCacheClearEnabledIsTrue() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getCache().isClearEnabled()).isTrue();
    }

    @Test
    void defaultDependenciesOsvEnabledIsTrue() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getDependencies().isOsvEnabled()).isTrue();
    }

    @Test
    void defaultDependenciesRequestTimeoutIsTenSeconds() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getDependencies().getRequestTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void defaultDependenciesMaxPackagesIs250() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getDependencies().getMaxPackages()).isEqualTo(250);
    }

    // -------------------------------------------------------------------------
    // Binder — parsing from environment properties
    // -------------------------------------------------------------------------

    @Test
    void defaultDependenciesMaxAdvisoriesIs200() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getDependencies().getMaxAdvisories()).isEqualTo(200);
    }

    @Test
    void bindsEnabledModeOn() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.enabled", "ON");

        BootUiProperties props = bind(env);

        assertThat(props.getEnabled()).isEqualTo(BootUiProperties.Mode.ON);
    }

    @Test
    void bindsEnabledModeOff() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.enabled", "OFF");

        BootUiProperties props = bind(env);

        assertThat(props.getEnabled()).isEqualTo(BootUiProperties.Mode.OFF);
    }

    @Test
    void bindsEnabledModeCaseInsensitive() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.enabled", "on");

        BootUiProperties props = bind(env);

        assertThat(props.getEnabled()).isEqualTo(BootUiProperties.Mode.ON);
    }

    @Test
    void bindsExposeValuesMetadataOnly() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.expose-values", "METADATA_ONLY");

        BootUiProperties props = bind(env);

        assertThat(props.getExposeValues()).isEqualTo(BootUiProperties.ValueExposure.METADATA_ONLY);
    }

    @Test
    void bindsExposeValuesFull() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.expose-values", "FULL");

        BootUiProperties props = bind(env);

        assertThat(props.getExposeValues()).isEqualTo(BootUiProperties.ValueExposure.FULL);
    }

    @Test
    void bindsCustomOverridesFile() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.overrides-file", "/etc/myapp/overrides.properties");

        BootUiProperties props = bind(env);

        assertThat(props.getOverridesFile()).isEqualTo("/etc/myapp/overrides.properties");
    }

    @Test
    void bindsEndpointTimeoutAsDuration() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.endpoint-timeout", "30s");

        BootUiProperties props = bind(env);

        assertThat(props.getEndpointTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void bindsMaskSecretsToFalse() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.mask-secrets", "false");

        BootUiProperties props = bind(env);

        assertThat(props.isMaskSecrets()).isFalse();
    }

    @Test
    void bindsAllowNonLocalhostToTrue() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.allow-non-localhost", "true");

        BootUiProperties props = bind(env);

        assertThat(props.isAllowNonLocalhost()).isTrue();
    }

    @Test
    void bindsShowBannerToFalse() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.show-banner", "false");

        BootUiProperties props = bind(env);

        assertThat(props.isShowBanner()).isFalse();
    }

    @Test
    void bindsDevServicesRestartEnabled() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.dev-services.restart-enabled", "true");

        BootUiProperties props = bind(env);

        assertThat(props.getDevServices().isRestartEnabled()).isTrue();
    }

    @Test
    void bindsDevServicesLogTailBytes() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.dev-services.log-tail-bytes", "131072");

        BootUiProperties props = bind(env);

        assertThat(props.getDevServices().getLogTailBytes()).isEqualTo(131072);
    }

    @Test
    void bindsCacheClearEnabledFalse() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.cache.clear-enabled", "false");

        BootUiProperties props = bind(env);

        assertThat(props.getCache().isClearEnabled()).isFalse();
    }

    @Test
    void bindsDependenciesOsvEnabledFalse() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.dependencies.osv-enabled", "false");

        BootUiProperties props = bind(env);

        assertThat(props.getDependencies().isOsvEnabled()).isFalse();
    }

    @Test
    void bindsDependenciesRequestTimeout() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.dependencies.request-timeout", "20s");

        BootUiProperties props = bind(env);

        assertThat(props.getDependencies().getRequestTimeout()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void bindsDependenciesMaxPackages() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.dependencies.max-packages", "100");

        BootUiProperties props = bind(env);

        assertThat(props.getDependencies().getMaxPackages()).isEqualTo(100);
    }

    @Test
    void bindsDependenciesMaxAdvisories() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.dependencies.max-advisories", "50");

        BootUiProperties props = bind(env);

        assertThat(props.getDependencies().getMaxAdvisories()).isEqualTo(50);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @Test
    void noPropertiesSetYieldsAllDefaults() {
        MockEnvironment env = new MockEnvironment();
        BindResult<BootUiProperties> result = Binder.get(env).bind("bootui", BootUiProperties.class);

        // When no bootui.* keys are present, bind returns unbound; defaults come from no-arg constructor.
        BootUiProperties props = result.orElseGet(BootUiProperties::new);

        assertThat(props.getEnabled()).isEqualTo(BootUiProperties.Mode.AUTO);
        assertThat(props.isLocalhostOnly()).isTrue();
        assertThat(props.isMaskSecrets()).isTrue();
        assertThat(props.getExposeValues()).isEqualTo(BootUiProperties.ValueExposure.MASKED);
        assertThat(props.getOverridesFile()).isEqualTo(".bootui/application-bootui.properties");
        assertThat(props.getEndpointTimeout()).isEqualTo(Duration.ofSeconds(5));
    }
}
