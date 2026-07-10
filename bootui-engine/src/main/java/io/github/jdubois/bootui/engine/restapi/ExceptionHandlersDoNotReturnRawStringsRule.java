package io.github.jdubois.bootui.engine.restapi;

import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ExceptionHandlerModel;
import java.util.ArrayList;
import java.util.List;

final class ExceptionHandlersDoNotReturnRawStringsRule extends AbstractRestApiRule {

    ExceptionHandlersDoNotReturnRawStringsRule() {
        super(new RestApiRuleDefinition(
                "RAPI-ERR-008",
                "Exception handlers return structured errors",
                RestApiCategory.ERROR_HANDLING,
                "LOW",
                "An exception handler that returns a raw String exposes an unstructured error contract with no stable"
                        + " fields for type, status, detail, or correlation metadata.",
                "Return a typed error DTO or an RFC 9457 problem-details representation instead of a raw String.",
                RestApiRuleHelp.PROBLEM_DETAIL_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        List<String> violations = new ArrayList<>();
        for (ExceptionHandlerModel handler : context.exceptionHandlers()) {
            if ("java.lang.String".equals(handler.bodyTypeName()) && handler.rendersBody()) {
                violations.add(simpleName(handler.declaringClassName()) + "#" + handler.methodName()
                        + " returns a raw String error body");
            }
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }

    private static String simpleName(String fullName) {
        int lastDot = fullName.lastIndexOf('.');
        return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
    }
}
