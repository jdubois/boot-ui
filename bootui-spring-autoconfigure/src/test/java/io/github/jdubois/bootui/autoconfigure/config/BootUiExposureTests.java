package io.github.jdubois.bootui.autoconfigure.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.ValueExposure;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

    @Test
    void reportsAnInvalidRuntimeValueOnceUntilItBindsAgain() {
        Logger logger = (Logger) LoggerFactory.getLogger(BootUiExposure.class);
        ListAppender<ILoggingEvent> warnings = new ListAppender<>();
        warnings.start();
        logger.addAppender(warnings);
        try {
            MockEnvironment environment = new MockEnvironment().withProperty("bootui.expose-values", "not-a-mode");
            BootUiExposure exposure = new BootUiExposure(environment, new BootUiProperties());

            // Log Tail resolves the policy per streamed line, and the warning is itself a captured line.
            for (int read = 0; read < 5; read++) {
                assertThat(exposure.valueExposure()).isEqualTo(ValueExposure.MASKED);
            }
            assertThat(warnings.list).hasSize(1);

            environment.setProperty("bootui.expose-values", "FULL");
            assertThat(exposure.valueExposure()).isEqualTo(ValueExposure.FULL);
            environment.setProperty("bootui.expose-values", "still-not-a-mode");
            assertThat(exposure.valueExposure()).isEqualTo(ValueExposure.MASKED);
            assertThat(warnings.list).hasSize(2);
        } finally {
            logger.detachAppender(warnings);
        }
    }
}
