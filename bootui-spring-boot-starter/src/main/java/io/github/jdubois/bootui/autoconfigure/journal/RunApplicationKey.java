package io.github.jdubois.bootui.autoconfigure.journal;

import io.github.jdubois.bootui.autoconfigure.javaagent.BootUiAgentClaimEnvironmentPostProcessor;
import io.github.jdubois.bootui.engine.journal.RunSummary;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * The key of a Spring application's runs ({@link RunSummary#applicationKey}), so run comparison never compares a run
 * with one of another application sharing the JVM, as the BootUI agent's claim slot keeps them apart: the mode, then
 * {@code spring.application.name}, else the {@code @SpringBootConfiguration} class, the same for every test of an
 * application, else {@code application}.
 */
public final class RunApplicationKey {

    private RunApplicationKey() {}

    /** The key of the application {@code context} runs, read from its bean definitions without creating any bean. */
    public static String of(Environment environment, ApplicationContext context) {
        String mode = environment instanceof ConfigurableEnvironment configurable
                ? BootUiAgentClaimEnvironmentPostProcessor.mode(configurable)
                : null;
        return RunSummary.applicationKey(mode, name(environment, context));
    }

    static String name(Environment environment, ApplicationContext context) {
        String configured = environment.getProperty("spring.application.name");
        if (configured != null && !configured.isBlank()) {
            return configured.strip();
        }
        if (context instanceof ConfigurableApplicationContext configurable) {
            try {
                ConfigurableListableBeanFactory beans = configurable.getBeanFactory();
                for (String name : beans.getBeanDefinitionNames()) {
                    BeanDefinition definition = beans.getBeanDefinition(name);
                    if (definition instanceof AnnotatedBeanDefinition annotated
                            && annotated.getMetadata().isAnnotated(SpringBootConfiguration.class.getName())) {
                        return annotated.getMetadata().getClassName();
                    }
                }
            } catch (RuntimeException ex) {
                // An unreadable definition names no application: the default below does.
            }
        }
        return "application";
    }
}
