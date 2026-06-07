package io.github.jdubois.bootui.autoconfigure.restapi;

import io.github.jdubois.bootui.autoconfigure.restapi.RestApiModel.ControllerModel;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiModel.ExceptionHandlerModel;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiModel.HandlerMethodModel;
import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
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
abstract class AbstractRestApiRule implements RestApiRule {

    private final RestApiRuleDefinition definition;

    AbstractRestApiRule(RestApiRuleDefinition definition) {
        this.definition = definition;
    }

    @Override
    public final RestApiRuleDefinition definition() {
        return definition;
    }

    @Override
    public final RestApiRuleResultDto evaluate(RestApiContext context) {
        try {
            return doEvaluate(context);
        } catch (RuntimeException | LinkageError ex) {
            return RestApiRuleSupport.error(definition, "Rule could not be evaluated: " + ex.getMessage());
        }
    }

    abstract RestApiRuleResultDto doEvaluate(RestApiContext context);

    /** Collects one violation detail per handler that matches the predicate. */
    RestApiRuleResultDto handlersMatching(
            RestApiContext context, Predicate<HandlerMethodModel> predicate, String suffix) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            if (predicate.test(handler)) {
                violations.add(handler.describe() + (suffix.isEmpty() ? "" : " — " + suffix));
            }
        }
        return RestApiRuleSupport.fromViolations(definition, violations);
    }
}

/** Shared static helpers for the REST API Advisor rules. */
final class RestApiRuleHelp {

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
    private static final Pattern VERSIONED_MEDIA_TYPE =
            Pattern.compile(".*(version=|vnd\\.).*", Pattern.CASE_INSENSITIVE);
    private static final Set<String> VERBS = Set.of(
            "get", "create", "update", "delete", "remove", "save", "add", "fetch", "find", "insert", "modify", "list",
            "post", "put", "patch", "read", "search");
    private static final Set<String> CREATION_PREFIXES = Set.of("create", "add", "save", "insert", "register", "new");

    private RestApiRuleHelp() {}

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
            if (lower.startsWith(verb)
                    && segment.length() > verb.length()
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

final class UseHttpMethodSpecificMappingsRule extends AbstractRestApiRule {
    UseHttpMethodSpecificMappingsRule() {
        super(new RestApiRuleDefinition(
                "RAPI-MAP-001",
                "Use HTTP-method-specific mappings",
                RestApiCategory.ROUTING,
                "MEDIUM",
                "Handlers mapped with @RequestMapping but no HTTP method match every verb, which hides intent and"
                        + " can expose state-changing operations over GET.",
                "Replace @RequestMapping without a method with @GetMapping/@PostMapping/@PutMapping/@DeleteMapping/"
                        + "@PatchMapping (or set the method attribute).",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(context, handler -> !handler.explicitHttpMethod(), "no HTTP method declared");
    }
}

final class NoDuplicateRouteMappingsRule extends AbstractRestApiRule {
    NoDuplicateRouteMappingsRule() {
        super(new RestApiRuleDefinition(
                "RAPI-MAP-002",
                "No duplicate route mappings",
                RestApiCategory.ROUTING,
                "HIGH",
                "Two handlers mapped to the same HTTP method and path lead to ambiguous mapping exceptions at startup"
                        + " or unpredictable dispatch.",
                "Ensure each (HTTP method, path) pair is handled by exactly one method.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
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
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }
}

final class StateChangingHandlersNotOnGetRule extends AbstractRestApiRule {
    StateChangingHandlersNotOnGetRule() {
        super(new RestApiRuleDefinition(
                "RAPI-MAP-003",
                "State-changing handlers are not mapped to GET",
                RestApiCategory.ROUTING,
                "HIGH",
                "GET must be safe and idempotent. A create/update/delete-style handler mapped to GET can be triggered"
                        + " by crawlers, prefetching, or caching.",
                "Map state-changing operations to POST/PUT/PATCH/DELETE instead of GET.",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.nameLooksStateChanging()
                        && handler.httpMethods().contains("GET"),
                "state-changing name mapped to GET");
    }
}

final class PreferClassLevelBasePathRule extends AbstractRestApiRule {
    PreferClassLevelBasePathRule() {
        super(new RestApiRuleDefinition(
                "RAPI-MAP-004",
                "Prefer a class-level base path",
                RestApiCategory.ROUTING,
                "LOW",
                "Controllers that repeat the same leading path segment on every method but declare no type-level"
                        + " @RequestMapping duplicate routing information.",
                "Hoist the shared prefix into a class-level @RequestMapping and keep method paths relative.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
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
            List<HandlerMethodModel> controllerHandlers = byController.getOrDefault(controller.className(), List.of());
            String shared = sharedLeadingSegment(controllerHandlers);
            if (shared != null) {
                violations.add(controller.simpleName() + " repeats leading path segment '/" + shared
                        + "' on every method but has no class-level @RequestMapping");
            }
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
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
        List<String> segments = RestApiRuleHelp.segments(path);
        if (segments.isEmpty()) {
            return null;
        }
        String first = segments.get(0);
        return RestApiRuleHelp.isVariable(first) ? null : first;
    }
}

final class ConsistentPathStyleRule extends AbstractRestApiRule {
    ConsistentPathStyleRule() {
        super(new RestApiRuleDefinition(
                "RAPI-MAP-005",
                "Consistent path style (no trailing slash)",
                RestApiCategory.ROUTING,
                "LOW",
                "Trailing slashes and doubled slashes in mapping paths create inconsistent URLs; trailing-slash"
                        + " matching is also disabled by default in Spring 6+.",
                "Declare mapping paths without trailing slashes and without empty segments.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
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
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }
}

// ---------------------------------------------------------------------------------------------
// Naming & resource design — RAPI-NAME
// ---------------------------------------------------------------------------------------------

final class ResourcePathsAreNounsRule extends AbstractRestApiRule {
    ResourcePathsAreNounsRule() {
        super(new RestApiRuleDefinition(
                "RAPI-NAME-001",
                "Resource paths are nouns, not verbs",
                RestApiCategory.NAMING,
                "MEDIUM",
                "Verb-based path segments such as /getUser or /createOrder duplicate the HTTP method and break the"
                        + " resource-oriented REST model.",
                "Model resources as nouns (/users, /orders) and express the action with the HTTP method.",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            for (String path : handler.effectivePaths()) {
                for (String segment : RestApiRuleHelp.staticSegments(path)) {
                    if (RestApiRuleHelp.isVerbSegment(segment)) {
                        violations.add(handler.describe() + " — verb-like path segment '" + segment + "'");
                        break;
                    }
                }
            }
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }
}

final class CollectionsUsePluralNounsRule extends AbstractRestApiRule {
    CollectionsUsePluralNounsRule() {
        super(new RestApiRuleDefinition(
                "RAPI-NAME-002",
                "Collections use plural nouns",
                RestApiCategory.NAMING,
                "LOW",
                "Endpoints returning a collection but addressed with a singular noun read inconsistently (/user vs"
                        + " /users).",
                "Use plural nouns for collection resources and keep singular forms for single-item paths.",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            if (!handler.httpMethods().contains("GET") || !handler.returnsCollection()) {
                continue;
            }
            for (String path : handler.effectivePaths()) {
                List<String> staticSegments = RestApiRuleHelp.staticSegments(path);
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
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }
}

final class PathSegmentsAreKebabCaseRule extends AbstractRestApiRule {
    PathSegmentsAreKebabCaseRule() {
        super(new RestApiRuleDefinition(
                "RAPI-NAME-003",
                "Path segments are kebab-case/lowercase",
                RestApiCategory.NAMING,
                "LOW",
                "camelCase, snake_case, or upper-case path segments produce inconsistent, case-sensitive URLs.",
                "Use lower-case kebab-case path segments (/order-items, not /orderItems or /order_items).",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            for (String path : handler.effectivePaths()) {
                for (String segment : RestApiRuleHelp.staticSegments(path)) {
                    if (RestApiRuleHelp.isNonKebab(segment)) {
                        violations.add(handler.describe() + " — non-kebab-case path segment '" + segment + "'");
                        break;
                    }
                }
            }
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }
}

// ---------------------------------------------------------------------------------------------
// Status codes & responses — RAPI-RESP
// ---------------------------------------------------------------------------------------------

final class CreationReturns201Rule extends AbstractRestApiRule {
    CreationReturns201Rule() {
        super(new RestApiRuleDefinition(
                "RAPI-RESP-001",
                "Creation endpoints return 201 Created",
                RestApiCategory.RESPONSES,
                "MEDIUM",
                "A POST that creates a resource but returns the default 200 OK hides the created status and (usually)"
                        + " the Location of the new resource.",
                "Return 201 via @ResponseStatus(HttpStatus.CREATED) or ResponseEntity.created(...).",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.httpMethods().contains("POST")
                        && RestApiRuleHelp.isCreationName(handler.methodName())
                        && !handler.returnsResponseEntity()
                        && !"CREATED".equals(handler.responseStatusValue()),
                "POST creation defaults to 200 OK");
    }
}

final class VoidDeleteReturns204Rule extends AbstractRestApiRule {
    VoidDeleteReturns204Rule() {
        super(new RestApiRuleDefinition(
                "RAPI-RESP-002",
                "Void DELETE returns 204 No Content",
                RestApiCategory.RESPONSES,
                "LOW",
                "A DELETE handler returning void but defaulting to 200 OK sends an empty 200 instead of the more"
                        + " precise 204 No Content.",
                "Annotate void DELETE handlers with @ResponseStatus(HttpStatus.NO_CONTENT) or return"
                        + " ResponseEntity.noContent().",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.httpMethods().contains("DELETE")
                        && handler.returnsVoid()
                        && !handler.returnsResponseEntity()
                        && !"NO_CONTENT".equals(handler.responseStatusValue()),
                "void DELETE defaults to 200 OK");
    }
}

final class NoUntypedResponseEntityRule extends AbstractRestApiRule {
    NoUntypedResponseEntityRule() {
        super(new RestApiRuleDefinition(
                "RAPI-RESP-003",
                "No untyped ResponseEntity body",
                RestApiCategory.RESPONSES,
                "LOW",
                "ResponseEntity<?> or ResponseEntity<Object> erases the response contract, so clients and OpenAPI"
                        + " tooling cannot infer the body type.",
                "Parameterize ResponseEntity with the concrete DTO type returned by the handler.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.returnsResponseEntity() && handler.bodyIsUntyped(),
                "untyped ResponseEntity body");
    }
}

final class ReadEndpointsReturnRepresentationRule extends AbstractRestApiRule {
    ReadEndpointsReturnRepresentationRule() {
        super(new RestApiRuleDefinition(
                "RAPI-RESP-004",
                "Read endpoints return a representation",
                RestApiCategory.RESPONSES,
                "LOW",
                "A GET returning a bare String or primitive exposes a value without a stable, evolvable"
                        + " representation.",
                "Return a DTO/record representation from read endpoints instead of a raw String or primitive.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler ->
                        handler.httpMethods().contains("GET") && handler.bodyIsScalar() && !handler.returnsCollection(),
                "GET returns a bare scalar (String/primitive)");
    }
}

// ---------------------------------------------------------------------------------------------
// Input validation & binding — RAPI-VALID
// ---------------------------------------------------------------------------------------------

final class RequestBodyIsValidatedRule extends AbstractRestApiRule {
    RequestBodyIsValidatedRule() {
        super(new RestApiRuleDefinition(
                "RAPI-VALID-001",
                "@RequestBody is validated",
                RestApiCategory.VALIDATION,
                "HIGH",
                "A @RequestBody parameter without @Valid/@Validated is bound without bean-validation, so malformed"
                        + " payloads reach the business logic unchecked.",
                "Annotate @RequestBody parameters with @Valid (or @Validated) and declare constraints on the DTO.",
                RestApiRuleHelp.VALIDATION_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.hasRequestBody() && !handler.requestBodyValidated(),
                "@RequestBody is not validated");
    }
}

final class ControllerValidatedForParamConstraintsRule extends AbstractRestApiRule {
    ControllerValidatedForParamConstraintsRule() {
        super(new RestApiRuleDefinition(
                "RAPI-VALID-002",
                "Controller is @Validated for parameter constraints",
                RestApiCategory.VALIDATION,
                "MEDIUM",
                "Constraint annotations on @PathVariable/@RequestParam are ignored unless the controller class is"
                        + " annotated @Validated.",
                "Add @Validated to controllers that place constraints directly on method parameters.",
                RestApiRuleHelp.VALIDATION_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.hasConstrainedSimpleParam() && !handler.controllerValidated(),
                "parameter constraints without class-level @Validated");
    }
}

final class NoMassAssignmentViaEntitiesRule extends AbstractRestApiRule {
    NoMassAssignmentViaEntitiesRule() {
        super(new RestApiRuleDefinition(
                "RAPI-VALID-003",
                "No mass-assignment via JPA entities",
                RestApiCategory.VALIDATION,
                "HIGH",
                "Binding a request body directly to a JPA @Entity lets clients set any persistent field"
                        + " (mass-assignment / over-posting), including ids and relationships.",
                "Bind requests to a dedicated request DTO and map explicitly to the entity.",
                RestApiRuleHelp.VALIDATION_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context, HandlerMethodModel::requestBodyIsEntity, "@RequestBody bound to a JPA @Entity");
    }
}

final class ExplicitRequestParamBindingRule extends AbstractRestApiRule {
    ExplicitRequestParamBindingRule() {
        super(new RestApiRuleDefinition(
                "RAPI-VALID-004",
                "Explicit @RequestParam binding",
                RestApiCategory.VALIDATION,
                "LOW",
                "An Optional @RequestParam with neither required=false nor defaultValue relies on implicit binding"
                        + " behaviour that is easy to misread.",
                "Make optional query parameters explicit with required=false or a defaultValue.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                HandlerMethodModel::hasUnboundedOptionalRequestParam,
                "Optional @RequestParam without explicit required/defaultValue");
    }
}

// ---------------------------------------------------------------------------------------------
// DTO & payload contracts — RAPI-DTO
// ---------------------------------------------------------------------------------------------

final class NoEntitiesInResponsesRule extends AbstractRestApiRule {
    NoEntitiesInResponsesRule() {
        super(new RestApiRuleDefinition(
                "RAPI-DTO-001",
                "Don't expose JPA entities in responses",
                RestApiCategory.PAYLOADS,
                "HIGH",
                "Returning a JPA @Entity couples the API to the persistence model and can leak lazy associations or"
                        + " internal fields and trigger serialization-time queries.",
                "Return a response DTO/record and map from the entity in the service or controller.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context, handler -> handler.bodyIsEntity() && !handler.returnsVoid(), "response body is a JPA @Entity");
    }
}

final class NoUntypedResponseBodiesRule extends AbstractRestApiRule {
    NoUntypedResponseBodiesRule() {
        super(new RestApiRuleDefinition(
                "RAPI-DTO-002",
                "No untyped response bodies",
                RestApiCategory.PAYLOADS,
                "MEDIUM",
                "Returning Map, Object, or JsonNode as the body produces an undocumented, untyped contract that"
                        + " clients and OpenAPI tooling cannot model.",
                "Return a typed DTO/record instead of Map/Object/JsonNode.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.bodyIsUntyped() && !handler.returnsVoid() && !handler.returnsResponseEntity(),
                "untyped response body (Map/Object/JsonNode)");
    }
}

final class WrapTopLevelCollectionsRule extends AbstractRestApiRule {
    WrapTopLevelCollectionsRule() {
        super(new RestApiRuleDefinition(
                "RAPI-DTO-003",
                "Wrap top-level collections",
                RestApiCategory.PAYLOADS,
                "LOW",
                "Returning a raw top-level array or List makes the response impossible to evolve (you cannot add"
                        + " paging or metadata without a breaking change).",
                "Wrap collections in an object (e.g. a page/result wrapper) rather than returning a bare List/array.",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.returnsCollection() && !handler.returnsPageOrSlice(),
                "returns a raw top-level collection");
    }
}

final class DtosAreImmutableRule extends AbstractRestApiRule {
    DtosAreImmutableRule() {
        super(new RestApiRuleDefinition(
                "RAPI-DTO-004",
                "Request/response DTOs are immutable",
                RestApiCategory.PAYLOADS,
                "INFO",
                "Response payload types that expose public setters are mutable, which makes them easy to mutate"
                        + " accidentally and harder to reason about.",
                "Prefer Java records or otherwise immutable DTOs without public setters.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            if (handler.bodyExposesSetters() && !handler.returnsVoid()) {
                violations.add(
                        handler.describe() + " — response DTO '" + handler.bodyTypeName() + "' exposes public setters");
            }
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }
}

// ---------------------------------------------------------------------------------------------
// Pagination & collections — RAPI-PAGE
// ---------------------------------------------------------------------------------------------

final class CollectionReadsArePaginatedRule extends AbstractRestApiRule {
    CollectionReadsArePaginatedRule() {
        super(new RestApiRuleDefinition(
                "RAPI-PAGE-001",
                "Collection reads are paginated",
                RestApiCategory.PAGINATION,
                "MEDIUM",
                "A GET returning a Collection with no Pageable parameter loads and serializes the entire result set,"
                        + " which does not scale.",
                "Accept a Pageable parameter (or explicit page/size) and return a bounded result.",
                RestApiRuleHelp.PAGINATION_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.httpMethods().contains("GET")
                        && handler.returnsCollection()
                        && !handler.returnsPageOrSlice()
                        && !handler.hasPageable(),
                "collection GET without pagination");
    }
}

final class ReturnPagedTypeRule extends AbstractRestApiRule {
    ReturnPagedTypeRule() {
        super(new RestApiRuleDefinition(
                "RAPI-PAGE-002",
                "Return a paged type for find-all reads",
                RestApiCategory.PAGINATION,
                "LOW",
                "\"Find all\"-style handlers returning an unbounded List cannot communicate paging metadata to the"
                        + " client.",
                "Return Page or Slice (or an equivalent wrapper) from find-all reads.",
                RestApiRuleHelp.PAGINATION_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler ->
                        handler.nameLooksLikeFindAll() && handler.returnsCollection() && !handler.returnsPageOrSlice(),
                "find-all handler returns an unbounded List");
    }
}

// ---------------------------------------------------------------------------------------------
// Versioning & content negotiation — RAPI-VER
// ---------------------------------------------------------------------------------------------

final class ApiIsVersionedRule extends AbstractRestApiRule {
    ApiIsVersionedRule() {
        super(new RestApiRuleDefinition(
                "RAPI-VER-001",
                "API is versioned",
                RestApiCategory.VERSIONING,
                "INFO",
                "No version signal (no /vN path segment, version header, or versioned media type) was found, which"
                        + " makes breaking changes hard to roll out.",
                "Adopt a versioning strategy (path, header, or media-type versioning) before the API is consumed"
                        + " externally.",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (context.handlers().isEmpty()) {
            return RestApiRuleSupport.pass(definition());
        }
        for (HandlerMethodModel handler : context.handlers()) {
            if (RestApiRuleHelp.hasVersionSignal(handler)) {
                return RestApiRuleSupport.pass(definition());
            }
        }
        return RestApiRuleSupport.fromViolations(
                definition(),
                List.of("No API version signal (no /vN path, version header, or versioned media type) was detected"
                        + " across " + context.handlers().size() + " handler(s)."));
    }
}

final class MutatingEndpointsDeclareMediaTypesRule extends AbstractRestApiRule {
    MutatingEndpointsDeclareMediaTypesRule() {
        super(new RestApiRuleDefinition(
                "RAPI-VER-002",
                "Mutating endpoints declare media types",
                RestApiCategory.VERSIONING,
                "LOW",
                "POST/PUT/PATCH handlers that declare neither consumes nor produces accept and emit any media type,"
                        + " which weakens content negotiation.",
                "Declare consumes/produces (e.g. application/json) on mutating endpoints.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> isMutating(handler)
                        && handler.produces().isEmpty()
                        && handler.consumes().isEmpty(),
                "mutating endpoint declares no consumes/produces");
    }

    private static boolean isMutating(HandlerMethodModel handler) {
        return handler.httpMethods().contains("POST")
                || handler.httpMethods().contains("PUT")
                || handler.httpMethods().contains("PATCH");
    }
}

final class NoWildcardMediaTypesRule extends AbstractRestApiRule {
    NoWildcardMediaTypesRule() {
        super(new RestApiRuleDefinition(
                "RAPI-VER-003",
                "No wildcard media types",
                RestApiCategory.VERSIONING,
                "LOW",
                "Declaring produces/consumes of */* disables meaningful content negotiation and defeats the purpose"
                        + " of declaring media types.",
                "Use concrete media types (e.g. application/json) instead of */*.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(context, RestApiRuleHelp::containsWildcardMediaType, "declares a */* media type");
    }
}

// ---------------------------------------------------------------------------------------------
// Error handling & documentation — RAPI-ERR / RAPI-DOC
// ---------------------------------------------------------------------------------------------

final class CentralizedExceptionHandlingRule extends AbstractRestApiRule {
    CentralizedExceptionHandlingRule() {
        super(new RestApiRuleDefinition(
                "RAPI-ERR-001",
                "Centralized exception handling exists",
                RestApiCategory.ERROR_HANDLING,
                "MEDIUM",
                "Controllers are present but no @RestControllerAdvice/@ControllerAdvice with an @ExceptionHandler was"
                        + " found, so error responses are likely ad-hoc and inconsistent.",
                "Add a @RestControllerAdvice with @ExceptionHandler methods to produce consistent error responses.",
                RestApiRuleHelp.PROBLEM_DETAIL_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (context.controllers().isEmpty()) {
            return RestApiRuleSupport.pass(definition());
        }
        if (context.hasExceptionHandling()) {
            return RestApiRuleSupport.pass(definition());
        }
        return RestApiRuleSupport.fromViolations(
                definition(),
                List.of("No @ControllerAdvice/@RestControllerAdvice with @ExceptionHandler was found for "
                        + context.controllers().size() + " controller(s)."));
    }
}

final class NoBroadThrowsOnHandlersRule extends AbstractRestApiRule {
    NoBroadThrowsOnHandlersRule() {
        super(new RestApiRuleDefinition(
                "RAPI-ERR-002",
                "No broad throws on handlers",
                RestApiCategory.ERROR_HANDLING,
                "LOW",
                "Handlers declaring throws Exception or Throwable obscure the real failure modes and discourage"
                        + " targeted exception handling.",
                "Throw specific exceptions and map them with @ExceptionHandler instead of declaring throws"
                        + " Exception/Throwable.",
                RestApiRuleHelp.PROBLEM_DETAIL_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context, HandlerMethodModel::declaresBroadThrows, "declares throws Exception/Throwable");
    }
}

final class PreferProblemDetailRule extends AbstractRestApiRule {
    PreferProblemDetailRule() {
        super(new RestApiRuleDefinition(
                "RAPI-ERR-003",
                "Prefer RFC 7807 ProblemDetail",
                RestApiCategory.ERROR_HANDLING,
                "INFO",
                "@ExceptionHandler methods that model errors as ad-hoc maps/strings instead of ProblemDetail produce"
                        + " non-standard error payloads.",
                "Return ProblemDetail (or ErrorResponse) from @ExceptionHandler methods for RFC 7807/9457 compliant"
                        + " errors.",
                RestApiRuleHelp.PROBLEM_DETAIL_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (context.exceptionHandlers().isEmpty()) {
            return RestApiRuleSupport.pass(definition());
        }
        List<String> violations = new ArrayList<>();
        for (ExceptionHandlerModel handler : context.exceptionHandlers()) {
            if (!handler.returnsProblemType()) {
                violations.add(simpleName(handler.declaringClassName()) + "#" + handler.methodName() + " returns '"
                        + simpleName(handler.bodyTypeName()) + "' instead of ProblemDetail");
            }
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }

    private static String simpleName(String fullName) {
        int lastDot = fullName.lastIndexOf('.');
        return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
    }
}

final class EndpointsAreDocumentedRule extends AbstractRestApiRule {
    EndpointsAreDocumentedRule() {
        super(new RestApiRuleDefinition(
                "RAPI-DOC-001",
                "Endpoints are documented",
                RestApiCategory.ERROR_HANDLING,
                "INFO",
                "springdoc-openapi is on the classpath but some handlers have no @Operation, so the generated"
                        + " documentation is incomplete.",
                "Add @Operation (summary/description) to handler methods to document the API.",
                RestApiRuleHelp.OPENAPI_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (!context.springdocPresent()) {
            return RestApiRuleSupport.skipped(definition(), "springdoc-openapi is not on the host classpath.");
        }
        return handlersMatching(context, handler -> !handler.hasOperationAnnotation(), "no @Operation annotation");
    }
}

final class ControllersAreTaggedRule extends AbstractRestApiRule {
    ControllersAreTaggedRule() {
        super(new RestApiRuleDefinition(
                "RAPI-DOC-002",
                "Controllers are grouped/tagged",
                RestApiCategory.ERROR_HANDLING,
                "INFO",
                "springdoc-openapi is on the classpath but some controllers have no @Tag, so endpoints are not"
                        + " grouped in the generated documentation.",
                "Add @Tag to controllers to group their endpoints in the OpenAPI documentation.",
                RestApiRuleHelp.OPENAPI_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (!context.springdocPresent()) {
            return RestApiRuleSupport.skipped(definition(), "springdoc-openapi is not on the host classpath.");
        }
        List<String> violations = new ArrayList<>();
        for (ControllerModel controller : context.controllers()) {
            if (!controller.hasTag()) {
                violations.add(controller.simpleName() + " has no @Tag grouping");
            }
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }
}
