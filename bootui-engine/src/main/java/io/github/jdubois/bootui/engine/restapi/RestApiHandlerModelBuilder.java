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
import io.github.jdubois.bootui.engine.archunit.KotlinBytecode;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ControllerModel;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ExceptionHandlerModel;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.HandlerMethodModel;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ThrownExceptionModel;
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
            Types.QUARKUS_REST_RESPONSE,
            // Kotlin coroutines' deferred value, the coroutine analogue of CompletableFuture.
            "kotlinx.coroutines.Deferred");

    private static final Set<String> COLLECTION_TYPES = Set.of(
            "java.util.List",
            "java.util.Collection",
            "java.util.Set",
            "java.lang.Iterable",
            "java.util.stream.Stream",
            "reactor.core.publisher.Flux",
            // Quarkus/SmallRye Mutiny's async stream type: https://smallrye.io/smallrye-mutiny/latest/concepts/multi/
            "io.smallrye.mutiny.Multi",
            // Kotlin coroutines' cold stream, which Spring adapts exactly like a Flux.
            "kotlinx.coroutines.flow.Flow");

    private static final Set<String> UNTYPED_TYPES = Set.of(
            "java.lang.Object",
            "java.util.Map",
            "java.util.HashMap",
            "com.fasterxml.jackson.databind.JsonNode",
            // Spring Boot 4 ships Jackson 3 under the new tools.jackson.* package/artifact; Quarkus still ships
            // Jackson 2 (com.fasterxml.jackson.*, above). See the Jackson 3 migration guide:
            // https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md
            "tools.jackson.databind.JsonNode");

    private static final Set<String> STREAM_TYPES = Set.of(
            "java.util.stream.Stream", "reactor.core.publisher.Flux",
            "io.smallrye.mutiny.Multi", "kotlinx.coroutines.flow.Flow");

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
    private static final Set<String> NON_MUTATING_METHOD_PREFIXES = Set.of("postprocess", "putaside", "patchversion");

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

    /** Header name (case-insensitive) recognised by the expired IETF HTTPAPI Idempotency-Key draft. */
    private static final String IDEMPOTENCY_KEY_HEADER_NAME = "idempotency-key";

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

    private static final Set<String> RESPONSE_PARAMETER_TYPES = Set.of(
            Types.HTTP_SERVLET_RESPONSE,
            "jakarta.servlet.ServletResponse",
            "java.io.OutputStream",
            "java.io.Writer",
            Types.REACTIVE_SERVER_HTTP_RESPONSE,
            Types.SERVER_WEB_EXCHANGE);

    private final List<ControllerModel> controllers = new ArrayList<>();
    private final List<HandlerMethodModel> handlers = new ArrayList<>();
    private final List<ExceptionHandlerModel> exceptionHandlers = new ArrayList<>();
    private final List<String> responseStatusExceptionClasses = new ArrayList<>();
    private final List<ThrownExceptionModel> thrownExceptions = new ArrayList<>();
    private boolean hasExceptionHandling;
    private int springControllerCount;
    private int jaxRsResourceCount;
    private boolean incomplete;

    private RestApiHandlerModelBuilder() {}

    static RestApiHandlerModelBuilder build(JavaClasses classes) {
        RestApiHandlerModelBuilder builder = new RestApiHandlerModelBuilder();
        for (JavaClass type : classes) {
            try {
                builder.inspect(type);
            } catch (RuntimeException | LinkageError ex) {
                builder.incomplete = true;
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

    List<ThrownExceptionModel> thrownExceptions() {
        return List.copyOf(thrownExceptions);
    }

    boolean hasExceptionHandling() {
        return hasExceptionHandling;
    }

    boolean incomplete() {
        return incomplete;
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
        boolean classValidated = annotated(type, Types.VALIDATED) || metaAnnotated(type, Types.VALIDATED);
        boolean hasTag = hasTagAnnotation(type);
        boolean hidden = hasHiddenAnnotation(type);
        boolean classResponseBody = annotated(type, Types.RESPONSE_BODY) || metaAnnotated(type, Types.RESPONSE_BODY);
        Optional<? extends JavaAnnotation<?>> typeLevelMapping =
                typeHierarchyMappingAnnotation(type, Types.REQUEST_MAPPING);
        List<String> typeLevelPaths = typeLevelMapping
                .map(annotation -> stringValues(annotation, "value", "path"))
                .orElse(List.of());
        List<String> typeLevelProduces = typeLevelMapping
                .map(annotation -> stringValues(annotation, "produces"))
                .orElse(List.of());
        List<String> typeLevelConsumes = typeLevelMapping
                .map(annotation -> stringValues(annotation, "consumes"))
                .orElse(List.of());
        List<String> typeLevelParams = typeLevelMapping
                .map(annotation -> stringValues(annotation, "params"))
                .orElse(List.of());
        List<String> typeLevelHeaders = typeLevelMapping
                .map(annotation -> stringValues(annotation, "headers"))
                .orElse(List.of());
        List<String> typeLevelMethods = typeLevelMapping
                .map(annotation -> enumValues(annotation, "method"))
                .orElse(List.of());
        String typeLevelVersion = typeLevelMapping
                .map(annotation -> annotationString(annotation, "version"))
                .orElse("");

        int handlerCount = 0;
        for (JavaMethod method : KotlinBytecode.declaredMethods(type)) {
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
                incomplete = true;
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
                safeIsInterface(type),
                handlerCount,
                RestApiModel.Framework.SPRING));
    }

    /** A class is a JAX-RS resource when it carries {@code @Path} or any JAX-RS HTTP-method method. */
    private static boolean isJaxRsResource(JavaClass type) {
        if (annotated(type, Types.REGISTER_REST_CLIENT)) {
            return false;
        }
        if (annotated(type, Types.JAXRS_PATH)) {
            return true;
        }
        for (JavaMethod method : KotlinBytecode.declaredMethods(type)) {
            if (!jaxRsHttpMethods(method).isEmpty()) {
                return true;
            }
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
        for (JavaMethod method : KotlinBytecode.declaredMethods(type)) {
            try {
                HandlerMethodModel model = toJaxRsHandler(
                        type, method, classValidated, hidden, typeLevelPaths, typeLevelProduces, typeLevelConsumes);
                if (model != null) {
                    handlers.add(model);
                    handlerCount++;
                }
            } catch (RuntimeException | LinkageError ex) {
                incomplete = true;
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
                safeIsInterface(type),
                handlerCount,
                RestApiModel.Framework.JAX_RS));
    }

    private HandlerMethodModel toJaxRsHandler(
            JavaClass type,
            JavaMethod method,
            boolean classValidated,
            boolean classHidden,
            List<String> typeLevelPaths,
            List<String> typeLevelProduces,
            List<String> typeLevelConsumes) {

        Set<String> httpMethods = jaxRsHttpMethods(method);
        if (httpMethods.isEmpty()) {
            // A @Path-only method with no HTTP verb is a sub-resource locator, not a request handler.
            return null;
        }

        List<String> mappingPaths = method.isAnnotatedWith(Types.JAXRS_PATH)
                ? stringValues(method.getAnnotationOfType(Types.JAXRS_PATH), "value")
                : List.of();
        List<String> effectivePaths =
                annotated(type, Types.JAXRS_PATH) ? effectivePaths(typeLevelPaths, mappingPaths) : List.of();
        List<String> produces = methodStringAttr(method, Types.JAXRS_PRODUCES, "value");
        List<String> consumes = methodStringAttr(method, Types.JAXRS_CONSUMES, "value");
        List<String> effectiveProduces = produces.isEmpty() ? List.copyOf(typeLevelProduces) : dedupe(produces);
        List<String> effectiveConsumes = consumes.isEmpty() ? List.copyOf(typeLevelConsumes) : dedupe(consumes);

        JavaType returnType = declaredReturnType(method);
        JavaClass rawReturn = returnType.toErasure();
        String returnTypeName = rawReturn.getName();
        boolean returnsResponseEntity = hasStatusEnvelope(returnType);
        boolean returnsVoid = noBodyReturn(returnType, false);
        // Per the Jakarta REST spec, a void-returning resource method always answers 204 No Content —
        // unlike Spring MVC, which defaults an unannotated void handler to 200 OK. Modelling that as a
        // known response status keeps RAPI-RESP-002/RAPI-RESP-005 from flagging a JAX-RS void handler
        // for a "silently defaults to 200 OK" footgun that cannot actually happen on this framework.
        boolean hasImplicitNoContentStatus = returnsVoid && !returnsResponseEntity;

        JavaType bodyType = unwrapWrappers(returnType);
        JavaClass bodyErasure = bodyType.toErasure();
        boolean returnsStream = STREAM_TYPES.contains(bodyErasure.getName());
        boolean returnsCollection = isCollection(bodyErasure);
        if (returnsCollection) {
            JavaType element = elementType(bodyType);
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
        List<String> versionBindings = new ArrayList<>();

        for (JavaParameter parameter : KotlinBytecode.declaredParameters(method)) {
            if (isJaxRsEntityParam(parameter)) {
                hasRequestBody = true;
                requestBodyValidated |= parameter.isAnnotatedWith(Types.VALID);
                JavaClass payload = requestPayload(parameter);
                requestBodyIsEntity |= safeAnnotated(payload, Types.ENTITY);
                requestBodyIsSimple |= isSimpleBodyType(payload);
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
                    versionBindings.add("query:" + query);
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
                    versionBindings.add("header:" + header);
                }
            }
        }

        boolean stateChanging = nameLooksStateChanging(method.getName());
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
        collectThrownExceptions(type, method);
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
                List.of(),
                List.of(),
                List.copyOf(pathVariableNames),
                requestBodyIsSimple,
                hasTagAnnotation(method),
                hidden,
                false,
                paginationParamFamily,
                hasIdempotencyKeyHeader,
                isDeprecated,
                operationMarkedDeprecated,
                RestApiModel.Framework.JAX_RS,
                versionBindings,
                hasBodyEnvelope(returnType),
                returnsStream);
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
            Optional<JavaType> mapperType = findMapperType(type, new LinkedHashSet<>(), 0);
            if (mapperType.isPresent()) {
                hasExceptionHandling = true;
                JavaType argument = firstTypeArgument(mapperType.get());
                List<String> exceptionTypes =
                        argument instanceof JavaClass exception ? List.of(exception.getName()) : List.of();
                addJaxRsExceptionMapperModel(type, findMethod(type, "toResponse", 1), exceptionTypes);
            }
        } catch (RuntimeException | LinkageError ex) {
            incomplete = true;
        }
    }

    private static Optional<JavaType> findMapperType(JavaClass type, Set<String> visited, int depth) {
        if (!visited.add(type.getName())) {
            return Optional.empty();
        }
        if (depth >= 10) {
            throw new IllegalStateException("REST mapper hierarchy exceeds analysis bound");
        }
        for (JavaType iface : type.getInterfaces()) {
            if (Types.JAXRS_EXCEPTION_MAPPER.equals(iface.toErasure().getName())) {
                return Optional.of(iface);
            }
            Optional<JavaType> inherited = findMapperType(iface.toErasure(), visited, depth + 1);
            if (inherited.isPresent()) {
                return inherited;
            }
        }
        return type.getRawSuperclass().flatMap(superclass -> findMapperType(superclass, visited, depth + 1));
    }

    /**
     * Models a RESTEasy Reactive {@code @ServerExceptionMapper} method as a centralized exception handler.
     * This is the simpler, {@code @Provider}-free exception-mapper style that is the idiomatic default on
     * Quarkus (no {@code ExceptionMapper<X>} interface to implement); the method can live on any CDI bean,
     * not just a JAX-RS resource. See https://quarkus.io/guides/rest#exception-mapping
     */
    private void collectServerExceptionMapperMethods(JavaClass type) {
        for (JavaMethod method : KotlinBytecode.declaredMethods(type)) {
            try {
                if (!method.isAnnotatedWith(Types.SERVER_EXCEPTION_MAPPER)) {
                    continue;
                }
                hasExceptionHandling = true;
                List<String> exceptionTypes =
                        annotationClassNames(method.getAnnotationOfType(Types.SERVER_EXCEPTION_MAPPER), "value");
                if (exceptionTypes.isEmpty()) {
                    exceptionTypes = exceptionParameterTypes(method);
                }
                addJaxRsExceptionMapperModel(type, Optional.of(method), exceptionTypes);
            } catch (RuntimeException | LinkageError ex) {
                incomplete = true;
            }
        }
    }

    private static List<String> exceptionParameterTypes(JavaMethod method) {
        List<String> types = new ArrayList<>();
        for (JavaParameter parameter : KotlinBytecode.declaredParameters(method)) {
            JavaClass raw = parameter.getRawType();
            if ("java.lang.Throwable".equals(raw.getName()) || extendsClass(raw, "java.lang.Throwable")) {
                types.add(raw.getName());
            }
        }
        return dedupe(types);
    }

    private static Optional<JavaMethod> findMethod(JavaClass type, String name, int parameterCount) {
        JavaClass current = type;
        for (int depth = 0; depth < 10; depth++) {
            for (JavaMethod method : KotlinBytecode.declaredMethods(current)) {
                if (method.getName().equals(name)
                        && method.getRawParameterTypes().size() == parameterCount) {
                    return Optional.of(method);
                }
            }
            Optional<JavaClass> parent = current.getRawSuperclass();
            if (parent.isEmpty()) {
                return Optional.empty();
            }
            current = parent.get();
        }
        throw new IllegalStateException("REST mapper method hierarchy exceeds analysis bound");
    }

    private void addJaxRsExceptionMapperModel(
            JavaClass type, Optional<JavaMethod> methodOpt, List<String> exceptionTypes) {
        String methodName = methodOpt.map(JavaMethod::getName).orElse("toResponse");
        String bodyType = methodOpt
                .map(method -> resolveBodyTypeName(declaredReturnType(method)))
                .orElse(Types.JAXRS_RESPONSE);
        boolean returnsResponseEntity = methodOpt
                .map(method -> hasStatusEnvelope(declaredReturnType(method)))
                .orElse(true);
        boolean returnsVoid = methodOpt
                .map(method -> noBodyReturn(declaredReturnType(method), false))
                .orElse(false);
        boolean hasResponseParam =
                methodOpt.map(RestApiHandlerModelBuilder::hasResponseParameter).orElse(false);
        // Spring's ProblemDetail/@ResponseStatus types do not exist on JAX-RS, so those two fields are
        // always false/empty here. RFC 9457 itself is framework-neutral, but the bounded model cannot
        // reliably prove that an arbitrary JAX-RS payload implements the problem-details schema.
        boolean catchesExceptionOrThrowable = exceptionTypes.stream()
                .anyMatch(name -> "java.lang.Exception".equals(name) || "java.lang.Throwable".equals(name));
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
                true,
                exceptionTypes,
                declaredProduces(type, methodOpt),
                RestApiModel.Framework.JAX_RS));
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
        for (JavaMethod method : KotlinBytecode.declaredMethods(type)) {
            try {
                if (!method.isAnnotatedWith(Types.EXCEPTION_HANDLER)) {
                    continue;
                }
                JavaType returnType = declaredReturnType(method);
                String bodyType = resolveBodyTypeName(returnType);
                JavaClass bodyClass = unwrapWrappers(returnType).toErasure();
                boolean problemType = bodyClass.isAssignableTo(Types.PROBLEM_DETAIL)
                        || bodyClass.isAssignableTo(Types.ERROR_RESPONSE);
                boolean returnsResponseEntity = hasStatusEnvelope(returnType);
                boolean returnsVoid = noBodyReturn(returnType, true);
                boolean hasResponseStatus =
                        method.isAnnotatedWith(Types.RESPONSE_STATUS) || type.isAnnotatedWith(Types.RESPONSE_STATUS);
                String handlerResponseStatusValue = responseStatusValue(method, type);
                boolean catchesExceptionOrThrowable = catchesBroadException(method);
                boolean hasResponseParam = hasResponseParameter(method);
                boolean methodRendersBody =
                        rendersBody || method.isAnnotatedWith(Types.RESPONSE_BODY) || hasBodyEnvelope(returnType);
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
                        methodRendersBody,
                        springHandledExceptionTypes(method),
                        springDeclaredProduces(method, type),
                        RestApiModel.Framework.SPRING));
                if (isAdvice) {
                    foundAdviceHandler = true;
                }
            } catch (RuntimeException | LinkageError ex) {
                incomplete = true;
            }
        }
        if (foundAdviceHandler
                || (isAdvice
                        && (extendsClass(type, Types.RESPONSE_ENTITY_EXCEPTION_HANDLER)
                                || extendsClass(type, Types.REACTIVE_RESPONSE_ENTITY_EXCEPTION_HANDLER)))) {
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

        httpMethods.addAll(typeLevelMethods);

        boolean explicitHttpMethod = !httpMethods.isEmpty();
        List<String> effectivePaths = effectivePaths(typeLevelPaths, mappingPaths);

        JavaType returnType = declaredReturnType(method);
        JavaClass rawReturn = returnType.toErasure();
        String returnTypeName = rawReturn.getName();
        boolean returnsResponseEntity = hasStatusEnvelope(returnType);
        boolean returnsVoid = noBodyReturn(returnType, true);

        JavaType bodyType = unwrapWrappers(returnType);
        JavaClass bodyErasure = bodyType.toErasure();
        boolean returnsStream = STREAM_TYPES.contains(bodyErasure.getName());
        boolean returnsCollection = isCollection(bodyErasure);
        boolean returnsPageOrSlice =
                Types.PAGE.equals(bodyErasure.getName()) || Types.SLICE.equals(bodyErasure.getName());
        if (returnsCollection) {
            JavaType element = elementType(bodyType);
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

        for (JavaParameter parameter : KotlinBytecode.declaredParameters(method)) {
            String paramTypeName = parameter.getRawType().getName();
            if (parameter.isAnnotatedWith(Types.REQUEST_BODY)) {
                hasRequestBody = true;
                // Field constraints on the DTO only cascade with @Valid/@Validated; a bare
                // constraint annotation (e.g. @NotNull) on the parameter validates only the
                // body reference itself, so it does not count as request-body validation.
                requestBodyValidated |= hasCascadeValidation(parameter);
                JavaClass payload = requestPayload(parameter);
                requestBodyIsEntity |= safeAnnotated(payload, Types.ENTITY);
                requestBodyIsSimple |= isSimpleBodyType(payload);
            }
            boolean simpleBinding =
                    parameter.isAnnotatedWith(Types.PATH_VARIABLE) || parameter.isAnnotatedWith(Types.REQUEST_PARAM);
            if (simpleBinding && hasConstraintAnnotation(parameter)) {
                hasConstrainedSimpleParam = true;
            }
            if (parameter.isAnnotatedWith(Types.REQUEST_PARAM)) {
                if (isPrimitive(parameter.getRawType())
                        && !"boolean".equals(paramTypeName)
                        && !annotated(type, "kotlin.Metadata")
                        && isOptionalRequestParam(parameter)) {
                    hasUnboundedPrimitiveRequestParam = true;
                }
                String paramTypeName2 = parameter.getRawType().getName();
                if (("java.util.Map".equals(paramTypeName2) || Types.MULTI_VALUE_MAP.equals(paramTypeName2))
                        && explicitBindingName(parameter, Types.REQUEST_PARAM) == null) {
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
                if (explicitName != null && requiredPathVariable(parameter)) {
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
        }

        boolean hasResponseStatus =
                method.isAnnotatedWith(Types.RESPONSE_STATUS) || type.isAnnotatedWith(Types.RESPONSE_STATUS);
        boolean methodHasResponseStatus = method.isAnnotatedWith(Types.RESPONSE_STATUS);
        String responseStatusValue = responseStatusValue(method, type);
        boolean declaresBroadThrows = declaresBroadThrows(method);
        collectThrownExceptions(type, method);
        boolean hasOperation = hasOperationAnnotation(method);
        boolean stateChanging = nameLooksStateChanging(method.getName());
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
                || hasBodyEnvelope(returnType);
        String mappingVersion = mappingString(method, "version");
        if (mappingVersion.isBlank()) {
            mappingVersion = typeLevelVersion;
        }
        List<String> effectiveProduces = produces.isEmpty() ? List.copyOf(typeLevelProduces) : dedupe(produces);
        List<String> effectiveConsumes = consumes.isEmpty() ? List.copyOf(typeLevelConsumes) : dedupe(consumes);
        List<String> params = union(typeLevelParams, mappingStrings(method, "params"));
        List<String> headers = union(typeLevelHeaders, mappingStrings(method, "headers"));
        List<String> versionBindings = new ArrayList<>();
        for (JavaParameter parameter : KotlinBytecode.declaredParameters(method)) {
            String query = explicitBindingName(parameter, Types.REQUEST_PARAM);
            if (query != null && RestApiRuleHelp.VERSION_PARAM_NAMES.contains(query.toLowerCase(Locale.ROOT))) {
                versionBindings.add("query:" + query);
            }
            String header = explicitBindingName(parameter, Types.REQUEST_HEADER);
            if (header != null && RestApiRuleHelp.VERSION_PARAM_NAMES.contains(header.toLowerCase(Locale.ROOT))) {
                versionBindings.add("header:" + header);
            }
        }
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
                operationMarkedDeprecated,
                RestApiModel.Framework.SPRING,
                versionBindings,
                hasBodyEnvelope(returnType),
                returnsStream);
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

    /**
     * Spring's request-mapping resolver searches the type hierarchy. Model the same bounded fallback so a
     * concrete controller inherits type-level mapping facts from a base class or controller interface.
     */
    private static Optional<? extends JavaAnnotation<?>> typeHierarchyMappingAnnotation(
            JavaClass type, String annotationName) {
        return typeHierarchyMappingAnnotation(type, annotationName, new LinkedHashSet<>(), 0);
    }

    private static Optional<? extends JavaAnnotation<?>> typeHierarchyMappingAnnotation(
            JavaClass type, String annotationName, Set<String> visited, int depth) {
        if (!visited.add(type.getName())) {
            return Optional.empty();
        }
        if (depth >= 10) {
            throw new IllegalStateException("REST mapping hierarchy exceeds analysis bound");
        }
        Optional<? extends JavaAnnotation<?>> annotation = type.tryGetAnnotationOfType(annotationName);
        if (annotation.isPresent()) {
            return annotation;
        }
        Optional<JavaClass> superclass = type.getRawSuperclass();
        if (superclass.isPresent()
                && !"java.lang.Object".equals(superclass.get().getName())) {
            Optional<? extends JavaAnnotation<?>> inherited =
                    typeHierarchyMappingAnnotation(superclass.get(), annotationName, visited, depth + 1);
            if (inherited.isPresent()) {
                return inherited;
            }
        }
        for (JavaType interfaceType : type.getInterfaces()) {
            Optional<? extends JavaAnnotation<?>> inherited =
                    typeHierarchyMappingAnnotation(interfaceType.toErasure(), annotationName, visited, depth + 1);
            if (inherited.isPresent()) {
                return inherited;
            }
        }
        return Optional.empty();
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

    private static String annotationString(JavaAnnotation<?> annotation, String key) {
        for (String value : stringValues(annotation, key)) {
            if (!value.isBlank()) {
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
                String combined;
                if (leaf.isEmpty()) {
                    combined = root;
                } else if (root.isEmpty()) {
                    combined = leaf;
                } else if (root.endsWith("/") && leaf.startsWith("/")) {
                    combined = root + leaf.substring(1);
                } else {
                    combined = root + (root.endsWith("/") || leaf.startsWith("/") ? "" : "/") + leaf;
                }
                result.add(normalizePath(combined));
            }
        }
        return dedupe(result);
    }

    static String normalizePath(String raw) {
        return raw.startsWith("/") ? raw : "/" + raw;
    }

    private static boolean hasStatusEnvelope(JavaType type) {
        return containsEnvelope(type, false);
    }

    private static boolean hasBodyEnvelope(JavaType type) {
        return containsEnvelope(type, true);
    }

    private static boolean containsEnvelope(JavaType type, boolean includeHttpEntity) {
        JavaType current = type;
        for (int depth = 0; depth < 10; depth++) {
            String name = current.toErasure().getName();
            if (Types.RESPONSE_ENTITY.equals(name)
                    || Types.QUARKUS_REST_RESPONSE.equals(name)
                    || Types.JAXRS_RESPONSE.equals(name)
                    || (includeHttpEntity && Types.HTTP_ENTITY.equals(name))) {
                return true;
            }
            if (!WRAPPER_TYPES.contains(name)) {
                return false;
            }
            if (Types.HTTP_ENTITY.equals(name) || Types.OPTIONAL.equals(name)) {
                return false;
            }
            JavaType argument = firstTypeArgument(current);
            if (argument == null) {
                return false;
            }
            current = argument;
        }
        throw new IllegalStateException("REST response wrapper depth exceeds analysis bound");
    }

    private JavaType unwrapWrappers(JavaType type) {
        return unwrapWrappers(type, true);
    }

    private JavaType unwrapWrappers(JavaType type, boolean unwrapOptional) {
        JavaType current = type;
        boolean bodyEnvelopeSeen = false;
        for (int depth = 0; depth < 10; depth++) {
            String name = current.toErasure().getName();
            if (!WRAPPER_TYPES.contains(name) || (!unwrapOptional && Types.OPTIONAL.equals(name))) {
                return current;
            }
            if (Types.RESPONSE_ENTITY.equals(name)
                    || Types.HTTP_ENTITY.equals(name)
                    || Types.QUARKUS_REST_RESPONSE.equals(name)) {
                if (bodyEnvelopeSeen) {
                    return current;
                }
                bodyEnvelopeSeen = true;
            }
            JavaType argument = firstTypeArgument(current);
            if (argument == null) {
                return current;
            }
            current = argument;
        }
        throw new IllegalStateException("REST body wrapper depth exceeds analysis bound");
    }

    private boolean noBodyReturn(JavaType type, boolean spring) {
        String name = unwrapWrappers(type, false).toErasure().getName();
        return isVoidLike(name)
                || (spring && "org.springframework.http.HttpHeaders".equals(name) && !containsEnvelope(type, true));
    }

    private static JavaType elementType(JavaType type) {
        JavaClass erasure = type.toErasure();
        return erasure.isArray() ? erasure.getComponentType() : firstTypeArgument(type);
    }

    private JavaClass requestPayload(JavaParameter parameter) {
        JavaType body = unwrapWrappers(parameter.getType());
        if (isCollection(body.toErasure())) {
            JavaType element = elementType(body);
            if (element != null) {
                body = unwrapWrappers(element);
            }
        }
        return body.toErasure();
    }

    private static boolean hasCascadeValidation(JavaParameter parameter) {
        for (JavaAnnotation<JavaParameter> annotation : parameter.getAnnotations()) {
            JavaClass type = annotation.getRawType();
            if (Types.VALID.equals(type.getName())
                    || Types.VALIDATED.equals(type.getName())
                    || type.isMetaAnnotatedWith(Types.VALIDATED)) {
                return true;
            }
        }
        return false;
    }

    private static boolean requiredPathVariable(JavaParameter parameter) {
        if (Types.OPTIONAL.equals(parameter.getRawType().getName())) {
            return false;
        }
        return parameter
                .getAnnotationOfType(Types.PATH_VARIABLE)
                .get("required")
                .map(Boolean.TRUE::equals)
                .orElse(true);
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

    /**
     * The return type the developer declared. A Kotlin {@code suspend fun} always compiles to an
     * {@code Object}-returning method whose real result type is the type argument of its trailing
     * {@code Continuation<? super T>} parameter, so reading the JVM return type would model every
     * suspending handler as returning an untyped body.
     */
    private static JavaType declaredReturnType(JavaMethod method) {
        List<JavaClass> parameters = method.getRawParameterTypes();
        if (!parameters.isEmpty()
                && "kotlin.coroutines.Continuation"
                        .equals(parameters.get(parameters.size() - 1).getName())) {
            return KotlinBytecode.suspendResultType(method)
                    .orElseThrow(() -> new IllegalStateException("REST suspend result type is unavailable"));
        }
        return method.getReturnType();
    }

    /** Whether the named type carries no body: {@code void}, {@code Void}, or Kotlin's {@code Unit}. */
    private static boolean isVoidLike(String typeName) {
        return "void".equals(typeName) || "java.lang.Void".equals(typeName) || KotlinBytecode.isUnit(typeName);
    }

    private String resolveBodyTypeName(JavaType returnType) {
        JavaType body = unwrapWrappers(returnType);
        JavaClass erasure = body.toErasure();
        if (isCollection(erasure) || Types.PAGE.equals(erasure.getName()) || Types.SLICE.equals(erasure.getName())) {
            JavaType element = elementType(body);
            if (element != null) {
                return element.toErasure().getName();
            }
        }
        return erasure.getName();
    }

    private static boolean isCollection(JavaClass type) {
        if (type.isArray()) {
            return !type.getComponentType().isPrimitive();
        }
        return COLLECTION_TYPES.contains(type.getName());
    }

    private static boolean isPrimitive(JavaClass type) {
        return type.isPrimitive();
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
        String defaultValue = ann.get("defaultValue")
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .orElse("\uE000");
        boolean hasDefault = defaultValue.indexOf('\uE000') < 0;
        boolean hasNonblankDefault = hasDefault && !defaultValue.isBlank();
        return (requiredFalse || hasDefault) && !hasNonblankDefault;
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

    private static Set<String> jaxRsHttpMethods(JavaMethod method) {
        Set<String> methods = new LinkedHashSet<>();
        for (JavaAnnotation<JavaMethod> annotation : method.getAnnotations()) {
            jaxRsHttpMethod(annotation).ifPresent(methods::add);
        }
        return methods;
    }

    private static Optional<String> jaxRsHttpMethod(JavaAnnotation<?> annotation) {
        JavaClass annotationType = annotation.getRawType();
        Optional<? extends JavaAnnotation<?>> httpMethod =
                annotationType.tryGetAnnotationOfType(Types.JAXRS_HTTP_METHOD);
        if (httpMethod.isEmpty()) {
            return Optional.empty();
        }
        return httpMethod
                .get()
                .get("value")
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(value -> value.toUpperCase(Locale.ROOT));
    }

    private static boolean isSimpleBodyType(JavaClass type) {
        if (type.isArray() || type.isPrimitive()) {
            return true;
        }
        String name = type.getName();
        return SIMPLE_BODY_TYPES.contains(name) || SCALAR_TYPES.contains(name) || UNTYPED_TYPES.contains(name);
    }

    private static boolean nameLooksStateChanging(String methodName) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        if (NON_MUTATING_METHOD_PREFIXES.stream().anyMatch(lower::startsWith)) {
            return false;
        }
        return startsWithWord(methodName, STATE_CHANGING_PREFIXES);
    }

    /**
     * True when a method carries either the Swagger ({@code io.swagger.v3.oas.annotations}) or the
     * MicroProfile OpenAPI ({@code org.eclipse.microprofile.openapi.annotations}) form of {@code @Tag}.
     * SmallRye OpenAPI (Quarkus' {@code quarkus-smallrye-openapi}) recognises both annotation families
     * equally: https://quarkus.io/guides/openapi-swaggerui
     */
    private static boolean hasTagAnnotation(JavaMethod method) {
        return method.isAnnotatedWith(Types.TAG)
                || method.isAnnotatedWith(Types.MP_TAG)
                || method.isAnnotatedWith("io.swagger.v3.oas.annotations.tags.Tags")
                || method.isAnnotatedWith("org.eclipse.microprofile.openapi.annotations.tags.Tags")
                || !methodStringAttr(method, Types.OPERATION, "tags").isEmpty()
                || !methodStringAttr(method, Types.MP_OPERATION, "tags").isEmpty();
    }

    private static boolean hasTagAnnotation(JavaClass type) {
        return annotated(type, Types.TAG)
                || annotated(type, Types.MP_TAG)
                || annotated(type, "io.swagger.v3.oas.annotations.tags.Tags")
                || annotated(type, "org.eclipse.microprofile.openapi.annotations.tags.Tags");
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
        for (JavaParameter parameter : KotlinBytecode.declaredParameters(method)) {
            String name = parameter.getRawType().getName();
            if (RESPONSE_PARAMETER_TYPES.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean extendsClass(JavaClass type, String superName) {
        JavaClass current = type;
        for (int depth = 0; depth < 10; depth++) {
            Optional<JavaClass> superclass = current.getRawSuperclass();
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
        throw new IllegalStateException("REST superclass hierarchy exceeds analysis bound");
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
        Set<JavaMethod> methods = type.getMethods();
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

    /**
     * The exception types a Spring {@code @ExceptionHandler} declares: the annotation's explicit
     * {@code value()} when present, otherwise the method's {@code Throwable} parameters (Spring's own rule).
     */
    private static List<String> springHandledExceptionTypes(JavaMethod method) {
        Optional<JavaAnnotation<JavaMethod>> annotation = method.tryGetAnnotationOfType(Types.EXCEPTION_HANDLER);
        if (annotation.isPresent()) {
            List<String> types = annotationClassNames(annotation.get(), "value", "exception");
            if (!types.isEmpty()) {
                return types;
            }
        }
        return exceptionParameterTypes(method);
    }

    private static List<String> annotationClassNames(JavaAnnotation<?> annotation, String... attributes) {
        List<String> types = new ArrayList<>();
        for (String attribute : attributes) {
            Object value = annotation.get(attribute).orElse(null);
            if (value instanceof Object[] values) {
                for (Object element : values) {
                    if (element instanceof JavaClass type) {
                        types.add(type.getName());
                    }
                }
            } else if (value instanceof JavaClass type) {
                types.add(type.getName());
            }
        }
        return dedupe(types);
    }

    /** Spring's declared error media types: method-level {@code produces}, falling back to class level. */
    private static List<String> springDeclaredProduces(JavaMethod method, JavaClass type) {
        // @ExceptionHandler carries its own produces() and wins, exactly as it does at runtime; the
        // error-contract catalogue reads the same attribute, so the rule and the panel agree.
        return method.tryGetAnnotationOfType(Types.EXCEPTION_HANDLER)
                .map(annotation -> stringValues(annotation, "produces"))
                .orElse(List.of());
    }

    /** JAX-RS declared error media types: method-level {@code @Produces}, falling back to class level. */
    private static List<String> declaredProduces(JavaClass type, Optional<JavaMethod> methodOpt) {
        List<String> produces = methodOpt
                .map(method -> methodStringAttr(method, Types.JAXRS_PRODUCES, "value"))
                .orElse(List.of());
        if (!produces.isEmpty()) {
            return produces;
        }
        return type.tryGetAnnotationOfType(Types.JAXRS_PRODUCES)
                .map(annotation -> stringValues(annotation, "value"))
                .orElse(List.of());
    }

    /**
     * Records the application exception types an endpoint method declares in its {@code throws} clause,
     * together with their resolved ancestors, so RAPI-ERR-009 can decide handler coverage without a class
     * hierarchy of its own. Framework and JDK exceptions are excluded: they are already mapped by the
     * framework's own default resolution, so reporting them would be noise rather than evidence.
     */
    private void collectThrownExceptions(JavaClass type, JavaMethod method) {
        try {
            for (JavaClass thrown : method.getExceptionTypes()) {
                String name = thrown.getName();
                if (name.startsWith("java.")
                        || name.startsWith("jakarta.")
                        || name.startsWith("org.springframework.")) {
                    continue;
                }
                List<String> superTypes = new ArrayList<>();
                for (JavaClass ancestor : thrown.getAllRawSuperclasses()) {
                    superTypes.add(ancestor.getName());
                }
                thrownExceptions.add(new ThrownExceptionModel(
                        type.getSimpleName(), method.getName(), name, thrown.getSimpleName(), superTypes));
            }
        } catch (RuntimeException | LinkageError ex) {
            incomplete = true;
        }
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
        return type.isAnnotatedWith(annotationName);
    }

    private static boolean metaAnnotated(JavaClass type, String annotationName) {
        return type.isMetaAnnotatedWith(annotationName);
    }

    private static boolean safeAnnotated(JavaClass type, String annotationName) {
        return annotated(type, annotationName);
    }

    private static boolean safeIsRecord(JavaClass type) {
        return type.isRecord();
    }

    private static boolean safeIsInterface(JavaClass type) {
        return type.isInterface();
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
        for (JavaField field : type.getFields()) {
            String fieldTypeName = field.getRawType().getName();
            if (Types.DATE.equals(fieldTypeName) || Types.CALENDAR.equals(fieldTypeName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the method's {@code @ExceptionHandler} annotation declares {@code Exception} or
     * {@code Throwable} in its {@code value} array.
     */
    private static boolean catchesBroadException(JavaMethod method) {
        return springHandledExceptionTypes(method).stream()
                .anyMatch(name -> "java.lang.Exception".equals(name) || "java.lang.Throwable".equals(name));
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
            incomplete = true;
        }
    }
}
