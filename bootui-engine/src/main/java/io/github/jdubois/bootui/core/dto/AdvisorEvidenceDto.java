package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Assessment evidence recorded before result filtering and dismissal.
 *
 * @param usable a completed applicable check or genuine finding, excluding missing-evidence notices
 */
public record AdvisorEvidenceDto(boolean usable, boolean coverageComplete, List<String> limitations) {
    public AdvisorEvidenceDto {
        limitations = limitations == null
                ? List.of()
                : limitations.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(value -> value.replaceAll("[\\p{Cntrl}]", " ").strip())
                        .filter(value -> !value.isEmpty())
                        .map(value -> value.substring(0, Math.min(value.length(), 240)))
                        .distinct()
                        .limit(20)
                        .toList();
    }

    public static AdvisorEvidenceDto unknown() {
        return new AdvisorEvidenceDto(false, false, List.of("Assessment evidence is unavailable."));
    }
}
