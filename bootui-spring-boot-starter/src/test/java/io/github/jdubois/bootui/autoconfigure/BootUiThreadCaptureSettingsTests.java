package io.github.jdubois.bootui.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.engine.threads.ThreadCaptureSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

class BootUiThreadCaptureSettingsTests {

    @Test
    void defaultsMatchTheSharedEngine() {
        assertThat(new BootUiEngineConfiguration().bootUiThreadCaptureSettings(new BootUiProperties()))
                .isEqualTo(ThreadCaptureSettings.defaults());
    }

    @Test
    void bindsEveryThreadLimitWithoutTransposingSameTypedSettings() {
        var environment = new MockEnvironment()
                .withProperty("bootui.threads.max-threads", "241")
                .withProperty("bootui.threads.max-stack-depth", "43")
                .withProperty("bootui.threads.max-owned-locks", "17")
                .withProperty("bootui.threads.capture-deadline-millis", "2100")
                .withProperty("bootui.threads.include-synchronizers", "true")
                .withProperty("bootui.threads.max-captures", "7")
                .withProperty("bootui.threads.max-retained-bytes", "9437184")
                .withProperty("bootui.threads.max-capture-bytes", "1048576")
                .withProperty("bootui.threads.ttl-millis", "120000")
                .withProperty("bootui.threads.observation-samples", "4")
                .withProperty("bootui.threads.observation-interval-millis", "1500");
        var properties =
                Binder.get(environment).bind("bootui", BootUiProperties.class).get();

        assertThat(new BootUiEngineConfiguration().bootUiThreadCaptureSettings(properties))
                .isEqualTo(
                        new ThreadCaptureSettings(241, 43, 17, 2100, true, 7, 9_437_184, 1_048_576, 120_000, 4, 1500));
    }

    @Test
    void invalidOverridesFailRatherThanUsingDefaults() {
        var properties = new BootUiProperties();
        properties.getThreads().setMaxThreads(0);

        assertThatThrownBy(() -> new BootUiEngineConfiguration().bootUiThreadCaptureSettings(properties))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configuredObservationReservesTheLastCaptureBudgetWithoutOverflow() {
        var properties = new BootUiProperties();
        properties.getThreads().setObservationSamples(2);
        properties.getThreads().setObservationIntervalMillis(28_000);
        var configuration = new BootUiEngineConfiguration();
        assertThat(configuration.bootUiThreadCaptureSettings(properties).observationIntervalMillis())
                .isEqualTo(28_000);

        for (long interval : new long[] {28_001, 30_000, Long.MAX_VALUE}) {
            properties.getThreads().setObservationIntervalMillis(interval);
            assertThatThrownBy(() -> configuration.bootUiThreadCaptureSettings(properties))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
