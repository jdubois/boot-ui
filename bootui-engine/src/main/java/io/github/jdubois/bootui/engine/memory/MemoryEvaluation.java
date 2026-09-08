package io.github.jdubois.bootui.engine.memory;

import io.github.jdubois.bootui.core.dto.MemoryRuleResultDto;

/** Internal outcome returned at the branch that consumes a rule's required observations. */
record MemoryEvaluation(MemoryRuleResultDto result, boolean usable, boolean requiredUnknown) {

    static MemoryEvaluation complete(MemoryRuleResultDto result) {
        return new MemoryEvaluation(result, true, false);
    }

    static MemoryEvaluation unknown(MemoryRuleResultDto result) {
        return new MemoryEvaluation(result, false, true);
    }

    static MemoryEvaluation inapplicable(MemoryRuleResultDto result) {
        return new MemoryEvaluation(result, false, false);
    }

    MemoryEvaluation withMissingEvidence() {
        return new MemoryEvaluation(result, "VIOLATION".equals(result.status()) && result.violationCount() > 0, true);
    }
}
