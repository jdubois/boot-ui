package io.github.jdubois.bootui.engine.support;

import java.util.List;

/**
 * Framework-neutral matcher for "is this fully-qualified name owned by BootUI itself?", used to keep
 * BootUI's own loggers (and similar internals) out of the panels it serves.
 *
 * <p>The set of internal package prefixes is adapter input: the Spring adapter feeds
 * {@code io.github.jdubois.bootui.autoconfigure} + {@code io.github.jdubois.bootui.core}; the Quarkus
 * adapter feeds {@code io.github.jdubois.bootui.quarkus} + {@code io.github.jdubois.bootui.core}. Both
 * deliberately leave the shared {@code engine}/{@code spi} packages visible. Sharing this one matcher
 * keeps the two adapters from drifting on the exact boundary semantics.</p>
 */
public final class InternalPackageMatcher {

    /**
     * BootUI's own packages, whose loggers BootUI never records as the application's: not the sample applications',
     * which live under {@code io.github.jdubois.bootui} too and are application code.
     */
    public static final InternalPackageMatcher BOOTUI = new InternalPackageMatcher(List.of(
            "io.github.jdubois.bootui.autoconfigure",
            "io.github.jdubois.bootui.engine",
            "io.github.jdubois.bootui.core",
            "io.github.jdubois.bootui.spi",
            "io.github.jdubois.bootui.agent",
            "io.github.jdubois.bootui.cli",
            "io.github.jdubois.bootui.client",
            "io.github.jdubois.bootui.quarkus",
            "io.github.jdubois.bootui.conformance"));

    private final List<String> packages;

    public InternalPackageMatcher(List<String> packages) {
        this.packages = List.copyOf(packages);
    }

    /**
     * Whether {@code value} is one of the configured packages or a type/logger nested under it. Nested
     * class separators ({@code $}) are normalized to dots, and matching uses dotted-prefix boundaries so
     * {@code ...core} never matches a sibling like {@code ...coreextra}.
     */
    public boolean matchesName(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.replace('$', '.');
        for (String packageName : packages) {
            if (normalized.equals(packageName) || normalized.startsWith(packageName + ".")) {
                return true;
            }
        }
        return false;
    }
}
