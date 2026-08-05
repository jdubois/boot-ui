package io.github.jdubois.bootui.autoconfigure.jms;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.engine.jms.JmsActivityRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class JmsBackendAbsenceTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
            .withPropertyValues("bootui.enabled=ON");

    @Test
    void startsWithoutSpringJms() {
        runner.withClassLoader(new FilteredClassLoader("org.springframework.jms"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(JmsActivityRecorder.class);
                    assertThat(context).doesNotHaveBean(JmsProducerCaptureBeanPostProcessor.class);
                    assertThat(context).doesNotHaveBean(JmsListenerCaptureBeanPostProcessor.class);
                    assertThat(context).doesNotHaveBean(JmsController.class);
                });
    }

    @Test
    void startsWithoutJakartaJms() {
        runner.withClassLoader(new FilteredClassLoader("jakarta.jms")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(JmsActivityRecorder.class);
            assertThat(context).doesNotHaveBean(JmsProducerCaptureBeanPostProcessor.class);
            assertThat(context).doesNotHaveBean(JmsListenerCaptureBeanPostProcessor.class);
            assertThat(context).doesNotHaveBean(JmsController.class);
        });
    }
}
