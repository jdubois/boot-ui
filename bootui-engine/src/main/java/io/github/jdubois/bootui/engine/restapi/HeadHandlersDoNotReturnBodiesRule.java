package io.github.jdubois.bootui.engine.restapi;

import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;

final class HeadHandlersDoNotReturnBodiesRule extends AbstractRestApiRule {

    HeadHandlersDoNotReturnBodiesRule() {
        super(new RestApiRuleDefinition(
                "RAPI-RESP-009",
                "HEAD handlers do not return response bodies",
                RestApiCategory.RESPONSES,
                "LOW",
                "RFC 9110 requires HEAD responses to omit message content. A dedicated HEAD handler that returns a"
                        + " body declares work and a representation that the server must discard.",
                "Return void or a headers-only response from dedicated HEAD handlers; let the framework derive HEAD"
                        + " from GET when no distinct metadata calculation is needed.",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.httpMethods().contains("HEAD")
                        && handler.serializesBody()
                        && hasStaticallyProvableBody(handler.bodyTypeName()),
                "HEAD handler declares a response body that cannot be sent");
    }

    private static boolean hasStaticallyProvableBody(String bodyTypeName) {
        return !"void".equals(bodyTypeName)
                && !"java.lang.Void".equals(bodyTypeName)
                && !RestApiModel.Types.RESPONSE_ENTITY.equals(bodyTypeName)
                && !RestApiModel.Types.HTTP_ENTITY.equals(bodyTypeName)
                && !RestApiModel.Types.JAXRS_RESPONSE.equals(bodyTypeName)
                && !RestApiModel.Types.QUARKUS_REST_RESPONSE.equals(bodyTypeName);
    }
}
