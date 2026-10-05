package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class BootUiStartupBannerTests {

    private final BootUiProperties properties = new BootUiProperties();

    @Test
    void usesHttpSchemeByDefault() {
        MockEnvironment environment = new MockEnvironment();

        assertThat(BootUiAutoConfiguration.buildStartupUrl(environment, properties))
                .isEqualTo("http://localhost:8080/bootui");
    }

    @Test
    void usesHttpsSchemeWhenSslEnabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("server.ssl.enabled", "true");

        assertThat(BootUiAutoConfiguration.buildStartupUrl(environment, properties))
                .isEqualTo("https://localhost:8080/bootui");
    }

    @Test
    void usesHttpSchemeWhenSslExplicitlyDisabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("server.ssl.enabled", "false");

        assertThat(BootUiAutoConfiguration.buildStartupUrl(environment, properties))
                .isEqualTo("http://localhost:8080/bootui");
    }

    @Test
    void usesConfiguredServerPort() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("server.port", "9443");
        environment.setProperty("server.ssl.enabled", "true");

        assertThat(BootUiAutoConfiguration.buildStartupUrl(environment, properties))
                .isEqualTo("https://localhost:9443/bootui");
    }

    @Test
    void prefersLocalServerPortOverServerPort() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("server.port", "0");
        environment.setProperty("local.server.port", "54321");

        assertThat(BootUiAutoConfiguration.buildStartupUrl(environment, properties))
                .isEqualTo("http://localhost:54321/bootui");
    }

    @Test
    void includesServletContextPath() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("server.servlet.context-path", "/app");
        environment.setProperty("server.ssl.enabled", "true");

        assertThat(BootUiAutoConfiguration.buildStartupUrl(environment, properties))
                .isEqualTo("https://localhost:8080/app/bootui");
    }

    @Test
    void honorsCustomBootUiPath() {
        MockEnvironment environment = new MockEnvironment();
        properties.setPath("/console");

        assertThat(BootUiAutoConfiguration.buildStartupUrl(environment, properties))
                .isEqualTo("http://localhost:8080/console");
    }
}
