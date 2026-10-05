package io.github.jdubois.bootui.autoconfigure.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.config.BootUiStartupEnvironmentPostProcessor.BufferingApplicationStartupRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.mock.env.MockEnvironment;

class BootUiStartupEnvironmentPostProcessorTests {

    private static final String AOT_ENABLED = "spring.aot.enabled";

    private final BootUiStartupEnvironmentPostProcessor processor = new BootUiStartupEnvironmentPostProcessor();

    @Test
    void installsBufferingStartupWhenBootUiIsEnabled() {
        MockEnvironment env = new MockEnvironment().withProperty("bootui.enabled", "ON");
        SpringApplication application = new SpringApplication();

        processor.postProcessEnvironment(env, application);

        assertThat(application.getApplicationStartup()).isInstanceOf(BufferingApplicationStartup.class);
    }

    @Test
    void doesNotInstallWhenBootUiDisabledOnTheJvm() {
        MockEnvironment env = new MockEnvironment();
        SpringApplication application = new SpringApplication();

        processor.postProcessEnvironment(env, application);

        assertThat(application.getApplicationStartup()).isNotInstanceOf(BufferingApplicationStartup.class);
    }

    @Test
    void installsInAotRuntimeEvenWhenBootUiDisabled() {
        // A native image AOT-processed with BootUI active bakes in Spring Boot's StartupEndpoint, which
        // needs a BufferingApplicationStartup bean. Provide it so the host application can still start.
        withAotRuntime(() -> {
            MockEnvironment env = new MockEnvironment();
            SpringApplication application = new SpringApplication();

            processor.postProcessEnvironment(env, application);

            assertThat(application.getApplicationStartup()).isInstanceOf(BufferingApplicationStartup.class);
        });
    }

    @Test
    void respectsStartupOptOutEvenInAotRuntime() {
        withAotRuntime(() -> {
            MockEnvironment env = new MockEnvironment().withProperty("bootui.startup.enabled", "false");
            SpringApplication application = new SpringApplication();

            processor.postProcessEnvironment(env, application);

            assertThat(application.getApplicationStartup()).isNotInstanceOf(BufferingApplicationStartup.class);
        });
    }

    @Test
    void doesNotInstallWhenCapacityIsNotPositive() {
        MockEnvironment env =
                new MockEnvironment().withProperty("bootui.enabled", "ON").withProperty("bootui.startup.capacity", "0");
        SpringApplication application = new SpringApplication();

        processor.postProcessEnvironment(env, application);

        assertThat(application.getApplicationStartup()).isNotInstanceOf(BufferingApplicationStartup.class);
    }

    @Test
    void keepsAUserConfiguredBufferingStartup() {
        MockEnvironment env = new MockEnvironment().withProperty("bootui.enabled", "ON");
        SpringApplication application = new SpringApplication();
        BufferingApplicationStartup existing = new BufferingApplicationStartup(8);
        application.setApplicationStartup(existing);

        processor.postProcessEnvironment(env, application);

        assertThat(application.getApplicationStartup()).isSameAs(existing);
    }

    @Test
    void registrarRegistersBufferingStartupSingleton() {
        BufferingApplicationStartup startup = new BufferingApplicationStartup(16);
        BufferingApplicationStartupRegistrar registrar = new BufferingApplicationStartupRegistrar(startup);

        try (GenericApplicationContext context = new GenericApplicationContext()) {
            registrar.initialize(context);

            assertThat(context.getBeanFactory().getSingleton(BootUiStartupEnvironmentPostProcessor.BEAN_NAME))
                    .isSameAs(startup);
        }
    }

    @Test
    void runsAtHighPrecedence() {
        assertThat(processor.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 20);
    }

    private static void withAotRuntime(Runnable body) {
        String previous = System.getProperty(AOT_ENABLED);
        System.setProperty(AOT_ENABLED, "true");
        try {
            body.run();
        } finally {
            if (previous == null) {
                System.clearProperty(AOT_ENABLED);
            } else {
                System.setProperty(AOT_ENABLED, previous);
            }
        }
    }
}
