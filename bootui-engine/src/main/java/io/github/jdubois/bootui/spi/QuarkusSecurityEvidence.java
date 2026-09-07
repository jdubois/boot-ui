package io.github.jdubois.bootui.spi;

import java.util.List;
import java.util.Set;

/** Internal observation completeness; never serialized as part of the public security report. */
public record QuarkusSecurityEvidence(
        Set<String> unknownRules,
        List<String> incomplete,
        List<String> failures,
        List<QuarkusSecurityEndpoint> endpoints,
        boolean endpointMetadata) {
    public static final QuarkusSecurityEvidence LEGACY =
            new QuarkusSecurityEvidence(Set.of(), List.of(), List.of(), List.of(), false);

    public QuarkusSecurityEvidence {
        unknownRules = Set.copyOf(unknownRules);
        incomplete = List.copyOf(incomplete);
        failures = List.copyOf(failures);
        endpoints = List.copyOf(endpoints);
    }
}
