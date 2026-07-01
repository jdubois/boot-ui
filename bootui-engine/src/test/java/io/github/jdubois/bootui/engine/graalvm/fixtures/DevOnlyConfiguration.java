package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Triggers SPRING-AOT-003 by combining @Configuration with a @Profile condition. */
@Configuration
@Profile("dev")
public class DevOnlyConfiguration {

    @Bean
    public Object devBean() {
        return new Object();
    }
}
