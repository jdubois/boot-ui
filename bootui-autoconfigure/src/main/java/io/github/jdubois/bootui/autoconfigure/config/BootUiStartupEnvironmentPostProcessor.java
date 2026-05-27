package io.github.jdubois.bootui.autoconfigure.config;

import io.github.jdubois.bootui.autoconfigure.BootUiActivationCondition;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.metrics.ApplicationStartup;

/**
 * Installs a {@link BufferingApplicationStartup} on the {@link SpringApplication}
 * so Spring Boot's {@code StartupEndpoint} (and BootUI's startup panel) can
 * report startup steps.
 *
 * <p>Without a {@code BufferingApplicationStartup}, the default
 * {@link ApplicationStartup} discards every step and BootUI's startup panel
 * shows "No startup data available".</p>
 *
 * <p>Runs only when BootUI is going to activate (per
 * {@link BootUiActivationCondition}) and only if the user has not already
 * configured a {@code BufferingApplicationStartup}. Can be disabled with
 * {@code bootui.startup.enabled=false}. Buffer size is configurable via
 * {@code bootui.startup.capacity} (default 4096).</p>
 */
public class BootUiStartupEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    static final String BEAN_NAME = "bufferingApplicationStartup";

    static final int DEFAULT_CAPACITY = 4096;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("bootui.startup.enabled", Boolean.class, true)) {
            return;
        }
        if (!BootUiActivationCondition.resolve(environment, application.getClassLoader()).enabled()) {
            return;
        }
        ApplicationStartup current = application.getApplicationStartup();
        if (current instanceof BufferingApplicationStartup) {
            return;
        }
        int capacity = environment.getProperty("bootui.startup.capacity", Integer.class, DEFAULT_CAPACITY);
        if (capacity <= 0) {
            return;
        }
        BufferingApplicationStartup buffering = new BufferingApplicationStartup(capacity);
        application.setApplicationStartup(buffering);
        application.addInitializers(new BufferingApplicationStartupRegistrar(buffering));
    }

    /**
     * Registers the {@link BufferingApplicationStartup} as a singleton bean so
     * Spring Boot's auto-configured {@code StartupEndpoint} can be exposed.
     */
    static final class BufferingApplicationStartupRegistrar
        implements ApplicationContextInitializer<ConfigurableApplicationContext>, Ordered {

        private final BufferingApplicationStartup startup;

        BufferingApplicationStartupRegistrar(BufferingApplicationStartup startup) {
            this.startup = startup;
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            var beanFactory = context.getBeanFactory();
            if (!beanFactory.containsSingleton(BEAN_NAME)) {
                beanFactory.registerSingleton(BEAN_NAME, this.startup);
            }
        }
    }
}
