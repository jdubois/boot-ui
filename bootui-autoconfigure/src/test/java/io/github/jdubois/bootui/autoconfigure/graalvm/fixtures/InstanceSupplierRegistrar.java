package io.github.jdubois.bootui.autoconfigure.graalvm.fixtures;

import java.util.function.Supplier;
import org.springframework.beans.factory.support.RootBeanDefinition;

/** Triggers SPRING-AOT-002 by backing a bean definition with a programmatic instance supplier. */
public class InstanceSupplierRegistrar {

    public RootBeanDefinition define() {
        RootBeanDefinition definition = new RootBeanDefinition(Object.class);
        Supplier<Object> supplier = Object::new;
        definition.setInstanceSupplier(supplier);
        return definition;
    }
}
