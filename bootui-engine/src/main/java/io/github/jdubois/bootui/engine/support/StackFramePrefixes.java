package io.github.jdubois.bootui.engine.support;

import java.util.List;
import java.util.stream.Stream;

/**
 * Shared deny-list of fully-qualified class name prefixes that belong to the JDK, common frameworks/
 * libraries, or BootUI itself rather than the host application's own code.
 *
 * <p>Used to pick the first "application frame" out of a stack trace — for {@link
 * io.github.jdubois.bootui.engine.exceptions.ExceptionStore}'s exception location, and for {@code
 * SqlTraceRecorder}'s SQL call-site capture — without either feature special-casing JDBC drivers,
 * connection pools, Hibernate internals, or BootUI's own instrumentation.</p>
 *
 * <p>BootUI is matched by its module packages rather than by the whole {@code io.github.jdubois.bootui} namespace,
 * so the sample applications ({@code io.github.jdubois.bootui.sample} and {@code
 * io.github.jdubois.bootui.webfluxsample}) count as application code and show real call sites.</p>
 */
public final class StackFramePrefixes {

    /** Package prefixes of BootUI's own modules, including their tests. Package-private for tests. */
    static final List<String> BOOTUI_MODULE_PREFIXES = List.of(
            "io.github.jdubois.bootui.core.",
            "io.github.jdubois.bootui.engine.",
            "io.github.jdubois.bootui.spi.",
            "io.github.jdubois.bootui.autoconfigure.",
            "io.github.jdubois.bootui.quarkus.",
            "io.github.jdubois.bootui.client.",
            "io.github.jdubois.bootui.cli.",
            "io.github.jdubois.bootui.conformance.",
            // The Java agent and its bootstrap bridge (PLAN-v2 §5.13): their frames sit on application stacks.
            "io.github.jdubois.bootui.agent.");

    private static final List<String> THIRD_PARTY_PREFIXES = List.of(
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
            "org.jboss.");

    private static final List<String> FRAMEWORK_PREFIXES = Stream.concat(
                    THIRD_PARTY_PREFIXES.stream(), BOOTUI_MODULE_PREFIXES.stream())
            .toList();

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
