package io.github.jdubois.bootui.engine.restapi;

import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.HandlerMethodModel;
import java.util.Locale;

/**
 * RAPI-VALID-006: a Spring handler binds the request body more than once. Spring MVC's
 * {@code RequestResponseBodyMethodProcessor} reads the request stream for each {@code @RequestBody}; the second
 * binding finds it consumed and fails with "Required request body is missing" (or receives {@code null} when it is
 * optional). WebFlux request bodies are single-subscription streams. Form-encoded bodies are excluded because Spring
 * MVC reconstructs them from the already-parsed request parameters. JAX-RS rejects several entity parameters at
 * deployment, so it is not evaluated.
 */
final class SingleRequestBodyRule extends AbstractRestApiRule {

    SingleRequestBodyRule() {
        super(new RestApiRuleDefinition(
                "RAPI-VALID-006",
                "Bind at most one @RequestBody per handler",
                RestApiCategory.VALIDATION,
                "HIGH",
                "A Spring handler declares several @RequestBody parameters. The request body is a single stream: on"
                        + " Spring MVC the second binding finds it already consumed and the request fails with 400"
                        + " \"Required request body is missing\" (or receives null when optional); WebFlux request"
                        + " bodies can be consumed only once.",
                "Bind one request DTO that composes the parts (for example a record with both objects), or use"
                        + " @RequestPart for multipart requests.",
                RestApiRuleHelp.REQUEST_BODY_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (context.jaxRs()) {
            return RestApiRuleSupport.skipped(
                    definition(),
                    "Not applicable on JAX-RS: Jakarta REST allows one entity parameter, and Quarkus REST rejects"
                            + " several at deployment.");
        }
        return handlersMatching(
                context,
                handler -> !handler.jaxRs() && handler.hasRequestBody(),
                handler -> !handler.jaxRs() && handler.requestBodyCount() > 1 && !consumesOnlyForms(handler),
                "declares several @RequestBody parameters; only the first can read the request body");
    }

    private static boolean consumesOnlyForms(HandlerMethodModel handler) {
        return !handler.effectiveConsumes().isEmpty()
                && handler.effectiveConsumes().stream()
                        .map(media -> RestApiRuleHelp.normalizeMediaType(media).toLowerCase(Locale.ROOT))
                        .allMatch("application/x-www-form-urlencoded"::equals);
    }
}
