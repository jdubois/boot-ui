package io.github.jdubois.bootui.engine.restapi;

import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;

final class HeadHandlersDoNotReturnBodiesRule extends AbstractRestApiRule {

    HeadHandlersDoNotReturnBodiesRule() {
        super(new RestApiRuleDefinition(
                "RAPI-RESP-009",
                "Review dedicated HEAD representation work",
                RestApiCategory.RESPONSES,
                "INFO",
                "RFC 9110 requires HEAD responses to omit message content. A dedicated HEAD handler that returns a"
                        + " content-capable type may do avoidable work. Frameworks suppress HEAD content; the signature"
                        + " does not prove forbidden content is transmitted.",
                "Return void or a headers-only response from dedicated HEAD handlers; let the framework derive HEAD"
                        + " from GET when no distinct metadata calculation is needed.",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.httpMethods().contains("HEAD")
                        && !handler.httpMethods().contains("GET"),
                handler -> handler.httpMethods().contains("HEAD")
                        && !handler.httpMethods().contains("GET")
                        && handler.serializesBody()
                        && !handler.returnsVoid()
                        && !handler.hasResponseParam()
                        && hasStaticallyProvableBody(handler.bodyTypeName()),
                "dedicated HEAD declares a content-capable type; review whether representation work is needed");
    }

    private static boolean hasStaticallyProvableBody(String bodyTypeName) {
        return !"void".equals(bodyTypeName)
                && !"java.lang.Void".equals(bodyTypeName)
                && !"kotlin.Unit".equals(bodyTypeName)
                && !"java.lang.Object".equals(bodyTypeName)
                && !"org.springframework.http.HttpHeaders".equals(bodyTypeName)
                && !RestApiModel.Types.RESPONSE_ENTITY.equals(bodyTypeName)
                && !RestApiModel.Types.HTTP_ENTITY.equals(bodyTypeName)
                && !RestApiModel.Types.JAXRS_RESPONSE.equals(bodyTypeName)
                && !RestApiModel.Types.QUARKUS_REST_RESPONSE.equals(bodyTypeName);
    }
}
