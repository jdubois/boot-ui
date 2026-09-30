package io.github.jdubois.bootui.engine.advisor;

import io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto;
import java.util.ArrayList;
import java.util.List;

/**
 * Ordered, rule-local builder of findings. A rule adds each finding's text, together with its location when
 * the evidence names one code element, and hands the whole list to its advisor's result support.
 */
public final class AdvisorFindings {

    private final List<AdvisorViolation> violations = new ArrayList<>();

    public void add(String text) {
        violations.add(AdvisorViolation.of(text));
    }

    public void add(String text, AdvisorViolationLocationDto location) {
        violations.add(new AdvisorViolation(text, location));
    }

    public boolean isEmpty() {
        return violations.isEmpty();
    }

    public int size() {
        return violations.size();
    }

    /** The findings in insertion order. */
    public List<AdvisorViolation> list() {
        return List.copyOf(violations);
    }
}
