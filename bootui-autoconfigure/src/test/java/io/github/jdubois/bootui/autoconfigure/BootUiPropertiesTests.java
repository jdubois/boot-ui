package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
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
    void defaultAllowNonLocalhostIsFalse() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.isAllowNonLocalhost()).isFalse();
    }

    @Test
    void defaultTrustedProxiesIsEmpty() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getTrustedProxies()).isEmpty();
    }

    @Test
    void defaultTrustContainerGatewayIsOff() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getTrustContainerGateway()).isEqualTo(BootUiProperties.Mode.OFF);
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
    void defaultReadOnlyIsFalse() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.isReadOnly()).isFalse();
    }

    @Test
    void defaultPanelSettingsAreEnabledAndWritable() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.isPanelEnabled("config")).isTrue();
        assertThat(props.isPanelReadOnly("config")).isFalse();
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
    void defaultMonitoringExcludeSelfIsTrue() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getMonitoring().isExcludeSelf()).isTrue();
    }

    @Test
    void defaultVulnerabilitiesOsvEnabledIsTrue() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getVulnerabilities().isOsvEnabled()).isTrue();
    }

    @Test
    void defaultVulnerabilitiesRequestTimeoutIsTenSeconds() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getVulnerabilities().getRequestTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void defaultVulnerabilitiesMaxPackagesIs250() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getVulnerabilities().getMaxPackages()).isEqualTo(250);
    }

    // -------------------------------------------------------------------------
    // Binder — parsing from environment properties
    // -------------------------------------------------------------------------

    @Test
    void defaultVulnerabilitiesMaxAdvisoriesIs200() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getVulnerabilities().getMaxAdvisories()).isEqualTo(200);
    }

    @Test
    void defaultGithubSettingsAreBoundedAndEnabledForExplicitRefresh() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getGithub().isApiEnabled()).isTrue();
        assertThat(props.getGithub().getRequestTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.getGithub().getMaxPullRequests()).isEqualTo(10);
        assertThat(props.getGithub().getMaxIssues()).isEqualTo(25);
        assertThat(props.getGithub().getMaxWorkflowRuns()).isEqualTo(20);
        assertThat(props.getGithub().getQuotaSafetyThreshold()).isEqualTo(10);
        assertThat(props.getGithub().getMaxApiCalls()).isEqualTo(17);
        assertThat(props.getGithub().getAllowedApiHosts()).containsExactly("api.github.com");
    }

    @Test
    void defaultHttpExchangesMaxExchangesIs200() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getHttpExchanges().getMaxExchanges()).isEqualTo(200);
    }

    @Test
    void defaultClaudeCodeRawRevealIsFalse() {
        BootUiProperties props = new BootUiProperties();
        assertThat(props.getClaudeCode().isAllowRawReveal()).isFalse();
    }

    @Test
    void defaultClaudeCodeSessionDirectoryIsProjectsDirectory() {
        BootUiProperties props = new BootUiProperties();
        Path defaultSessionStateDir = props.getClaudeCode().defaultSessionStateDir();
        assertThat(defaultSessionStateDir.getFileName()).hasToString("projects");
        assertThat(defaultSessionStateDir.getParent().getFileName()).hasToString(".claude");
    }

    @Test
    void bindsEnabledModeOn() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.enabled", "ON");

        BootUiProperties props = bind(env);

        assertThat(props.getEnabled()).isEqualTo(BootUiProperties.Mode.ON);
    }

    @Test
    void bindsTrustedProxies() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.trusted-proxies", "172.16.0.0/12,192.168.0.0/16");

        BootUiProperties props = bind(env);

        assertThat(props.getTrustedProxies()).containsExactly("172.16.0.0/12", "192.168.0.0/16");
    }

    @Test
    void bindsTrustContainerGatewayOn() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.trust-container-gateway", "ON");

        BootUiProperties props = bind(env);

        assertThat(props.getTrustContainerGateway()).isEqualTo(BootUiProperties.Mode.ON);
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
    void bindsHttpExchangesMaxExchanges() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.http-exchanges.max-exchanges", "25");

        BootUiProperties props = bind(env);

        assertThat(props.getHttpExchanges().getMaxExchanges()).isEqualTo(25);
    }

    @Test
    void bindsGithubSettings() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.github.api-enabled", "false");
        env.setProperty("bootui.github.request-timeout", "3s");
        env.setProperty("bootui.github.max-pull-requests", "5");
        env.setProperty("bootui.github.max-issues", "6");
        env.setProperty("bootui.github.max-workflow-runs", "7");
        env.setProperty("bootui.github.quota-safety-threshold", "4");
        env.setProperty("bootui.github.max-api-calls", "8");
        env.setProperty("bootui.github.allowed-api-hosts", "api.github.com,ghe.example.com");

        BootUiProperties props = bind(env);

        assertThat(props.getGithub().isApiEnabled()).isFalse();
        assertThat(props.getGithub().getRequestTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(props.getGithub().getMaxPullRequests()).isEqualTo(5);
        assertThat(props.getGithub().getMaxIssues()).isEqualTo(6);
        assertThat(props.getGithub().getMaxWorkflowRuns()).isEqualTo(7);
        assertThat(props.getGithub().getQuotaSafetyThreshold()).isEqualTo(4);
        assertThat(props.getGithub().getMaxApiCalls()).isEqualTo(8);
        assertThat(props.getGithub().getAllowedApiHosts()).containsExactly("api.github.com", "ghe.example.com");
    }

    @Test
    void bindsCustomOverridesFile() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.overrides-file", "/etc/myapp/overrides.properties");

        BootUiProperties props = bind(env);

        assertThat(props.getOverridesFile()).isEqualTo("/etc/myapp/overrides.properties");
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
    void bindsReadOnlyToTrue() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.read-only", "true");

        BootUiProperties props = bind(env);

        assertThat(props.isReadOnly()).isTrue();
        assertThat(props.isPanelReadOnly("config")).isTrue();
    }

    @Test
    void bindsPanelSettings() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.panels.config.enabled", "false");
        env.setProperty("bootui.panels.loggers.read-only", "true");

        BootUiProperties props = bind(env);

        assertThat(props.isPanelEnabled("config")).isFalse();
        assertThat(props.isPanelReadOnly("config")).isFalse();
        assertThat(props.isPanelEnabled("loggers")).isTrue();
        assertThat(props.isPanelReadOnly("loggers")).isTrue();
        assertThat(props.getPanels()).containsOnlyKeys("config", "loggers");
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
    void bindsMonitoringExcludeSelfFalse() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.monitoring.exclude-self", "false");

        BootUiProperties props = bind(env);

        assertThat(props.getMonitoring().isExcludeSelf()).isFalse();
    }

    @Test
    void bindsVulnerabilitiesOsvEnabledFalse() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.vulnerabilities.osv-enabled", "false");

        BootUiProperties props = bind(env);

        assertThat(props.getVulnerabilities().isOsvEnabled()).isFalse();
    }

    @Test
    void bindsVulnerabilitiesRequestTimeout() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.vulnerabilities.request-timeout", "20s");

        BootUiProperties props = bind(env);

        assertThat(props.getVulnerabilities().getRequestTimeout()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void bindsVulnerabilitiesMaxPackages() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.vulnerabilities.max-packages", "100");

        BootUiProperties props = bind(env);

        assertThat(props.getVulnerabilities().getMaxPackages()).isEqualTo(100);
    }

    @Test
    void bindsVulnerabilitiesMaxAdvisories() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.vulnerabilities.max-advisories", "50");

        BootUiProperties props = bind(env);

        assertThat(props.getVulnerabilities().getMaxAdvisories()).isEqualTo(50);
    }

    @Test
    void bindsClaudeCodeSettings() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("bootui.claude-code.enabled", "ON");
        env.setProperty("bootui.claude-code.session-state-dir", "/tmp/claude-projects");
        env.setProperty("bootui.claude-code.max-sessions", "25");
        env.setProperty("bootui.claude-code.allow-raw-reveal", "true");

        BootUiProperties props = bind(env);

        assertThat(props.getClaudeCode().getEnabled()).isEqualTo(BootUiProperties.Mode.ON);
        assertThat(props.getClaudeCode().getSessionStateDir()).isEqualTo("/tmp/claude-projects");
        assertThat(props.getClaudeCode().getMaxSessions()).isEqualTo(25);
        assertThat(props.getClaudeCode().isAllowRawReveal()).isTrue();
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
        assertThat(props.isMaskSecrets()).isTrue();
        assertThat(props.getExposeValues()).isEqualTo(BootUiProperties.ValueExposure.MASKED);
        assertThat(props.getOverridesFile()).isEqualTo(".bootui/application-bootui.properties");
    }
}
