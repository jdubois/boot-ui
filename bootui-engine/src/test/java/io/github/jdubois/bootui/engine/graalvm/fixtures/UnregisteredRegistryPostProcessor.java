package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;

/** Not declared as a bean, so its registration is not observable; SPRING-AOT-007 must stay quiet. */
public class UnregisteredRegistryPostProcessor implements BeanDefinitionRegistryPostProcessor {

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {}
}
