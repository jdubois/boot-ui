package io.github.jdubois.bootui.engine.restapi;

import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;

/**
 * RAPI-ERR-011 retains its dismissal identity but no longer emits: stack-trace calls alone cannot
 * distinguish server-side logging from response exposure.
 */
final class ExceptionHandlersDoNotExposeStackTracesRule extends AbstractRestApiRule {

    ExceptionHandlersDoNotExposeStackTracesRule() {
        super(new RestApiRuleDefinition(
                "RAPI-ERR-011",
                "Exception handlers do not expose stack traces",
                RestApiCategory.ERROR_HANDLING,
                "HIGH",
                "Retired heuristic: stack-trace access or printing does not establish response exposure without data flow.",
                "Log the failure with a correlation identifier through the logging framework and return only a"
                        + " stable, non-revealing error representation to the client.",
                RestApiRuleHelp.PROBLEM_DETAIL_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return RestApiRuleSupport.skipped(
                definition(),
                "Retired heuristic: stack-trace calls can be server-side logging; bytecode calls do not prove response exposure.");
    }
}
