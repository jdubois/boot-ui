package io.github.jdubois.bootui.core.dto;

import java.util.List;
import java.util.Objects;

/**
 * What changed in the retained findings since the previous comparable scan. Present only for advisors that track
 * finding identity, and {@code null} on the first scan.
 *
 * <p>A finding is only called new or resolved when both scans concluded its rule and retained all of its findings;
 * otherwise it is counted as {@code unverifiedCount} instead, never as resolved.</p>
 *
 * @param previousScanId the scan this one was compared with
 * @param complete {@code false} when either scan truncated its retained details, contains unkeyed findings, or
 *     left some finding's movement unverified; the reason is in {@code notes}
 * @param newCount keyed findings reported now and not before
 * @param resolvedCount keyed findings reported before and not now
 * @param unchangedCount keyed findings reported in both scans
 * @param unkeyedCount findings of rules without an identity (counted, never compared)
 * @param resolved at most {@value #MAX_RESOLVED} resolved findings, with their text from the previous scan
 * @param notes why the comparison is incomplete, bounded
 * @param unverifiedCount keyed findings that are neither confirmed new nor confirmed resolved
 * @param rules the rules with a new, resolved or unverified finding, bounded
 */
public record AdvisorChangesDto(
        String previousScanId,
        boolean complete,
        int newCount,
        int resolvedCount,
        int unchangedCount,
        int unkeyedCount,
        List<AdvisorResolvedFindingDto> resolved,
        List<String> notes,
        int unverifiedCount,
        List<AdvisorRuleChangeDto> rules) {

    public static final int MAX_RESOLVED = 50;
    public static final int MAX_RULES = 100;
    private static final int MAX_NOTES = 10;
    private static final int MAX_NOTE_LENGTH = 240;

    public AdvisorChangesDto {
        resolved = bounded(resolved, MAX_RESOLVED);
        notes = notes == null
                ? List.of()
                : notes.stream()
                        .filter(Objects::nonNull)
                        .map(value -> value.replaceAll("[\\p{Cntrl}]", " ").strip())
                        .filter(value -> !value.isEmpty())
                        .map(value -> value.substring(0, Math.min(value.length(), MAX_NOTE_LENGTH)))
                        .distinct()
                        .limit(MAX_NOTES)
                        .toList();
        rules = bounded(rules, MAX_RULES);
    }

    private static <T> List<T> bounded(List<T> values, int limit) {
        List<T> copy = DtoCollections.immutableCopy(values);
        return copy.size() <= limit ? copy : DtoCollections.immutableCopy(copy.subList(0, limit));
    }

    public AdvisorChangesDto(
            String previousScanId,
            boolean complete,
            int newCount,
            int resolvedCount,
            int unchangedCount,
            int unkeyedCount,
            List<AdvisorResolvedFindingDto> resolved,
            List<String> notes) {
        this(previousScanId, complete, newCount, resolvedCount, unchangedCount, unkeyedCount, resolved, notes, 0, List.of());
    }
}
