package io.github.jdubois.bootui.engine.restapi;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ControllerModel;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ExceptionHandlerModel;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.HandlerMethodModel;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.Types;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Translates the imported {@link JavaClasses} into the bounded {@link HandlerMethodModel} /
 * {@link ControllerModel} snapshot the rules consume. Every per-class and per-method extraction is
 * isolated in a try/catch so a single unresolvable class or member degrades to "skip this element"
 * rather than aborting the whole scan.
 */
final class RestApiHandlerModelBuilder {

    private static final Set<String> WRAPPER_TYPES = Set.of(
            Types.RESPONSE_ENTITY,
            Types.HTTP_ENTITY,
            Types.OPTIONAL,
            "java.util.concurrent.Callable",
            "java.util.concurrent.CompletableFuture",
            "java.util.concurrent.CompletionStage",
            "reactor.core.publisher.Mono",
            "org.springframework.web.context.request.async.DeferredResult",
            "org.springframework.web.context.request.async.WebAsyncTask",
            // Quarkus/SmallRye Mutiny's async wrapper: https://smallrye.io/smallrye-mutiny/latest/concepts/uni/
            "io.smallrye.mutiny.Uni",
            // Quarkus REST's typed, GraalVM-friendly analogue of ResponseEntity<T>. Plain
            // jakarta.ws.rs.core.Response is deliberately NOT added here: it is non-generic, so there is no
            // generic body type to unwrap (see https://quarkus.io/guides/rest#reactive).
            Types.QUARKUS_REST_RESPONSE);

    private static final Set<String> COLLECTION_TYPES = Set.of(
            "java.util.List",
            "java.util.Collection",
            "java.util.Set",
            "java.lang.Iterable",
            "java.util.stream.Stream",
            "reactor.core.publisher.Flux",
            // Quarkus/SmallRye Mutiny's async stream type: https://smallrye.io/smallrye-mutiny/latest/concepts/multi/
            "io.smallrye.mutiny.Multi");

    private static final Set<String> UNTYPED_TYPES = Set.of(
            "java.lang.Object",
            "java.util.Map",
            "java.util.HashMap",
            "com.fasterxml.jackson.databind.JsonNode",
            // Spring Boot 4 ships Jackson 3 under the new tools.jackson.* package/artifact; Quarkus still ships
            // Jackson 2 (com.fasterxml.jackson.*, above). See the Jackson 3 migration guide:
            // https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md
            "tools.jackson.databind.JsonNode");

    private static final Set<String> SCALAR_TYPES = Set.of(
            "java.lang.String",
            "java.lang.CharSequence",
            "java.lang.Number",
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double",
            "java.lang.Character");

    private static final Set<String> STATE_CHANGING_PREFIXES = Set.of(
            "create",
            "update",
            "delete",
            "remove",
            "save",
            "add",
            "insert",
            "modify",
            "patch",
            "put",
            "post",
            "register",
            "edit");

    private static final List<String> MAPPING_ANNOTATIONS = List.of(
            Types.GET_MAPPING,
            Types.POST_MAPPING,
            Types.PUT_MAPPING,
            Types.DELETE_MAPPING,
            Types.PATCH_MAPPING,
            Types.REQUEST_MAPPING);

    /** Request-body parameter types where bean validation is meaningless (so VALID-001 should skip). */
    private static final Set<String> SIMPLE_BODY_TYPES = Set.of(
            "java.lang.String",
            "java.lang.CharSequence",
            "org.springframework.core.io.Resource",
            "org.springframework.web.multipart.MultipartFile",
            "jakarta.servlet.http.Part",
            "java.io.InputStream",
            "byte[]");

    /** Lower-cased query-parameter names that signal manual pagination (so PAGE-001 should pass). */
    private static final Set<String> PAGE_PARAM_NAMES =
            Set.of("page", "size", "limit", "offset", "pagenumber", "pagesize", "perpage", "cursor", "after", "before");

    // Pagination "family" sub-sets of PAGE_PARAM_NAMES, used to detect when an application mixes more than
    // one pagination vocabulary across its handlers (RAPI-PAGE-003), mirroring Zalando's/Microsoft's
    // pagination guidance that a single API should commit to one dialect.
    private static final Set<String> PAGE_SIZE_PARAM_NAMES =
            Set.of("page", "size", "pagenumber", "pagesize", "perpage");
    private static final Set<String> OFFSET_LIMIT_PARAM_NAMES = Set.of("offset", "limit");
    private static final Set<String> CURSOR_PARAM_NAMES = Set.of("cursor", "after", "before");

    /** Header name (case-insensitive) recognised by the IETF HTTPAPI Idempotency-Key draft. */
    private static final String IDEMPOTENCY_KEY_HEADER_NAME = "idempotency-key";

    private static final List<String> JAXRS_METHOD_ANNOTATIONS = List.of(
            Types.JAXRS_GET,
            Types.JAXRS_POST,
            Types.JAXRS_PUT,
            Types.JAXRS_DELETE,
            Types.JAXRS_PATCH,
            Types.JAXRS_HEAD,
            Types.JAXRS_OPTIONS);

    /** Parameter annotations that bind a non-body source; any param carrying one is not the request entity. */
    private static final Set<String> JAXRS_BOUND_PARAM_ANNOTATIONS = Set.of(
            Types.JAXRS_PATH_PARAM,
            Types.JAXRS_QUERY_PARAM,
            Types.JAXRS_HEADER_PARAM,
            Types.JAXRS_COOKIE_PARAM,
            Types.JAXRS_MATRIX_PARAM,
            Types.JAXRS_FORM_PARAM,
            Types.JAXRS_BEAN_PARAM,
            Types.JAXRS_CONTEXT,
            Types.REST_PATH,
            Types.REST_QUERY,
            Types.REST_HEADER,
            Types.REST_FORM,
            Types.REST_COOKIE,
            Types.REST_MATRIX);

    private final List<ControllerModel> controllers = new ArrayList<>();
    private final List<HandlerMethodModel> handlers = new ArrayList<>();
    private final List<ExceptionHandlerModel> exceptionHandlers = new ArrayList<>();
    private final List<String> responseStatusExceptionClasses = new ArrayList<>();
    private boolean hasExceptionHandling;
    private int springControllerCount;
    private int jaxRsResourceCount;

    private RestApiHandlerModelBuilder() {}

    static RestApiHandlerModelBuilder build(JavaClasses classes) {
        RestApiHandlerModelBuilder builder = new RestApiHandlerModelBuilder();
        for (JavaClass type : classes) {
            try {
                builder.inspect(type);
            } catch (RuntimeException | LinkageError ex) {
                // Skip a class that cannot be introspected; the rest of the scan continues.
            }
        }
        return builder;
    }

    List<ControllerModel> controllers() {
        return List.copyOf(controllers);
    }

    List<HandlerMethodModel> handlers() {
        return List.copyOf(handlers);
    }

    List<ExceptionHandlerModel> exceptionHandlers() {
        return List.copyOf(exceptionHandlers);
    }

    List<String> responseStatusExceptionClasses() {
        return List.copyOf(responseStatusExceptionClasses);
    }

    boolean hasExceptionHandling() {
        return hasExceptionHandling;
    }

    /**
     * The framework the modelled handlers were derived from: {@code JAX_RS} when the application's
     * resources are JAX-RS (and no Spring controllers were found), {@code SPRING} otherwise. Drives
     * which framework-specific rules apply versus skip honestly.
     */
    RestApiModel.Framework framework() {
        return jaxRsResourceCount > 0 && springControllerCount == 0
                ? RestApiModel.Framework.JAX_RS
                : RestApiModel.Framework.SPRING;
    }

    private void inspect(JavaClass type) {
        scanResponseStatusException(type);
        collectExceptionHandlers(type);
        collectJaxRsExceptionMapper(type);
        collectServerExceptionMapperMethods(type);
        if (isController(type)) {
            inspectSpringController(type);
        } else if (isJaxRsResource(type)) {
            inspectJaxRsResource(type);
        }
    }

    private void inspectSpringController(JavaClass type) {
        springControllerCount++;
        boolean restController = annotated(type, Types.REST_CONTROLLER) || metaAnnotated(type, Types.REST_CONTROLLER);
        boolean classValidated = annotated(type, Types.VALIDATED);
        boolean hasTag = hasTagAnnotation(type);
        boolean hidden = hasHiddenAnnotation(type);
        boolean classResponseBody = annotated(type, Types.RESPONSE_BODY) || metaAnnotated(type, Types.RESPONSE_BODY);
        List<String> typeLevelPaths = mappingPaths(type, Types.REQUEST_MAPPING);
        List<String> typeLevelProduces = mappingAttribute(type, Types.REQUEST_MAPPING, "produces");
        List<String> typeLevelConsumes = mappingAttribute(type, Types.REQUEST_MAPPING, "consumes");
        List<String> typeLevelParams = mappingAttribute(type, Types.REQUEST_MAPPING, "params");
        List<String> typeLevelHeaders = mappingAttribute(type, Types.REQUEST_MAPPING, "headers");
        List<String> typeLevelMethods = mappingEnumAttribute(type, Types.REQUEST_MAPPING, "method");
        String typeLevelVersion = mappingStringAttribute(type, Types.REQUEST_MAPPING, "version");

        int handlerCount = 0;
        for (JavaMethod method : type.getMethods()) {
            try {
                HandlerMethodModel model = toHandler(
                        type,
                        method,
                        restController,
                        classValidated,
                        classResponseBody,
                        hidden,
                        typeLevelPaths,
                        typeLevelProduces,
                        typeLevelConsumes,
                        typeLevelParams,
                        typeLevelHeaders,
                        typeLevelMethods,
                        typeLevelVersion);
                if (model != null) {
                    handlers.add(model);
                    handlerCount++;
                }
            } catch (RuntimeException | LinkageError ex) {
                // Skip a method that cannot be introspected.
            }
        }
        controllers.add(new ControllerModel(
                type.getName(),
                safeSimpleName(type),
                restController,
                typeLevelPaths,
                classValidated,
                hasTag,
                hidden,
                handlerCount));
    }

    /** A class is a JAX-RS resource when it carries {@code @Path} or any JAX-RS HTTP-method method. */
    private static boolean isJaxRsResource(JavaClass type) {
        if (annotated(type, Types.JAXRS_PATH)) {
            return true;
        }
        try {
            for (JavaMethod method : type.getMethods()) {
                for (String httpAnnotation : JAXRS_METHOD_ANNOTATIONS) {
                    if (method.isAnnotatedWith(httpAnnotation)) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
        return false;
    }

    private void inspectJaxRsResource(JavaClass type) {
        jaxRsResourceCount++;
        boolean classValidated = annotated(type, Types.VALIDATED) || annotated(type, Types.VALID);
        boolean hasTag = hasTagAnnotation(type);
        boolean hidden = hasHiddenAnnotation(type);
        List<String> typeLevelPaths = mappingPaths(type, Types.JAXRS_PATH);
        List<String> typeLevelProduces = mappingAttribute(type, Types.JAXRS_PRODUCES, "value");
        List<String> typeLevelConsumes = mappingAttribute(type, Types.JAXRS_CONSUMES, "value");

        int handlerCount = 0;
        for (JavaMethod method : type.getMethods()) {
            try {
                HandlerMethodModel model = toJaxRsHandler(
                        type, method, classValidated, hidden, typeLevelPaths, typeLevelProduces, typeLevelConsumes);
                if (model != null) {
                    handlers.add(model);
                    handlerCount++;
                }
            } catch (RuntimeException | LinkageError ex) {
                // Skip a method that cannot be introspected.
            }
        }
        controllers.add(new ControllerModel(
                type.getName(),
                safeSimpleName(type),
                true,
                typeLevelPaths,
                classValidated,
                hasTag,
                hidden,
                handlerCount));
    }

    private HandlerMethodModel toJaxRsHandler(
            JavaClass type,
            JavaMethod method,
            boolean classValidated,
            boolean classHidden,
            List<String> typeLevelPaths,
            List<String> typeLevelProduces,
            List<String> typeLevelConsumes) {

        Set<String> httpMethods = new LinkedHashSet<>();
        for (String annotation : JAXRS_METHOD_ANNOTATIONS) {
            if (method.isAnnotatedWith(annotation)) {
                httpMethods.add(simpleName(annotation));
            }
        }
        if (httpMethods.isEmpty()) {
            // A @Path-only method with no HTTP verb is a sub-resource locator, not a request handler.
            return null;
        }

        List<String> mappingPaths = method.isAnnotatedWith(Types.JAXRS_PATH)
                ? stringValues(method.getAnnotationOfType(Types.JAXRS_PATH), "value")
                : List.of();
        List<String> effectivePaths = effectivePaths(typeLevelPaths, mappingPaths);
        List<String> produces = methodStringAttr(method, Types.JAXRS_PRODUCES, "value");
        List<String> consumes = methodStringAttr(method, Types.JAXRS_CONSUMES, "value");
        List<String> effectiveProduces = produces.isEmpty() ? List.copyOf(typeLevelProduces) : dedupe(produces);
        List<String> effectiveConsumes = consumes.isEmpty() ? List.copyOf(typeLevelConsumes) : dedupe(consumes);

        JavaType returnType = method.getReturnType();
        JavaClass rawReturn = returnType.toErasure();
        String returnTypeName = rawReturn.getName();
        boolean returnsResponseEntity =
                Types.JAXRS_RESPONSE.equals(returnTypeName) || Types.QUARKUS_REST_RESPONSE.equals(returnTypeName);
        boolean returnsVoid = "void".equals(returnTypeName) || "java.lang.Void".equals(returnTypeName);
        // Per the Jakarta REST spec, a void-returning resource method always answers 204 No Content —
        // unlike Spring MVC, which defaults an unannotated void handler to 200 OK. Modelling that as a
        // known response status keeps RAPI-RESP-002/RAPI-RESP-005 from flagging a JAX-RS void handler
        // for a "silently defaults to 200 OK" footgun that cannot actually happen on this framework.
        boolean hasImplicitNoContentStatus = returnsVoid;

        JavaType bodyType = unwrapWrappers(returnType);
        JavaClass bodyErasure = bodyType.toErasure();
        boolean returnsCollection = isCollection(bodyErasure);
        if (returnsCollection) {
            JavaType element = firstTypeArgument(bodyType);
            if (element != null) {
                bodyType = element;
                bodyErasure = element.toErasure();
            }
        }
        String bodyTypeName = bodyErasure.getName();
        boolean bodyIsEntity = safeAnnotated(bodyErasure, Types.ENTITY);
        boolean bodyIsUntyped = UNTYPED_TYPES.contains(bodyTypeName);
        boolean bodyIsScalar = SCALAR_TYPES.contains(bodyTypeName) || isPrimitive(bodyErasure);
        boolean bodyIsRecord = safeIsRecord(bodyErasure);
        boolean bodyExposesSetters = exposesPublicSetters(bodyErasure, bodyIsRecord, bodyIsEntity);
        boolean bodyHasLegacyDateField = hasLegacyDateField(bodyErasure);

        boolean hasRequestBody = false;
        boolean requestBodyValidated = false;
        boolean requestBodyIsEntity = false;
        boolean requestBodyIsSimple = false;
        boolean hasConstrainedSimpleParam = false;
        boolean hasExplicitPageParam = false;
        boolean hasIdempotencyKeyHeader = false;
        List<String> pathVariableNames = new ArrayList<>();
        List<String> pageQueryParamNames = new ArrayList<>();
        // Only version-signal-matching names are folded into params/headers (not a full parameter-binding
        // dump): JAX-RS has no Spring-style conditional-dispatch mapping attribute, and RAPI-MAP-002's
        // duplicate-route detection also reads these two lists, so adding unrelated binding names would
        // create false negatives there. Header/query-param API versioning (RAPI-VER-001/006) is the one
        // place a JAX-RS parameter binding is a legitimate analogue of Spring's params=/headers= condition.
        List<String> versionParams = new ArrayList<>();
        List<String> versionHeaders = new ArrayList<>();

        for (JavaParameter parameter : method.getParameters()) {
            try {
                if (isJaxRsEntityParam(parameter)) {
                    hasRequestBody = true;
                    requestBodyValidated |= parameter.isAnnotatedWith(Types.VALID);
                    requestBodyIsEntity |= safeAnnotated(parameter.getRawType(), Types.ENTITY);
                    requestBodyIsSimple |= isSimpleBodyType(parameter.getRawType());
                } else if (hasConstraintAnnotation(parameter)) {
                    hasConstrainedSimpleParam = true;
                }
                String pathName = explicitBindingName(parameter, Types.JAXRS_PATH_PARAM);
                if (pathName == null) {
                    pathName = explicitBindingName(parameter, Types.REST_PATH);
                }
                if (pathName != null) {
                    pathVariableNames.add(pathName);
                }
                String query = explicitBindingName(parameter, Types.JAXRS_QUERY_PARAM);
                if (query == null) {
                    query = explicitBindingName(parameter, Types.REST_QUERY);
                }
                if (query != null) {
                    pageQueryParamNames.add(query);
                    String lowerQuery = query.toLowerCase(Locale.ROOT);
                    if (PAGE_PARAM_NAMES.contains(lowerQuery)) {
                        hasExplicitPageParam = true;
                    }
                    if (RestApiRuleHelp.VERSION_PARAM_NAMES.contains(lowerQuery)) {
                        versionParams.add(query);
                    }
                }
                String header = explicitBindingName(parameter, Types.JAXRS_HEADER_PARAM);
                if (header == null) {
                    header = explicitBindingName(parameter, Types.REST_HEADER);
                }
                if (header != null) {
                    if (IDEMPOTENCY_KEY_HEADER_NAME.equalsIgnoreCase(header)) {
                        hasIdempotencyKeyHeader = true;
                    }
                    if (RestApiRuleHelp.VERSION_PARAM_NAMES.contains(header.toLowerCase(Locale.ROOT))) {
                        versionHeaders.add(header);
                    }
                }
            } catch (RuntimeException | LinkageError ex) {
                // Skip a parameter that cannot be introspected.
            }
        }

        boolean stateChanging = startsWithWord(method.getName(), STATE_CHANGING_PREFIXES);
        String lowerName = method.getName().toLowerCase(Locale.ROOT);
        boolean findAll = lowerName.startsWith("findall")
                || lowerName.startsWith("getall")
                || lowerName.startsWith("listall")
                || lowerName.equals("list")
                || lowerName.startsWith("fetchall")
                || lowerName.startsWith("readall");
        boolean hidden = classHidden || hasHiddenAnnotation(method) || operationHidden(method);
        // A broad "throws Exception/Throwable" is a plain JVM method-signature fact, not a Spring-specific
        // one, so it applies to JAX-RS resource methods exactly the same way (RAPI-ERR-002).
        boolean declaresBroadThrows = declaresBroadThrows(method);
        boolean isDeprecated = type.isAnnotatedWith(Types.DEPRECATED) || method.isAnnotatedWith(Types.DEPRECATED);
        boolean operationMarkedDeprecated = operationMarkedDeprecated(method);
        String paginationParamFamily = paginationFamily(false, pageQueryParamNames);

        return new HandlerMethodModel(
                type.getName(),
                safeSimpleName(type),
                method.getName(),
                true,
                classValidated,
                List.copyOf(httpMethods),
                true,
                List.copyOf(mappingPaths),
                effectivePaths,
                dedupe(produces),
                dedupe(consumes),
                returnTypeName,
                simpleName(returnTypeName),
                returnsVoid,
                returnsResponseEntity,
                returnsCollection,
                false,
                bodyTypeName,
                bodyIsEntity,
                bodyIsUntyped,
                bodyIsScalar,
                bodyExposesSetters,
                bodyIsRecord,
                bodyHasLegacyDateField,
                hasRequestBody,
                requestBodyValidated,
                requestBodyIsEntity,
                hasConstrainedSimpleParam,
                false,
                false,
                false,
                hasExplicitPageParam,
                hasImplicitNoContentStatus,
                false,
                hasImplicitNoContentStatus ? "NO_CONTENT" : "",
                declaresBroadThrows,
                hasOperationAnnotation(method),
                stateChanging,
                findAll,
                true,
                "",
                effectiveProduces,
                effectiveConsumes,
                List.copyOf(versionParams),
                List.copyOf(versionHeaders),
                List.copyOf(pathVariableNames),
                requestBodyIsSimple,
                hasTagAnnotation(method),
                hidden,
                false,
                paginationParamFamily,
                hasIdempotencyKeyHeader,
                isDeprecated,
                operationMarkedDeprecated);
    }

    /** A JAX-RS body parameter is one with neither a binding annotation nor {@code @Context}. */
    private static boolean isJaxRsEntityParam(JavaParameter parameter) {
        for (JavaAnnotation<JavaParameter> annotation : parameter.getAnnotations()) {
            if (JAXRS_BOUND_PARAM_ANNOTATIONS.contains(annotation.getRawType().getName())) {
                return false;
            }
        }
        return true;
    }

    /** Models a JAX-RS {@code @Provider ExceptionMapper<X>} implementation as a centralized exception handler. */
    private void collectJaxRsExceptionMapper(JavaClass type) {
        try {
            if (!type.isAnnotatedWith(Types.JAXRS_PROVIDER)) {
                return;
            }
            for (JavaType iface : type.getInterfaces()) {
                if (!Types.JAXRS_EXCEPTION_MAPPER.equals(iface.toErasure().getName())) {
                    continue;
                }
                hasExceptionHandling = true;
                JavaType argument = firstTypeArgument(iface);
                String exceptionType = argument != null ? argument.toErasure().getName() : "java.lang.Throwable";
                addJaxRsExceptionMapperModel(type, findMethod(type, "toResponse", 1), exceptionType);
                return;
            }
        } catch (RuntimeException | LinkageError ex) {
            // Skip unresolvable class.
        }
    }

    /**
     * Models a RESTEasy Reactive {@code @ServerExceptionMapper} method as a centralized exception handler.
     * This is the simpler, {@code @Provider}-free exception-mapper style that is the idiomatic default on
     * Quarkus (no {@code ExceptionMapper<X>} interface to implement); the method can live on any CDI bean,
     * not just a JAX-RS resource. See https://quarkus.io/guides/rest#exception-mapping
     */
    private void collectServerExceptionMapperMethods(JavaClass type) {
        for (JavaMethod method : type.getMethods()) {
            try {
                if (!method.isAnnotatedWith(Types.SERVER_EXCEPTION_MAPPER)) {
                    continue;
                }
                hasExceptionHandling = true;
                addJaxRsExceptionMapperModel(type, Optional.of(method), firstExceptionParameterType(method));
            } catch (RuntimeException | LinkageError ex) {
                // Skip a method that cannot be introspected.
            }
        }
    }

    /** JAX-RS/Servlet context parameter types a {@code @ServerExceptionMapper} may also declare. */
    private static final Set<String> SERVER_EXCEPTION_MAPPER_CONTEXT_PARAM_TYPES = Set.of(
            "jakarta.ws.rs.container.ContainerRequestContext",
            "jakarta.ws.rs.core.UriInfo",
            "jakarta.ws.rs.core.HttpHeaders",
            "jakarta.ws.rs.core.Request",
            "jakarta.servlet.http.HttpServletRequest",
            "jakarta.servlet.http.HttpServletResponse");

    /** The exception type a {@code @ServerExceptionMapper} method handles: its first non-context parameter. */
    private static String firstExceptionParameterType(JavaMethod method) {
        for (JavaParameter parameter : method.getParameters()) {
            try {
                String name = parameter.getRawType().getName();
                if (!SERVER_EXCEPTION_MAPPER_CONTEXT_PARAM_TYPES.contains(name)) {
                    return name;
                }
            } catch (RuntimeException | LinkageError ex) {
                // Skip a parameter that cannot be introspected.
            }
        }
        return "java.lang.Throwable";
    }

    private static Optional<JavaMethod> findMethod(JavaClass type, String name, int parameterCount) {
        for (JavaMethod method : type.getMethods()) {
            if (method.getName().equals(name) && method.getRawParameterTypes().size() == parameterCount) {
                return Optional.of(method);
            }
        }
        return Optional.empty();
    }

    private void addJaxRsExceptionMapperModel(JavaClass type, Optional<JavaMethod> methodOpt, String exceptionType) {
        String methodName = methodOpt.map(JavaMethod::getName).orElse("toResponse");
        String returnTypeName = methodOpt
                .map(method -> method.getReturnType().toErasure().getName())
                .orElse(Types.JAXRS_RESPONSE);
        String bodyType = methodOpt
                .map(method -> resolveBodyTypeName(method.getReturnType()))
                .orElse(Types.JAXRS_RESPONSE);
        boolean returnsResponseEntity =
                Types.JAXRS_RESPONSE.equals(returnTypeName) || Types.QUARKUS_REST_RESPONSE.equals(returnTypeName);
        boolean returnsVoid = "void".equals(returnTypeName) || "java.lang.Void".equals(returnTypeName);
        boolean hasResponseParam =
                methodOpt.map(RestApiHandlerModelBuilder::hasResponseParameter).orElse(false);
        // No JAX-RS equivalent of Spring's ProblemDetail/@ResponseStatus exists, so those two fields are
        // always false/empty here; RAPI-ERR-003/RAPI-ERR-006 are Spring-only rules (see SPRING_ONLY_RULE_IDS
        // in RestApiScanner) and never evaluate JAX-RS-derived exception handlers.
        boolean catchesExceptionOrThrowable =
                "java.lang.Exception".equals(exceptionType) || "java.lang.Throwable".equals(exceptionType);
        exceptionHandlers.add(new ExceptionHandlerModel(
                type.getName(),
                methodName,
                bodyType,
                false,
                returnsResponseEntity,
                returnsVoid,
                false,
                "",
                catchesExceptionOrThrowable,
                hasResponseParam,
                true));
    }

    private void collectExceptionHandlers(JavaClass type) {
        boolean isAdvice = annotated(type, Types.CONTROLLER_ADVICE)
                || annotated(type, Types.REST_CONTROLLER_ADVICE)
                || metaAnnotated(type, Types.CONTROLLER_ADVICE);
        boolean rendersBody = annotated(type, Types.REST_CONTROLLER)
                || metaAnnotated(type, Types.REST_CONTROLLER)
                || annotated(type, Types.REST_CONTROLLER_ADVICE)
                || metaAnnotated(type, Types.REST_CONTROLLER_ADVICE)
                || annotated(type, Types.RESPONSE_BODY)
                || metaAnnotated(type, Types.RESPONSE_BODY);
        boolean foundAdviceHandler = false;
        for (JavaMethod method : type.getMethods()) {
            try {
                if (!method.isAnnotatedWith(Types.EXCEPTION_HANDLER)) {
                    continue;
                }
                JavaType returnType = method.getReturnType();
                String returnTypeName = returnType.toErasure().getName();
                String bodyType = resolveBodyTypeName(returnType);
                boolean problemType = Types.PROBLEM_DETAIL.equals(bodyType) || Types.ERROR_RESPONSE.equals(bodyType);
                boolean returnsResponseEntity =
                        Types.RESPONSE_ENTITY.equals(returnTypeName) || Types.HTTP_ENTITY.equals(returnTypeName);
                boolean returnsVoid = "void".equals(returnTypeName)
                        || "java.lang.Void".equals(returnTypeName)
                        || "java.lang.Void".equals(bodyType);
                boolean hasResponseStatus =
                        method.isAnnotatedWith(Types.RESPONSE_STATUS) || type.isAnnotatedWith(Types.RESPONSE_STATUS);
                String handlerResponseStatusValue = responseStatusValue(method, type);
                boolean catchesExceptionOrThrowable = catchesBroadException(method);
                boolean hasResponseParam = hasResponseParameter(method);
                boolean methodRendersBody =
                        rendersBody || method.isAnnotatedWith(Types.RESPONSE_BODY) || returnsResponseEntity;
                exceptionHandlers.add(new ExceptionHandlerModel(
                        type.getName(),
                        method.getName(),
                        bodyType,
                        problemType,
                        returnsResponseEntity,
                        returnsVoid,
                        hasResponseStatus,
                        handlerResponseStatusValue,
                        catchesExceptionOrThrowable,
                        hasResponseParam,
                        methodRendersBody));
                if (isAdvice) {
                    foundAdviceHandler = true;
                }
            } catch (RuntimeException | LinkageError ex) {
                // Ignore an unreadable exception-handler method.
            }
        }
        if (foundAdviceHandler || (isAdvice && extendsClass(type, Types.RESPONSE_ENTITY_EXCEPTION_HANDLER))) {
            hasExceptionHandling = true;
        }
    }

    private HandlerMethodModel toHandler(
            JavaClass type,
            JavaMethod method,
            boolean restController,
            boolean classValidated,
            boolean classResponseBody,
            boolean classHidden,
            List<String> typeLevelPaths,
            List<String> typeLevelProduces,
            List<String> typeLevelConsumes,
            List<String> typeLevelParams,
            List<String> typeLevelHeaders,
            List<String> typeLevelMethods,
            String typeLevelVersion) {

        Set<String> httpMethods = new LinkedHashSet<>();
        List<String> mappingPaths = new ArrayList<>();
        List<String> produces = new ArrayList<>();
        List<String> consumes = new ArrayList<>();
        boolean isHandler = false;

        isHandler |=
                readSpecificMapping(method, Types.GET_MAPPING, "GET", httpMethods, mappingPaths, produces, consumes);
        isHandler |=
                readSpecificMapping(method, Types.POST_MAPPING, "POST", httpMethods, mappingPaths, produces, consumes);
        isHandler |=
                readSpecificMapping(method, Types.PUT_MAPPING, "PUT", httpMethods, mappingPaths, produces, consumes);
        isHandler |= readSpecificMapping(
                method, Types.DELETE_MAPPING, "DELETE", httpMethods, mappingPaths, produces, consumes);
        isHandler |= readSpecificMapping(
                method, Types.PATCH_MAPPING, "PATCH", httpMethods, mappingPaths, produces, consumes);

        Optional<JavaAnnotation<JavaMethod>> requestMapping = method.tryGetAnnotationOfType(Types.REQUEST_MAPPING);
        if (requestMapping.isPresent()) {
            isHandler = true;
            JavaAnnotation<JavaMethod> ann = requestMapping.get();
            mappingPaths.addAll(stringValues(ann, "value", "path"));
            produces.addAll(stringValues(ann, "produces"));
            consumes.addAll(stringValues(ann, "consumes"));
            httpMethods.addAll(enumValues(ann, "method"));
        }

        if (!isHandler) {
            return null;
        }

        // Inherit the class-level HTTP method constraint when no method-level annotation sets one.
        if (httpMethods.isEmpty() && !typeLevelMethods.isEmpty()) {
            httpMethods.addAll(typeLevelMethods);
        }

        boolean explicitHttpMethod = !httpMethods.isEmpty();
        List<String> effectivePaths = effectivePaths(typeLevelPaths, mappingPaths);

        JavaType returnType = method.getReturnType();
        JavaClass rawReturn = returnType.toErasure();
        String returnTypeName = rawReturn.getName();
        boolean returnsResponseEntity =
                Types.RESPONSE_ENTITY.equals(returnTypeName) || Types.HTTP_ENTITY.equals(returnTypeName);
        boolean returnsVoid = "void".equals(returnTypeName) || "java.lang.Void".equals(returnTypeName);

        JavaType bodyType = unwrapWrappers(returnType);
        JavaClass bodyErasure = bodyType.toErasure();
        boolean returnsCollection = isCollection(bodyErasure);
        boolean returnsPageOrSlice =
                Types.PAGE.equals(bodyErasure.getName()) || Types.SLICE.equals(bodyErasure.getName());
        if (returnsCollection) {
            JavaType element = firstTypeArgument(bodyType);
            if (element != null) {
                bodyType = element;
                bodyErasure = element.toErasure();
            }
        } else if (returnsPageOrSlice) {
            JavaType element = firstTypeArgument(bodyType);
            if (element != null) {
                bodyType = element;
                bodyErasure = element.toErasure();
            }
        }
        String bodyTypeName = bodyErasure.getName();
        boolean bodyIsEntity = safeAnnotated(bodyErasure, Types.ENTITY);
        boolean bodyIsUntyped = UNTYPED_TYPES.contains(bodyTypeName);
        boolean bodyIsScalar = SCALAR_TYPES.contains(bodyTypeName) || isPrimitive(bodyErasure);
        boolean bodyIsRecord = safeIsRecord(bodyErasure);
        boolean bodyExposesSetters = exposesPublicSetters(bodyErasure, bodyIsRecord, bodyIsEntity);
        boolean bodyHasLegacyDateField = hasLegacyDateField(bodyErasure);

        boolean hasRequestBody = false;
        boolean requestBodyValidated = false;
        boolean requestBodyIsEntity = false;
        boolean requestBodyIsSimple = false;
        boolean hasConstrainedSimpleParam = false;
        boolean hasPageable = false;
        boolean hasUnboundedPrimitiveRequestParam = false;
        boolean hasUnboundedMapRequestParam = false;
        boolean hasExplicitPageParam = false;
        boolean hasIdempotencyKeyHeader = false;
        List<String> pathVariableNames = new ArrayList<>();
        List<String> pageQueryParamNames = new ArrayList<>();

        for (JavaParameter parameter : method.getParameters()) {
            try {
                String paramTypeName = parameter.getRawType().getName();
                if (parameter.isAnnotatedWith(Types.REQUEST_BODY)) {
                    hasRequestBody = true;
                    // Field constraints on the DTO only cascade with @Valid/@Validated; a bare
                    // constraint annotation (e.g. @NotNull) on the parameter validates only the
                    // body reference itself, so it does not count as request-body validation.
                    requestBodyValidated |=
                            parameter.isAnnotatedWith(Types.VALID) || parameter.isAnnotatedWith(Types.VALIDATED);
                    requestBodyIsEntity |= safeAnnotated(parameter.getRawType(), Types.ENTITY);
                    requestBodyIsSimple |= isSimpleBodyType(parameter.getRawType());
                }
                boolean simpleBinding = parameter.isAnnotatedWith(Types.PATH_VARIABLE)
                        || parameter.isAnnotatedWith(Types.REQUEST_PARAM);
                if (simpleBinding && hasConstraintAnnotation(parameter)) {
                    hasConstrainedSimpleParam = true;
                }
                if (parameter.isAnnotatedWith(Types.REQUEST_PARAM)) {
                    if (isPrimitive(parameter.getRawType()) && isOptionalRequestParam(parameter)) {
                        hasUnboundedPrimitiveRequestParam = true;
                    }
                    String paramTypeName2 = parameter.getRawType().getName();
                    if ("java.util.Map".equals(paramTypeName2) || Types.MULTI_VALUE_MAP.equals(paramTypeName2)) {
                        hasUnboundedMapRequestParam = true;
                    }
                    String explicitName = explicitBindingName(parameter, Types.REQUEST_PARAM);
                    if (explicitName != null) {
                        pageQueryParamNames.add(explicitName);
                        if (PAGE_PARAM_NAMES.contains(explicitName.toLowerCase(Locale.ROOT))) {
                            hasExplicitPageParam = true;
                        }
                    }
                }
                if (parameter.isAnnotatedWith(Types.PATH_VARIABLE)) {
                    String explicitName = explicitBindingName(parameter, Types.PATH_VARIABLE);
                    if (explicitName != null) {
                        pathVariableNames.add(explicitName);
                    }
                }
                if (parameter.isAnnotatedWith(Types.REQUEST_HEADER)) {
                    String headerName = explicitBindingName(parameter, Types.REQUEST_HEADER);
                    if (headerName != null && IDEMPOTENCY_KEY_HEADER_NAME.equalsIgnoreCase(headerName)) {
                        hasIdempotencyKeyHeader = true;
                    }
                }
                if (Types.PAGEABLE.equals(paramTypeName)) {
                    hasPageable = true;
                }
            } catch (RuntimeException | LinkageError ex) {
                // Skip a parameter that cannot be introspected.
            }
        }

        boolean hasResponseStatus =
                method.isAnnotatedWith(Types.RESPONSE_STATUS) || type.isAnnotatedWith(Types.RESPONSE_STATUS);
        boolean methodHasResponseStatus = method.isAnnotatedWith(Types.RESPONSE_STATUS);
        String responseStatusValue = responseStatusValue(method, type);
        boolean declaresBroadThrows = declaresBroadThrows(method);
        boolean hasOperation = hasOperationAnnotation(method);
        boolean stateChanging = startsWithWord(method.getName(), STATE_CHANGING_PREFIXES);
        String lowerName = method.getName().toLowerCase(Locale.ROOT);
        boolean findAll = lowerName.startsWith("findall")
                || lowerName.startsWith("getall")
                || lowerName.startsWith("listall")
                || lowerName.equals("list")
                || lowerName.startsWith("fetchall")
                || lowerName.startsWith("readall");

        boolean serializesBody = restController
                || classResponseBody
                || method.isAnnotatedWith(Types.RESPONSE_BODY)
                || returnsResponseEntity;
        String mappingVersion = mappingString(method, "version");
        if (mappingVersion.isBlank()) {
            mappingVersion = typeLevelVersion;
        }
        List<String> effectiveProduces = produces.isEmpty() ? List.copyOf(typeLevelProduces) : dedupe(produces);
        List<String> effectiveConsumes = consumes.isEmpty() ? List.copyOf(typeLevelConsumes) : dedupe(consumes);
        List<String> params = union(typeLevelParams, mappingStrings(method, "params"));
        List<String> headers = union(typeLevelHeaders, mappingStrings(method, "headers"));
        boolean hasTag = hasTagAnnotation(method);
        boolean hidden = classHidden || hasHiddenAnnotation(method) || operationHidden(method);
        boolean handlerHasResponseParam = hasResponseParameter(method);
        boolean isDeprecated = type.isAnnotatedWith(Types.DEPRECATED) || method.isAnnotatedWith(Types.DEPRECATED);
        boolean operationMarkedDeprecated = operationMarkedDeprecated(method);
        String paginationParamFamily = paginationFamily(hasPageable, pageQueryParamNames);

        return new HandlerMethodModel(
                type.getName(),
                safeSimpleName(type),
                method.getName(),
                restController,
                classValidated,
                List.copyOf(httpMethods),
                explicitHttpMethod,
                List.copyOf(mappingPaths),
                effectivePaths,
                dedupe(produces),
                dedupe(consumes),
                returnTypeName,
                simpleName(returnTypeName),
                returnsVoid,
                returnsResponseEntity,
                returnsCollection,
                returnsPageOrSlice,
                bodyTypeName,
                bodyIsEntity,
                bodyIsUntyped,
                bodyIsScalar,
                bodyExposesSetters,
                bodyIsRecord,
                bodyHasLegacyDateField,
                hasRequestBody,
                requestBodyValidated,
                requestBodyIsEntity,
                hasConstrainedSimpleParam,
                hasPageable,
                hasUnboundedPrimitiveRequestParam,
                hasUnboundedMapRequestParam,
                hasExplicitPageParam,
                hasResponseStatus,
                methodHasResponseStatus,
                responseStatusValue,
                declaresBroadThrows,
                hasOperation,
                stateChanging,
                findAll,
                serializesBody,
                mappingVersion,
                effectiveProduces,
                effectiveConsumes,
                params,
                headers,
                List.copyOf(pathVariableNames),
                requestBodyIsSimple,
                hasTag,
                hidden,
                handlerHasResponseParam,
                paginationParamFamily,
                hasIdempotencyKeyHeader,
                isDeprecated,
                operationMarkedDeprecated);
    }

    private static boolean readSpecificMapping(
            JavaMethod method,
            String annotationName,
            String httpMethod,
            Set<String> httpMethods,
            List<String> mappingPaths,
            List<String> produces,
            List<String> consumes) {
        Optional<JavaAnnotation<JavaMethod>> annotation = method.tryGetAnnotationOfType(annotationName);
        if (annotation.isEmpty()) {
            return false;
        }
        JavaAnnotation<JavaMethod> ann = annotation.get();
        httpMethods.add(httpMethod);
        mappingPaths.addAll(stringValues(ann, "value", "path"));
        produces.addAll(stringValues(ann, "produces"));
        consumes.addAll(stringValues(ann, "consumes"));
        return true;
    }

    private static boolean isController(JavaClass type) {
        return annotated(type, Types.REST_CONTROLLER)
                || annotated(type, Types.CONTROLLER)
                || metaAnnotated(type, Types.REST_CONTROLLER)
                || metaAnnotated(type, Types.CONTROLLER);
    }

    private static List<String> mappingPaths(JavaClass type, String annotationName) {
        Optional<? extends JavaAnnotation<?>> annotation = type.tryGetAnnotationOfType(annotationName);
        if (annotation.isEmpty()) {
            return List.of();
        }
        return stringValues(annotation.get(), "value", "path");
    }

    private static List<String> mappingAttribute(JavaClass type, String annotationName, String key) {
        Optional<? extends JavaAnnotation<?>> annotation = type.tryGetAnnotationOfType(annotationName);
        if (annotation.isEmpty()) {
            return List.of();
        }
        return stringValues(annotation.get(), key);
    }

    /** Reads a string-array attribute from a method-level annotation (e.g. JAX-RS {@code @Produces}). */
    private static List<String> methodStringAttr(JavaMethod method, String annotationName, String key) {
        Optional<JavaAnnotation<JavaMethod>> annotation = method.tryGetAnnotationOfType(annotationName);
        if (annotation.isEmpty()) {
            return List.of();
        }
        return stringValues(annotation.get(), key);
    }

    /** Collects a string attribute from whichever mapping annotation(s) a handler method declares. */
    private static List<String> mappingStrings(JavaMethod method, String key) {
        List<String> values = new ArrayList<>();
        for (String annotationName : MAPPING_ANNOTATIONS) {
            method.tryGetAnnotationOfType(annotationName).ifPresent(ann -> values.addAll(stringValues(ann, key)));
        }
        return values;
    }

    private static String mappingString(JavaMethod method, String key) {
        for (String value : mappingStrings(method, key)) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static List<String> union(List<String> first, List<String> second) {
        List<String> values = new ArrayList<>(first);
        values.addAll(second);
        return dedupe(values);
    }

    private static List<String> stringValues(JavaAnnotation<?> annotation, String... keys) {
        List<String> values = new ArrayList<>();
        for (String key : keys) {
            annotation.get(key).ifPresent(value -> addStrings(values, value));
        }
        return values;
    }

    private static List<String> enumValues(JavaAnnotation<?> annotation, String key) {
        List<String> values = new ArrayList<>();
        annotation.get(key).ifPresent(value -> addStrings(values, value));
        return values;
    }

    private static void addStrings(List<String> target, Object value) {
        if (value instanceof Object[] array) {
            for (Object element : array) {
                addString(target, element);
            }
        } else {
            addString(target, value);
        }
    }

    private static void addString(List<String> target, Object value) {
        if (value instanceof JavaEnumConstant enumConstant) {
            target.add(enumConstant.name());
        } else if (value instanceof String text && !text.isBlank()) {
            target.add(text);
        }
    }

    private static List<String> effectivePaths(List<String> typeLevelPaths, List<String> mappingPaths) {
        List<String> roots = typeLevelPaths.isEmpty() ? List.of("") : typeLevelPaths;
        List<String> leaves = mappingPaths.isEmpty() ? List.of("") : mappingPaths;
        List<String> result = new ArrayList<>();
        for (String root : roots) {
            for (String leaf : leaves) {
                result.add(normalizePath(root + "/" + leaf));
            }
        }
        return dedupe(result);
    }

    static String normalizePath(String raw) {
        String collapsed = raw.replaceAll("/{2,}", "/");
        if (!collapsed.startsWith("/")) {
            collapsed = "/" + collapsed;
        }
        if (collapsed.length() > 1 && collapsed.endsWith("/")) {
            collapsed = collapsed.substring(0, collapsed.length() - 1);
        }
        return collapsed;
    }

    private JavaType unwrapWrappers(JavaType type) {
        JavaType current = type;
        for (int depth = 0; depth < 4; depth++) {
            String name = current.toErasure().getName();
            if (!WRAPPER_TYPES.contains(name)) {
                return current;
            }
            JavaType argument = firstTypeArgument(current);
            if (argument == null) {
                return current;
            }
            current = argument;
        }
        return current;
    }

    private static JavaType firstTypeArgument(JavaType type) {
        if (type instanceof JavaParameterizedType parameterized) {
            List<JavaType> arguments = parameterized.getActualTypeArguments();
            if (!arguments.isEmpty()) {
                return arguments.get(0);
            }
        }
        return null;
    }

    private String resolveBodyTypeName(JavaType returnType) {
        JavaType body = unwrapWrappers(returnType);
        JavaClass erasure = body.toErasure();
        if (isCollection(erasure) || Types.PAGE.equals(erasure.getName()) || Types.SLICE.equals(erasure.getName())) {
            JavaType element = firstTypeArgument(body);
            if (element != null) {
                return element.toErasure().getName();
            }
        }
        return erasure.getName();
    }

    private static boolean isCollection(JavaClass type) {
        try {
            if (type.isArray()) {
                return true;
            }
        } catch (RuntimeException | LinkageError ex) {
            // fall through to name match
        }
        return COLLECTION_TYPES.contains(type.getName());
    }

    private static boolean isPrimitive(JavaClass type) {
        try {
            return type.isPrimitive();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static boolean hasConstraintAnnotation(JavaParameter parameter) {
        for (JavaAnnotation<JavaParameter> annotation : parameter.getAnnotations()) {
            String name = annotation.getRawType().getName();
            if (name.startsWith(Types.CONSTRAINT_PACKAGE) || Types.VALID.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOptionalRequestParam(JavaParameter parameter) {
        Optional<JavaAnnotation<JavaParameter>> annotation = parameter.tryGetAnnotationOfType(Types.REQUEST_PARAM);
        if (annotation.isEmpty()) {
            return false;
        }
        JavaAnnotation<JavaParameter> ann = annotation.get();
        boolean requiredFalse = ann.get("required").map(Boolean.FALSE::equals).orElse(false);
        boolean hasDefault = ann.get("defaultValue")
                .map(value -> value instanceof String text && !text.isBlank() && text.indexOf('\uE000') < 0)
                .orElse(false);
        return requiredFalse && !hasDefault;
    }

    private static String explicitBindingName(JavaParameter parameter, String annotationName) {
        Optional<JavaAnnotation<JavaParameter>> annotation = parameter.tryGetAnnotationOfType(annotationName);
        if (annotation.isEmpty()) {
            return null;
        }
        JavaAnnotation<JavaParameter> ann = annotation.get();
        for (String key : List.of("value", "name")) {
            Optional<Object> raw = ann.get(key);
            if (raw.isPresent() && raw.get() instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private static boolean isSimpleBodyType(JavaClass type) {
        try {
            if (type.isArray() || type.isPrimitive()) {
                return true;
            }
        } catch (RuntimeException | LinkageError ex) {
            // fall through to name match
        }
        String name = type.getName();
        return SIMPLE_BODY_TYPES.contains(name) || SCALAR_TYPES.contains(name) || UNTYPED_TYPES.contains(name);
    }

    /**
     * True when a method carries either the Swagger ({@code io.swagger.v3.oas.annotations}) or the
     * MicroProfile OpenAPI ({@code org.eclipse.microprofile.openapi.annotations}) form of {@code @Tag}.
     * SmallRye OpenAPI (Quarkus' {@code quarkus-smallrye-openapi}) recognises both annotation families
     * equally: https://quarkus.io/guides/openapi-swaggerui
     */
    private static boolean hasTagAnnotation(JavaMethod method) {
        return method.isAnnotatedWith(Types.TAG) || method.isAnnotatedWith(Types.MP_TAG);
    }

    private static boolean hasTagAnnotation(JavaClass type) {
        return annotated(type, Types.TAG) || annotated(type, Types.MP_TAG);
    }

    /**
     * Swagger's standalone {@code @Hidden} annotation suppresses OpenAPI documentation. MicroProfile
     * OpenAPI has no standalone equivalent class; its analogous signal is the {@code @Operation(hidden =
     * true)} attribute, already covered by {@link #operationHidden(JavaMethod)}.
     */
    private static boolean hasHiddenAnnotation(JavaMethod method) {
        return method.isAnnotatedWith(Types.HIDDEN);
    }

    private static boolean hasHiddenAnnotation(JavaClass type) {
        return annotated(type, Types.HIDDEN);
    }

    /** Swagger/MicroProfile OpenAPI {@code @Operation}, either of which documents an endpoint. */
    private static boolean hasOperationAnnotation(JavaMethod method) {
        return method.isAnnotatedWith(Types.OPERATION) || method.isAnnotatedWith(Types.MP_OPERATION);
    }

    private static boolean operationHidden(JavaMethod method) {
        return operationBooleanAttribute(method, "hidden");
    }

    /** True when {@code @Operation(deprecated = true)} is present (Swagger or MicroProfile OpenAPI). */
    private static boolean operationMarkedDeprecated(JavaMethod method) {
        return operationBooleanAttribute(method, "deprecated");
    }

    private static boolean operationBooleanAttribute(JavaMethod method, String attributeName) {
        return operationAttributeTrue(method, Types.OPERATION, attributeName)
                || operationAttributeTrue(method, Types.MP_OPERATION, attributeName);
    }

    private static boolean operationAttributeTrue(JavaMethod method, String annotationName, String attributeName) {
        Optional<JavaAnnotation<JavaMethod>> annotation = method.tryGetAnnotationOfType(annotationName);
        return annotation.isPresent()
                && annotation.get().get(attributeName).map(Boolean.TRUE::equals).orElse(false);
    }

    /**
     * Classifies a set of pagination query-parameter names (plus Spring Data {@code Pageable} binding)
     * into a "family" used by RAPI-PAGE-003 to flag an application that mixes more than one pagination
     * vocabulary. Returns {@code ""} when no recognised pagination parameter is present.
     */
    private static String paginationFamily(boolean hasPageable, List<String> queryParamNames) {
        if (hasPageable) {
            return "PAGE_SIZE";
        }
        for (String name : queryParamNames) {
            if (PAGE_SIZE_PARAM_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                return "PAGE_SIZE";
            }
        }
        for (String name : queryParamNames) {
            if (OFFSET_LIMIT_PARAM_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                return "OFFSET_LIMIT";
            }
        }
        for (String name : queryParamNames) {
            if (CURSOR_PARAM_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                return "CURSOR";
            }
        }
        return "";
    }

    private static boolean hasResponseParameter(JavaMethod method) {
        for (JavaParameter parameter : method.getParameters()) {
            try {
                String name = parameter.getRawType().getName();
                if (Types.HTTP_SERVLET_RESPONSE.equals(name) || Types.SERVER_HTTP_RESPONSE.equals(name)) {
                    return true;
                }
            } catch (RuntimeException | LinkageError ex) {
                // Skip a parameter that cannot be introspected.
            }
        }
        return false;
    }

    private static boolean extendsClass(JavaClass type, String superName) {
        JavaClass current = type;
        for (int depth = 0; depth < 10; depth++) {
            Optional<JavaClass> superclass;
            try {
                superclass = current.getRawSuperclass();
            } catch (RuntimeException | LinkageError ex) {
                return false;
            }
            if (superclass.isEmpty()) {
                return false;
            }
            current = superclass.get();
            if (superName.equals(current.getName())) {
                return true;
            }
            if ("java.lang.Object".equals(current.getName())) {
                return false;
            }
        }
        return false;
    }

    private static boolean exposesPublicSetters(JavaClass type, boolean isRecord, boolean isEntity) {
        if (isRecord || isEntity) {
            return false;
        }
        String name = type.getName();
        if (UNTYPED_TYPES.contains(name)
                || SCALAR_TYPES.contains(name)
                || isPrimitive(type)
                || name.startsWith("java.")) {
            return false;
        }
        Set<JavaMethod> methods;
        try {
            methods = type.getMethods();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
        if (methods.isEmpty()) {
            return false;
        }
        for (JavaMethod method : methods) {
            String methodName = method.getName();
            if (methodName.length() > 3
                    && methodName.startsWith("set")
                    && Character.isUpperCase(methodName.charAt(3))
                    && method.getRawParameterTypes().size() == 1
                    && method.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.PUBLIC)) {
                return true;
            }
        }
        return false;
    }

    private static String responseStatusValue(JavaMethod method, JavaClass type) {
        String value = responseStatusValue(method.tryGetAnnotationOfType(Types.RESPONSE_STATUS));
        if (!value.isEmpty()) {
            return value;
        }
        return responseStatusValue(type.tryGetAnnotationOfType(Types.RESPONSE_STATUS));
    }

    private static String responseStatusValue(Optional<? extends JavaAnnotation<?>> annotation) {
        if (annotation.isEmpty()) {
            return "";
        }
        JavaAnnotation<?> ann = annotation.get();
        // @ResponseStatus declares "value" and "code" as @AliasFor each other, both defaulting to
        // INTERNAL_SERVER_ERROR. ArchUnit reads raw attributes (no @AliasFor resolution), so whichever
        // attribute the developer set holds the real status while the other stays at the default. Prefer
        // the non-default attribute; fall back to the default only when neither was overridden.
        String fallback = "";
        for (String key : List.of("value", "code")) {
            Optional<Object> raw = ann.get(key);
            if (raw.isPresent() && raw.get() instanceof JavaEnumConstant enumConstant) {
                String name = enumConstant.name();
                if (!"INTERNAL_SERVER_ERROR".equals(name)) {
                    return name;
                }
                fallback = name;
            }
        }
        return fallback;
    }

    private static boolean declaresBroadThrows(JavaMethod method) {
        for (JavaClass exceptionType : method.getExceptionTypes()) {
            String name = exceptionType.getName();
            if ("java.lang.Exception".equals(name) || "java.lang.Throwable".equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithWord(String name, Set<String> prefixes) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String prefix : prefixes) {
            if (lower.equals(prefix)) {
                return true;
            }
            if (lower.startsWith(prefix)
                    && name.length() > prefix.length()
                    && Character.isUpperCase(name.charAt(prefix.length()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean annotated(JavaClass type, String annotationName) {
        try {
            return type.isAnnotatedWith(annotationName);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static boolean metaAnnotated(JavaClass type, String annotationName) {
        try {
            return type.isMetaAnnotatedWith(annotationName);
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static boolean safeAnnotated(JavaClass type, String annotationName) {
        return annotated(type, annotationName);
    }

    private static boolean safeIsRecord(JavaClass type) {
        try {
            return type.isRecord();
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
    }

    private static String safeSimpleName(JavaClass type) {
        try {
            String simple = type.getSimpleName();
            return simple.isEmpty() ? type.getName() : simple;
        } catch (RuntimeException | LinkageError ex) {
            return type.getName();
        }
    }

    private static String simpleName(String fullName) {
        int lastDot = fullName.lastIndexOf('.');
        return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
    }

    private static List<String> dedupe(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    /** Reads enum-valued attributes (e.g. {@code method}) from a type-level annotation. */
    private static List<String> mappingEnumAttribute(JavaClass type, String annotationName, String key) {
        Optional<? extends JavaAnnotation<?>> annotation = type.tryGetAnnotationOfType(annotationName);
        if (annotation.isEmpty()) {
            return List.of();
        }
        return enumValues(annotation.get(), key);
    }

    /** Reads the first non-blank string attribute from a type-level annotation. */
    private static String mappingStringAttribute(JavaClass type, String annotationName, String key) {
        for (String value : mappingAttribute(type, annotationName, key)) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /**
     * True when the response body type exposes any field typed {@code java.util.Date} or
     * {@code java.util.Calendar}.
     */
    private static boolean hasLegacyDateField(JavaClass type) {
        String name = type.getName();
        if (name.startsWith("java.")
                || UNTYPED_TYPES.contains(name)
                || SCALAR_TYPES.contains(name)
                || isPrimitive(type)) {
            return false;
        }
        try {
            for (JavaField field : type.getFields()) {
                String fieldTypeName = field.getRawType().getName();
                if (Types.DATE.equals(fieldTypeName) || Types.CALENDAR.equals(fieldTypeName)) {
                    return true;
                }
            }
        } catch (RuntimeException | LinkageError ex) {
            return false;
        }
        return false;
    }

    /**
     * True when the method's {@code @ExceptionHandler} annotation declares {@code Exception} or
     * {@code Throwable} in its {@code value} array.
     */
    private static boolean catchesBroadException(JavaMethod method) {
        Optional<JavaAnnotation<JavaMethod>> annotation = method.tryGetAnnotationOfType(Types.EXCEPTION_HANDLER);
        if (annotation.isEmpty()) {
            return false;
        }
        Optional<Object> value = annotation.get().get("value");
        if (value.isEmpty()) {
            return false;
        }
        return containsBroadExceptionType(value.get());
    }

    private static boolean containsBroadExceptionType(Object value) {
        if (value instanceof Object[] array) {
            for (Object element : array) {
                if (isBroadExceptionClass(element)) {
                    return true;
                }
            }
        } else {
            return isBroadExceptionClass(value);
        }
        return false;
    }

    private static boolean isBroadExceptionClass(Object element) {
        if (element instanceof JavaClass jc) {
            String name = jc.getName();
            return "java.lang.Exception".equals(name) || "java.lang.Throwable".equals(name);
        }
        return false;
    }

    /**
     * Records exception classes annotated with {@code @ResponseStatus} for RAPI-ERR-006 detection.
     * Called for every imported class (not just controllers).
     */
    private void scanResponseStatusException(JavaClass type) {
        try {
            if (!type.isAnnotatedWith(Types.RESPONSE_STATUS)) {
                return;
            }
            if (extendsClass(type, "java.lang.Throwable")) {
                responseStatusExceptionClasses.add(type.getName());
            }
        } catch (RuntimeException | LinkageError ex) {
            // Skip unresolvable class.
        }
    }
}
