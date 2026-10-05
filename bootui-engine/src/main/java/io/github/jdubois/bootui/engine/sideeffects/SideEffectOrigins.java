package io.github.jdubois.bootui.engine.sideeffects;

import io.github.jdubois.bootui.core.SecretValueDetector;
import java.util.List;

/**
 * Where a file operation or an environment read came from, and where a file is ({@code docs/PLAN-v2.md} §5.16,
 * M5-5d): the agent's bridge gives each record a JDK context from its frame summary and the first frame outside the JDK;
 * this class names its origin, so class loading, the JDK's own files, and logging appenders are grouped apart from the
 * application's, and the location of a path pattern the bridge made relative to the working directory ({@code ./}), the
 * temporary directory ({@code $TMPDIR}), or the home ({@code ~}). It also masks, per path segment, what looks like a
 * secret value, whatever {@code bootui.expose-values} says: a pattern is never a value.
 */
public final class SideEffectOrigins {

    /** The application's own code: a frame in its packages called the JDK. */
    public static final String APPLICATION = "application";

    /** A dependency, with no application frame on the stack, as a pool, a driver, or a server on its own thread. */
    public static final String LIBRARY = "library";

    /** Class loading and class-path resources. */
    public static final String CLASS_PATH = "class-path";

    /** The JDK alone: no frame outside it. */
    public static final String JDK = "jdk";

    /** A logging framework's appender or the JDK's logging handler. */
    public static final String LOGGING = "logging";

    /** BootUI's own work: never shown. */
    static final String BOOTUI = "bootui";

    /** No frame summary: the agent's cache of them was full. */
    public static final String UNKNOWN = "unknown";

    /** The locations of a file pattern. */
    public static final String WORKING_DIRECTORY = "working-directory";

    public static final String TEMPORARY_DIRECTORY = "temporary-directory";
    public static final String HOME = "home";
    public static final String SYSTEM = "system";
    public static final String ELSEWHERE = "elsewhere";
    public static final String JAVA_HOME = "java-home";

    /** What masks a secret-looking path segment or name. */
    static final String MASKED = "******";

    /** Logging frameworks, by the package of the first frame outside the JDK. */
    static final List<String> LOGGING_PACKAGES = List.of(
            "ch.qos.logback.",
            "org.apache.logging.log4j.",
            "org.apache.log4j.",
            "org.jboss.logmanager.",
            "org.slf4j.",
            "org.tinylog.",
            "org.apache.catalina.valves.",
            "io.quarkus.bootstrap.logging.",
            "io.quarkus.vertx.http.runtime.filters.accesslog.",
            "net.logstash.logback.");

    /** Class loaders and class-path scanners outside the JDK. */
    static final List<String> CLASS_PATH_PACKAGES = List.of(
            "org.springframework.boot.loader.",
            "io.quarkus.bootstrap.",
            "org.jboss.modules.",
            "org.apache.catalina.loader.",
            "org.apache.tomcat.util.scan.",
            "org.apache.tomcat.util.descriptor.",
            // Quarkus dev mode compiling and watching the sources, as its javac reads src/main/java.
            "io.quarkus.deployment.dev.");

    private SideEffectOrigins() {}

    /**
     * The origin of a record with JDK context {@code context} ({@link SideEffectRecord#context()}), first frame outside
     * the JDK {@code outside} and first application frame {@code application}, both {@code Class#method} or {@code null}.
     */
    static String origin(int context, String outside, String application) {
        switch (context) {
            case SideEffectRecord.CONTEXT_JDK_LOGGING:
                return LOGGING;
            case SideEffectRecord.CONTEXT_CLASS_LOADING:
                return CLASS_PATH;
            case SideEffectRecord.CONTEXT_JDK_ONLY:
                return JDK;
            default:
                break;
        }
        if (outside == null) {
            return application == null ? UNKNOWN : APPLICATION;
        }
        if (startsWithAny(outside, LOGGING_PACKAGES)) {
            return LOGGING;
        }
        if (startsWithAny(outside, CLASS_PATH_PACKAGES)) {
            return CLASS_PATH;
        }
        return application != null ? APPLICATION : LIBRARY;
    }

    /** Whether rows of {@code origin} are grouped apart from the application's own: class path, JDK, logging. */
    public static boolean groupedApart(String origin) {
        return CLASS_PATH.equals(origin) || JDK.equals(origin) || LOGGING.equals(origin);
    }

    /** The location of a path pattern the agent made. */
    static String location(String pattern) {
        if (pattern == null) {
            return ELSEWHERE;
        }
        if (pattern.equals("$TMPDIR") || pattern.startsWith("$TMPDIR/")) {
            return TEMPORARY_DIRECTORY;
        }
        if (pattern.equals(".") || pattern.startsWith("./")) {
            return WORKING_DIRECTORY;
        }
        if (pattern.equals("~") || pattern.startsWith("~/")) {
            return HOME;
        }
        if (pattern.startsWith("/proc/") || pattern.startsWith("/sys/") || pattern.startsWith("/dev/")) {
            return SYSTEM;
        }
        return ELSEWHERE;
    }

    /** {@code pattern} with every segment that looks like a secret value masked. */
    static String maskPath(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return pattern;
        }
        String[] segments = pattern.split("/", -1);
        boolean masked = false;
        for (int i = 0; i < segments.length; i++) {
            if (!segments[i].isEmpty() && SecretValueDetector.looksLikeSecret(segments[i])) {
                segments[i] = MASKED;
                masked = true;
            }
        }
        return masked ? String.join("/", segments) : pattern;
    }

    /** {@code name}, an environment variable's or a system property's, masked when it looks like a secret value. */
    static String maskName(String name) {
        return name != null && SecretValueDetector.looksLikeSecret(name) ? MASKED : name;
    }

    private static boolean startsWithAny(String text, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
