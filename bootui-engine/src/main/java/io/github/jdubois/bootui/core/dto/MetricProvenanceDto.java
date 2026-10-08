package io.github.jdubois.bootui.core.dto;

/**
 * Evidence-backed provenance and explanation for one Micrometer meter family.
 *
 * <p>{@code explanationSource} marks both where the explanation came from and how confident BootUI is
 * about it: {@code NATIVE} (the registry's own meter description), {@code CURATED} (BootUI's versioned
 * meter-family catalogue) or {@code UNKNOWN} (no description is available and BootUI does not invent
 * one). {@code classified} is {@code false} when no catalogue family matched the meter name, in which
 * case the meter stays in the explicit application/unclassified group instead of receiving a guessed
 * contributor.</p>
 */
public record MetricProvenanceDto(
        String groupId,
        String groupLabel,
        String contributor,
        String familyId,
        String familyLabel,
        boolean classified,
        String explanation,
        String explanationSource,
        String interpretation) {}
