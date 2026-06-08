package io.github.jdubois.bootui.autoconfigure.graalvm.fixtures;

import java.util.function.Supplier;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;

/** Triggers SPRING-AOT-002 via a BeanDefinitionBuilder backed by a Supplier overload. */
public class SupplierBeanDefiner {

    public BeanDefinitionBuilder build() {
        Supplier<Object> supplier = Object::new;
        return BeanDefinitionBuilder.genericBeanDefinition(Object.class, supplier);
    }
}
