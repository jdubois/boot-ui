package io.github.jdubois.bootui.autoconfigure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

/** Coverage for the shared "what did the host actually configure?" property lookup. */
class BootUiContributedPropertiesTests {

    private static final String SHOW_DETAILS = "management.endpoint.health.show-details";

    @Test
    void ignoresBootUiActuatorDefaultContributedThroughDefaultProperties() {
        MockEnvironment environment = new MockEnvironment();
        environment
                .getPropertySources()
                .addLast(new MapPropertySource("defaultProperties", Map.of(SHOW_DETAILS, "always")));

        assertThat(BootUiContributedProperties.hostProperty(environment, SHOW_DETAILS))
                .isNull();
    }

    @Test
    void ignoresBootUiActuatorDefaultWhenConfigurationPropertiesSourceIsAttached() {
        // Regression for #923: Spring Boot attaches a "configurationProperties" source at the front of the
        // environment that resolves through every other source. Without skipping it, BootUI's own default
        // is returned under that source's name and reported as host configuration.
        StandardEnvironment environment = new StandardEnvironment();
        environment
                .getPropertySources()
                .addLast(new MapPropertySource("defaultProperties", Map.of(SHOW_DETAILS, "always")));
        ConfigurationPropertySources.attach(environment);

        assertThat(BootUiContributedProperties.hostProperty(environment, SHOW_DETAILS))
                .isNull();
    }

    @Test
    void returnsHostValueEvenWhenConfigurationPropertiesSourceIsAttached() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("application", Map.of(SHOW_DETAILS, "always")));
        ConfigurationPropertySources.attach(environment);

        assertThat(BootUiContributedProperties.hostProperty(environment, SHOW_DETAILS))
                .isEqualTo("always");
    }

    @Test
    void returnsHostValueThatDiffersFromTheBootUiDefaultInDefaultProperties() {
        MockEnvironment environment = new MockEnvironment();
        environment
                .getPropertySources()
                .addLast(new MapPropertySource("defaultProperties", Map.of(SHOW_DETAILS, "when-authorized")));

        assertThat(BootUiContributedProperties.hostProperty(environment, SHOW_DETAILS))
                .isEqualTo("when-authorized");
    }

    @Test
    void skipsBlankAndMissingValues() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("application", Map.of(SHOW_DETAILS, "  ")));

        assertThat(BootUiContributedProperties.hostProperty(environment, SHOW_DETAILS))
                .isNull();
        assertThat(BootUiContributedProperties.hostProperty(environment, "management.endpoint.health.show-components"))
                .isNull();
    }

    @Test
    void firstHostPropertyReturnsTheFirstKeySetByTheHost() {
        MockEnvironment environment = new MockEnvironment();
        environment
                .getPropertySources()
                .addLast(new MapPropertySource("defaultProperties", Map.of(SHOW_DETAILS, "always")));
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "application", Map.of("management.endpoint.health.show-components", "always")));

        assertThat(BootUiContributedProperties.firstHostProperty(
                        environment, SHOW_DETAILS, "management.endpoint.health.show-components"))
                .isEqualTo("always");
    }

    @Test
    void toleratesNullArguments() {
        assertThat(BootUiContributedProperties.hostProperty(null, SHOW_DETAILS)).isNull();
        assertThat(BootUiContributedProperties.hostProperty(new MockEnvironment(), null))
                .isNull();
        assertThat(BootUiContributedProperties.firstHostProperty(new MockEnvironment(), (String[]) null))
                .isNull();
    }
}
