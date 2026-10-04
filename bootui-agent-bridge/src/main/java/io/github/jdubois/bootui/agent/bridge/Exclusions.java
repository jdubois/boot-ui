package io.github.jdubois.bootui.agent.bridge;

/**
 * The classes the BootUI agent never instruments, by binary name (PLAN-v2 §5.13): BootUI's own modules, the agent's
 * libraries, the JDK, other agents' runtimes, and the proxies and generated classes frameworks define (CGLIB and AOT
 * proxies, ArC's generated beans, Hibernate and Mockito proxies, JDK dynamic proxies). One list, here on the bootstrap
 * class path beside the bridge, so the agent's matchers and the engine's Code Inventory, which reads it through the
 * bridge, can never disagree on which application methods the agent could have seen. JDK types only.
 */
public final class Exclusions {

    private static final String[] PREFIXES = {
        "io.github.jdubois.bootui.agent.",
        "io.github.jdubois.bootui.engine.",
        "io.github.jdubois.bootui.core.",
        "io.github.jdubois.bootui.spi.",
        "io.github.jdubois.bootui.autoconfigure.",
        "io.github.jdubois.bootui.quarkus.",
        "net.bytebuddy.",
        "java.",
        "javax.",
        "jdk.",
        "sun.",
        "com.sun.",
        "io.opentelemetry.javaagent.",
        "com.intellij.rt.",
        "org.jacoco.agent.rt."
    };

    private static final String[] CONTAINS = {
        "$$", "$HibernateProxy$", "$MockitoMock$", "$ByteBuddy$", "$Proxy",
    };

    private static final String[] SUFFIXES = {"_Subclass", "_ClientProxy", "_Bean"};

    private Exclusions() {}

    /** Whether the agent never instruments the class {@code binaryName} ({@code com.example.Outer$Inner}). */
    public static boolean excluded(String binaryName) {
        if (binaryName == null) {
            return true;
        }
        for (String prefix : PREFIXES) {
            if (binaryName.startsWith(prefix)) {
                return true;
            }
        }
        for (String part : CONTAINS) {
            if (binaryName.indexOf(part) >= 0) {
                return true;
            }
        }
        for (String suffix : SUFFIXES) {
            if (binaryName.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code binaryName} names a class a framework or the JDK generated: a proxy, a CGLIB or AOT subclass. */
    static boolean generated(String binaryName) {
        for (String part : CONTAINS) {
            if (binaryName.indexOf(part) >= 0) {
                return true;
            }
        }
        for (String suffix : SUFFIXES) {
            if (binaryName.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** The excluded name prefixes: a copy. */
    public static String[] prefixes() {
        return PREFIXES.clone();
    }

    /** The excluded name parts: a copy. */
    public static String[] contains() {
        return CONTAINS.clone();
    }

    /** The excluded name suffixes: a copy. */
    public static String[] suffixes() {
        return SUFFIXES.clone();
    }
}
