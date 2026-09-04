package io.github.jdubois.bootui.engine.support;

import java.util.Locale;

/**
 * Relaxed-binding-aware matching for configuration property names, shared by every BootUI adapter.
 *
 * <p>Property sources enumerate their names verbatim: Spring's {@code SystemEnvironmentPropertySource} and
 * Quarkus/MicroProfile both report an environment variable as {@code BOOTUI_MCP_ENABLED}, because relaxed
 * binding only applies when a name is <em>looked up</em>, never when the source is enumerated. A literal
 * substring search therefore cannot find {@code bootui.mcp.enabled} in an env-only property.
 *
 * <p>{@link #canonicalize(String)} maps both sides of a name comparison onto one form — lower case, with
 * {@code _} and {@code -} treated as {@code .} — so the dotted, kebab-case and {@code UPPER_SNAKE_CASE}
 * spellings of the same property all compare equal. The mapping is per character and length preserving, so
 * canonical containment is a superset of literal containment: every query that matched a name before still
 * matches it. Only names are canonicalized; values, descriptions and defaults keep literal matching.
 */
public final class RelaxedNames {

    private RelaxedNames() {}

    /**
     * The canonical form used to compare property names: lower case, with {@code _} and {@code -} normalized
     * to {@code .}. Returns {@code null} for a {@code null} input.
     */
    public static String canonicalize(String value) {
        if (value == null) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT).replace('_', '.').replace('-', '.');
    }

    /**
     * Whether {@code name} contains {@code canonicalQuery} once both are canonicalized. An empty query
     * matches everything, mirroring {@link PagedList#contains(String, String)}.
     *
     * @param name the literal property name, as reported by the property source
     * @param canonicalQuery a query already run through {@link #canonicalize(String)}
     */
    public static boolean contains(String name, String canonicalQuery) {
        if (canonicalQuery == null || canonicalQuery.isEmpty()) {
            return true;
        }
        String canonicalName = canonicalize(name);
        return canonicalName != null && canonicalName.contains(canonicalQuery);
    }
}
