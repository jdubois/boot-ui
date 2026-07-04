package io.github.jdubois.bootui.engine.graalvm.fixtures;

import javax.management.NotCompliantMBeanException;
import javax.management.StandardMBean;

/**
 * Does not trigger GRAAL-JMX-002: subclassing {@link StandardMBean} is the JDK's supported pattern
 * for standard MBeans, even though {@code StandardMBean} itself implements {@code DynamicMBean}.
 */
public class StandardMBeanSubclass extends StandardMBean implements SampleMBean {

    protected StandardMBeanSubclass() throws NotCompliantMBeanException {
        super(SampleMBean.class);
    }

    @Override
    public String getName() {
        return "sample";
    }
}

/** Standard MBean management interface, following the {@code FooMBean} naming convention. */
interface SampleMBean {
    String getName();
}
