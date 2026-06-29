package io.github.jdubois.bootui.engine.restapi;

import java.util.List;

/**
 * Bounded, read-only snapshot of the host application's web layer, derived once per scan from the
 * imported {@link com.tngtech.archunit.core.domain.JavaClasses}. Rules operate on these records
 * instead of re-walking ArchUnit, so a single rule can never trip over an unresolvable class.
 */
final class RestApiModel {

    private RestApiModel() {}

    /** Which web framework a controller/resource was modelled from; drives per-framework rule applicability. */
    enum Framework {
        SPRING,
        JAX_RS
    }

    /** Well-known annotation / type names matched by string so BootUI need not depend on them. */
    static final class Types {
        private Types() {}

        static final String CONTROLLER = "org.springframework.stereotype.Controller";
        static final String REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";
        static final String REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";
        static final String GET_MAPPING = "org.springframework.web.bind.annotation.GetMapping";
        static final String POST_MAPPING = "org.springframework.web.bind.annotation.PostMapping";
        static final String PUT_MAPPING = "org.springframework.web.bind.annotation.PutMapping";
        static final String DELETE_MAPPING = "org.springframework.web.bind.annotation.DeleteMapping";
        static final String PATCH_MAPPING = "org.springframework.web.bind.annotation.PatchMapping";

        static final String REQUEST_BODY = "org.springframework.web.bind.annotation.RequestBody";
        static final String REQUEST_PARAM = "org.springframework.web.bind.annotation.RequestParam";
        static final String PATH_VARIABLE = "org.springframework.web.bind.annotation.PathVariable";
        static final String RESPONSE_STATUS = "org.springframework.web.bind.annotation.ResponseStatus";
        static final String RESPONSE_BODY = "org.springframework.web.bind.annotation.ResponseBody";
        static final String EXCEPTION_HANDLER = "org.springframework.web.bind.annotation.ExceptionHandler";
        static final String CONTROLLER_ADVICE = "org.springframework.web.bind.annotation.ControllerAdvice";
        static final String REST_CONTROLLER_ADVICE = "org.springframework.web.bind.annotation.RestControllerAdvice";
        static final String RESPONSE_ENTITY_EXCEPTION_HANDLER =
                "org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler";
        static final String HTTP_SERVLET_RESPONSE = "jakarta.servlet.http.HttpServletResponse";
        static final String SERVER_HTTP_RESPONSE = "org.springframework.http.server.ServerHttpResponse";

        static final String VALID = "jakarta.validation.Valid";
        static final String VALIDATED = "org.springframework.validation.annotation.Validated";
        static final String CONSTRAINT_PACKAGE = "jakarta.validation.constraints.";

        static final String ENTITY = "jakarta.persistence.Entity";

        static final String RESPONSE_ENTITY = "org.springframework.http.ResponseEntity";
        static final String HTTP_ENTITY = "org.springframework.http.HttpEntity";
        static final String PROBLEM_DETAIL = "org.springframework.http.ProblemDetail";
        static final String ERROR_RESPONSE = "org.springframework.web.ErrorResponse";

        static final String MULTI_VALUE_MAP = "org.springframework.util.MultiValueMap";
        static final String DATE = "java.util.Date";
        static final String CALENDAR = "java.util.Calendar";

        static final String OPTIONAL = "java.util.Optional";
        static final String PAGEABLE = "org.springframework.data.domain.Pageable";
        static final String PAGE = "org.springframework.data.domain.Page";
        static final String SLICE = "org.springframework.data.domain.Slice";

        static final String OPERATION = "io.swagger.v3.oas.annotations.Operation";
        static final String TAG = "io.swagger.v3.oas.annotations.tags.Tag";
        static final String HIDDEN = "io.swagger.v3.oas.annotations.Hidden";

        // --- JAX-RS (jakarta.ws.rs) — recognised so the advisor models Quarkus / JAX-RS resources too ---
        static final String JAXRS_PATH = "jakarta.ws.rs.Path";
        static final String JAXRS_GET = "jakarta.ws.rs.GET";
        static final String JAXRS_POST = "jakarta.ws.rs.POST";
        static final String JAXRS_PUT = "jakarta.ws.rs.PUT";
        static final String JAXRS_DELETE = "jakarta.ws.rs.DELETE";
        static final String JAXRS_PATCH = "jakarta.ws.rs.PATCH";
        static final String JAXRS_HEAD = "jakarta.ws.rs.HEAD";
        static final String JAXRS_OPTIONS = "jakarta.ws.rs.OPTIONS";
        static final String JAXRS_PATH_PARAM = "jakarta.ws.rs.PathParam";
        static final String JAXRS_QUERY_PARAM = "jakarta.ws.rs.QueryParam";
        static final String JAXRS_HEADER_PARAM = "jakarta.ws.rs.HeaderParam";
        static final String JAXRS_COOKIE_PARAM = "jakarta.ws.rs.CookieParam";
        static final String JAXRS_MATRIX_PARAM = "jakarta.ws.rs.MatrixParam";
        static final String JAXRS_FORM_PARAM = "jakarta.ws.rs.FormParam";
        static final String JAXRS_BEAN_PARAM = "jakarta.ws.rs.BeanParam";
        static final String JAXRS_CONTEXT = "jakarta.ws.rs.core.Context";
        static final String JAXRS_PRODUCES = "jakarta.ws.rs.Produces";
        static final String JAXRS_CONSUMES = "jakarta.ws.rs.Consumes";
        static final String JAXRS_PROVIDER = "jakarta.ws.rs.ext.Provider";
        static final String JAXRS_EXCEPTION_MAPPER = "jakarta.ws.rs.ext.ExceptionMapper";
        static final String JAXRS_RESPONSE = "jakarta.ws.rs.core.Response";
        static final String QUARKUS_REST_RESPONSE = "org.jboss.resteasy.reactive.RestResponse";

        // --- RESTEasy Reactive (quarkus-rest) parameter annotations: still mark a param as bound, so the
        // remaining unannotated parameter is correctly identified as the request entity body. ---
        static final String REST_PATH = "org.jboss.resteasy.reactive.RestPath";
        static final String REST_QUERY = "org.jboss.resteasy.reactive.RestQuery";
        static final String REST_HEADER = "org.jboss.resteasy.reactive.RestHeader";
        static final String REST_FORM = "org.jboss.resteasy.reactive.RestForm";
        static final String REST_COOKIE = "org.jboss.resteasy.reactive.RestCookie";
        static final String REST_MATRIX = "org.jboss.resteasy.reactive.RestMatrix";
    }

    /** A controller class (annotated {@code @Controller} or {@code @RestController}). */
    record ControllerModel(
            String className,
            String simpleName,
            boolean restController,
            List<String> typeLevelPaths,
            boolean classValidated,
            boolean hasTag,
            boolean hidden,
            int handlerCount) {

        ControllerModel {
            typeLevelPaths = List.copyOf(typeLevelPaths);
        }
    }

    /** One request-handling method on a controller, with the facts the rules need pre-extracted. */
    record HandlerMethodModel(
            String controllerClassName,
            String controllerSimpleName,
            String methodName,
            boolean restController,
            boolean controllerValidated,
            List<String> httpMethods,
            boolean explicitHttpMethod,
            List<String> mappingPaths,
            List<String> effectivePaths,
            List<String> produces,
            List<String> consumes,
            String returnTypeName,
            String returnSimpleName,
            boolean returnsVoid,
            boolean returnsResponseEntity,
            boolean returnsCollection,
            boolean returnsPageOrSlice,
            String bodyTypeName,
            boolean bodyIsEntity,
            boolean bodyIsUntyped,
            boolean bodyIsScalar,
            boolean bodyExposesSetters,
            boolean bodyIsRecord,
            boolean bodyHasLegacyDateField,
            boolean hasRequestBody,
            boolean requestBodyValidated,
            boolean requestBodyIsEntity,
            boolean hasConstrainedSimpleParam,
            boolean hasPageable,
            boolean hasUnboundedPrimitiveRequestParam,
            boolean hasUnboundedMapRequestParam,
            boolean hasExplicitPageParam,
            boolean hasResponseStatus,
            boolean methodHasResponseStatus,
            String responseStatusValue,
            boolean declaresBroadThrows,
            boolean hasOperationAnnotation,
            boolean nameLooksStateChanging,
            boolean nameLooksLikeFindAll,
            boolean serializesBody,
            String mappingVersion,
            List<String> effectiveProduces,
            List<String> effectiveConsumes,
            List<String> params,
            List<String> headers,
            List<String> pathVariableNames,
            boolean requestBodyIsSimple,
            boolean hasTag,
            boolean hidden,
            boolean hasResponseParam) {

        HandlerMethodModel {
            httpMethods = List.copyOf(httpMethods);
            mappingPaths = List.copyOf(mappingPaths);
            effectivePaths = List.copyOf(effectivePaths);
            produces = List.copyOf(produces);
            consumes = List.copyOf(consumes);
            effectiveProduces = List.copyOf(effectiveProduces);
            effectiveConsumes = List.copyOf(effectiveConsumes);
            params = List.copyOf(params);
            headers = List.copyOf(headers);
            pathVariableNames = List.copyOf(pathVariableNames);
        }

        String describe() {
            String paths = effectivePaths.isEmpty() ? "(no path)" : String.join(", ", effectivePaths);
            String methods = httpMethods.isEmpty() ? "ANY" : String.join("/", httpMethods);
            return controllerSimpleName + "#" + methodName + " [" + methods + " " + paths + "]";
        }
    }

    /** An {@code @ExceptionHandler} method, used by the ProblemDetail and error-status rules. */
    record ExceptionHandlerModel(
            String declaringClassName,
            String methodName,
            String bodyTypeName,
            boolean returnsProblemType,
            boolean returnsResponseEntity,
            boolean returnsVoid,
            boolean hasResponseStatus,
            boolean catchesExceptionOrThrowable,
            boolean hasResponseParam,
            boolean rendersBody) {}
}
