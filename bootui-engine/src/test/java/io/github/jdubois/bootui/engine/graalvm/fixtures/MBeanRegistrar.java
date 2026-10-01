package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.lang.management.ManagementFactory;
import javax.management.JMException;
import javax.management.ObjectName;

/** Triggers GRAAL-JMX-001 by registering an application MBean. */
public class MBeanRegistrar {

    public interface CounterMBean {
        int getCount();
    }

    public static class Counter implements CounterMBean {
        @Override
        public int getCount() {
            return 0;
        }
    }

    public void register() throws JMException {
        ManagementFactory.getPlatformMBeanServer().registerMBean(new Counter(), new ObjectName("app:type=Counter"));
    }
}
