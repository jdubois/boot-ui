package io.github.jdubois.bootui.autoconfigure.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.ValueExposure;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class BootUiExposureTests {

    @Test
    void resolvesRuntimeExposureAndMaskingOverridesFromEnvironment() {
        BootUiProperties properties = new BootUiProperties();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("bootui.expose-values", "FULL")
                .withProperty("bootui.mask-secrets", "false");

        BootUiExposure exposure = new BootUiExposure(environment, properties);

        assertThat(exposure.valueExposure()).isEqualTo(ValueExposure.FULL);
        assertThat(exposure.maskSecrets()).isFalse();
    }

    @Test
    void invalidRuntimeExposureFallsBackToAlreadyBoundValue() {
        BootUiProperties properties = new BootUiProperties();
        properties.setExposeValues(ValueExposure.METADATA_ONLY);
        MockEnvironment environment = new MockEnvironment().withProperty("bootui.expose-values", "not-a-mode");

        BootUiExposure exposure = new BootUiExposure(environment, properties);

        assertThat(exposure.valueExposure()).isEqualTo(ValueExposure.METADATA_ONLY);
    }
}
