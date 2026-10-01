package io.github.jdubois.bootui.engine.restapi;

import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorFindings;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ControllerModel;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.HandlerMethodModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RAPI-VER-007: a Spring GET/HEAD/DELETE handler that reads no request body still carries a consumes condition that
 * a request without a body cannot satisfy. Spring's {@code ConsumesRequestCondition} only waives its check for
 * bodiless requests when a {@code @RequestBody(required = false)} parameter says so; otherwise a missing
 * {@code Content-Type} is matched as {@code application/octet-stream}, so ordinary GET requests receive 415 on both
 * Spring MVC and WebFlux. The usual cause is a class-level {@code consumes} meant for the write methods, which is
 * reported once per controller.
 */
final class BodylessHandlersDoNotRequireContentTypeRule extends AbstractRestApiRule {

    private static final Set<String> BODYLESS_METHODS = Set.of("GET", "HEAD", "DELETE");
    private static final Set<String> OCTET_STREAM_RANGES =
            Set.of("*", "*/*", "application/*", "application/octet-stream");

    BodylessHandlersDoNotRequireContentTypeRule() {
        super(new RestApiRuleDefinition(
                "RAPI-VER-007",
                "Bodyless handlers must not require a Content-Type",
                RestApiCategory.VERSIONING,
                "HIGH",
                "A Spring GET, HEAD or DELETE handler that binds no request body declares (or inherits from its class)"
                        + " a consumes condition. Without a @RequestBody(required = false) parameter Spring still"
                        + " matches consumes against requests that carry no body, treating a missing Content-Type as"
                        + " application/octet-stream, so ordinary requests are rejected with 415 Unsupported Media"
                        + " Type.",
                "Declare consumes on the methods that read a body (POST/PUT/PATCH) instead of at class level, or remove"
                        + " it from GET/HEAD/DELETE handlers.",
                RestApiRuleHelp.CONSUMES_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (context.jaxRs()) {
            return RestApiRuleSupport.skipped(
                    definition(),
                    "Not applicable on JAX-RS: this rule checks Spring's consumes matching for requests without a"
                            + " body; Jakarta REST selects resource methods with a different algorithm.");
        }
        List<HandlerMethodModel> spring =
                context.handlers().stream().filter(handler -> !handler.jaxRs()).toList();
        Map<String, List<HandlerMethodModel>> fromClassLevel = new LinkedHashMap<>();
        AdvisorFindings violations = new AdvisorFindings();
        for (HandlerMethodModel handler :
                context.targets(spring, BodylessHandlersDoNotRequireContentTypeRule::acceptsBodylessMethod)) {
            if (!rejectsRequestsWithoutContentType(handler) || hasContentTypeAlternative(handler, spring)) {
                continue;
            }
            if (handler.consumesFromType()) {
                fromClassLevel
                        .computeIfAbsent(handler.controllerClassName(), ignored -> new ArrayList<>())
                        .add(handler);
            } else {
                violations.add(
                        handler.describe() + " — consumes " + handler.effectiveConsumes()
                                + " without a request body; requests without Content-Type get 415",
                        context.location(handler));
            }
        }
        for (Map.Entry<String, List<HandlerMethodModel>> entry : fromClassLevel.entrySet()) {
            List<HandlerMethodModel> handlers = entry.getValue();
            HandlerMethodModel first = handlers.get(0);
            Object location = context.controllers().stream()
                    .filter(controller -> controller.className().equals(entry.getKey()))
                    .findFirst()
                    .map(ControllerModel.class::cast)
                    .orElse(null);
            violations.add(
                    first.controllerSimpleName() + " — class-level consumes " + first.effectiveConsumes()
                            + " applies to " + handlers.size() + " bodyless handler(s) that reject requests without"
                            + " Content-Type with 415: "
                            + String.join(
                                    ", ",
                                    handlers.stream()
                                            .map(HandlerMethodModel::describe)
                                            .toList()),
                    location == null ? context.location(first) : context.location(location));
        }
        return RestApiRuleSupport.fromViolations(context, definition(), violations);
    }

    private static boolean acceptsBodylessMethod(HandlerMethodModel handler) {
        return handler.httpMethods().stream().anyMatch(BODYLESS_METHODS::contains);
    }

    private static boolean rejectsRequestsWithoutContentType(HandlerMethodModel handler) {
        List<String> consumes = handler.effectiveConsumes();
        if (consumes.isEmpty()
                || handler.requestBodyCount() > 0
                || handler.hasBodyAccessParam()
                || consumes.stream().anyMatch(media -> media.contains("${") || media.contains("#{"))) {
            return false;
        }
        return consumes.stream().noneMatch(BodylessHandlersDoNotRequireContentTypeRule::matchesOctetStream);
    }

    /** Mirrors Spring's {@code ConsumeMediaTypeExpression.match} for a request without Content-Type. */
    private static boolean matchesOctetStream(String expression) {
        String trimmed = expression.trim();
        boolean negated = trimmed.startsWith("!");
        String media = RestApiRuleHelp.normalizeMediaType(negated ? trimmed.substring(1) : trimmed);
        return negated != OCTET_STREAM_RANGES.contains(media);
    }

    /**
     * Content-type based dispatch: another handler for the same path and bodyless method with a different consumes
     * condition (or none) serves the requests this handler rejects, so the 415 is not reachable.
     */
    private static boolean hasContentTypeAlternative(HandlerMethodModel handler, List<HandlerMethodModel> all) {
        for (HandlerMethodModel other : all) {
            if (other == handler || other.effectiveConsumes().equals(handler.effectiveConsumes())) {
                continue;
            }
            boolean sharedMethod = other.httpMethods().isEmpty()
                    || other.httpMethods().stream()
                            .anyMatch(method -> BODYLESS_METHODS.contains(method)
                                    && handler.httpMethods().contains(method));
            boolean sharedPath = other.effectivePaths().stream().anyMatch(handler.effectivePaths()::contains);
            if (sharedMethod && sharedPath) {
                return true;
            }
        }
        return false;
    }
}
