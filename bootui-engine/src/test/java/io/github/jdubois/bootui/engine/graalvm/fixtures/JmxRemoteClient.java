package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

/** Triggers GRAAL-JMX-001 through remote JMX client calls that need --enable-monitoring=jmxclient. */
public class JmxRemoteClient {

    public MemoryMXBean remoteMemory(String url) throws IOException {
        MBeanServerConnection connection =
                JMXConnectorFactory.connect(new JMXServiceURL(url)).getMBeanServerConnection();
        return ManagementFactory.getPlatformMXBean(connection, MemoryMXBean.class);
    }

    public MemoryMXBean localMemory() {
        return ManagementFactory.getPlatformMXBean(MemoryMXBean.class);
    }
}
