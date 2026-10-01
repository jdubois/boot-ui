package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/** Triggers SPRING-AOT-008 through refresh-scoped @Bean methods. */
@Configuration(proxyBeanMethods = false)
public class RefreshScopedBeans {

    @Bean
    @RefreshScope
    public Object refreshable() {
        return new Object();
    }

    @Bean
    @Scope("refresh")
    public Object explicitScope() {
        return new Object();
    }

    @Bean
    @Scope("prototype")
    public Object prototype() {
        return new Object();
    }
}
