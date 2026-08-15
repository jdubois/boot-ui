package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.aot.generate.Generated;
import org.springframework.beans.factory.support.AbstractBeanDefinition;

@Generated
public class GeneratedInstanceSupplierRegistrar {

    public void register(AbstractBeanDefinition beanDefinition) {
        beanDefinition.setInstanceSupplier(Object::new);
    }
}
