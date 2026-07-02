package io.github.jdubois.bootui.engine.support;

import java.util.List;

/**
 * Shared deny-list of fully-qualified class name prefixes that belong to the JDK, common frameworks/
 * libraries, or BootUI itself rather than the host application's own code.
 *
 * <p>Used to pick the first "application frame" out of a stack trace — for {@link
 * io.github.jdubois.bootui.engine.exceptions.ExceptionStore}'s exception location, and for {@code
 * SqlTraceRecorder}'s SQL call-site capture — without either feature special-casing JDBC drivers,
 * connection pools, Hibernate internals, or BootUI's own instrumentation.</p>
 */
public final class StackFramePrefixes {

    private static final List<String> FRAMEWORK_PREFIXES = List.of(
            "java.",
            "javax.",
            "jakarta.",
            "jdk.",
            "sun.",
            "com.sun.",
            "org.springframework.",
            "org.apache.",
            "ch.qos.",
            "org.slf4j.",
            "io.micrometer.",
            "org.hibernate.",
            "com.zaxxer.",
            "org.junit.",
            "org.gradle.",
            "org.eclipse.",
            "reactor.",
            "io.netty.",
            "io.vertx.",
            "io.quarkus.",
            "org.aspectj.",
            "net.bytebuddy.",
            "org.jboss.",
            "io.github.jdubois.bootui.");

    private StackFramePrefixes() {}

    /**
     * Whether {@code className} belongs to a known framework/JDK/BootUI-internal package rather than
     * application code. Returns {@code true} (i.e. "not application code") for {@code null}, so callers
     * can filter without a separate null check.
     */
    public static boolean isFrameworkClass(String className) {
        if (className == null) {
            return true;
        }
        for (String prefix : FRAMEWORK_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
