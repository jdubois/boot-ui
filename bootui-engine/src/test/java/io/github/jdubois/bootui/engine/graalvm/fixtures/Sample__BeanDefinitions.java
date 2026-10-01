package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.lang.reflect.Field;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.util.ReflectionUtils;

/**
 * Models Spring AOT-generated bean-definition code, which must not trigger SPRING-AOT-002 or GRAAL-REFLECT-001: the
 * generated code ships with its own runtime hints.
 */
public class Sample__BeanDefinitions {

    public void register() {
        RootBeanDefinition definition = new RootBeanDefinition();
        definition.setInstanceSupplier(Object::new);
    }

    public void inject(Object bean, Object value) {
        Field field = ReflectionUtils.findField(bean.getClass(), "repository");
        ReflectionUtils.setField(field, bean, value);
    }
}
