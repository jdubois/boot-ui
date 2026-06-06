package io.github.jdubois.bootui.autoconfigure.restadvisor;

import io.github.jdubois.bootui.autoconfigure.restadvisor.RestApiAdvisorModel.ControllerModel;
import io.github.jdubois.bootui.autoconfigure.restadvisor.RestApiAdvisorModel.ExceptionHandlerModel;
import io.github.jdubois.bootui.autoconfigure.restadvisor.RestApiAdvisorModel.HandlerMethodModel;
import io.github.jdubois.bootui.core.dto.RestApiAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Base class for curated REST API Advisor rules.
 *
 * <p>Subclasses inspect the derived handler model and return a result; any failure to evaluate is
 * captured and reported as an {@code ERROR} outcome (catching {@link RuntimeException} and
 * {@link LinkageError}, but never {@link VirtualMachineError}) so one broken rule never aborts the
 * scan.</p>
 */
abstract class AbstractRestApiAdvisorRule implements RestApiAdvisorRule {

    private final RestApiAdvisorRuleDefinition definition;

    AbstractRestApiAdvisorRule(RestApiAdvisorRuleDefinition definition) {
        this.definition = definition;
    }

    @Override
    public final RestApiAdvisorRuleDefinition definition() {
        return definition;
    }

    @Override
    public final RestApiAdvisorRuleResultDto evaluate(RestApiAdvisorContext context) {
        try {
            return doEvaluate(context);
        } catch (RuntimeException | LinkageError ex) {
            return RestApiAdvisorRuleSupport.error(definition, "Rule could not be evaluated: " + ex.getMessage());
        }
    }

    abstract RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context);

    /** Collects one violation detail per handler that matches the predicate. */
    RestApiAdvisorRuleResultDto handlersMatching(
            RestApiAdvisorContext context, Predicate<HandlerMethodModel> predicate, String suffix) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            if (predicate.test(handler)) {
                violations.add(handler.describe() + (suffix.isEmpty() ? "" : " — " + suffix));
            }
        }
        return RestApiAdvisorRuleSupport.fromViolations(definition, violations);
    }
}

/** Shared static helpers for the REST API Advisor rules. */
final class RestApiAdvisorRuleHelp {

    static final String SPRING_WEB_DOCS =
            "https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html";
    static final String REST_GUIDELINES = "https://www.rfc-editor.org/rfc/rfc9110.html";
    static final String PROBLEM_DETAIL_DOCS = "https://www.rfc-editor.org/rfc/rfc9457.html";
    static final String VALIDATION_DOCS =
            "https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html";
    static final String PAGINATION_DOCS =
            "https://docs.spring.io/spring-data/commons/reference/repositories/core-extensions.html";
    static final String OPENAPI_DOCS = "https://springdoc.org/";

    private static final Pattern VERSION_SEGMENT = Pattern.compile("v\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSIONED_MEDIA_TYPE = Pattern.compile(".*(version=|vnd\\.).*", Pattern.CASE_INSENSITIVE);
    private static final Set<String> VERBS = Set.of(
            "get", "create", "update", "delete", "remove", "save", "add", "fetch", "find", "insert", "modify", "list",
            "post", "put", "patch", "read", "search");
    private static final Set<String> CREATION_PREFIXES = Set.of("create", "add", "save", "insert", "register", "new");

    private RestApiAdvisorRuleHelp() {}

    static List<String> segments(String path) {
        List<String> result = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isBlank()) {
                result.add(segment);
            }
        }
        return result;
    }

    static boolean isVariable(String segment) {
        return segment.startsWith("{") || segment.startsWith(":");
    }

    static List<String> staticSegments(String path) {
        List<String> result = new ArrayList<>();
        for (String segment : segments(path)) {
            if (!isVariable(segment)) {
                result.add(segment);
            }
        }
        return result;
    }

    static boolean isVerbSegment(String segment) {
        String lower = segment.toLowerCase(Locale.ROOT);
        for (String verb : VERBS) {
            if (lower.equals(verb)) {
                return true;
            }
            if (lower.startsWith(verb) && segment.length() > verb.length()
                    && Character.isUpperCase(segment.charAt(verb.length()))) {
                return true;
            }
        }
        return false;
    }

    static boolean isNonKebab(String segment) {
        for (int i = 0; i < segment.length(); i++) {
            char ch = segment.charAt(i);
            if (Character.isUpperCase(ch) || ch == '_') {
                return true;
            }
        }
        return false;
    }

    static boolean isCreationName(String methodName) {
        String lower = methodName.toLowerCase(Locale.ROOT);
        for (String prefix : CREATION_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasVersionSignal(HandlerMethodModel handler) {
        for (String path : handler.effectivePaths()) {
            for (String segment : segments(path)) {
                if (VERSION_SEGMENT.matcher(segment).matches()) {
                    return true;
                }
            }
        }
        for (String mediaType : handler.produces()) {
            if (VERSIONED_MEDIA_TYPE.matcher(mediaType).matches()) {
                return true;
            }
        }
        for (String mediaType : handler.consumes()) {
            if (VERSIONED_MEDIA_TYPE.matcher(mediaType).matches()) {
                return true;
            }
        }
        return false;
    }

    static boolean containsWildcardMediaType(HandlerMethodModel handler) {
        return handler.produces().contains("*/*") || handler.consumes().contains("*/*");
    }
}

// ---------------------------------------------------------------------------------------------
// Routing & HTTP method mapping — RAPI-MAP
// ---------------------------------------------------------------------------------------------

final class UseHttpMethodSpecificMappingsRule extends AbstractRestApiAdvisorRule {
    UseHttpMethodSpecificMappingsRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-MAP-001",
                "Use HTTP-method-specific mappings",
                RestApiAdvisorCategory.ROUTING,
                "MEDIUM",
                "Handlers mapped with @RequestMapping but no HTTP method match every verb, which hides intent and"
                        + " can expose state-changing operations over GET.",
                "Replace @RequestMapping without a method with @GetMapping/@PostMapping/@PutMapping/@DeleteMapping/"
                        + "@PatchMapping (or set the method attribute).",
                RestApiAdvisorRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        return handlersMatching(context, handler -> !handler.explicitHttpMethod(), "no HTTP method declared");
    }
}

final class NoDuplicateRouteMappingsRule extends AbstractRestApiAdvisorRule {
    NoDuplicateRouteMappingsRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-MAP-002",
                "No duplicate route mappings",
                RestApiAdvisorCategory.ROUTING,
                "HIGH",
                "Two handlers mapped to the same HTTP method and path lead to ambiguous mapping exceptions at startup"
                        + " or unpredictable dispatch.",
                "Ensure each (HTTP method, path) pair is handled by exactly one method.",
                RestApiAdvisorRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        Map<String, List<String>> byRoute = new LinkedHashMap<>();
        for (HandlerMethodModel handler : context.handlers()) {
            List<String> methods = handler.httpMethods().isEmpty() ? List.of("ANY") : handler.httpMethods();
            for (String method : methods) {
                for (String path : handler.effectivePaths()) {
                    byRoute.computeIfAbsent(method + " " + path, ignored -> new ArrayList<>())
                            .add(handler.controllerSimpleName() + "#" + handler.methodName());
                }
            }
        }
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : byRoute.entrySet()) {
            if (entry.getValue().size() > 1) {
                violations.add(entry.getKey() + " handled by " + String.join(", ", entry.getValue()));
            }
        }
        return RestApiAdvisorRuleSupport.fromViolations(definition(), violations);
    }
}

final class StateChangingHandlersNotOnGetRule extends AbstractRestApiAdvisorRule {
    StateChangingHandlersNotOnGetRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-MAP-003",
                "State-changing handlers are not mapped to GET",
                RestApiAdvisorCategory.ROUTING,
                "HIGH",
                "GET must be safe and idempotent. A create/update/delete-style handler mapped to GET can be triggered"
                        + " by crawlers, prefetching, or caching.",
                "Map state-changing operations to POST/PUT/PATCH/DELETE instead of GET.",
                RestApiAdvisorRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        return handlersMatching(
                context,
                handler -> handler.nameLooksStateChanging() && handler.httpMethods().contains("GET"),
                "state-changing name mapped to GET");
    }
}

final class PreferClassLevelBasePathRule extends AbstractRestApiAdvisorRule {
    PreferClassLevelBasePathRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-MAP-004",
                "Prefer a class-level base path",
                RestApiAdvisorCategory.ROUTING,
                "LOW",
                "Controllers that repeat the same leading path segment on every method but declare no type-level"
                        + " @RequestMapping duplicate routing information.",
                "Hoist the shared prefix into a class-level @RequestMapping and keep method paths relative.",
                RestApiAdvisorRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        Map<String, List<HandlerMethodModel>> byController = new LinkedHashMap<>();
        for (HandlerMethodModel handler : context.handlers()) {
            byController
                    .computeIfAbsent(handler.controllerClassName(), ignored -> new ArrayList<>())
                    .add(handler);
        }
        List<String> violations = new ArrayList<>();
        for (ControllerModel controller : context.controllers()) {
            if (!controller.typeLevelPaths().isEmpty() || controller.handlerCount() < 2) {
                continue;
            }
            List<HandlerMethodModel> controllerHandlers =
                    byController.getOrDefault(controller.className(), List.of());
            String shared = sharedLeadingSegment(controllerHandlers);
            if (shared != null) {
                violations.add(controller.simpleName() + " repeats leading path segment '/" + shared
                        + "' on every method but has no class-level @RequestMapping");
            }
        }
        return RestApiAdvisorRuleSupport.fromViolations(definition(), violations);
    }

    private static String sharedLeadingSegment(List<HandlerMethodModel> handlers) {
        if (handlers.size() < 2) {
            return null;
        }
        String shared = null;
        for (HandlerMethodModel handler : handlers) {
            if (handler.effectivePaths().isEmpty()) {
                return null;
            }
            String first = firstSegment(handler.effectivePaths().get(0));
            if (first == null) {
                return null;
            }
            if (shared == null) {
                shared = first;
            } else if (!shared.equals(first)) {
                return null;
            }
        }
        return shared;
    }

    private static String firstSegment(String path) {
        List<String> segments = RestApiAdvisorRuleHelp.segments(path);
        if (segments.isEmpty()) {
            return null;
        }
        String first = segments.get(0);
        return RestApiAdvisorRuleHelp.isVariable(first) ? null : first;
    }
}

final class ConsistentPathStyleRule extends AbstractRestApiAdvisorRule {
    ConsistentPathStyleRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-MAP-005",
                "Consistent path style (no trailing slash)",
                RestApiAdvisorCategory.ROUTING,
                "LOW",
                "Trailing slashes and doubled slashes in mapping paths create inconsistent URLs; trailing-slash"
                        + " matching is also disabled by default in Spring 6+.",
                "Declare mapping paths without trailing slashes and without empty segments.",
                RestApiAdvisorRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            for (String path : handler.mappingPaths()) {
                boolean trailing = path.length() > 1 && path.endsWith("/");
                boolean doubled = path.contains("//");
                if (trailing || doubled) {
                    violations.add(handler.describe() + " — mapping path '" + path + "' has an irregular slash");
                }
            }
        }
        return RestApiAdvisorRuleSupport.fromViolations(definition(), violations);
    }
}

// ---------------------------------------------------------------------------------------------
// Naming & resource design — RAPI-NAME
// ---------------------------------------------------------------------------------------------

final class ResourcePathsAreNounsRule extends AbstractRestApiAdvisorRule {
    ResourcePathsAreNounsRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-NAME-001",
                "Resource paths are nouns, not verbs",
                RestApiAdvisorCategory.NAMING,
                "MEDIUM",
                "Verb-based path segments such as /getUser or /createOrder duplicate the HTTP method and break the"
                        + " resource-oriented REST model.",
                "Model resources as nouns (/users, /orders) and express the action with the HTTP method.",
                RestApiAdvisorRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            for (String path : handler.effectivePaths()) {
                for (String segment : RestApiAdvisorRuleHelp.staticSegments(path)) {
                    if (RestApiAdvisorRuleHelp.isVerbSegment(segment)) {
                        violations.add(handler.describe() + " — verb-like path segment '" + segment + "'");
                        break;
                    }
                }
            }
        }
        return RestApiAdvisorRuleSupport.fromViolations(definition(), violations);
    }
}

final class CollectionsUsePluralNounsRule extends AbstractRestApiAdvisorRule {
    CollectionsUsePluralNounsRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-NAME-002",
                "Collections use plural nouns",
                RestApiAdvisorCategory.NAMING,
                "LOW",
                "Endpoints returning a collection but addressed with a singular noun read inconsistently (/user vs"
                        + " /users).",
                "Use plural nouns for collection resources and keep singular forms for single-item paths.",
                RestApiAdvisorRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            if (!handler.httpMethods().contains("GET") || !handler.returnsCollection()) {
                continue;
            }
            for (String path : handler.effectivePaths()) {
                List<String> staticSegments = RestApiAdvisorRuleHelp.staticSegments(path);
                if (staticSegments.isEmpty()) {
                    continue;
                }
                String last = staticSegments.get(staticSegments.size() - 1);
                if (!last.toLowerCase(Locale.ROOT).endsWith("s")) {
                    violations.add(handler.describe() + " — collection path '/" + last + "' is singular");
                    break;
                }
            }
        }
        return RestApiAdvisorRuleSupport.fromViolations(definition(), violations);
    }
}

final class PathSegmentsAreKebabCaseRule extends AbstractRestApiAdvisorRule {
    PathSegmentsAreKebabCaseRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-NAME-003",
                "Path segments are kebab-case/lowercase",
                RestApiAdvisorCategory.NAMING,
                "LOW",
                "camelCase, snake_case, or upper-case path segments produce inconsistent, case-sensitive URLs.",
                "Use lower-case kebab-case path segments (/order-items, not /orderItems or /order_items).",
                RestApiAdvisorRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            for (String path : handler.effectivePaths()) {
                for (String segment : RestApiAdvisorRuleHelp.staticSegments(path)) {
                    if (RestApiAdvisorRuleHelp.isNonKebab(segment)) {
                        violations.add(handler.describe() + " — non-kebab-case path segment '" + segment + "'");
                        break;
                    }
                }
            }
        }
        return RestApiAdvisorRuleSupport.fromViolations(definition(), violations);
    }
}

// ---------------------------------------------------------------------------------------------
// Status codes & responses — RAPI-RESP
// ---------------------------------------------------------------------------------------------

final class CreationReturns201Rule extends AbstractRestApiAdvisorRule {
    CreationReturns201Rule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-RESP-001",
                "Creation endpoints return 201 Created",
                RestApiAdvisorCategory.RESPONSES,
                "MEDIUM",
                "A POST that creates a resource but returns the default 200 OK hides the created status and (usually)"
                        + " the Location of the new resource.",
                "Return 201 via @ResponseStatus(HttpStatus.CREATED) or ResponseEntity.created(...).",
                RestApiAdvisorRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        return handlersMatching(
                context,
                handler -> handler.httpMethods().contains("POST")
                        && RestApiAdvisorRuleHelp.isCreationName(handler.methodName())
                        && !handler.returnsResponseEntity()
                        && !"CREATED".equals(handler.responseStatusValue()),
                "POST creation defaults to 200 OK");
    }
}

final class VoidDeleteReturns204Rule extends AbstractRestApiAdvisorRule {
    VoidDeleteReturns204Rule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-RESP-002",
                "Void DELETE returns 204 No Content",
                RestApiAdvisorCategory.RESPONSES,
                "LOW",
                "A DELETE handler returning void but defaulting to 200 OK sends an empty 200 instead of the more"
                        + " precise 204 No Content.",
                "Annotate void DELETE handlers with @ResponseStatus(HttpStatus.NO_CONTENT) or return"
                        + " ResponseEntity.noContent().",
                RestApiAdvisorRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        return handlersMatching(
                context,
                handler -> handler.httpMethods().contains("DELETE")
                        && handler.returnsVoid()
                        && !handler.returnsResponseEntity()
                        && !"NO_CONTENT".equals(handler.responseStatusValue()),
                "void DELETE defaults to 200 OK");
    }
}

final class NoUntypedResponseEntityRule extends AbstractRestApiAdvisorRule {
    NoUntypedResponseEntityRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-RESP-003",
                "No untyped ResponseEntity body",
                RestApiAdvisorCategory.RESPONSES,
                "LOW",
                "ResponseEntity<?> or ResponseEntity<Object> erases the response contract, so clients and OpenAPI"
                        + " tooling cannot infer the body type.",
                "Parameterize ResponseEntity with the concrete DTO type returned by the handler.",
                RestApiAdvisorRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        return handlersMatching(
                context,
                handler -> handler.returnsResponseEntity() && handler.bodyIsUntyped(),
                "untyped ResponseEntity body");
    }
}

final class ReadEndpointsReturnRepresentationRule extends AbstractRestApiAdvisorRule {
    ReadEndpointsReturnRepresentationRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-RESP-004",
                "Read endpoints return a representation",
                RestApiAdvisorCategory.RESPONSES,
                "LOW",
                "A GET returning a bare String or primitive exposes a value without a stable, evolvable"
                        + " representation.",
                "Return a DTO/record representation from read endpoints instead of a raw String or primitive.",
                RestApiAdvisorRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        return handlersMatching(
                context,
                handler -> handler.httpMethods().contains("GET")
                        && handler.bodyIsScalar()
                        && !handler.returnsCollection(),
                "GET returns a bare scalar (String/primitive)");
    }
}

// ---------------------------------------------------------------------------------------------
// Input validation & binding — RAPI-VALID
// ---------------------------------------------------------------------------------------------

final class RequestBodyIsValidatedRule extends AbstractRestApiAdvisorRule {
    RequestBodyIsValidatedRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-VALID-001",
                "@RequestBody is validated",
                RestApiAdvisorCategory.VALIDATION,
                "HIGH",
                "A @RequestBody parameter without @Valid/@Validated is bound without bean-validation, so malformed"
                        + " payloads reach the business logic unchecked.",
                "Annotate @RequestBody parameters with @Valid (or @Validated) and declare constraints on the DTO.",
                RestApiAdvisorRuleHelp.VALIDATION_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        return handlersMatching(
                context,
                handler -> handler.hasRequestBody() && !handler.requestBodyValidated(),
                "@RequestBody is not validated");
    }
}

final class ControllerValidatedForParamConstraintsRule extends AbstractRestApiAdvisorRule {
    ControllerValidatedForParamConstraintsRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-VALID-002",
                "Controller is @Validated for parameter constraints",
                RestApiAdvisorCategory.VALIDATION,
                "MEDIUM",
                "Constraint annotations on @PathVariable/@RequestParam are ignored unless the controller class is"
                        + " annotated @Validated.",
                "Add @Validated to controllers that place constraints directly on method parameters.",
                RestApiAdvisorRuleHelp.VALIDATION_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        return handlersMatching(
                context,
                handler -> handler.hasConstrainedSimpleParam() && !handler.controllerValidated(),
                "parameter constraints without class-level @Validated");
    }
}

final class NoMassAssignmentViaEntitiesRule extends AbstractRestApiAdvisorRule {
    NoMassAssignmentViaEntitiesRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-VALID-003",
                "No mass-assignment via JPA entities",
                RestApiAdvisorCategory.VALIDATION,
                "HIGH",
                "Binding a request body directly to a JPA @Entity lets clients set any persistent field"
                        + " (mass-assignment / over-posting), including ids and relationships.",
                "Bind requests to a dedicated request DTO and map explicitly to the entity.",
                RestApiAdvisorRuleHelp.VALIDATION_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        return handlersMatching(
                context, HandlerMethodModel::requestBodyIsEntity, "@RequestBody bound to a JPA @Entity");
    }
}

final class ExplicitRequestParamBindingRule extends AbstractRestApiAdvisorRule {
    ExplicitRequestParamBindingRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-VALID-004",
                "Explicit @RequestParam binding",
                RestApiAdvisorCategory.VALIDATION,
                "LOW",
                "An Optional @RequestParam with neither required=false nor defaultValue relies on implicit binding"
                        + " behaviour that is easy to misread.",
                "Make optional query parameters explicit with required=false or a defaultValue.",
                RestApiAdvisorRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        return handlersMatching(
                context,
                HandlerMethodModel::hasUnboundedOptionalRequestParam,
                "Optional @RequestParam without explicit required/defaultValue");
    }
}

// ---------------------------------------------------------------------------------------------
// DTO & payload contracts — RAPI-DTO
// ---------------------------------------------------------------------------------------------

final class NoEntitiesInResponsesRule extends AbstractRestApiAdvisorRule {
    NoEntitiesInResponsesRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-DTO-001",
                "Don't expose JPA entities in responses",
                RestApiAdvisorCategory.PAYLOADS,
                "HIGH",
                "Returning a JPA @Entity couples the API to the persistence model and can leak lazy associations or"
                        + " internal fields and trigger serialization-time queries.",
                "Return a response DTO/record and map from the entity in the service or controller.",
                RestApiAdvisorRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        return handlersMatching(
                context,
                handler -> handler.bodyIsEntity() && !handler.returnsVoid(),
                "response body is a JPA @Entity");
    }
}

final class NoUntypedResponseBodiesRule extends AbstractRestApiAdvisorRule {
    NoUntypedResponseBodiesRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-DTO-002",
                "No untyped response bodies",
                RestApiAdvisorCategory.PAYLOADS,
                "MEDIUM",
                "Returning Map, Object, or JsonNode as the body produces an undocumented, untyped contract that"
                        + " clients and OpenAPI tooling cannot model.",
                "Return a typed DTO/record instead of Map/Object/JsonNode.",
                RestApiAdvisorRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        return handlersMatching(
                context,
                handler -> handler.bodyIsUntyped() && !handler.returnsVoid() && !handler.returnsResponseEntity(),
                "untyped response body (Map/Object/JsonNode)");
    }
}

final class WrapTopLevelCollectionsRule extends AbstractRestApiAdvisorRule {
    WrapTopLevelCollectionsRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-DTO-003",
                "Wrap top-level collections",
                RestApiAdvisorCategory.PAYLOADS,
                "LOW",
                "Returning a raw top-level array or List makes the response impossible to evolve (you cannot add"
                        + " paging or metadata without a breaking change).",
                "Wrap collections in an object (e.g. a page/result wrapper) rather than returning a bare List/array.",
                RestApiAdvisorRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        return handlersMatching(
                context,
                handler -> handler.returnsCollection() && !handler.returnsPageOrSlice(),
                "returns a raw top-level collection");
    }
}

final class DtosAreImmutableRule extends AbstractRestApiAdvisorRule {
    DtosAreImmutableRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-DTO-004",
                "Request/response DTOs are immutable",
                RestApiAdvisorCategory.PAYLOADS,
                "INFO",
                "Response payload types that expose public setters are mutable, which makes them easy to mutate"
                        + " accidentally and harder to reason about.",
                "Prefer Java records or otherwise immutable DTOs without public setters.",
                RestApiAdvisorRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            if (handler.bodyExposesSetters() && !handler.returnsVoid()) {
                violations.add(handler.describe() + " — response DTO '" + handler.bodyTypeName()
                        + "' exposes public setters");
            }
        }
        return RestApiAdvisorRuleSupport.fromViolations(definition(), violations);
    }
}

// ---------------------------------------------------------------------------------------------
// Pagination & collections — RAPI-PAGE
// ---------------------------------------------------------------------------------------------

final class CollectionReadsArePaginatedRule extends AbstractRestApiAdvisorRule {
    CollectionReadsArePaginatedRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-PAGE-001",
                "Collection reads are paginated",
                RestApiAdvisorCategory.PAGINATION,
                "MEDIUM",
                "A GET returning a Collection with no Pageable parameter loads and serializes the entire result set,"
                        + " which does not scale.",
                "Accept a Pageable parameter (or explicit page/size) and return a bounded result.",
                RestApiAdvisorRuleHelp.PAGINATION_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        return handlersMatching(
                context,
                handler -> handler.httpMethods().contains("GET")
                        && handler.returnsCollection()
                        && !handler.returnsPageOrSlice()
                        && !handler.hasPageable(),
                "collection GET without pagination");
    }
}

final class ReturnPagedTypeRule extends AbstractRestApiAdvisorRule {
    ReturnPagedTypeRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-PAGE-002",
                "Return a paged type for find-all reads",
                RestApiAdvisorCategory.PAGINATION,
                "LOW",
                "\"Find all\"-style handlers returning an unbounded List cannot communicate paging metadata to the"
                        + " client.",
                "Return Page or Slice (or an equivalent wrapper) from find-all reads.",
                RestApiAdvisorRuleHelp.PAGINATION_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        return handlersMatching(
                context,
                handler -> handler.nameLooksLikeFindAll()
                        && handler.returnsCollection()
                        && !handler.returnsPageOrSlice(),
                "find-all handler returns an unbounded List");
    }
}

// ---------------------------------------------------------------------------------------------
// Versioning & content negotiation — RAPI-VER
// ---------------------------------------------------------------------------------------------

final class ApiIsVersionedRule extends AbstractRestApiAdvisorRule {
    ApiIsVersionedRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-VER-001",
                "API is versioned",
                RestApiAdvisorCategory.VERSIONING,
                "INFO",
                "No version signal (no /vN path segment, version header, or versioned media type) was found, which"
                        + " makes breaking changes hard to roll out.",
                "Adopt a versioning strategy (path, header, or media-type versioning) before the API is consumed"
                        + " externally.",
                RestApiAdvisorRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        if (context.handlers().isEmpty()) {
            return RestApiAdvisorRuleSupport.pass(definition());
        }
        for (HandlerMethodModel handler : context.handlers()) {
            if (RestApiAdvisorRuleHelp.hasVersionSignal(handler)) {
                return RestApiAdvisorRuleSupport.pass(definition());
            }
        }
        return RestApiAdvisorRuleSupport.fromViolations(
                definition(),
                List.of("No API version signal (no /vN path, version header, or versioned media type) was detected"
                        + " across " + context.handlers().size() + " handler(s)."));
    }
}

final class MutatingEndpointsDeclareMediaTypesRule extends AbstractRestApiAdvisorRule {
    MutatingEndpointsDeclareMediaTypesRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-VER-002",
                "Mutating endpoints declare media types",
                RestApiAdvisorCategory.VERSIONING,
                "LOW",
                "POST/PUT/PATCH handlers that declare neither consumes nor produces accept and emit any media type,"
                        + " which weakens content negotiation.",
                "Declare consumes/produces (e.g. application/json) on mutating endpoints.",
                RestApiAdvisorRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        return handlersMatching(
                context,
                handler -> isMutating(handler) && handler.produces().isEmpty() && handler.consumes().isEmpty(),
                "mutating endpoint declares no consumes/produces");
    }

    private static boolean isMutating(HandlerMethodModel handler) {
        return handler.httpMethods().contains("POST")
                || handler.httpMethods().contains("PUT")
                || handler.httpMethods().contains("PATCH");
    }
}

final class NoWildcardMediaTypesRule extends AbstractRestApiAdvisorRule {
    NoWildcardMediaTypesRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-VER-003",
                "No wildcard media types",
                RestApiAdvisorCategory.VERSIONING,
                "LOW",
                "Declaring produces/consumes of */* disables meaningful content negotiation and defeats the purpose"
                        + " of declaring media types.",
                "Use concrete media types (e.g. application/json) instead of */*.",
                RestApiAdvisorRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        return handlersMatching(
                context, RestApiAdvisorRuleHelp::containsWildcardMediaType, "declares a */* media type");
    }
}

// ---------------------------------------------------------------------------------------------
// Error handling & documentation — RAPI-ERR / RAPI-DOC
// ---------------------------------------------------------------------------------------------

final class CentralizedExceptionHandlingRule extends AbstractRestApiAdvisorRule {
    CentralizedExceptionHandlingRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-ERR-001",
                "Centralized exception handling exists",
                RestApiAdvisorCategory.ERROR_HANDLING,
                "MEDIUM",
                "Controllers are present but no @RestControllerAdvice/@ControllerAdvice with an @ExceptionHandler was"
                        + " found, so error responses are likely ad-hoc and inconsistent.",
                "Add a @RestControllerAdvice with @ExceptionHandler methods to produce consistent error responses.",
                RestApiAdvisorRuleHelp.PROBLEM_DETAIL_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        if (context.controllers().isEmpty()) {
            return RestApiAdvisorRuleSupport.pass(definition());
        }
        if (context.hasExceptionHandling()) {
            return RestApiAdvisorRuleSupport.pass(definition());
        }
        return RestApiAdvisorRuleSupport.fromViolations(
                definition(),
                List.of("No @ControllerAdvice/@RestControllerAdvice with @ExceptionHandler was found for "
                        + context.controllers().size() + " controller(s)."));
    }
}

final class NoBroadThrowsOnHandlersRule extends AbstractRestApiAdvisorRule {
    NoBroadThrowsOnHandlersRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-ERR-002",
                "No broad throws on handlers",
                RestApiAdvisorCategory.ERROR_HANDLING,
                "LOW",
                "Handlers declaring throws Exception or Throwable obscure the real failure modes and discourage"
                        + " targeted exception handling.",
                "Throw specific exceptions and map them with @ExceptionHandler instead of declaring throws"
                        + " Exception/Throwable.",
                RestApiAdvisorRuleHelp.PROBLEM_DETAIL_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        return handlersMatching(
                context, HandlerMethodModel::declaresBroadThrows, "declares throws Exception/Throwable");
    }
}

final class PreferProblemDetailRule extends AbstractRestApiAdvisorRule {
    PreferProblemDetailRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-ERR-003",
                "Prefer RFC 7807 ProblemDetail",
                RestApiAdvisorCategory.ERROR_HANDLING,
                "INFO",
                "@ExceptionHandler methods that model errors as ad-hoc maps/strings instead of ProblemDetail produce"
                        + " non-standard error payloads.",
                "Return ProblemDetail (or ErrorResponse) from @ExceptionHandler methods for RFC 7807/9457 compliant"
                        + " errors.",
                RestApiAdvisorRuleHelp.PROBLEM_DETAIL_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        if (context.exceptionHandlers().isEmpty()) {
            return RestApiAdvisorRuleSupport.pass(definition());
        }
        List<String> violations = new ArrayList<>();
        for (ExceptionHandlerModel handler : context.exceptionHandlers()) {
            if (!handler.returnsProblemType()) {
                violations.add(simpleName(handler.declaringClassName()) + "#" + handler.methodName()
                        + " returns '" + simpleName(handler.bodyTypeName()) + "' instead of ProblemDetail");
            }
        }
        return RestApiAdvisorRuleSupport.fromViolations(definition(), violations);
    }

    private static String simpleName(String fullName) {
        int lastDot = fullName.lastIndexOf('.');
        return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
    }
}

final class EndpointsAreDocumentedRule extends AbstractRestApiAdvisorRule {
    EndpointsAreDocumentedRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-DOC-001",
                "Endpoints are documented",
                RestApiAdvisorCategory.ERROR_HANDLING,
                "INFO",
                "springdoc-openapi is on the classpath but some handlers have no @Operation, so the generated"
                        + " documentation is incomplete.",
                "Add @Operation (summary/description) to handler methods to document the API.",
                RestApiAdvisorRuleHelp.OPENAPI_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        if (!context.springdocPresent()) {
            return RestApiAdvisorRuleSupport.skipped(
                    definition(), "springdoc-openapi is not on the host classpath.");
        }
        return handlersMatching(context, handler -> !handler.hasOperationAnnotation(), "no @Operation annotation");
    }
}

final class ControllersAreTaggedRule extends AbstractRestApiAdvisorRule {
    ControllersAreTaggedRule() {
        super(new RestApiAdvisorRuleDefinition(
                "RAPI-DOC-002",
                "Controllers are grouped/tagged",
                RestApiAdvisorCategory.ERROR_HANDLING,
                "INFO",
                "springdoc-openapi is on the classpath but some controllers have no @Tag, so endpoints are not"
                        + " grouped in the generated documentation.",
                "Add @Tag to controllers to group their endpoints in the OpenAPI documentation.",
                RestApiAdvisorRuleHelp.OPENAPI_DOCS));
    }

    @Override
    RestApiAdvisorRuleResultDto doEvaluate(RestApiAdvisorContext context) {
        if (!context.springdocPresent()) {
            return RestApiAdvisorRuleSupport.skipped(
                    definition(), "springdoc-openapi is not on the host classpath.");
        }
        List<String> violations = new ArrayList<>();
        for (ControllerModel controller : context.controllers()) {
            if (!controller.hasTag()) {
                violations.add(controller.simpleName() + " has no @Tag grouping");
            }
        }
        return RestApiAdvisorRuleSupport.fromViolations(definition(), violations);
    }
}
