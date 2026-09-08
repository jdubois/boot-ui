package io.github.jdubois.bootui.engine.restapi;

import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import io.github.jdubois.bootui.engine.errorcontract.ErrorBodyCategory;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ExceptionHandlerModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAPI-ERR-010 — review different error-body declaration categories without inferring wire schemas.
 *
 * <p>Both signals are read from declarations only: the response-body category derived from each handler's
 * declared return type, and the error media types each handler declares. The rule stays silent unless at
 * least two informative body-rendering declarations differ for compatible media types. Their actual
 * serialized schemas and response content are not observed.</p>
 */
final class ConsistentErrorContractRule extends AbstractRestApiRule {

    ConsistentErrorContractRule() {
        super(new RestApiRuleDefinition(
                "RAPI-ERR-010",
                "Error responses share one contract",
                RestApiCategory.ERROR_HANDLING,
                "LOW",
                "Informative exception-handler body declarations differ for compatible media types. Dynamic/unknown"
                        + " shapes are not contradictions, and distinct negotiated formats can be intentional.",
                "Review whether the declared error representations should align for the same media contract."
                        + " RFC 9457 adoption is optional.",
                RestApiRuleHelp.PROBLEM_DETAIL_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (context.exceptionHandlers().stream()
                .anyMatch(handler -> handler.rendersBody()
                        && !handler.returnsVoid()
                        && !handler.hasResponseParam()
                        && RestApiRuleHelp.hasUnknownBody(handler))) {
            context.evidence().requiredUnknown = true;
        }
        List<ExceptionHandlerModel> rendering = context.exceptionHandlers().stream()
                .filter(ExceptionHandlerModel::rendersBody)
                .filter(handler -> !handler.returnsVoid())
                .filter(handler -> !handler.hasResponseParam())
                .filter(handler -> !RestApiRuleHelp.hasUnknownBody(handler))
                .toList();
        if (rendering.size() < 2) {
            return RestApiRuleSupport.pass(definition());
        }
        context.evidence().applicable = true;

        Map<String, ExceptionHandlerModel> firstByMedia = new LinkedHashMap<>();
        ExceptionHandlerModel first = rendering.get(0);
        ExceptionHandlerModel different = null;
        ExceptionHandlerModel unspecified = null;
        for (ExceptionHandlerModel handler : rendering) {
            if (different == null && !bodyCategory(first).equals(bodyCategory(handler))) {
                different = handler;
            }
            if (handler.produces().isEmpty()) {
                if (!bodyCategory(first).equals(bodyCategory(handler))) {
                    return disagreement(first, handler);
                }
                if (different != null) {
                    return disagreement(different, handler);
                }
                unspecified = handler;
            } else {
                if (unspecified != null && !bodyCategory(unspecified).equals(bodyCategory(handler))) {
                    return disagreement(unspecified, handler);
                }
                for (String value : handler.produces()) {
                    String media = RestApiRuleHelp.normalizeMediaType(value);
                    if (media.startsWith("!") || media.contains("*")) {
                        continue;
                    }
                    ExceptionHandlerModel other = firstByMedia.putIfAbsent(media, handler);
                    if (other != null && !bodyCategory(other).equals(bodyCategory(handler))) {
                        return disagreement(other, handler);
                    }
                }
            }
        }
        return RestApiRuleSupport.pass(definition());
    }

    private RestApiRuleResultDto disagreement(ExceptionHandlerModel first, ExceptionHandlerModel second) {
        return RestApiRuleSupport.fromViolations(
                definition(),
                List.of("Exception handlers have different error body declaration categories: "
                        + bodyCategory(first) + " (" + simpleName(first.declaringClassName()) + "#" + first.methodName()
                        + "); " + bodyCategory(second) + " (" + simpleName(second.declaringClassName()) + "#"
                        + second.methodName() + ")"));
    }

    /**
     * The declared body shape of a handler, kept deliberately coarse so a rename or a package move cannot
     * turn one contract into two. Classification is delegated to {@link ErrorBodyCategory} so this rule and
     * the REST API panel's error-contract catalogue can never disagree.
     */
    private static String bodyCategory(ExceptionHandlerModel handler) {
        if (handler.returnsProblemType()) {
            return "problem details";
        }
        String category = ErrorBodyCategory.classify(
                handler.returnsVoid() ? "void" : null, handler.bodyTypeName(), handler.returnsResponseEntity());
        return switch (category) {
            case ErrorBodyCategory.PROBLEM_DETAIL -> "problem details";
            case ErrorBodyCategory.STRING -> "raw string";
            case ErrorBodyCategory.DYNAMIC -> "untyped map/object";
            case ErrorBodyCategory.EMPTY -> "no body";
            case ErrorBodyCategory.CUSTOM_OBJECT -> "custom object";
            default -> "unresolved";
        };
    }

    private static String simpleName(String fullName) {
        int lastDot = fullName.lastIndexOf('.');
        return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
    }
}
