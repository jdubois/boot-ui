package io.github.jdubois.bootui.autoconfigure.restapi;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiModel.ControllerModel;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiModel.ExceptionHandlerModel;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiModel.HandlerMethodModel;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiModel.Types;
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
            "org.springframework.web.context.request.async.WebAsyncTask");

    private static final Set<String> COLLECTION_TYPES = Set.of(
            "java.util.List",
            "java.util.Collection",
            "java.util.Set",
            "java.lang.Iterable",
            "java.util.stream.Stream",
            "reactor.core.publisher.Flux");

    private static final Set<String> UNTYPED_TYPES =
            Set.of("java.lang.Object", "java.util.Map", "java.util.HashMap", "com.fasterxml.jackson.databind.JsonNode");

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
    private static final Set<String> PAGE_PARAM_NAMES = Set.of(
            "page", "size", "limit", "offset", "pagenumber", "pagesize", "perpage",
            "cursor", "after", "before");

    private final List<ControllerModel> controllers = new ArrayList<>();
    private final List<HandlerMethodModel> handlers = new ArrayList<>();
    private final List<ExceptionHandlerModel> exceptionHandlers = new ArrayList<>();
    private final List<String> responseStatusExceptionClasses = new ArrayList<>();
    private boolean hasExceptionHandling;

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

    private void inspect(JavaClass type) {
        scanResponseStatusException(type);
        collectExceptionHandlers(type);
        if (!isController(type)) {
            return;
        }
        boolean restController = annotated(type, Types.REST_CONTROLLER) || metaAnnotated(type, Types.REST_CONTROLLER);
        boolean classValidated = annotated(type, Types.VALIDATED);
        boolean hasTag = annotated(type, Types.TAG);
        boolean hidden = annotated(type, Types.HIDDEN);
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
        List<String> pathVariableNames = new ArrayList<>();

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
                    if (explicitName != null && PAGE_PARAM_NAMES.contains(explicitName.toLowerCase(Locale.ROOT))) {
                        hasExplicitPageParam = true;
                    }
                }
                if (parameter.isAnnotatedWith(Types.PATH_VARIABLE)) {
                    String explicitName = explicitBindingName(parameter, Types.PATH_VARIABLE);
                    if (explicitName != null) {
                        pathVariableNames.add(explicitName);
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
        boolean hasOperation = method.isAnnotatedWith(Types.OPERATION);
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
        boolean hasTag = method.isAnnotatedWith(Types.TAG);
        boolean hidden = classHidden || method.isAnnotatedWith(Types.HIDDEN) || operationHidden(method);
        boolean handlerHasResponseParam = hasResponseParameter(method);

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
                handlerHasResponseParam);
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

    private static boolean operationHidden(JavaMethod method) {
        Optional<JavaAnnotation<JavaMethod>> annotation = method.tryGetAnnotationOfType(Types.OPERATION);
        return annotation.isPresent()
                && annotation.get().get("hidden").map(Boolean.TRUE::equals).orElse(false);
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
