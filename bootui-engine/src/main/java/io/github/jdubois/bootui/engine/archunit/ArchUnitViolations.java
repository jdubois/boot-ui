package io.github.jdubois.bootui.engine.archunit;

import com.tngtech.archunit.lang.EvaluationResult;
import io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorViolation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pairs each line of an ArchUnit failure report with the location of the one code element that produced it.
 *
 * <p>The report text is kept exactly as ArchUnit sorts it. Locations come from the violating objects ArchUnit
 * hands to a {@code ViolationHandler} with each message, never from the text. When a message was produced by
 * several events with different locations and the counts cannot be matched one to one, that message keeps no
 * location rather than a guessed one.</p>
 */
public final class ArchUnitViolations {

    private ArchUnitViolations() {}

    /** The report's detail lines, in order, each with its location or {@code null}. */
    public static List<AdvisorViolation> of(EvaluationResult evaluation) {
        List<String> details = evaluation.getFailureReport().getDetails();
        Map<String, List<AdvisorViolationLocationDto>> byMessage = new HashMap<>();
        try {
            evaluation.handleViolations((Collection<Object> objects, String message) -> byMessage
                    .computeIfAbsent(message, ignored -> new ArrayList<>())
                    .add(ArchUnitLocations.of(objects)));
        } catch (RuntimeException | LinkageError ex) {
            byMessage.clear();
        }
        Map<String, Integer> occurrences = new HashMap<>();
        details.forEach(detail -> occurrences.merge(detail, 1, Integer::sum));
        Map<String, List<AdvisorViolationLocationDto>> assignable = new HashMap<>();
        byMessage.forEach((message, locations) -> {
            Integer count = occurrences.get(message);
            if (count == null) return;
            if (locations.size() == count) {
                // ArchUnit sorts the text stably, so equal lines keep the order their events were handled in.
                assignable.put(message, locations);
            } else if (locations.stream().distinct().count() == 1 && locations.get(0) != null) {
                // Every event behind this text points at the same element, so each copy of the text does too.
                assignable.put(message, java.util.Collections.nCopies(count, locations.get(0)));
            }
        });
        Map<String, Integer> next = new HashMap<>();
        List<AdvisorViolation> violations = new ArrayList<>(details.size());
        for (String detail : details) {
            List<AdvisorViolationLocationDto> locations = assignable.get(detail);
            int index = next.merge(detail, 1, Integer::sum) - 1;
            violations.add(new AdvisorViolation(
                    detail, locations == null || index >= locations.size() ? null : locations.get(index)));
        }
        return violations;
    }
}
