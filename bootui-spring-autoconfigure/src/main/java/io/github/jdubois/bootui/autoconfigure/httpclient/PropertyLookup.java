package io.github.jdubois.bootui.autoconfigure.httpclient;

import java.util.Locale;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

/**
 * Reads a configuration property twice: exactly as written, and after placeholder resolution.
 *
 * <p>{@link Environment#getProperty(String)} already interpolates {@code ${...}} placeholders, which hides
 * precisely the state the HTTP Clients panel must report honestly — a base URL whose placeholder never
 * resolved. This helper therefore walks the property sources itself for the raw text, then resolves it
 * non-strictly, so an unresolved placeholder survives into the report instead of throwing or silently
 * looking configured.</p>
 *
 * <p>Only names are used as lookup keys; nothing here logs, mutates or exposes a value.</p>
 */
final class PropertyLookup {

    private final Environment environment;

    PropertyLookup(Environment environment) {
        this.environment = environment;
    }

    /**
     * The first resolvable form of {@code key}, trying the plain key and Spring's indexed map form, or
     * {@code null} when none of them is set.
     */
    Value find(String prefix, String name, String suffix) {
        Value plain = get(prefix + "." + name + "." + suffix);
        if (plain != null) {
            return plain;
        }
        // Map-bound groups accept the indexed form, which is also the only safe form for a name that
        // itself contains dots (a fully-qualified interface name used as a configuration key).
        return get(prefix + "[\"" + name + "\"]." + suffix);
    }

    /** The property under an exact key, or {@code null} when it is not set. */
    Value get(String key) {
        if (environment == null || key == null) {
            return null;
        }
        String raw = rawValue(key);
        if (raw == null) {
            String resolved = environment.getProperty(key);
            return resolved == null || resolved.isBlank() ? null : new Value(key, resolved, resolved);
        }
        return new Value(key, raw, resolve(raw));
    }

    /**
     * The value exactly as a property source holds it. Every source is consulted, including non-enumerable
     * ones such as Spring Boot's relaxed-binding {@code configurationProperties} source, so a raw lookup
     * still finds a key written in another valid form and an unresolved placeholder is never lost.
     */
    private String rawValue(String key) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return null;
        }
        for (PropertySource<?> source : configurable.getPropertySources()) {
            Object value;
            try {
                value = source.getProperty(key);
            } catch (RuntimeException ex) {
                // A property source that cannot answer for this key must not break the whole panel.
                continue;
            }
            if (value != null) {
                String text = String.valueOf(value);
                return text.isBlank() ? null : text;
            }
        }
        return null;
    }

    /** Resolves placeholders in a literal value that did not come from a property key. */
    String resolveText(String raw) {
        return environment == null ? raw : resolve(raw);
    }

    private String resolve(String raw) {
        try {
            String resolved = environment.resolvePlaceholders(raw);
            return resolved == null || resolved.isBlank() ? raw : resolved;
        } catch (RuntimeException ex) {
            return raw;
        }
    }

    /** Normalizes a duration-ish property value for display without inventing a unit it does not have. */
    static String describeDuration(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("PT") || trimmed.startsWith("pt")) {
            return trimmed.toUpperCase(Locale.ROOT);
        }
        return trimmed.matches("-?\\d+") ? trimmed + "ms" : trimmed;
    }

    /**
     * One configuration value: the key it came from, the text exactly as written, and the text after
     * placeholder resolution.
     */
    record Value(String key, String raw, String resolved) {}
}
