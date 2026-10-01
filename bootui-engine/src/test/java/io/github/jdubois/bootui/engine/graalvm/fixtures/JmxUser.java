package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;

/** Obtains the platform MBeanServer, which Native Image substitutes without --enable-monitoring. */
public class JmxUser {

    public MBeanServer mbeanServer() {
        return ManagementFactory.getPlatformMBeanServer();
    }
}
