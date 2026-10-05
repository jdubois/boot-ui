package io.github.jdubois.bootui.autoconfigure;

import io.github.jdubois.bootui.core.BootUiPathNormalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * Resolves the application-relative prefixes under which Spring Boot's own static-resource mapping can
 * serve the packaged BootUI bundle.
 *
 * <p>The bundle lives at the fixed classpath location {@code META-INF/resources/bootui/}, but the URL it
 * surfaces at is not fixed: the static-resource handler sits behind the DispatcherServlet mapping
 * ({@code spring.mvc.servlet.path}) and behind a configurable pattern
 * ({@code spring.mvc.static-path-pattern} / {@code spring.webflux.static-path-pattern}). With
 * {@code spring.mvc.servlet.path=/app} the shell answers at {@code /app/bootui/index.html}; with
 * {@code spring.mvc.static-path-pattern=/static/**} it answers at {@code /static/bootui/index.html}.
 * A guard pinned to {@code /bootui/**} alone would miss both.</p>
 *
 * <p>A prefix is only derived when the configured pattern can actually reach a nested resource — that
 * is, when it ends in a multi-segment wildcard. {@code /resources/*} matches a single segment and can
 * never serve {@code /resources/bootui/index.html}, so claiming that namespace would block host routes
 * for no security benefit.</p>
 *
 * <p>The reserved {@code /bootui} mount is always included, so the namespace BootUI claims stays claimed
 * regardless of how the host has moved its static handling. Values are read once at startup: these are
 * static Spring Boot properties, not live-overridable BootUI configuration.</p>
 *
 * <p>This covers Spring Boot's static-resource <em>mapping</em>, not arbitrary resource-location
 * aliasing. A host that deliberately points {@code spring.web.resources.static-locations} inside
 * BootUI's classpath directory is republishing those files under a URL of its own choosing; that is
 * host-controlled and out of scope here.</p>
 */
public final class BootUiShellGuardMounts {

    private BootUiShellGuardMounts() {}

    /** Mounts to guard in a servlet (Spring MVC) application. */
    public static List<String> servlet(Environment environment) {
        return mounts(
                pathPrefix(environment.getProperty("spring.mvc.servlet.path", "/")),
                nestedPatternPrefix(environment.getProperty("spring.mvc.static-path-pattern", "/**")));
    }

    /** Mounts to guard in a reactive (Spring WebFlux) application. */
    public static List<String> reactive(Environment environment) {
        return mounts("", nestedPatternPrefix(environment.getProperty("spring.webflux.static-path-pattern", "/**")));
    }

    private static List<String> mounts(String servletPrefix, String staticPrefix) {
        Set<String> mounts = new LinkedHashSet<>();
        mounts.add(BootUiPathNormalizer.DEFAULT_PATH);
        if (staticPrefix != null) {
            mounts.add(servletPrefix + staticPrefix + BootUiPathNormalizer.DEFAULT_PATH);
        }
        return List.copyOf(mounts);
    }

    /** Normalizes a configured path prefix to either {@code ""} or a value like {@code "/app"}. */
    private static String pathPrefix(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.startsWith("/") ? trimmed : "/" + trimmed;
    }

    /**
     * Returns the literal prefix of a resource pattern that can serve nested resources
     * ({@code /static/**} yields {@code /static}), or {@code null} when the pattern cannot reach
     * {@code bootui/**} at all.
     */
    private static String nestedPatternPrefix(String pattern) {
        if (pattern == null || !matchesNestedPaths(pattern)) {
            return null;
        }
        int wildcard = indexOfWildcard(pattern);
        return pathPrefix(pattern.substring(0, wildcard));
    }

    /** A pattern reaches nested resources only through {@code **} or a capture-all {@code {*name}}. */
    private static boolean matchesNestedPaths(String pattern) {
        return pattern.contains("**") || pattern.contains("{*");
    }

    private static int indexOfWildcard(String pattern) {
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' || c == '?' || c == '{') {
                return i;
            }
        }
        return pattern.length();
    }
}
