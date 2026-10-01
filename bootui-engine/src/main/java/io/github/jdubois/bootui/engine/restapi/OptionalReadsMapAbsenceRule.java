package io.github.jdubois.bootui.engine.restapi;

import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;

/**
 * RAPI-RESP-011: a Spring GET handler returns {@code java.util.Optional} as its body. Spring has no return-value
 * handling that turns an empty Optional into 404: the client receives 200 with an empty or {@code null} body.
 * {@code ResponseEntity.of(Optional)} exists for exactly this mapping. JAX-RS is not evaluated.
 */
final class OptionalReadsMapAbsenceRule extends AbstractRestApiRule {

    OptionalReadsMapAbsenceRule() {
        super(new RestApiRuleDefinition(
                "RAPI-RESP-011",
                "Map an empty Optional read to an explicit status",
                RestApiCategory.RESPONSES,
                "LOW",
                "A Spring GET handler returns Optional<T> as its body. Spring does not translate an empty Optional into"
                        + " 404 Not Found: the client receives 200 with an empty or JSON null body.",
                "Return ResponseEntity.of(optional) or throw a not-found error so absence maps to 404, or document"
                        + " that the API answers 200 with null on purpose.",
                RestApiRuleHelp.RESPONSE_ENTITY_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (context.jaxRs()) {
            return RestApiRuleSupport.skipped(
                    definition(),
                    "Not applicable on JAX-RS: this rule checks Spring's handling of Optional return values.");
        }
        return handlersMatching(
                context,
                handler -> !handler.jaxRs() && handler.httpMethods().contains("GET") && handler.serializesBody(),
                handler -> !handler.jaxRs()
                        && handler.httpMethods().contains("GET")
                        && handler.serializesBody()
                        && handler.returnsOptional()
                        && !handler.hasResponseParam(),
                "returns Optional; an empty value is answered with 200 and an empty body, not 404");
    }
}
