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
