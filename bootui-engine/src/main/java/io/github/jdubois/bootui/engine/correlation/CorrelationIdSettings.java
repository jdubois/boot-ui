package io.github.jdubois.bootui.engine.correlation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Immutable, already-validated set of inbound header names BootUI reads correlation identifiers from.
 *
 * <p>Built by an adapter once from its own configuration ({@code bootui.http-exchanges.correlation-id-headers}
 * on both Spring and Quarkus) and then shared by the framework-neutral extraction, so all three stacks
 * resolve the same names in the same order. Configured names that are invalid, over-long, reserved, or
 * beyond the bound on additional names are not silently dropped: they are kept in
 * {@link #rejectedHeaderNames()} so an adapter can report them once at startup.</p>
 *
 * @param headerNames the accepted, normalized (lower-case) header names in canonical order — the built-in
 *     names first, then accepted configured names in configuration order
 * @param rejectedHeaderNames configured names that were refused, in configuration order and in their
 *     original spelling, so the report names exactly what the developer wrote
 */
public record CorrelationIdSettings(List<String> headerNames, List<String> rejectedHeaderNames) {

    private static final CorrelationIdSettings DEFAULTS =
            new CorrelationIdSettings(CorrelationIdPolicy.BUILT_IN_HEADER_NAMES, List.of());

    public CorrelationIdSettings {
        headerNames = headerNames == null ? List.of() : List.copyOf(headerNames);
        rejectedHeaderNames = rejectedHeaderNames == null ? List.of() : List.copyOf(rejectedHeaderNames);
    }

    /** The built-in header names only — what every adapter uses when nothing is configured. */
    public static CorrelationIdSettings defaults() {
        return DEFAULTS;
    }

    /**
     * Validates configured additional header names against {@link CorrelationIdPolicy} and combines the
     * accepted ones with the built-in names.
     *
     * <p>A configured name is rejected when it is not a valid HTTP field name, exceeds
     * {@link CorrelationIdPolicy#MAX_HEADER_NAME_LENGTH}, names credential-bearing material, or arrives
     * after {@link CorrelationIdPolicy#MAX_ADDITIONAL_HEADER_NAMES} names have already been accepted.
     * Duplicates (including duplicates of a built-in name, in any casing) are folded away without being
     * reported as rejections, because the resulting behavior is exactly what was asked for.</p>
     *
     * @param additionalHeaderNames configured names, or {@code null}/empty for the built-ins only
     */
    public static CorrelationIdSettings of(Collection<String> additionalHeaderNames) {
        if (additionalHeaderNames == null || additionalHeaderNames.isEmpty()) {
            return defaults();
        }
        LinkedHashSet<String> accepted = new LinkedHashSet<>(CorrelationIdPolicy.BUILT_IN_HEADER_NAMES);
        List<String> rejected = new ArrayList<>();
        int added = 0;
        for (String candidate : additionalHeaderNames) {
            String normalized = CorrelationIdPolicy.normalizeHeaderName(candidate);
            if (normalized == null || CorrelationIdPolicy.isReserved(normalized)) {
                rejected.add(candidate == null ? "" : candidate.trim());
                continue;
            }
            if (accepted.contains(normalized)) {
                continue;
            }
            if (added >= CorrelationIdPolicy.MAX_ADDITIONAL_HEADER_NAMES) {
                rejected.add(candidate.trim());
                continue;
            }
            accepted.add(normalized);
            added++;
        }
        return new CorrelationIdSettings(List.copyOf(accepted), rejected);
    }

    /** Convenience for tests and simple wiring. */
    public static CorrelationIdSettings of(String... additionalHeaderNames) {
        return additionalHeaderNames == null ? defaults() : of(List.of(additionalHeaderNames));
    }
}
