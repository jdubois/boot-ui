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

    boolean usesChainEvidence() {
        return switch (definition.category()) {
            case AUTHORIZATION, CSRF, HEADERS, SESSION -> true;
            default -> false;
        };
    }

    @Override
    public final SecurityRuleResultDto evaluate(ReactiveSecurityContext context) {
        context.evaluation().begin();
        SecurityRuleResultDto result = evaluateObserved(context);
        context.evaluation().finish(result);
        return result;
    }

    private SecurityRuleResultDto evaluateObserved(ReactiveSecurityContext context) {
        try {
            String failure = context.environment().analysisFailures().get(definition.id());
            if (failure != null) {
                return ReactiveSecuritySupport.error(definition, "Configuration observation failed: " + failure);
            }
            SecurityRuleResultDto result = evaluateRule(context);
            if (usesChainEvidence()) {
                for (WebFilterChainObservation chain : context.chains()) {
                    context.required(chain.filtersObserved() && chain.analysisFailure() == null);
                    if (!ReactiveSecuritySupport.VIOLATION.equals(result.status()) && chain.analysisFailure() != null) {
                        return ReactiveSecuritySupport.error(
                                definition, "Chain metadata observation failed: " + chain.analysisFailure());
                    }
                }
            }
            if (definition.category() == ReactiveSecurityCategory.ACTUATOR) {
                context.required(context.environment().actuatorObservationComplete());
            }
            if (!context.required(!context.environment().incompleteRules().contains(definition.id()))) {
                if (!ReactiveSecuritySupport.VIOLATION.equals(result.status())) {
                    return skipped("Configuration provenance or inventory is incomplete.");
                }
            }
            return result;
        } catch (RuntimeException | LinkageError ex) {
            return ReactiveSecuritySupport.error(
                    definition, "Rule could not be evaluated: " + ex.getClass().getName());
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
        boolean complete = context.required(context.chains().stream()
                .allMatch(chain -> chain.filtersObserved() && chain.analysisFailure() == null));
        if (details.isEmpty() && !complete) {
            for (WebFilterChainObservation chain : context.chains()) {
                if (chain.analysisFailure() != null) {
                    return ReactiveSecuritySupport.error(
                            definition, "Chain metadata observation failed: " + chain.analysisFailure());
                }
            }
            return skipped("Web filters could not be observed for every reactive security chain.");
        }
        return violation(details);
    }

    SecurityRuleResultDto corsViolation(ReactiveSecurityContext context, List<String> details) {
        context.applies(!context.corsConfigs().isEmpty());
        boolean complete = context.required(context.corsObservationComplete());
        if (details.isEmpty() && !complete) {
            return skipped("Reactive CORS sources are present but could not all be inspected.");
        }
        return violation(details);
    }

    SecurityRuleResultDto headerViolation(ReactiveSecurityContext context, List<String> details) {
        boolean incomplete = !context.required(!context.chains().stream()
                .anyMatch(chain -> !chain.filtersObserved()
                        || (chain.hasHeaderWriterWebFilter()
                                && (!chain.headerWritersObserved() || !chain.cspObserved()))));
        if (details.isEmpty() && incomplete) {
            return skipped("Header-writer details could not be fully observed.");
        }
        return violation(details);
    }
}
