package io.github.jdubois.bootui.autoconfigure.config;

import io.github.jdubois.bootui.autoconfigure.BootUiActivationCondition;
import org.springframework.aot.AotDetector;
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
 *
 * <p>In an AOT-optimized image (for example a GraalVM native image) the buffer
 * is also installed when BootUI is <em>inactive</em> at runtime. AOT freezes
 * bean conditions at build time: if AOT processing ran with BootUI active,
 * Spring Boot baked in its {@code StartupEndpoint}, which has a hard dependency
 * on a {@code BufferingApplicationStartup} bean. BootUI is the only thing that
 * contributes that bean, so without this the host application would fail to
 * start with {@code APPLICATION FAILED TO START}. Installing the buffer keeps
 * such an image bootable; it remains an in-memory buffer and exposes nothing on
 * its own.</p>
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
        if (!shouldInstall(environment, application)) {
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
     * Whether the {@link BufferingApplicationStartup} should be installed. True when BootUI is going to
     * activate, or when running AOT-generated artifacts (so a {@code StartupEndpoint} that AOT may have
     * baked in does not leave the host application unable to start; see the class javadoc).
     */
    private static boolean shouldInstall(ConfigurableEnvironment environment, SpringApplication application) {
        if (BootUiActivationCondition.resolve(environment, application.getClassLoader())
                .enabled()) {
            return true;
        }
        return AotDetector.useGeneratedArtifacts();
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
