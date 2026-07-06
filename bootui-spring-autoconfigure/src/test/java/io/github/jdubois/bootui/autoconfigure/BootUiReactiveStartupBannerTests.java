package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Pins {@link BootUiReactiveAutoConfiguration#buildStartupUrl(org.springframework.core.env.Environment,
 * BootUiProperties)}, the reactive twin deliberately duplicated from {@link
 * BootUiAutoConfiguration#buildStartupUrl} rather than called cross-class (see that method's Javadoc for
 * the {@code NoClassDefFoundError: jakarta/servlet/Filter} classloading trap this avoids). Mirrors {@link
 * BootUiStartupBannerTests} case for case so both copies stay behaviorally identical.
 */
class BootUiReactiveStartupBannerTests {

    private final BootUiProperties properties = new BootUiProperties();

    @Test
    void usesHttpSchemeByDefault() {
        MockEnvironment environment = new MockEnvironment();

        assertThat(BootUiReactiveAutoConfiguration.buildStartupUrl(environment, properties))
                .isEqualTo("http://localhost:8080/bootui");
    }

    @Test
    void usesHttpsSchemeWhenSslEnabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("server.ssl.enabled", "true");

        assertThat(BootUiReactiveAutoConfiguration.buildStartupUrl(environment, properties))
                .isEqualTo("https://localhost:8080/bootui");
    }

    @Test
    void usesConfiguredServerPort() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("server.port", "9443");
        environment.setProperty("server.ssl.enabled", "true");

        assertThat(BootUiReactiveAutoConfiguration.buildStartupUrl(environment, properties))
                .isEqualTo("https://localhost:9443/bootui");
    }

    @Test
    void prefersLocalServerPortOverServerPort() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("server.port", "0");
        environment.setProperty("local.server.port", "54321");

        assertThat(BootUiReactiveAutoConfiguration.buildStartupUrl(environment, properties))
                .isEqualTo("http://localhost:54321/bootui");
    }

    @Test
    void honorsCustomBootUiPath() {
        MockEnvironment environment = new MockEnvironment();
        properties.setPath("/console");

        assertThat(BootUiReactiveAutoConfiguration.buildStartupUrl(environment, properties))
                .isEqualTo("http://localhost:8080/console");
    }
}
