package io.github.jdubois.bootui.engine.graalvm.fixtures;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;

/** Ordinary bean lookups that use the AOT instance supplier; SPRING-AOT-006 must stay quiet. */
public class PlainBeanLookup {

    public Object byName(BeanFactory beanFactory) {
        return beanFactory.getBean("report");
    }

    public String typed(BeanFactory beanFactory) {
        return beanFactory.getBean("report", String.class);
    }

    public Object fromProvider(ObjectProvider<Object> provider) {
        return provider.getObject();
    }
}
