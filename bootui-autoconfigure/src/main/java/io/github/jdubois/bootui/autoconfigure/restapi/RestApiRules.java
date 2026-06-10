package io.github.jdubois.bootui.autoconfigure.restapi;

import io.github.jdubois.bootui.autoconfigure.restapi.RestApiModel.ControllerModel;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiModel.ExceptionHandlerModel;
import io.github.jdubois.bootui.autoconfigure.restapi.RestApiModel.HandlerMethodModel;
import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
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
    static final String CREATED_DOCS = "https://www.rfc-editor.org/rfc/rfc9110.html#section-15.3.2";
    static final String PATCH_DOCS = "https://www.rfc-editor.org/rfc/rfc5789.html";
    static final String API_VERSIONING_DOCS =
            "https://docs.spring.io/spring-framework/reference/web/webmvc-versioning.html";

    private static final Pattern VERSION_SEGMENT = Pattern.compile("v\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATH_VARIABLE_TOKEN = Pattern.compile("\\{([^}/]+)\\}");
    private static final Set<String> VERBS = Set.of(
            "get", "create", "update", "delete", "remove", "save", "add", "fetch", "insert", "modify", "post", "put",
            "patch", "read");

    /**
     * Verbs that are unambiguously HTTP-method-like when standing alone as a path segment.
     * HTTP-method words (post, put, patch) and generic-English words (read) are excluded from the
     * standalone exact-match check to avoid false positives like /blog/post/{id}, but remain in
     * {@link #VERBS} so camelCase prefix detection (postMessage, putData, readAll) still works.
     */
    private static final Set<String> VERB_STANDALONE =
            Set.of("get", "create", "update", "delete", "remove", "save", "add", "fetch", "insert", "modify");

    private static final Set<String> CREATION_PREFIXES = Set.of("create", "add", "save", "insert", "register", "new");
    static final Set<String> PATCH_MEDIA_TYPES = Set.of("application/merge-patch+json", "application/json-patch+json");

    private static final Set<String> FORMAT_EXTENSIONS = Set.of(".json", ".xml", ".html", ".csv", ".yaml", ".yml");

    /** Format-extension suffixes removed from Spring's suffix content negotiation in Spring Framework 6+. */
    static boolean hasFormatExtension(String segment) {
        String lower = segment.toLowerCase(Locale.ROOT);
        for (String ext : FORMAT_EXTENSIONS) {
            if (lower.endsWith(ext) && lower.length() > ext.length()) {
                return true;
            }
        }
        return false;
    }

    /** Exact mapping param/header names (case-insensitive) that signal header/param API versioning. */
    private static final Set<String> VERSION_PARAM_NAMES =
            Set.of("version", "api-version", "x-api-version", "accept-version", "api_version");

    /**
     * Leading static path segments that denote operational, documentation, or auth endpoints which
     * are conventionally left unversioned; they should not make an otherwise versioned API look
     * "mixed".
     */
    private static final Set<String> NON_API_SEGMENTS = Set.of(
            "actuator",
            "health",
            "info",
            "ready",
            "readiness",
            "live",
            "liveness",
            "metrics",
            "prometheus",
            "error",
            "login",
            "logout",
            "oauth",
            "oauth2",
            "token",
            "swagger-ui",
            "swagger",
            "api-docs",
            "v3",
            "webjars",
            "favicon.ico");

    /** Path/method tokens that mark an intentional collection-wide mutation (no single id needed). */
    private static final Set<String> BULK_SEGMENTS = Set.of("bulk", "batch", "all");

    /**
     * Path segments that identify a singleton or current-principal resource, where a mutating method
     * legitimately needs no {id} token (e.g. {@code PUT /users/me}, {@code PATCH /settings}).
     */
    private static final Set<String> SINGLETON_SEGMENTS = Set.of(
            "me",
            "self",
            "current",
            "profile",
            "settings",
            "preferences",
            "session",
            "account",
            "config",
            "configuration",
            "cart");

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
        // Standalone exact match: only clear, HTTP-method-unambiguous verbs.
        if (VERB_STANDALONE.contains(lower)) {
            return true;
        }
        // camelCase prefix check: all verbs including post/put/patch/read to catch postMessage etc.
        for (String verb : VERBS) {
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
        for (String prefix : CREATION_PREFIXES) {
            if (startsWithWord(methodName, prefix)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code name} begins with {@code prefix} at a camelCase word boundary (or equals it). */
    static boolean startsWithWord(String name, String prefix) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals(prefix)) {
            return true;
        }
        return lower.startsWith(prefix)
                && name.length() > prefix.length()
                && Character.isUpperCase(name.charAt(prefix.length()));
    }

    static boolean hasVersionSignal(HandlerMethodModel handler) {
        if (!handler.mappingVersion().isBlank()) {
            return true;
        }
        for (String path : handler.effectivePaths()) {
            for (String segment : segments(path)) {
                if (VERSION_SEGMENT.matcher(segment).matches()) {
                    return true;
                }
            }
        }
        for (String mediaType : handler.effectiveProduces()) {
            if (isVersionedMediaType(mediaType)) {
                return true;
            }
        }
        for (String mediaType : handler.effectiveConsumes()) {
            if (isVersionedMediaType(mediaType)) {
                return true;
            }
        }
        return hasVersionParam(handler.params()) || hasVersionParam(handler.headers());
    }

    /** True when a mapping {@code params}/{@code headers} entry keys on a known API-version name. */
    private static boolean hasVersionParam(List<String> conditions) {
        for (String condition : conditions) {
            String key = conditionKey(condition);
            if (VERSION_PARAM_NAMES.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /** Extracts the lower-cased key of a Spring mapping condition such as {@code "X-API-Version=1"}. */
    private static String conditionKey(String condition) {
        String key = condition;
        if (key.startsWith("!")) {
            key = key.substring(1);
        }
        int cut = key.length();
        for (char delimiter : new char[] {'=', '<', '>', '!'}) {
            int index = key.indexOf(delimiter);
            if (index >= 0 && index < cut) {
                cut = index;
            }
        }
        return key.substring(0, cut).trim().toLowerCase(Locale.ROOT);
    }

    /** True when the handler's first static path segment is an operational/doc/auth endpoint. */
    static boolean isNonApiEndpoint(HandlerMethodModel handler) {
        for (String path : handler.effectivePaths()) {
            List<String> statics = staticSegments(path);
            if (!statics.isEmpty() && NON_API_SEGMENTS.contains(statics.get(0).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** True when any path segment marks a bulk/batch (collection-wide) mutation. */
    static boolean isBulkMutation(HandlerMethodModel handler) {
        if (containsSegment(handler, BULK_SEGMENTS)) {
            return true;
        }
        String name = handler.methodName().toLowerCase(Locale.ROOT);
        return name.contains("bulk") || name.contains("batch") || name.contains("all");
    }

    /** True when any path segment denotes a singleton / current-principal resource. */
    static boolean isSingletonResource(HandlerMethodModel handler) {
        return containsSegment(handler, SINGLETON_SEGMENTS);
    }

    private static boolean containsSegment(HandlerMethodModel handler, Set<String> needles) {
        for (String path : handler.effectivePaths()) {
            for (String segment : staticSegments(path)) {
                if (needles.contains(segment.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Normalizes a media type for comparison: lower-cased, parameters ({@code ;charset=...}) stripped. */
    static String normalizeMediaType(String mediaType) {
        String value = mediaType.trim().toLowerCase(Locale.ROOT);
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) {
            value = value.substring(0, semicolon);
        }
        return value.trim();
    }

    private static boolean isVersionedMediaType(String mediaType) {
        String lower = mediaType.toLowerCase(Locale.ROOT);
        return lower.contains("version=") || lower.contains("vnd.");
    }

    static boolean containsWildcardMediaType(HandlerMethodModel handler) {
        return hasWildcard(handler.effectiveProduces()) || hasWildcard(handler.effectiveConsumes());
    }

    private static boolean hasWildcard(List<String> mediaTypes) {
        for (String mediaType : mediaTypes) {
            if (mediaType.contains("*")) {
                return true;
            }
        }
        return false;
    }

    /** Extracts declared path-variable token names ({@code {id}}, {@code {id:regex}}, {@code {*path}}). */
    static Set<String> pathVariableTokens(HandlerMethodModel handler) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String path : handler.effectivePaths()) {
            Matcher matcher = PATH_VARIABLE_TOKEN.matcher(path);
            while (matcher.find()) {
                String inner = matcher.group(1);
                if (inner.startsWith("*")) {
                    inner = inner.substring(1);
                }
                int colon = inner.indexOf(':');
                if (colon >= 0) {
                    inner = inner.substring(0, colon);
                }
                inner = inner.trim();
                if (!inner.isEmpty()) {
                    tokens.add(inner);
                }
            }
        }
        return tokens;
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
                "Two handlers mapped to the same HTTP method, path, consumes/produces, params, headers, and version"
                        + " lead to ambiguous mapping exceptions at startup or unpredictable dispatch.",
                "Ensure each (HTTP method, path, consumes/produces/params/headers/version) combination is handled by"
                        + " exactly one method.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        Map<String, List<String>> byRoute = new LinkedHashMap<>();
        for (HandlerMethodModel handler : context.handlers()) {
            List<String> methods = handler.httpMethods().isEmpty() ? List.of("ANY") : handler.httpMethods();
            String condition = conditionKey(handler);
            for (String method : methods) {
                for (String path : handler.effectivePaths()) {
                    byRoute.computeIfAbsent(method + " " + path + condition, ignored -> new ArrayList<>())
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

    /**
     * Distinguishes routes that share a verb and path but differ by content negotiation (consumes/produces),
     * required params/headers, or API version, so legitimate conditional mappings are not reported as duplicates.
     */
    private static String conditionKey(HandlerMethodModel handler) {
        List<String> parts = new ArrayList<>();
        parts.addAll(handler.effectiveConsumes());
        parts.addAll(handler.effectiveProduces());
        parts.addAll(handler.params());
        parts.addAll(handler.headers());
        if (!handler.mappingVersion().isBlank()) {
            parts.add("version=" + handler.mappingVersion());
        }
        if (parts.isEmpty()) {
            return "";
        }
        parts.sort(String::compareTo);
        return " {" + String.join(",", parts) + "}";
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
        for (ControllerModel controller : context.controllers()) {
            for (String path : controller.typeLevelPaths()) {
                if (hasIrregularSlash(path)) {
                    violations.add(controller.simpleName() + " — class-level mapping path '" + path
                            + "' has an irregular slash");
                }
            }
        }
        for (HandlerMethodModel handler : context.handlers()) {
            for (String path : handler.mappingPaths()) {
                if (hasIrregularSlash(path)) {
                    violations.add(handler.describe() + " — mapping path '" + path + "' has an irregular slash");
                }
            }
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }

    private static boolean hasIrregularSlash(String path) {
        boolean trailing = path.length() > 1 && path.endsWith("/");
        boolean doubled = path.contains("//");
        return trailing || doubled;
    }
}

// ---------------------------------------------------------------------------------------------
// Naming & resource design — RAPI-NAME
// ---------------------------------------------------------------------------------------------

final class PathVariablesAreBoundRule extends AbstractRestApiRule {
    PathVariablesAreBoundRule() {
        super(new RestApiRuleDefinition(
                "RAPI-MAP-006",
                "@PathVariable names match a path token",
                RestApiCategory.ROUTING,
                "HIGH",
                "A @PathVariable whose explicit name has no matching {token} in the mapping path fails at runtime with"
                        + " a missing-path-variable error.",
                "Make each @PathVariable name match a {token} in the mapping path (or correct the path template).",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            if (handler.pathVariableNames().isEmpty()
                    || handler.effectivePaths().isEmpty()) {
                continue;
            }
            Set<String> tokens = RestApiRuleHelp.pathVariableTokens(handler);
            List<String> unmatched = new ArrayList<>();
            for (String name : handler.pathVariableNames()) {
                if (!tokens.contains(name)) {
                    unmatched.add(name);
                }
            }
            if (!unmatched.isEmpty()) {
                violations.add(handler.describe() + " — @PathVariable name(s) " + unmatched
                        + " have no matching {token} in the mapping path");
            }
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }
}

final class NoRequestBodyOnBodylessMethodsRule extends AbstractRestApiRule {
    NoRequestBodyOnBodylessMethodsRule() {
        super(new RestApiRuleDefinition(
                "RAPI-MAP-007",
                "No @RequestBody on GET/HEAD/DELETE",
                RestApiCategory.ROUTING,
                "MEDIUM",
                "A @RequestBody on a GET, HEAD, or DELETE handler relies on a request body that proxies, caches, and"
                        + " HTTP clients may strip, so the payload is unreliable.",
                "Move the payload to query/path parameters, or use POST/PUT/PATCH when a request body is required.",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.hasRequestBody() && hasBodylessMethod(handler),
                "@RequestBody on a GET/HEAD/DELETE handler");
    }

    private static boolean hasBodylessMethod(HandlerMethodModel handler) {
        return handler.httpMethods().contains("GET")
                || handler.httpMethods().contains("HEAD")
                || handler.httpMethods().contains("DELETE");
    }
}

final class ResourcePathsAreNounsRule extends AbstractRestApiRule {
    ResourcePathsAreNounsRule() {
        super(new RestApiRuleDefinition(
                "RAPI-NAME-001",
                "Resource paths are nouns, not verbs",
                RestApiCategory.NAMING,
                "LOW",
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
    private static final Set<String> IRREGULAR_PLURALS = Set.of(
            "people",
            "children",
            "men",
            "women",
            "media",
            "data",
            "criteria",
            "indices",
            "vertices",
            "feet",
            "teeth",
            "geese",
            "mice",
            "series",
            "species");

    // Uncountable / collective / operational nouns that are legitimately singular in URLs.
    private static final Set<String> UNCOUNTABLE_NOUNS = Set.of(
            "history",
            "inventory",
            "staff",
            "info",
            "information",
            "search",
            "news",
            "status",
            "metadata",
            "feedback",
            "content",
            "equipment",
            "software",
            "hardware",
            "analytics",
            "reporting",
            "billing",
            "processing",
            "shipping",
            "access");

    CollectionsUsePluralNounsRule() {
        super(new RestApiRuleDefinition(
                "RAPI-NAME-002",
                "Collections use plural nouns",
                RestApiCategory.NAMING,
                "INFO",
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
                String lower = last.toLowerCase(Locale.ROOT);
                if (!lower.endsWith("s") && !IRREGULAR_PLURALS.contains(lower) && !UNCOUNTABLE_NOUNS.contains(lower)) {
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
                "Prefer ResponseEntity.created(uri) so the response carries both 201 and the Location header; use"
                        + " @ResponseStatus(HttpStatus.CREATED) only when the Location is set another way.",
                RestApiRuleHelp.CREATED_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.httpMethods().contains("POST")
                        && RestApiRuleHelp.isCreationName(handler.methodName())
                        && handler.serializesBody()
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
                        && handler.serializesBody()
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
                handler -> handler.returnsResponseEntity()
                        && (handler.bodyIsUntyped()
                                || RestApiModel.Types.RESPONSE_ENTITY.equals(handler.bodyTypeName())
                                || RestApiModel.Types.HTTP_ENTITY.equals(handler.bodyTypeName())),
                "untyped or raw ResponseEntity body");
    }
}

final class ReadEndpointsReturnRepresentationRule extends AbstractRestApiRule {
    ReadEndpointsReturnRepresentationRule() {
        super(new RestApiRuleDefinition(
                "RAPI-RESP-004",
                "Read endpoints return a representation",
                RestApiCategory.RESPONSES,
                "INFO",
                "A GET returning a bare String or primitive exposes a value without a stable, evolvable"
                        + " representation.",
                "Return a DTO/record representation from read endpoints instead of a raw String or primitive.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.httpMethods().contains("GET")
                        && handler.bodyIsScalar()
                        && !handler.returnsCollection()
                        && handler.serializesBody()
                        && !producesPlainText(handler),
                "GET returns a bare scalar (String/primitive)");
    }

    private static boolean producesPlainText(HandlerMethodModel handler) {
        for (String mediaType : handler.effectiveProduces()) {
            if (mediaType.toLowerCase(Locale.ROOT).startsWith("text/")) {
                return true;
            }
        }
        return false;
    }
}

// ---------------------------------------------------------------------------------------------
// Input validation & binding — RAPI-VALID
// ---------------------------------------------------------------------------------------------

final class VoidReadEndpointsReturnContentRule extends AbstractRestApiRule {
    VoidReadEndpointsReturnContentRule() {
        super(new RestApiRuleDefinition(
                "RAPI-RESP-005",
                "GET endpoints return content",
                RestApiCategory.RESPONSES,
                "LOW",
                "A GET handler that returns void responds with an empty 200 OK and no representation, which is rarely"
                        + " the intent for a read endpoint.",
                "Return the resource representation from GET handlers (or use a more precise status when no body is"
                        + " expected).",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.httpMethods().contains("GET")
                        && handler.returnsVoid()
                        && handler.serializesBody()
                        && !handler.hasResponseStatus(),
                "GET handler returns void (empty 200 OK)");
    }
}

final class NoContentResponsesHaveNoBodyRule extends AbstractRestApiRule {
    NoContentResponsesHaveNoBodyRule() {
        super(new RestApiRuleDefinition(
                "RAPI-RESP-006",
                "204 No Content responses carry no body",
                RestApiCategory.RESPONSES,
                "HIGH",
                "A handler annotated @ResponseStatus(NO_CONTENT) that still returns a body is contradictory: 204"
                        + " forbids a response body and clients/proxies may drop or reject it.",
                "Return void (or ResponseEntity) for 204 responses, or use 200 OK when a body is required.",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> "NO_CONTENT".equals(handler.responseStatusValue())
                        && !handler.returnsVoid()
                        && !"java.lang.Void".equals(handler.bodyTypeName())
                        && !handler.returnsResponseEntity()
                        && handler.serializesBody(),
                "204 No Content declared but handler returns a body");
    }
}

final class ResponseStatusIgnoredWithResponseEntityRule extends AbstractRestApiRule {
    ResponseStatusIgnoredWithResponseEntityRule() {
        super(new RestApiRuleDefinition(
                "RAPI-RESP-007",
                "@ResponseStatus is not combined with ResponseEntity",
                RestApiCategory.RESPONSES,
                "MEDIUM",
                "When a handler returns ResponseEntity, its status wins and a method-level @ResponseStatus is silently"
                        + " ignored, so the declared status is misleading.",
                "Set the status through ResponseEntity (e.g. ResponseEntity.status(...)) and drop the redundant"
                        + " @ResponseStatus.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.methodHasResponseStatus() && handler.returnsResponseEntity(),
                "@ResponseStatus is ignored alongside ResponseEntity");
    }
}

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
                handler ->
                        handler.hasRequestBody() && !handler.requestBodyValidated() && !handler.requestBodyIsSimple(),
                "@RequestBody is not validated");
    }
}

final class NoMassAssignmentViaEntitiesRule extends AbstractRestApiRule {
    NoMassAssignmentViaEntitiesRule() {
        super(new RestApiRuleDefinition(
                "RAPI-VALID-002",
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

final class OptionalPrimitiveRequestParamRule extends AbstractRestApiRule {
    OptionalPrimitiveRequestParamRule() {
        super(new RestApiRuleDefinition(
                "RAPI-VALID-003",
                "Optional @RequestParam is not a primitive",
                RestApiCategory.VALIDATION,
                "MEDIUM",
                "A primitive @RequestParam with required=false and no defaultValue throws 500"
                        + " (IllegalStateException) when the parameter is omitted, because null cannot be unboxed.",
                "Use the boxed wrapper type (e.g. Integer) or provide a defaultValue for optional primitive query"
                        + " parameters.",
                RestApiRuleHelp.VALIDATION_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                HandlerMethodModel::hasUnboundedPrimitiveRequestParam,
                "optional primitive @RequestParam can throw 500 when omitted");
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
                context,
                handler -> handler.bodyIsEntity() && !handler.returnsVoid() && handler.serializesBody(),
                "response body is a JPA @Entity");
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
                handler -> handler.bodyIsUntyped()
                        && !handler.returnsVoid()
                        && !handler.returnsResponseEntity()
                        && handler.serializesBody(),
                "untyped response body (Map/Object/JsonNode)");
    }
}

final class DtosAreImmutableRule extends AbstractRestApiRule {
    DtosAreImmutableRule() {
        super(new RestApiRuleDefinition(
                "RAPI-DTO-004",
                "Response DTOs are immutable",
                RestApiCategory.PAYLOADS,
                "INFO",
                "Response payload types that expose public setters are mutable, which makes them easy to mutate"
                        + " accidentally and harder to reason about.",
                "Prefer Java records or otherwise immutable response DTOs without public setters.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            if (handler.bodyExposesSetters() && !handler.returnsVoid() && handler.serializesBody()) {
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
                "LOW",
                "A GET returning a Collection with no Pageable parameter loads and serializes the entire result set,"
                        + " which does not scale.",
                "Accept a Pageable parameter (or explicit page/size/cursor) and return a bounded result.",
                RestApiRuleHelp.PAGINATION_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.httpMethods().contains("GET")
                        && handler.returnsCollection()
                        && !handler.returnsPageOrSlice()
                        && !handler.hasPageable()
                        && !handler.hasExplicitPageParam()
                        && handler.serializesBody(),
                "collection GET without pagination");
    }
}

final class ReturnPagedTypeRule extends AbstractRestApiRule {
    ReturnPagedTypeRule() {
        super(new RestApiRuleDefinition(
                "RAPI-PAGE-002",
                "Pageable handlers return a paged type",
                RestApiCategory.PAGINATION,
                "LOW",
                "A handler that accepts a Pageable but returns a raw List/array discards the paging metadata (total"
                        + " elements, total pages) that Page or Slice would carry.",
                "Return Page or Slice when the handler accepts a Pageable, so paging metadata reaches the client.",
                RestApiRuleHelp.PAGINATION_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.hasPageable() && handler.returnsCollection() && !handler.returnsPageOrSlice(),
                "accepts Pageable but returns a raw collection");
    }
}

// ---------------------------------------------------------------------------------------------
// Versioning & content negotiation — RAPI-VER
// ---------------------------------------------------------------------------------------------

final class ApiIsVersionedRule extends AbstractRestApiRule {
    ApiIsVersionedRule() {
        super(new RestApiRuleDefinition(
                "RAPI-VER-001",
                "API uses a consistent versioning strategy",
                RestApiCategory.VERSIONING,
                "INFO",
                "No version signal (no /vN path segment, version header/param, or versioned media type) was found, or"
                        + " only some handlers are versioned, which makes breaking changes hard to roll out"
                        + " consistently.",
                "Adopt one versioning strategy (path, header/param, or media-type versioning) and apply it across all"
                        + " API endpoints before the API is consumed externally.",
                RestApiRuleHelp.API_VERSIONING_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (context.handlers().isEmpty()) {
            return RestApiRuleSupport.pass(definition());
        }
        List<HandlerMethodModel> versionable = new ArrayList<>();
        List<HandlerMethodModel> unversioned = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            if (RestApiRuleHelp.isNonApiEndpoint(handler)) {
                continue;
            }
            versionable.add(handler);
            if (!RestApiRuleHelp.hasVersionSignal(handler)) {
                unversioned.add(handler);
            }
        }
        if (versionable.isEmpty() || unversioned.isEmpty()) {
            return RestApiRuleSupport.pass(definition());
        }
        if (unversioned.size() == versionable.size()) {
            return RestApiRuleSupport.fromViolations(
                    definition(),
                    List.of("No API version signal (no /vN path, version header/param, or versioned media type) was"
                            + " detected across " + versionable.size() + " handler(s)."));
        }
        List<String> examples =
                unversioned.stream().limit(5).map(HandlerMethodModel::describe).toList();
        return RestApiRuleSupport.fromViolations(
                definition(),
                List.of("Versioning is applied inconsistently: " + unversioned.size() + " of " + versionable.size()
                        + " API handler(s) have no version signal while others do. Unversioned example(s): "
                        + String.join("; ", examples)));
    }
}

final class MutatingEndpointsDeclareMediaTypesRule extends AbstractRestApiRule {
    MutatingEndpointsDeclareMediaTypesRule() {
        super(new RestApiRuleDefinition(
                "RAPI-VER-002",
                "Mutating endpoints declare a consumes media type",
                RestApiCategory.VERSIONING,
                "LOW",
                "POST/PUT/PATCH handlers that accept a @RequestBody but declare no consumes media type accept any"
                        + " content type, which weakens content negotiation and input validation.",
                "Declare consumes (e.g. application/json) on mutating endpoints that accept a request body.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> isMutating(handler)
                        && handler.hasRequestBody()
                        && handler.effectiveConsumes().isEmpty(),
                "mutating endpoint with a body declares no consumes media type");
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
                "Declaring produces/consumes with a wildcard (*/* or application/*) disables meaningful content"
                        + " negotiation and defeats the purpose of declaring media types.",
                "Use concrete media types (e.g. application/json) instead of wildcard media types.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(context, RestApiRuleHelp::containsWildcardMediaType, "declares a wildcard media type");
    }
}

// ---------------------------------------------------------------------------------------------
// Error handling & documentation — RAPI-ERR / RAPI-DOC
// ---------------------------------------------------------------------------------------------

final class PatchUsesPatchMediaTypeRule extends AbstractRestApiRule {
    PatchUsesPatchMediaTypeRule() {
        super(new RestApiRuleDefinition(
                "RAPI-VER-004",
                "PATCH declares a patch media type",
                RestApiCategory.VERSIONING,
                "INFO",
                "A PATCH handler that declares a consumes media type other than application/merge-patch+json or"
                        + " application/json-patch+json does not signal which patch document format it expects.",
                "Declare consumes = application/merge-patch+json (RFC 7396) or application/json-patch+json (RFC 6902)"
                        + " on PATCH handlers.",
                RestApiRuleHelp.PATCH_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.httpMethods().contains("PATCH")
                        && !handler.effectiveConsumes().isEmpty()
                        && !declaresPatchMediaType(handler),
                "PATCH does not declare a patch media type");
    }

    private static boolean declaresPatchMediaType(HandlerMethodModel handler) {
        for (String mediaType : handler.effectiveConsumes()) {
            if (RestApiRuleHelp.PATCH_MEDIA_TYPES.contains(RestApiRuleHelp.normalizeMediaType(mediaType))) {
                return true;
            }
        }
        return false;
    }
}

final class CentralizedExceptionHandlingRule extends AbstractRestApiRule {
    CentralizedExceptionHandlingRule() {
        super(new RestApiRuleDefinition(
                "RAPI-ERR-001",
                "Centralized exception handling exists",
                RestApiCategory.ERROR_HANDLING,
                "MEDIUM",
                "Controllers are present but no @RestControllerAdvice/@ControllerAdvice with @ExceptionHandler"
                        + " methods (or a ResponseEntityExceptionHandler subclass) was found, so error responses are"
                        + " likely ad-hoc and inconsistent.",
                "Add a @RestControllerAdvice with @ExceptionHandler methods (or extend ResponseEntityExceptionHandler)"
                        + " to produce consistent error responses.",
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
                "Prefer RFC 9457 ProblemDetail",
                RestApiCategory.ERROR_HANDLING,
                "INFO",
                "@ExceptionHandler methods that model errors as ad-hoc maps/strings instead of ProblemDetail produce"
                        + " non-standard error payloads.",
                "Return ProblemDetail (or ErrorResponse) from @ExceptionHandler methods for RFC 9457 compliant"
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

final class ExceptionHandlersSetErrorStatusRule extends AbstractRestApiRule {
    ExceptionHandlersSetErrorStatusRule() {
        super(new RestApiRuleDefinition(
                "RAPI-ERR-004",
                "Exception handlers set an explicit error status",
                RestApiCategory.ERROR_HANDLING,
                "MEDIUM",
                "An @ExceptionHandler that renders a body but neither returns ResponseEntity nor declares"
                        + " @ResponseStatus falls back to 200 OK, masking the failure from clients.",
                "Return ResponseEntity/ProblemDetail or add @ResponseStatus so the handler responds with an error"
                        + " status.",
                RestApiRuleHelp.PROBLEM_DETAIL_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (context.exceptionHandlers().isEmpty()) {
            return RestApiRuleSupport.pass(definition());
        }
        List<String> violations = new ArrayList<>();
        for (ExceptionHandlerModel handler : context.exceptionHandlers()) {
            if (handler.rendersBody()
                    && !handler.returnsResponseEntity()
                    && !handler.hasResponseStatus()
                    && !handler.returnsProblemType()
                    && !handler.returnsVoid()
                    && !handler.hasResponseParam()) {
                violations.add(simpleName(handler.declaringClassName()) + "#" + handler.methodName()
                        + " renders a body without an explicit error status (defaults to 200 OK)");
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
        return handlersMatching(
                context, handler -> !handler.hasOperationAnnotation() && !handler.hidden(), "no @Operation annotation");
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
        Set<String> controllersWithTaggedHandler = new LinkedHashSet<>();
        for (HandlerMethodModel handler : context.handlers()) {
            if (handler.hasTag()) {
                controllersWithTaggedHandler.add(handler.controllerClassName());
            }
        }
        List<String> violations = new ArrayList<>();
        for (ControllerModel controller : context.controllers()) {
            if (controller.hidden()) {
                continue;
            }
            if (!controller.hasTag() && !controllersWithTaggedHandler.contains(controller.className())) {
                violations.add(controller.simpleName() + " has no @Tag grouping");
            }
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }
}

// ---------------------------------------------------------------------------------------------
// Phase 2 additions
// ---------------------------------------------------------------------------------------------

final class MutatingItemMethodsTargetResourceRule extends AbstractRestApiRule {
    MutatingItemMethodsTargetResourceRule() {
        super(new RestApiRuleDefinition(
                "RAPI-MAP-008",
                "Mutating item methods target an identified resource",
                RestApiCategory.ROUTING,
                "LOW",
                "A PUT/PATCH/DELETE whose path has no {id} (or other path variable) mutates a collection URI rather"
                        + " than a specific resource, which is usually a missing identifier rather than an intended"
                        + " collection-wide operation.",
                "Add a path variable that identifies the resource (e.g. /orders/{id}); for intentional collection-wide"
                        + " mutations, name the endpoint explicitly (e.g. /orders/bulk).",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> isItemMutation(handler)
                        && !handler.effectivePaths().isEmpty()
                        && RestApiRuleHelp.pathVariableTokens(handler).isEmpty()
                        && !RestApiRuleHelp.isBulkMutation(handler)
                        && !RestApiRuleHelp.isSingletonResource(handler),
                "mutating item method has no resource identifier in the path");
    }

    private static boolean isItemMutation(HandlerMethodModel handler) {
        return handler.httpMethods().contains("PUT")
                || handler.httpMethods().contains("PATCH")
                || handler.httpMethods().contains("DELETE");
    }
}

final class CreatedResponsesExposeLocationRule extends AbstractRestApiRule {
    CreatedResponsesExposeLocationRule() {
        super(new RestApiRuleDefinition(
                "RAPI-RESP-008",
                "Created responses expose a Location",
                RestApiCategory.RESPONSES,
                "MEDIUM",
                "A handler annotated @ResponseStatus(CREATED) that returns a plain body (not ResponseEntity and with"
                        + " no servlet response argument) has no way to set the Location header of the newly created"
                        + " resource.",
                "Return ResponseEntity.created(uri).body(...) so the 201 response also carries the Location of the new"
                        + " resource.",
                RestApiRuleHelp.CREATED_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> "CREATED".equals(handler.responseStatusValue())
                        && !handler.returnsResponseEntity()
                        && !handler.hasResponseParam(),
                "@ResponseStatus(CREATED) response cannot set a Location header");
    }
}

final class ResponseProducingEndpointsDeclareProducesRule extends AbstractRestApiRule {
    ResponseProducingEndpointsDeclareProducesRule() {
        super(new RestApiRuleDefinition(
                "RAPI-VER-005",
                "Response-producing endpoints declare produces consistently",
                RestApiCategory.VERSIONING,
                "LOW",
                "Within a controller that declares produces media types on some response handlers, other"
                        + " body-returning handlers that omit produces create an inconsistent content contract.",
                "Declare produces (e.g. application/json) consistently on the response-producing handlers of a"
                        + " controller.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        Set<String> controllersDeclaringProduces = new LinkedHashSet<>();
        for (HandlerMethodModel handler : context.handlers()) {
            if (serializesRepresentation(handler)
                    && !handler.effectiveProduces().isEmpty()) {
                controllersDeclaringProduces.add(handler.controllerClassName());
            }
        }
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            if (serializesRepresentation(handler)
                    && handler.effectiveProduces().isEmpty()
                    && controllersDeclaringProduces.contains(handler.controllerClassName())) {
                violations.add(handler.describe() + " — serializes a body but declares no produces media type");
            }
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }

    private static boolean serializesRepresentation(HandlerMethodModel handler) {
        return handler.serializesBody() && !handler.returnsVoid();
    }
}

// ---------------------------------------------------------------------------------------------
// Phase 3 additions — new routing rules
// ---------------------------------------------------------------------------------------------

final class DuplicatePathVariableTokenRule extends AbstractRestApiRule {
    DuplicatePathVariableTokenRule() {
        super(new RestApiRuleDefinition(
                "RAPI-MAP-009",
                "No duplicate path-variable tokens in one template",
                RestApiCategory.ROUTING,
                "HIGH",
                "A path template with the same {token} name in two positions (e.g. /users/{id}/orders/{id}) is"
                        + " ambiguous — Spring can only bind one value to the parameter, and the mapping is broken by"
                        + " construction.",
                "Use distinct token names for each path variable (e.g. /users/{userId}/orders/{orderId}).",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            for (String path : handler.effectivePaths()) {
                List<String> duplicates = duplicateTokens(path);
                if (!duplicates.isEmpty()) {
                    violations.add(handler.describe() + " — path '" + path + "' has duplicate token(s): " + duplicates);
                }
            }
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }

    private static List<String> duplicateTokens(String path) {
        Map<String, Integer> count = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("\\{([^}/]+)\\}").matcher(path);
        while (matcher.find()) {
            String inner = matcher.group(1);
            if (inner.startsWith("*")) {
                inner = inner.substring(1);
            }
            int colon = inner.indexOf(':');
            if (colon >= 0) {
                inner = inner.substring(0, colon);
            }
            inner = inner.trim();
            if (!inner.isEmpty()) {
                count.merge(inner, 1, Integer::sum);
            }
        }
        List<String> duplicates = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : count.entrySet()) {
            if (entry.getValue() > 1) {
                duplicates.add(entry.getKey());
            }
        }
        return duplicates;
    }
}

final class CatchAllPatternRule extends AbstractRestApiRule {
    CatchAllPatternRule() {
        super(new RestApiRuleDefinition(
                "RAPI-MAP-010",
                "No catch-all wildcard patterns on REST handlers",
                RestApiCategory.ROUTING,
                "MEDIUM",
                "A /** or {*path} catch-all on a REST handler silently shadows sibling routes and swallows typos as"
                        + " 200 OK responses, masking 404s and making API discovery unreliable.",
                "Map each endpoint explicitly; use a dedicated wildcard handler only for truly generic forwarding"
                        + " outside the REST API surface.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(context, CatchAllPatternRule::hasCatchAllPattern, "catch-all wildcard in mapping path");
    }

    private static boolean hasCatchAllPattern(HandlerMethodModel handler) {
        for (String path : handler.effectivePaths()) {
            if (path.contains("/**") || path.contains("{*")) {
                return true;
            }
        }
        return false;
    }
}

final class DeepResourceNestingRule extends AbstractRestApiRule {
    private static final int MAX_NESTING_DEPTH = 3;

    DeepResourceNestingRule() {
        super(new RestApiRuleDefinition(
                "RAPI-MAP-011",
                "Resource nesting depth should not exceed 3 levels",
                RestApiCategory.ROUTING,
                "INFO",
                "A path template with more than 3 collection/{id} pairs (e.g. /a/{aId}/b/{bId}/c/{cId}/d/{dId}) is"
                        + " hard to read, often indicates a missing intermediate resource, and produces unwieldy URLs.",
                "Flatten deep nesting by exposing a top-level resource or reducing to at most 3 collection/{id}"
                        + " pairs in one path template.",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> maxNestingDepth(handler) > MAX_NESTING_DEPTH,
                "path template has more than " + MAX_NESTING_DEPTH + " collection/{id} nesting levels");
    }

    private static int maxNestingDepth(HandlerMethodModel handler) {
        int max = 0;
        for (String path : handler.effectivePaths()) {
            max = Math.max(max, nestingDepth(path));
        }
        return max;
    }

    private static int nestingDepth(String path) {
        List<String> segs = RestApiRuleHelp.segments(path);
        int depth = 0;
        boolean prevWasStatic = false;
        for (String seg : segs) {
            boolean isVar = RestApiRuleHelp.isVariable(seg);
            if (!isVar) {
                prevWasStatic = true;
            } else if (prevWasStatic) {
                depth++;
                prevWasStatic = false;
            } else {
                prevWasStatic = false;
            }
        }
        return depth;
    }
}

final class FormatSuffixInPathRule extends AbstractRestApiRule {
    FormatSuffixInPathRule() {
        super(new RestApiRuleDefinition(
                "RAPI-NAME-004",
                "No format-extension suffixes in path segments",
                RestApiCategory.NAMING,
                "LOW",
                "Path segments ending in .json, .xml, or similar format suffixes rely on suffix content negotiation,"
                        + " which was removed in Spring Framework 6 and is not supported in Spring Framework 7.",
                "Use the Accept header for content negotiation and remove format-extension suffixes from paths.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            for (String path : handler.effectivePaths()) {
                for (String segment : RestApiRuleHelp.staticSegments(path)) {
                    if (RestApiRuleHelp.hasFormatExtension(segment)) {
                        violations.add(handler.describe() + " — path segment '" + segment
                                + "' uses a format-extension suffix");
                        break;
                    }
                }
            }
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }
}

final class MixedVersioningStrategiesRule extends AbstractRestApiRule {
    MixedVersioningStrategiesRule() {
        super(new RestApiRuleDefinition(
                "RAPI-VER-006",
                "Consistent versioning strategy across handlers",
                RestApiCategory.VERSIONING,
                "INFO",
                "Handlers in the same application use different API versioning strategies (e.g. some use /vN/ path"
                        + " segments, others use versioned media types or version headers/params), producing an"
                        + " inconsistent contract that clients must handle specially.",
                "Settle on a single versioning strategy (path, header/param, media-type, or Spring Framework 7 version"
                        + " attribute) and apply it uniformly.",
                RestApiRuleHelp.API_VERSIONING_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (context.handlers().isEmpty()) {
            return RestApiRuleSupport.pass(definition());
        }
        Set<String> strategies = new LinkedHashSet<>();
        for (HandlerMethodModel handler : context.handlers()) {
            if (RestApiRuleHelp.isNonApiEndpoint(handler)) {
                continue;
            }
            strategies.addAll(versioningStrategies(handler));
        }
        if (strategies.size() <= 1) {
            return RestApiRuleSupport.pass(definition());
        }
        return RestApiRuleSupport.fromViolations(
                definition(),
                List.of("Mixed versioning strategies detected: " + strategies
                        + ". Standardise on one strategy across all API handlers."));
    }

    private static Set<String> versioningStrategies(HandlerMethodModel handler) {
        Set<String> strategies = new LinkedHashSet<>();
        if (!handler.mappingVersion().isBlank()) {
            strategies.add("MAPPING_VERSION");
        }
        for (String path : handler.effectivePaths()) {
            for (String segment : RestApiRuleHelp.segments(path)) {
                if (segment.matches("(?i)v\\d+")) {
                    strategies.add("PATH");
                }
            }
        }
        for (String mediaType : handler.effectiveProduces()) {
            if (isVersionedMediaType(mediaType)) {
                strategies.add("MEDIA_TYPE");
            }
        }
        for (String mediaType : handler.effectiveConsumes()) {
            if (isVersionedMediaType(mediaType)) {
                strategies.add("MEDIA_TYPE");
            }
        }
        return strategies;
    }

    private static boolean isVersionedMediaType(String mediaType) {
        String lower = mediaType.toLowerCase(Locale.ROOT);
        return lower.contains("version=") || lower.contains("vnd.");
    }
}

final class BroadExceptionHandlerRule extends AbstractRestApiRule {
    BroadExceptionHandlerRule() {
        super(new RestApiRuleDefinition(
                "RAPI-ERR-005",
                "Broad @ExceptionHandler should not collapse all errors to one status",
                RestApiCategory.ERROR_HANDLING,
                "LOW",
                "@ExceptionHandler(Exception.class) or Throwable mapped to a single fixed HTTP status collapses all"
                        + " 4xx and 5xx semantics: a validation error, a not-found, and a server fault all look the"
                        + " same to clients.",
                "Catch specific exception types and map each to its appropriate status (e.g. 400, 404, 409, 500),"
                        + " or delegate to ResponseEntityExceptionHandler.",
                RestApiRuleHelp.PROBLEM_DETAIL_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (context.exceptionHandlers().isEmpty()) {
            return RestApiRuleSupport.pass(definition());
        }
        List<String> violations = new ArrayList<>();
        for (ExceptionHandlerModel handler : context.exceptionHandlers()) {
            if (handler.catchesExceptionOrThrowable()
                    && (handler.hasResponseStatus() || handler.returnsResponseEntity())
                    && !handler.returnsProblemType()) {
                violations.add(simpleName(handler.declaringClassName()) + "#" + handler.methodName()
                        + " catches Exception/Throwable and maps all errors to a single fixed status");
            }
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }

    private static String simpleName(String fullName) {
        int lastDot = fullName.lastIndexOf('.');
        return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
    }
}

final class ResponseStatusOnExceptionRule extends AbstractRestApiRule {
    ResponseStatusOnExceptionRule() {
        super(new RestApiRuleDefinition(
                "RAPI-ERR-006",
                "Prefer ErrorResponseException over @ResponseStatus on exceptions",
                RestApiCategory.ERROR_HANDLING,
                "INFO",
                "The project uses ProblemDetail (RFC 9457) for error responses but some application exception classes"
                        + " are annotated with @ResponseStatus instead, mixing error-handling approaches.",
                "Replace @ResponseStatus on exception classes with ErrorResponseException (or a subclass) so all"
                        + " errors consistently produce RFC 9457 ProblemDetail responses.",
                RestApiRuleHelp.PROBLEM_DETAIL_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (context.responseStatusExceptionClasses().isEmpty()) {
            return RestApiRuleSupport.pass(definition());
        }
        boolean projectUsesProblemDetail =
                context.exceptionHandlers().stream().anyMatch(ExceptionHandlerModel::returnsProblemType);
        if (!projectUsesProblemDetail) {
            return RestApiRuleSupport.pass(definition());
        }
        List<String> violations = new ArrayList<>();
        for (String className : context.responseStatusExceptionClasses()) {
            violations.add(simpleName(className)
                    + " is annotated @ResponseStatus but the project uses ProblemDetail elsewhere");
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }

    private static String simpleName(String fullName) {
        int lastDot = fullName.lastIndexOf('.');
        return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
    }
}

final class UnboundedMapRequestParamRule extends AbstractRestApiRule {
    UnboundedMapRequestParamRule() {
        super(new RestApiRuleDefinition(
                "RAPI-VALID-004",
                "Avoid @RequestParam Map/MultiValueMap on public endpoints",
                RestApiCategory.VALIDATION,
                "LOW",
                "@RequestParam Map<String,?> or MultiValueMap binds every query parameter into an untyped map,"
                        + " creating an unbounded, undocumented input contract that is invisible to OpenAPI tooling and"
                        + " cannot be validated with bean-validation constraints.",
                "Declare each accepted query parameter explicitly with a typed @RequestParam so the contract is"
                        + " self-documenting and validatable.",
                RestApiRuleHelp.VALIDATION_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                HandlerMethodModel::hasUnboundedMapRequestParam,
                "@RequestParam Map/MultiValueMap binds all query parameters without type or validation");
    }
}

final class LegacyDateInDtoRule extends AbstractRestApiRule {
    LegacyDateInDtoRule() {
        super(new RestApiRuleDefinition(
                "RAPI-DTO-005",
                "Response/request DTOs should use java.time, not java.util.Date/Calendar",
                RestApiCategory.PAYLOADS,
                "LOW",
                "Fields typed java.util.Date or java.util.Calendar have ambiguous timezone semantics and"
                        + " serialise inconsistently across Jackson versions; Spring Boot 4 defaults to java.time.",
                "Replace java.util.Date/Calendar fields with java.time equivalents (Instant, LocalDate,"
                        + " ZonedDateTime, etc.).",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.bodyHasLegacyDateField() && !handler.returnsVoid() && handler.serializesBody(),
                "response DTO contains java.util.Date/Calendar fields — prefer java.time types");
    }
}
