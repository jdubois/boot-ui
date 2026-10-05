package io.github.jdubois.bootui.autoconfigure.spring;

import io.github.jdubois.bootui.core.dto.AdvisorEvidenceDto;
import io.github.jdubois.bootui.core.dto.SpringRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/** Scan-local accounting; only rules consuming an applicable observation can complete a check. */
final class SpringEvaluation {
    private boolean usable;
    private boolean observed;
    private boolean missing;
    private String missingReason;
    private final List<String> limitations = new ArrayList<>();

    void reset() {
        usable = false;
        limitations.clear();
        begin();
    }

    void begin() {
        observed = false;
        missing = false;
        missingReason = null;
    }

    boolean applies(boolean value) {
        observed |= value;
        return value;
    }

    void unknown() {
        unknown("Required non-eager Spring metadata is unavailable or custom; observation is unknown.");
    }

    void unknown(String reason) {
        missing = true;
        missingReason = SpringRuleSupport.detail(reason);
    }

    void finish(SpringRuleResultDto result) {
        boolean finding = SpringRuleSupport.VIOLATION.equals(result.status()) && result.violationCount() > 0;
        usable |= finding;
        if (SpringRuleSupport.ERROR.equals(result.status())) {
            limitations.add(result.id() + ": Spring rule evaluation failed; no runtime conclusion was made.");
        } else if (missing) {
            limitations.add(result.id() + ": " + missingReason);
        } else if (observed && SpringRuleSupport.PASS.equals(result.status())) {
            usable = true;
        }
    }

    AdvisorEvidenceDto evidence(List<String> collectionLimitations) {
        List<String> all = new ArrayList<>(collectionLimitations);
        all.addAll(limitations);
        return new AdvisorEvidenceDto(usable, all.isEmpty(), all);
    }
}
