package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Triggers SPRING-AOT-007 through a @Bean method returning a registry post-processor. */
@Configuration(proxyBeanMethods = false)
public class RegistryPostProcessorConfiguration {

    @Bean
    public static UnregisteredRegistryPostProcessor registryPostProcessor() {
        return new UnregisteredRegistryPostProcessor();
    }
}
