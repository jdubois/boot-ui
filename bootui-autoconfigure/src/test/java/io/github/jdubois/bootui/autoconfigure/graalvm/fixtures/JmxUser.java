package io.github.jdubois.bootui.autoconfigure.graalvm.fixtures;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;

/** Triggers GRAAL-JMX-001 by obtaining the platform MBeanServer. */
public class JmxUser {

    public MBeanServer mbeanServer() {
        return ManagementFactory.getPlatformMBeanServer();
    }
}
