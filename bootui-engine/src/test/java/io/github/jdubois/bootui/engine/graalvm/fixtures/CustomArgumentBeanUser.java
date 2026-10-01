package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;

/** Triggers SPRING-AOT-006 by creating beans with explicit constructor arguments. */
public class CustomArgumentBeanUser {

    public Object byName(BeanFactory beanFactory) {
        return beanFactory.getBean("report", "monthly");
    }

    public Object byType(BeanFactory beanFactory) {
        return beanFactory.getBean(Object.class, "monthly");
    }

    public Object fromProvider(ObjectProvider<Object> provider) {
        return provider.getObject("monthly");
    }
}
