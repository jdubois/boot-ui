package io.github.jdubois.bootui.autoconfigure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class BootUiPathPropertySourceTests {

    @Test
    void publishesDefaultPaths() {
        MockEnvironment environment = new MockEnvironment();

        BootUiPathPropertySource.apply(environment);

        assertThat(environment.getProperty("bootui.path")).isEqualTo("/bootui");
        assertThat(environment.getProperty("bootui.api-path")).isEqualTo("/bootui/api");
    }

    @Test
    void normalizesConfiguredUiAndApiPaths() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("bootui.path", " /dev-console/// ")
                .withProperty("bootui.api-path", " /internal/bootui-api/ ");

        BootUiPathPropertySource.apply(environment);

        assertThat(environment.getProperty("bootui.path")).isEqualTo("/dev-console");
        assertThat(environment.getProperty("bootui.api-path")).isEqualTo("/internal/bootui-api");
    }

    @Test
    void derivesApiPathFromNormalizedUiPath() {
        MockEnvironment environment = new MockEnvironment().withProperty("bootui.path", "/dev-console/");

        BootUiPathPropertySource.apply(environment);

        assertThat(environment.getProperty("bootui.api-path")).isEqualTo("/dev-console/api");
    }

    @Test
    void rejectsInvalidConfiguredPaths() {
        MockEnvironment invalidUi = new MockEnvironment().withProperty("bootui.path", "/admin/**");
        MockEnvironment invalidApi = new MockEnvironment().withProperty("bootui.api-path", "/admin/%2fapi");

        assertThatThrownBy(() -> BootUiPathPropertySource.apply(invalidUi))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bootui.path");
        assertThatThrownBy(() -> BootUiPathPropertySource.apply(invalidApi))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bootui.api-path");
    }
}
