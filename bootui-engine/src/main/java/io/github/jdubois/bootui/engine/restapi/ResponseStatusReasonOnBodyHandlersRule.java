package io.github.jdubois.bootui.engine.restapi;

import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorFindings;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ExceptionHandlerModel;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.HandlerMethodModel;

/**
 * RAPI-RESP-010: a Spring MVC REST handler or exception handler returns a body while its effective
 * {@code @ResponseStatus} sets a {@code reason}. {@code ServletInvocableHandlerMethod} then calls
 * {@code HttpServletResponse.sendError(status, reason)} and returns before return-value handling, so the returned
 * body (even a {@code ResponseEntity}) is discarded and the container or Boot error response is written instead.
 * WebFlux only applies the status and still writes the body, so the rule is not evaluated there.
 */
final class ResponseStatusReasonOnBodyHandlersRule extends AbstractRestApiRule {

    ResponseStatusReasonOnBodyHandlersRule() {
        super(new RestApiRuleDefinition(
                "RAPI-RESP-010",
                "Do not set @ResponseStatus reason on body-returning REST handlers",
                RestApiCategory.RESPONSES,
                "MEDIUM",
                "On Spring MVC a non-blank @ResponseStatus reason makes Spring call HttpServletResponse.sendError and"
                        + " skip return-value handling: the handler's returned body, even a ResponseEntity, is"
                        + " discarded and an error response is rendered instead. Spring documents reason as unsuitable"
                        + " for REST APIs.",
                "Remove the reason attribute. Return a ResponseEntity, or a ProblemDetail whose detail carries the"
                        + " message, to control both status and body.",
                RestApiRuleHelp.RESPONSE_STATUS_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (context.jaxRs()) {
            return RestApiRuleSupport.skipped(
                    definition(), "Not applicable on JAX-RS: this rule checks Spring's @ResponseStatus reason.");
        }
        switch (context.evidence().springWebStack()) {
            case REACTIVE -> {
                return RestApiRuleSupport.skipped(
                        definition(),
                        "Not applicable on Spring WebFlux: it applies the @ResponseStatus code, ignores the reason,"
                                + " and still writes the returned body.");
            }
            case UNKNOWN -> {
                return missingEvidence(
                        context,
                        "The Spring request stack is unknown; only Spring MVC discards the body of a handler with a"
                                + " @ResponseStatus reason.");
            }
            default -> {
                // Spring MVC: evaluated below.
            }
        }
        AdvisorFindings violations = new AdvisorFindings();
        for (HandlerMethodModel handler :
                context.targets(context.handlers(), ResponseStatusReasonOnBodyHandlersRule::returnsBody)) {
            if (!handler.responseStatusReason().isEmpty()) {
                violations.add(
                        handler.describe() + " — @ResponseStatus reason \"" + handler.responseStatusReason()
                                + "\" discards the returned body",
                        context.location(handler));
            }
        }
        for (ExceptionHandlerModel handler :
                context.targets(context.exceptionHandlers(), ResponseStatusReasonOnBodyHandlersRule::returnsBody)) {
            if (!handler.responseStatusReason().isEmpty()) {
                violations.add(
                        RestApiRuleHelp.simpleName(handler.declaringClassName()) + "#" + handler.methodName()
                                + " — @ResponseStatus reason \"" + handler.responseStatusReason()
                                + "\" discards the returned error body",
                        context.location(handler));
            }
        }
        return RestApiRuleSupport.fromViolations(context, definition(), violations);
    }

    static boolean returnsBody(HandlerMethodModel handler) {
        return !handler.jaxRs() && handler.serializesBody() && !handler.returnsVoid() && !handler.hasResponseParam();
    }

    private static boolean returnsBody(ExceptionHandlerModel handler) {
        return !handler.jaxRs() && handler.rendersBody() && !handler.returnsVoid() && !handler.hasResponseParam();
    }
}
