package io.github.jdubois.bootui.autoconfigure.scheduled;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

class BootUiSchedulingConfigurerTests {

    @Test
    void runsAtLowestPrecedenceSoItNeverRunsBeforeHostConfigurers() {
        BootUiSchedulingConfigurer configurer = new BootUiSchedulingConfigurer(new RecordingHandler());

        assertThat(configurer.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    void createsAnObservationRegistryWhenTheRegistrarHasNone() {
        RecordingHandler handler = new RecordingHandler();
        BootUiSchedulingConfigurer configurer = new BootUiSchedulingConfigurer(handler);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        assertThat(registrar.getObservationRegistry()).isNull();

        configurer.configureTasks(registrar);

        ObservationRegistry registry = registrar.getObservationRegistry();
        assertThat(registry).isNotNull();
        Observation.start("test", registry).stop();
        assertThat(handler.starts).isEqualTo(1);
        assertThat(handler.stops).isEqualTo(1);
    }

    @Test
    void addsToAnExistingRegistryWithoutReplacingIt() {
        RecordingHandler hostHandler = new RecordingHandler();
        RecordingHandler bootUiHandler = new RecordingHandler();
        ObservationRegistry existing = ObservationRegistry.create();
        existing.observationConfig().observationHandler(hostHandler);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        registrar.setObservationRegistry(existing);
        BootUiSchedulingConfigurer configurer = new BootUiSchedulingConfigurer(bootUiHandler);

        configurer.configureTasks(registrar);

        assertThat(registrar.getObservationRegistry()).isSameAs(existing);
        Observation.start("test", existing).stop();
        assertThat(hostHandler.starts).isEqualTo(1);
        assertThat(bootUiHandler.starts).isEqualTo(1);
    }

    private static final class RecordingHandler implements ObservationHandler<Observation.Context> {
        int starts;
        int stops;

        @Override
        public void onStart(Observation.Context context) {
            starts++;
        }

        @Override
        public void onStop(Observation.Context context) {
            stops++;
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }
    }
}
