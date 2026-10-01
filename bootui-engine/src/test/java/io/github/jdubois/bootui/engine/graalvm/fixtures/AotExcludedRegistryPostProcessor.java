package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.stereotype.Component;

/** Spring AOT implicitly excludes this processor from the runtime context; SPRING-AOT-007 must stay quiet. */
@Component
public class AotExcludedRegistryPostProcessor
        implements BeanDefinitionRegistryPostProcessor, BeanFactoryInitializationAotProcessor {

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {}

    @Override
    public BeanFactoryInitializationAotContribution processAheadOfTime(ConfigurableListableBeanFactory beanFactory) {
        return null;
    }
}
