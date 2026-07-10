package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.beans.factory.support.RootBeanDefinition;

/** Models Spring AOT-generated bean-definition code, which must not trigger SPRING-AOT-002. */
public class Sample__BeanDefinitions {

    public void register() {
        RootBeanDefinition definition = new RootBeanDefinition();
        definition.setInstanceSupplier(Object::new);
    }
}
