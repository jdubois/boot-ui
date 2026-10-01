package io.github.jdubois.bootui.engine.graalvm.fixtures;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;

/** Reads a local platform MXBean directly; GRAAL-JMX-001 must stay quiet. */
public class LocalMxBeanReader {

    public long heapUsed() {
        MemoryMXBean memory = ManagementFactory.getPlatformMXBean(MemoryMXBean.class);
        return memory.getHeapMemoryUsage().getUsed();
    }
}
