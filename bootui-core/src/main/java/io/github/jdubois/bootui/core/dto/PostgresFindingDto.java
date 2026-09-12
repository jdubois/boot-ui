package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One deterministic finding computed in Java from the collected statistics rows.
 *
 * <p>Findings are review prompts backed by the evidence that produced them, never verdicts, and never
 * generated text: the same rows always yield the same findings.</p>
 *
 * @param id the stable rule id, also the anchor in the published checks catalogue
 * @param dataSource the datasource the finding was computed for
 * @param sectionId the {@link PostgresSectionDto#id()} the finding came from
 * @param severity {@code CRITICAL}, {@code HIGH}, {@code MEDIUM}, {@code LOW} or {@code INFO}
 * @param evidence the measured values the rule fired on
 * @param caveat what this finding cannot prove — the replica caveat on unused indexes, above all
 */
public record PostgresFindingDto(
        String id,
        String dataSource,
        String sectionId,
        String title,
        String category,
        String severity,
        String description,
        String evidence,
        List<String> samples,
        String recommendation,
        String caveat,
        String learnMoreUrl) {

    public PostgresFindingDto {
        samples = DtoCollections.immutableCopy(samples);
    }
}
