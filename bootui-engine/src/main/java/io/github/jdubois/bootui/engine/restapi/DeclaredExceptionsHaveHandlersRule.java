package io.github.jdubois.bootui.engine.restapi;

import io.github.jdubois.bootui.core.dto.AdvisorViolationLocationDto;
import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorFindings;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ExceptionHandlerModel;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ThrownExceptionModel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * RAPI-ERR-009 — an endpoint declares an application exception for which no handler declaration was
 * found in the imported model. This is not scope- or precedence-aware runtime exception resolution.
 *
 * <p>The finding is evidence-backed on both sides: the exception type comes from a declared {@code throws}
 * clause, and coverage is decided against the declared {@code @ExceptionHandler}/{@code ExceptionMapper}
 * types, including supertypes, plus {@code @ResponseStatus}-annotated exceptions (which Spring maps without
 * a handler). It never claims a mapping is missing merely because no failure has been observed, and it
 * stays silent when the application declares no handlers at all — {@code RAPI-ERR-001} already reports
 * that.
 */
final class DeclaredExceptionsHaveHandlersRule extends AbstractRestApiRule {

    DeclaredExceptionsHaveHandlersRule() {
        super(new RestApiRuleDefinition(
                "RAPI-ERR-009",
                "Declared exceptions have a declared handler",
                RestApiCategory.ERROR_HANDLING,
                "MEDIUM",
                "An endpoint declares an application exception with no corresponding handler declaration found in"
                        + " the imported model. The global union does not establish native scope, precedence or actual"
                        + " runtime coverage, and framework defaults may be intentional. Unresolved mapper exception"
                        + " types make absence conclusions inapplicable.",
                "Review native handler/mapper coverage and the chosen error policy; add a declaration only if needed.",
                RestApiRuleHelp.PROBLEM_DETAIL_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (!context.evidence().completeExceptionModel()) {
            return missingEvidence(
                    context,
                    "Controller and exception metadata is incomplete; missing handler declarations cannot be"
                            + " inferred.");
        }
        if (context.exceptionHandlers().isEmpty() || context.thrownExceptions().isEmpty()) {
            return RestApiRuleSupport.pass(definition());
        }
        if (context.exceptionHandlers().stream()
                .anyMatch(handler -> handler.handledExceptionTypes().isEmpty())) {
            return missingEvidence(
                    context,
                    "A handler or mapper has unresolved handled-exception types; the imported inventory cannot"
                            + " establish that a declaration is missing.");
        }
        Set<String> mapped = new LinkedHashSet<>(context.responseStatusExceptionClasses());
        for (ExceptionHandlerModel handler : context.exceptionHandlers()) {
            mapped.addAll(handler.handledExceptionTypes());
        }
        // Texts are deduplicated as before. One text can stand for several endpoints (overloads, or controllers
        // sharing a simple name), so it keeps a location only when every occurrence is the same known element.
        // Locations alone cannot tell overloads apart, so the element identity decides.
        Map<String, AdvisorViolationLocationDto> reported = new LinkedHashMap<>();
        Map<String, String> identities = new HashMap<>();
        Set<String> spanning = new HashSet<>();
        for (ThrownExceptionModel thrown : context.targets(context.thrownExceptions())) {
            if (isMapped(thrown, mapped)) {
                continue;
            }
            String violation = thrown.controllerSimpleName() + "#" + thrown.methodName() + " declares "
                    + thrown.exceptionSimpleName()
                    + ", for which no handler declaration was found in the imported model";
            AdvisorViolationLocationDto location = context.location(thrown);
            String identity = context.elementIdentity(thrown);
            if (!reported.containsKey(violation)) {
                reported.put(violation, location);
                identities.put(violation, identity);
            } else if (identity == null
                    || !identity.equals(identities.get(violation))
                    || !Objects.equals(reported.get(violation), location)) {
                spanning.add(violation);
            }
        }
        AdvisorFindings violations = new AdvisorFindings();
        reported.forEach(
                (violation, location) -> violations.add(violation, spanning.contains(violation) ? null : location));
        return RestApiRuleSupport.fromViolations(context, definition(), violations);
    }

    /** A type is mapped when it, or any of its ancestors, is declared as handled. */
    private static boolean isMapped(ThrownExceptionModel thrown, Set<String> mapped) {
        if (mapped.contains(thrown.exceptionTypeName())) {
            return true;
        }
        for (String ancestor : thrown.exceptionSuperTypeNames()) {
            if (mapped.contains(ancestor)) {
                return true;
            }
        }
        return false;
    }
}
