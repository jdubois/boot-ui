package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Retrieval completeness for the concrete findings in one completed advisor scan.
 * This is independent of assessment evidence, coverage, and dismissal.
 *
 * @param locationNotes why some violation locations carry no source path, recorded during the scan; empty when
 *     every located class resolved or the advisor records no locations
 */
public record AdvisorViolationDetailsDto(
        String scanId, int total, int retained, int retentionLimit, boolean truncated, List<String> locationNotes) {

    private static final int MAX_LOCATION_NOTES = 10;
    private static final int MAX_LOCATION_NOTE_LENGTH = 240;

    public AdvisorViolationDetailsDto {
        locationNotes = locationNotes == null
                ? List.of()
                : locationNotes.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(value -> value.replaceAll("[\\p{Cntrl}]", " ").strip())
                        .filter(value -> !value.isEmpty())
                        .map(value -> value.substring(0, Math.min(value.length(), MAX_LOCATION_NOTE_LENGTH)))
                        .distinct()
                        .limit(MAX_LOCATION_NOTES)
                        .toList();
    }

    public AdvisorViolationDetailsDto(String scanId, int total, int retained, int retentionLimit, boolean truncated) {
        this(scanId, total, retained, retentionLimit, truncated, List.of());
    }

    public static AdvisorViolationDetailsDto unknown() {
        return new AdvisorViolationDetailsDto(null, 0, 0, 0, false);
    }
}
