package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.stereotype.Component;

/** Triggers SPRING-AOT-007: a registry post-processor bean that the generated context invokes again. */
@Component
public class ReplayingRegistryPostProcessor implements BeanDefinitionRegistryPostProcessor {

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {}
}
