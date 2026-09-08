package io.github.jdubois.bootui.engine.security;

import io.github.jdubois.bootui.core.dto.AdvisorEvidenceDto;
import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/** Evidence for one scan, populated in the evaluator rather than reconstructed from rule IDs. */
public final class SecurityEvaluation {
    private boolean usable;
    private boolean observed;
    private boolean missing;
    private final List<String> limitations = new ArrayList<>();

    public void reset() {
        usable = false;
        limitations.clear();
        begin();
    }

    public void begin() {
        observed = false;
        missing = false;
    }

    public boolean applies(boolean value) {
        observed |= value;
        return value;
    }

    public boolean hasApplicableTargets() {
        return observed;
    }

    public boolean required(boolean known) {
        missing |= !known;
        return known;
    }

    public void finish(SecurityRuleResultDto result) {
        boolean finding = "VIOLATION".equals(result.status()) && result.violationCount() > 0;
        usable |= finding;
        if (missing || "SKIPPED".equals(result.status()) || "ERROR".equals(result.status())) {
            String reason = ("SKIPPED".equals(result.status()) || "ERROR".equals(result.status()))
                            && !result.sampleViolations().isEmpty()
                    ? result.sampleViolations().get(0)
                    : result.name() + ": required observations are incomplete or unavailable.";
            limitations.add(result.id() + ": " + reason);
        } else if (observed && "PASS".equals(result.status())) {
            usable = true;
        }
    }

    public AdvisorEvidenceDto evidence(List<String> collectionLimitations) {
        List<String> all = new ArrayList<>(collectionLimitations);
        all.addAll(limitations);
        return new AdvisorEvidenceDto(usable, all.isEmpty(), all);
    }
}
