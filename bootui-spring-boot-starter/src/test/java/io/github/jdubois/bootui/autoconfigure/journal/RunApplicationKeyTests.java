package io.github.jdubois.bootui.autoconfigure.journal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class RunApplicationKeyTests {

    @Test
    void theKeyIsTheModeAndTheConfiguredApplicationName() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", " orders ")
                .withProperty("bootui.agent.mode", "dev");

        assertThat(RunApplicationKey.of(environment, null)).isEqualTo("dev:orders");
    }

    @Test
    void withoutANameTheKeyNamesTheSpringBootConfigurationClassWithoutCreatingIt() {
        MockEnvironment environment = new MockEnvironment().withProperty("bootui.agent.mode", "test");
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(Orders.class);

        assertThat(RunApplicationKey.of(environment, context)).isEqualTo("test:" + Orders.class.getName());
        assertThat(context.isActive()).isFalse();
        assertThat(RunApplicationKey.of(environment, new AnnotationConfigApplicationContext()))
                .isEqualTo("test:application");
    }

    @SpringBootApplication
    static class Orders {}
}
