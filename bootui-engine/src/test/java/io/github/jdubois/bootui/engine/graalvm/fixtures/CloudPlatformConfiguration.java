package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.boot.autoconfigure.condition.ConditionalOnCloudPlatform;
import org.springframework.boot.autoconfigure.condition.ConditionalOnJndi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnThreading;
import org.springframework.boot.cloud.CloudPlatform;
import org.springframework.boot.thread.Threading;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Triggers SPRING-AOT-003 with deployment-environment Spring Boot conditions. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnCloudPlatform(CloudPlatform.KUBERNETES)
public class CloudPlatformConfiguration {

    @Bean
    @ConditionalOnThreading(Threading.VIRTUAL)
    public Object virtualThreadExecutor() {
        return new Object();
    }

    @Bean
    @ConditionalOnJndi
    public Object jndiLookup() {
        return new Object();
    }
}
