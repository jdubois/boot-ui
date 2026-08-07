package io.github.jdubois.bootui.engine.reactivesecurity;

import io.github.jdubois.bootui.core.dto.SecurityRuleResultDto;
import java.util.List;

/**
 * Abstract base for reactive security rules, providing pass/skip/violation helpers that delegate to
 * {@link ReactiveSecuritySupport}.
 */
abstract class AbstractReactiveSecurityRule implements ReactiveSecurityRule {

    private final ReactiveSecurityRuleDefinition definition;

    AbstractReactiveSecurityRule(ReactiveSecurityRuleDefinition definition) {
        this.definition = definition;
    }

    @Override
    public final ReactiveSecurityRuleDefinition definition() {
        return definition;
    }

    abstract SecurityRuleResultDto evaluateRule(ReactiveSecurityContext context);

    @Override
    public final SecurityRuleResultDto evaluate(ReactiveSecurityContext context) {
        try {
            return evaluateRule(context);
        } catch (RuntimeException | LinkageError ex) {
            return ReactiveSecuritySupport.error(definition, "Rule could not be evaluated: " + ex.getMessage());
        }
    }

    SecurityRuleResultDto pass() {
        return ReactiveSecuritySupport.pass(definition);
    }

    SecurityRuleResultDto skipped(String reason) {
        return ReactiveSecuritySupport.skipped(definition, reason);
    }

    SecurityRuleResultDto violation(List<String> details) {
        return details.isEmpty() ? pass() : ReactiveSecuritySupport.violation(definition, details);
    }

    SecurityRuleResultDto violation(String severityOverride, List<String> details) {
        return details.isEmpty() ? pass() : ReactiveSecuritySupport.violation(definition, severityOverride, details);
    }

    SecurityRuleResultDto filterViolation(ReactiveSecurityContext context, List<String> details) {
        if (details.isEmpty() && context.chains().stream().anyMatch(chain -> !chain.filtersObserved())) {
            return skipped("Web filters could not be observed for every reactive security chain.");
        }
        return violation(details);
    }

    SecurityRuleResultDto corsViolation(ReactiveSecurityContext context, List<String> details) {
        if (details.isEmpty() && !context.corsObservationComplete()) {
            return skipped("Reactive CORS sources are present but could not all be inspected.");
        }
        return violation(details);
    }

    SecurityRuleResultDto headerViolation(ReactiveSecurityContext context, List<String> details) {
        if (details.isEmpty()
                && context.chains().stream()
                        .anyMatch(chain -> !chain.filtersObserved()
                                || (chain.hasHeaderWriterWebFilter() && !chain.headerWritersObserved()))) {
            return skipped("Header-writer details could not be fully observed.");
        }
        return violation(details);
    }
}
