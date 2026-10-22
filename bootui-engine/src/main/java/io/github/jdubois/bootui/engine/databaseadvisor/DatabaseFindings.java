package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.advisor.AdvisorViolation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Ordered, rule-local findings of a Database Advisor rule. Every finding must carry its canonical identity
 * ({@link DatabaseIdentities}), so a rule cannot report one without deciding what it is a finding about.
 */
final class DatabaseFindings {

    private final List<AdvisorViolation> violations = new ArrayList<>();

    void add(String text, String identity) {
        violations.add(AdvisorViolation.of(text, Objects.requireNonNull(identity, "Finding identity is required.")));
    }

    boolean isEmpty() {
        return violations.isEmpty();
    }

    int size() {
        return violations.size();
    }

    List<AdvisorViolation> list() {
        return List.copyOf(violations);
    }
}
