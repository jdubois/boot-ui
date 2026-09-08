package io.github.jdubois.bootui.engine.restapi;

import io.github.jdubois.bootui.core.dto.RestApiRuleResultDto;
import io.github.jdubois.bootui.engine.errorcontract.ErrorBodyCategory;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ControllerModel;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.ExceptionHandlerModel;
import io.github.jdubois.bootui.engine.restapi.RestApiModel.HandlerMethodModel;
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
        context.evidence().reset();
        try {
            RestApiRuleResultDto result = doEvaluate(context);
            context.evidence().complete(result);
            return result;
        } catch (RuntimeException | LinkageError ex) {
            return RestApiRuleSupport.error(
                    definition, "Rule could not be evaluated (" + ex.getClass().getSimpleName() + ").");
        }
    }

    abstract RestApiRuleResultDto doEvaluate(RestApiContext context);

    /** Collects one violation detail per handler that matches the predicate. */
    RestApiRuleResultDto handlersMatching(
            RestApiContext context, Predicate<HandlerMethodModel> predicate, String suffix) {
        return handlersMatching(context, handler -> true, predicate, suffix);
    }

    RestApiRuleResultDto handlersMatching(
            RestApiContext context,
            Predicate<HandlerMethodModel> applicable,
            Predicate<HandlerMethodModel> predicate,
            String suffix) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.targets(context.handlers(), applicable)) {
            if (predicate.test(handler)) {
                violations.add(handler.describe() + (suffix.isEmpty() ? "" : " — " + suffix));
            }
        }
        return RestApiRuleSupport.fromViolations(definition, violations);
    }

    RestApiRuleResultDto missingEvidence(RestApiContext context, String reason) {
        context.evidence().requiredUnknown = true;
        return RestApiRuleSupport.skipped(definition, reason);
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
    static final String PAGINATION_VOCABULARY_DOCS =
            "https://opensource.zalando.com/restful-api-guidelines/#pagination";
    static final String OPENAPI_DOCS = "https://springdoc.org/";
    static final String CREATED_DOCS = "https://www.rfc-editor.org/rfc/rfc9110.html#section-15.3.2";
    static final String PATCH_DOCS = "https://www.rfc-editor.org/rfc/rfc5789.html";
    static final String API_VERSIONING_DOCS =
            "https://docs.spring.io/spring-framework/reference/web/webmvc-versioning.html";
    static final String IDEMPOTENCY_KEY_DOCS =
            "https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/";
    static final String DEPRECATION_DOCS = "https://www.rfc-editor.org/rfc/rfc9745.html";
    static final String RETRY_AFTER_DOCS = "https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3";

    private static final Pattern VERSION_SEGMENT = Pattern.compile("v\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern PATH_VARIABLE_REGEX_TOKEN = Pattern.compile("\\{[^}/:]+:([^}]+)\\}");
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

    /** Spring {@code HttpStatus} enum constant names in the 5xx (server error) range. */
    static final Set<String> SERVER_ERROR_STATUS_NAMES = Set.of(
            "INTERNAL_SERVER_ERROR",
            "NOT_IMPLEMENTED",
            "BAD_GATEWAY",
            "SERVICE_UNAVAILABLE",
            "GATEWAY_TIMEOUT",
            "HTTP_VERSION_NOT_SUPPORTED",
            "VARIANT_ALSO_NEGOTIATES",
            "INSUFFICIENT_STORAGE",
            "LOOP_DETECTED",
            "BANDWIDTH_LIMIT_EXCEEDED",
            "NOT_EXTENDED",
            "NETWORK_AUTHENTICATION_REQUIRED");

    /**
     * Exact mapping param/header names (case-insensitive) that signal header/param API versioning. Package-
     * private so {@link RestApiHandlerModelBuilder} can reuse it when detecting JAX-RS
     * {@code @HeaderParam}/{@code @QueryParam} (and Quarkus {@code @RestHeader}/{@code @RestQuery}) version
     * bindings, the JAX-RS analogue of Spring's {@code @RequestHeader}/{@code @RequestParam}.
     */
    static final Set<String> VERSION_PARAM_NAMES =
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

    private RestApiRuleHelp() {}

    static List<String> segments(String path) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int depth = 0;
        for (int i = 0; i <= path.length(); i++) {
            if (i == path.length() || (path.charAt(i) == '/' && depth == 0)) {
                String segment = path.substring(start, i);
                if (!segment.isBlank()) {
                    result.add(segment);
                }
                start = i + 1;
            } else if (path.charAt(i) == '\\') {
                i++;
            } else if (path.charAt(i) == '{') {
                depth++;
            } else if (path.charAt(i) == '}' && depth > 0) {
                depth--;
            }
        }
        return result;
    }

    static boolean isVariable(String segment) {
        return segment.startsWith("{") || segment.startsWith(":") || segment.contains("${") || segment.contains("#{");
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
        return !handler.mappingVersion().isBlank()
                || !versioningStrategies(handler).isEmpty();
    }

    static Set<String> versioningStrategies(HandlerMethodModel handler) {
        Set<String> strategies = new LinkedHashSet<>();
        for (String path : handler.effectivePaths()) {
            for (String segment : segments(path)) {
                if (VERSION_SEGMENT.matcher(segment).matches()) {
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
        if (hasVersionParam(handler.params())
                || handler.versionBindings().stream().anyMatch(value -> value.startsWith("query:"))) {
            strategies.add("QUERY");
        }
        if (hasVersionParam(handler.headers())
                || handler.versionBindings().stream().anyMatch(value -> value.startsWith("header:"))) {
            strategies.add("HEADER");
        }
        return strategies;
    }

    /** True when a mapping {@code params}/{@code headers} entry keys on a known API-version name. */
    private static boolean hasVersionParam(List<String> conditions) {
        for (String condition : conditions) {
            if (condition.trim().startsWith("!") || condition.contains("!=")) {
                continue;
            }
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
        String lower = mediaType.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("!")) {
            return false;
        }
        return Pattern.compile(";\\s*(?:version|v)\\s*=\\s*\"?[^\\s;\"=]+")
                        .matcher(lower)
                        .find()
                || Pattern.compile("[.+-]v\\d+(?:[.+-]|$)")
                        .matcher(normalizeMediaType(lower))
                        .find();
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
    static Set<String> pathVariableTokens(String path) {
        return new LinkedHashSet<>(pathVariableTokenOccurrences(path));
    }

    static List<String> pathVariableTokenOccurrences(String path) {
        List<String> tokens = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < path.length(); i++) {
            char ch = path.charAt(i);
            if (ch == '\\') {
                i++;
                continue;
            }
            if (ch == '{') {
                if (depth++ == 0) {
                    start = i + 1;
                }
            } else if (ch == '}' && depth > 0 && --depth == 0) {
                String token = path.substring(start, i);
                int colon = token.indexOf(':');
                if (colon >= 0) {
                    token = token.substring(0, colon);
                }
                if (token.startsWith("*")) {
                    token = token.substring(1);
                }
                if (!token.isBlank() && !token.contains("/")) {
                    tokens.add(token.trim());
                }
            }
        }
        return tokens;
    }

    static boolean hasUnknownBody(ExceptionHandlerModel handler) {
        String body = handler.bodyTypeName();
        String category = ErrorBodyCategory.classify(null, body, handler.returnsResponseEntity());
        return category.equals(ErrorBodyCategory.DYNAMIC)
                || category.equals(ErrorBodyCategory.UNRESOLVED)
                || RestApiModel.Types.RESPONSE_ENTITY.equals(body)
                || RestApiModel.Types.HTTP_ENTITY.equals(body)
                || body.equals("com.fasterxml.jackson.databind.JsonNode")
                || body.equals("tools.jackson.databind.JsonNode");
    }

    /**
     * True when any path declares a JAX-RS regex path-variable template whose regex matches everything
     * (e.g. {@code {path:.*}}, {@code {path:.+}}) — the JAX-RS analogue of Spring's {@code /**}/{@code
     * {*path}} catch-all. This does not establish shadowing or the response status. Constrained templates
     * like {@code {id:[0-9]+}} are not flagged.
     */
    static boolean hasCatchAllRegexPathVariable(HandlerMethodModel handler) {
        for (String path : handler.effectivePaths()) {
            Matcher matcher = PATH_VARIABLE_REGEX_TOKEN.matcher(path);
            while (matcher.find()) {
                if (isAllMatchingRegex(matcher.group(1).trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isAllMatchingRegex(String regex) {
        String normalized = regex;
        if (normalized.startsWith("(") && normalized.endsWith(")")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized.equals(".*") || normalized.equals(".+");
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
                "A Spring @RequestMapping without an effective HTTP-method constraint matches multiple verbs."
                        + " The declaration does not establish whether the handler changes state.",
                "Replace @RequestMapping without a method with @GetMapping/@PostMapping/@PutMapping/@DeleteMapping/"
                        + "@PatchMapping (or set the method attribute).",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> !handler.jaxRs(),
                handler -> !handler.explicitHttpMethod(),
                "no HTTP method declared");
    }
}

final class NoDuplicateRouteMappingsRule extends AbstractRestApiRule {
    NoDuplicateRouteMappingsRule() {
        super(new RestApiRuleDefinition(
                "RAPI-MAP-002",
                "No duplicate route mappings",
                RestApiCategory.ROUTING,
                "HIGH",
                "Two imported handlers declare the same HTTP method, complete path and dispatch conditions."
                        + " This exact-condition check is not a complete framework ambiguity analysis.",
                "Ensure each (HTTP method, path, consumes/produces/params/headers/version) combination is handled by"
                        + " exactly one method.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        Map<RouteKey, List<String>> byRoute = new LinkedHashMap<>();
        for (HandlerMethodModel handler : context.targets(context.handlers())) {
            List<String> methods = handler.httpMethods().isEmpty() ? List.of("ANY") : handler.httpMethods();
            String condition = conditionKey(handler);
            for (String method : methods) {
                for (String path : new LinkedHashSet<>(handler.effectivePaths())) {
                    RouteKey key = new RouteKey(handler.framework(), method, path, condition);
                    byRoute.computeIfAbsent(key, ignored -> new ArrayList<>())
                            .add(handler.controllerSimpleName() + "#" + handler.methodName());
                }
            }
        }
        List<String> violations = new ArrayList<>();
        for (Map.Entry<RouteKey, List<String>> entry : byRoute.entrySet()) {
            if (entry.getValue().size() > 1) {
                RouteKey route = entry.getKey();
                violations.add(route.framework() + " " + route.method() + " " + route.path()
                        + " with matching dispatch conditions handled by " + String.join(", ", entry.getValue()));
            }
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }

    private record RouteKey(RestApiModel.Framework framework, String method, String path, String conditions) {}

    /**
     * Distinguishes routes that share a verb and path but differ by content negotiation (consumes/produces),
     * required params/headers, or API version, so legitimate conditional mappings are not reported as duplicates.
     */
    private static String conditionKey(HandlerMethodModel handler) {
        List<String> parts = new ArrayList<>();
        addConditionParts(parts, "consumes:", handler.effectiveConsumes());
        addConditionParts(parts, "produces:", handler.effectiveProduces());
        addConditionParts(parts, "params:", handler.params());
        addConditionParts(parts, "headers:", handler.headers());
        if (!handler.mappingVersion().isBlank()) {
            parts.add("version=" + handler.mappingVersion());
        }
        if (parts.isEmpty()) {
            return "";
        }
        parts.sort(String::compareTo);
        return " {" + String.join(",", parts) + "}";
    }

    private static void addConditionParts(List<String> target, String kind, List<String> values) {
        for (String value : values) {
            target.add(kind + value);
        }
    }
}

final class StateChangingHandlersNotOnGetRule extends AbstractRestApiRule {
    StateChangingHandlersNotOnGetRule() {
        super(new RestApiRuleDefinition(
                "RAPI-MAP-003",
                "Review mutation-like names on GET handlers",
                RestApiCategory.ROUTING,
                "LOW",
                "GET must be safe, but a create/update/delete/save-style method name is only a review signal,"
                        + " not proof of mutation. Crawlers and prefetchers may invoke GET automatically.",
                "Verify GET safety; if the operation requests a state change, use POST/PUT/PATCH/DELETE.",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.httpMethods().contains("GET"),
                handler -> handler.nameLooksStateChanging()
                        && handler.httpMethods().contains("GET"),
                "mutation-like name mapped to GET; review safety");
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
                        + " @RequestMapping duplicate routing information. Mappings declared on a controller"
                        + " interface are not evaluated: they are inherited by every implementation, so the"
                        + " implementing author cannot restructure them, and spec-first code generators (for example"
                        + " openapi-generator's kotlin-spring interfaceOnly output) emit exactly that layout.",
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
        for (ControllerModel controller : context.targets(
                context.controllers(),
                candidate -> !candidate.declaredOnInterface() && candidate.handlerCount() >= 2)) {
            if (controller.declaredOnInterface()
                    || !controller.typeLevelPaths().isEmpty()
                    || controller.handlerCount() < 2) {
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
            for (String path : handler.effectivePaths()) {
                String first = firstSegment(path);
                if (first == null) {
                    return null;
                }
                if (shared == null) {
                    shared = first;
                } else if (!shared.equals(first)) {
                    return null;
                }
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
                "INFO",
                "Trailing or doubled slashes may differ from a project's URL convention. This is optional style;"
                        + " Spring distinguishes trailing-slash variants, while other frameworks have different"
                        + " semantics.",
                "Review literal slash conventions without rewriting intentional paths or regex templates.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        List<String> violations = new ArrayList<>();
        for (ControllerModel controller : context.controllers()) {
            for (String path : context.targets(controller.typeLevelPaths())) {
                if (hasIrregularSlash(path)) {
                    violations.add(controller.simpleName() + " — class-level mapping path '" + path
                            + "' has an irregular slash");
                }
            }
        }
        for (HandlerMethodModel handler : context.handlers()) {
            for (String path : context.targets(handler.mappingPaths())) {
                if (hasIrregularSlash(path)) {
                    violations.add(handler.describe() + " — mapping path '" + path + "' has an irregular slash");
                }
            }
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }

    private static boolean hasIrregularSlash(String path) {
        if (path.contains("${") || path.contains("#{")) {
            return false;
        }
        boolean trailing = path.length() > 1 && path.endsWith("/");
        boolean doubled = false;
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            char ch = path.charAt(i);
            if (ch == '\\') {
                i++;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}' && depth > 0) {
                depth--;
            } else if (ch == '/' && depth == 0 && i > 0 && path.charAt(i - 1) == '/') {
                doubled = true;
            }
        }
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
        if (context.jaxRs()) return RestApiRuleSupport.springPathBinding(definition());
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            if (handler.jaxRs()
                    || handler.pathVariableNames().isEmpty()
                    || handler.effectivePaths().isEmpty()) {
                continue;
            }
            for (String path : handler.effectivePaths()) {
                if (path.contains("${") || path.contains("#{")) {
                    continue;
                }
                Set<String> tokens = RestApiRuleHelp.pathVariableTokens(path);
                context.evidence().applicable = true;
                List<String> unmatched = new ArrayList<>();
                for (String name : handler.pathVariableNames()) {
                    if (!tokens.contains(name)) {
                        unmatched.add(name);
                    }
                }
                if (!unmatched.isEmpty()) {
                    violations.add(handler.describe() + " — required @PathVariable name(s) " + unmatched
                            + " have no matching {token} in alternative '" + path + "'");
                }
            }
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }
}

final class NoRequestBodyOnBodylessMethodsRule extends AbstractRestApiRule {
    NoRequestBodyOnBodylessMethodsRule() {
        super(new RestApiRuleDefinition(
                "RAPI-MAP-007",
                "Review request entities on GET/HEAD/DELETE",
                RestApiCategory.ROUTING,
                "MEDIUM",
                "GET, HEAD and DELETE request content has no generally defined semantics in RFC 9110. Private"
                        + " agreements are possible, but intermediary and client interoperability needs review.",
                "Prefer query/path parameters or POST/PUT/PATCH unless a private request-content agreement is"
                        + " intentional.",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                NoRequestBodyOnBodylessMethodsRule::hasBodylessMethod,
                handler -> handler.hasRequestBody() && hasBodylessMethod(handler),
                "request entity declared on a GET/HEAD/DELETE handler");
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
                "INFO",
                "Verb-like paths may duplicate the HTTP method under a noun-oriented URL convention."
                        + " This is optional design guidance; action endpoints can be legitimate.",
                "Consider resource nouns (/users, /orders) where they fit the API's design.",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            for (String path : handler.effectivePaths()) {
                for (String segment : context.targets(RestApiRuleHelp.staticSegments(path))) {
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
                "An English-language spelling heuristic suggests a singular path for a collection return declaration."
                        + " It does not establish runtime resource cardinality or an HTTP requirement.",
                "Consider plural collection names if that matches the project's language and naming convention.",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            if (!handler.httpMethods().contains("GET")
                    || !(handler.returnsCollection() || handler.returnsPageOrSlice())) {
                continue;
            }
            for (String path : handler.effectivePaths()) {
                List<String> staticSegments = RestApiRuleHelp.staticSegments(path);
                if (staticSegments.isEmpty()) {
                    continue;
                }
                context.evidence().applicable = true;
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
                "INFO",
                "camelCase, snake_case or uppercase segments differ from an optional lowercase kebab-case convention."
                        + " Case-sensitive URI paths are valid.",
                "Consider lowercase kebab-case (/order-items) when choosing a consistent project convention.",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            for (String path : handler.effectivePaths()) {
                for (String segment : context.targets(RestApiRuleHelp.staticSegments(path))) {
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
                "LOW",
                "A creation-like POST name with no visible status selection suggests reviewing the success status."
                        + " The name does not prove creation, and runtime response behavior is not observed.",
                "For completed creation consider 201; asynchronous acceptance may use 202. A 201 identifies the"
                        + " resource through Location when present, otherwise through the target URI.",
                RestApiRuleHelp.CREATED_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler ->
                        handler.httpMethods().contains("POST") && RestApiRuleHelp.isCreationName(handler.methodName()),
                handler -> handler.httpMethods().contains("POST")
                        && RestApiRuleHelp.isCreationName(handler.methodName())
                        && handler.serializesBody()
                        && !handler.returnsResponseEntity()
                        && !handler.hasResponseParam()
                        && !handler.hasResponseStatus(),
                "creation-like POST name without a declared status; review completed-creation semantics");
    }
}

final class VoidDeleteReturns204Rule extends AbstractRestApiRule {
    VoidDeleteReturns204Rule() {
        super(new RestApiRuleDefinition(
                "RAPI-RESP-002",
                "Void DELETE returns 204 No Content",
                RestApiCategory.RESPONSES,
                "LOW",
                "A Spring DELETE with a no-body return and no visible status selection may use the default 200. Review"
                        + " whether 204 more precisely describes completed deletion; explicit statuses are preserved.",
                "Annotate void DELETE handlers with @ResponseStatus(HttpStatus.NO_CONTENT) or return"
                        + " ResponseEntity.noContent().",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> !handler.jaxRs() && handler.httpMethods().contains("DELETE") && handler.returnsVoid(),
                handler -> !handler.jaxRs()
                        && handler.httpMethods().contains("DELETE")
                        && handler.returnsVoid()
                        && handler.serializesBody()
                        && !handler.returnsResponseEntity()
                        && !handler.hasResponseParam()
                        && !handler.hasResponseStatus(),
                "no-body Spring DELETE without a declared status; consider 204");
    }
}

final class NoUntypedResponseEntityRule extends AbstractRestApiRule {
    NoUntypedResponseEntityRule() {
        super(new RestApiRuleDefinition(
                "RAPI-RESP-003",
                "Response envelopes expose a typed body contract",
                RestApiCategory.RESPONSES,
                "LOW",
                "A raw or dynamic generic response envelope limits body-schema inference from the signature."
                        + " Explicit schemas can still document dynamic responses; non-generic JAX-RS Response is not"
                        + " a raw generic declaration.",
                "Use a concrete response-envelope payload type where practical, or document its dynamic schema"
                        + " explicitly.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> !RestApiModel.Types.JAXRS_RESPONSE.equals(handler.bodyTypeName())
                        && handler.returnsBodyEnvelope(),
                handler -> !RestApiModel.Types.JAXRS_RESPONSE.equals(handler.bodyTypeName())
                        && handler.returnsBodyEnvelope()
                        && (handler.bodyIsUntyped()
                                || RestApiModel.Types.RESPONSE_ENTITY.equals(handler.bodyTypeName())
                                || RestApiModel.Types.HTTP_ENTITY.equals(handler.bodyTypeName())
                                || RestApiModel.Types.QUARKUS_REST_RESPONSE.equals(handler.bodyTypeName())),
                "generic response envelope has a raw or dynamic body declaration");
    }
}

final class ReadEndpointsReturnRepresentationRule extends AbstractRestApiRule {
    ReadEndpointsReturnRepresentationRule() {
        super(new RestApiRuleDefinition(
                "RAPI-RESP-004",
                "Consider evolvable representations for scalar reads",
                RestApiCategory.RESPONSES,
                "INFO",
                "A scalar is a valid representation, but adding fields later may require a contract change.",
                "Consider a DTO/record if the read representation is expected to grow; scalar and text APIs can be"
                        + " intentional.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.httpMethods().contains("GET") && handler.serializesBody(),
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
                "A no-body GET declaration with no explicit status or imperative response argument warrants a"
                        + " representation review. Its actual status and content are not observed.",
                "Return the resource representation from GET handlers (or use a more precise status when no body is"
                        + " expected).",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.httpMethods().contains("GET") && handler.serializesBody(),
                handler -> handler.httpMethods().contains("GET")
                        && handler.returnsVoid()
                        && handler.serializesBody()
                        && !handler.returnsResponseEntity()
                        && !handler.hasResponseParam()
                        && !handler.hasResponseStatus(),
                "GET declares no body and no explicit status or imperative response argument");
    }
}

final class NoContentResponsesHaveNoBodyRule extends AbstractRestApiRule {
    NoContentResponsesHaveNoBodyRule() {
        super(new RestApiRuleDefinition(
                "RAPI-RESP-006",
                "204 No Content responses carry no body",
                RestApiCategory.RESPONSES,
                "HIGH",
                "204 forbids response content. A content-capable return declaration alongside"
                        + " @ResponseStatus(NO_CONTENT) deserves review, but does not prove that content is transmitted.",
                "Return void (or ResponseEntity) for 204 responses, or use 200 OK when a body is required.",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> !handler.jaxRs() && "NO_CONTENT".equals(handler.responseStatusValue()),
                handler -> !handler.jaxRs()
                        && "NO_CONTENT".equals(handler.responseStatusValue())
                        && !handler.returnsVoid()
                        && !"java.lang.Void".equals(handler.bodyTypeName())
                        && !handler.returnsResponseEntity()
                        && !handler.hasResponseParam()
                        && handler.serializesBody(),
                "204 No Content annotation accompanies a content-capable return declaration");
    }
}

final class ResponseStatusIgnoredWithResponseEntityRule extends AbstractRestApiRule {
    ResponseStatusIgnoredWithResponseEntityRule() {
        super(new RestApiRuleDefinition(
                "RAPI-RESP-007",
                "Review overlapping ResponseStatus and ResponseEntity declarations",
                RestApiCategory.RESPONSES,
                "MEDIUM",
                "A status-bearing ResponseEntity normally selects status instead of a method-level @ResponseStatus. A"
                        + " nonempty annotation reason may short-circuit Spring response processing; inspect precedence.",
                "Choose an intentional status-selection path. Before removing @ResponseStatus, check whether its"
                        + " reason participates in the framework's error dispatch.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> !handler.jaxRs() && handler.methodHasResponseStatus(),
                handler -> !handler.jaxRs() && handler.methodHasResponseStatus() && handler.returnsResponseEntity(),
                "method-level @ResponseStatus and status-bearing ResponseEntity both declared; review precedence");
    }
}

final class RequestBodyIsValidatedRule extends AbstractRestApiRule {
    RequestBodyIsValidatedRule() {
        super(new RestApiRuleDefinition(
                "RAPI-VALID-001",
                "Review request-body cascade validation",
                RestApiCategory.VALIDATION,
                "LOW",
                "A complex request payload without a recognized cascade-validation annotation warrants review."
                        + " Constraints, programmatic validation and actual validation execution are not established.",
                "If payload fields need cascade bean-validation, use @Valid and declare constraints on the payload"
                        + " DTO.",
                RestApiRuleHelp.VALIDATION_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                HandlerMethodModel::hasRequestBody,
                handler ->
                        handler.hasRequestBody() && !handler.requestBodyValidated() && !handler.requestBodyIsSimple(),
                "complex request payload has no recognized cascade-validation annotation");
    }
}

final class NoMassAssignmentViaEntitiesRule extends AbstractRestApiRule {
    NoMassAssignmentViaEntitiesRule() {
        super(new RestApiRuleDefinition(
                "RAPI-VALID-002",
                "No mass-assignment via JPA entities",
                RestApiCategory.VALIDATION,
                "HIGH",
                "Binding a request directly to a JPA @Entity couples input to persistence and may allow over-posting."
                        + " The signature does not establish which fields the binder actually permits.",
                "Bind requests to a dedicated request DTO and map explicitly to the entity.",
                RestApiRuleHelp.VALIDATION_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                HandlerMethodModel::hasRequestBody,
                HandlerMethodModel::requestBodyIsEntity,
                "request payload declared as a JPA @Entity");
    }
}

final class OptionalPrimitiveRequestParamRule extends AbstractRestApiRule {
    OptionalPrimitiveRequestParamRule() {
        super(new RestApiRuleDefinition(
                "RAPI-VALID-003",
                "Optional @RequestParam is not a primitive",
                RestApiCategory.VALIDATION,
                "MEDIUM",
                "An optional Java numeric primitive @RequestParam without a nonblank default can fail binding"
                        + " when omitted: null cannot be unboxed and blank defaults cannot be converted."
                        + " Spring supplies false for boolean; uncertain Kotlin defaults are excluded.",
                "Use the boxed wrapper type (e.g. Integer) or provide a defaultValue for optional primitive query"
                        + " parameters.",
                RestApiRuleHelp.VALIDATION_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> !handler.jaxRs(),
                handler -> !handler.jaxRs() && handler.hasUnboundedPrimitiveRequestParam(),
                "optional primitive @RequestParam can fail binding when omitted");
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
                handler -> !handler.returnsVoid() && handler.serializesBody(),
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
                "LOW",
                "Map, Object or JsonNode limits schema inference from the return signature. Dynamic objects are valid"
                        + " and can be documented through explicit schemas.",
                "Prefer a typed DTO/record for inference, or explicitly document the dynamic response schema.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> !handler.returnsVoid() && !handler.returnsBodyEnvelope() && handler.serializesBody(),
                handler -> handler.bodyIsUntyped()
                        && !handler.returnsVoid()
                        && !handler.returnsBodyEnvelope()
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
        for (HandlerMethodModel handler : context.targets(
                context.handlers(), candidate -> !candidate.returnsVoid() && candidate.serializesBody())) {
            if (handler.bodyIsUntyped()) {
                // Object/Map/JsonNode does not describe an inspected DTO's members.
                context.evidence().requiredUnknown = true;
            }
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
                "A collection return without visible pagination input warrants a bounded-result review."
                        + " The signature does not establish database load, response size or internal limits.",
                "Consider page/size/cursor input or document a fixed bound; explicitly declared streams have different"
                        + " semantics.",
                RestApiRuleHelp.PAGINATION_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> handler.httpMethods().contains("GET")
                        && handler.returnsCollection()
                        && handler.serializesBody(),
                handler -> handler.httpMethods().contains("GET")
                        && handler.returnsCollection()
                        && !handler.returnsPageOrSlice()
                        && !handler.hasPageable()
                        && !handler.hasExplicitPageParam()
                        && !isExplicitStream(handler)
                        && handler.serializesBody(),
                "collection GET has no visible pagination input");
    }

    private static boolean isExplicitStream(HandlerMethodModel handler) {
        return handler.returnsStream()
                && handler.effectiveProduces().stream()
                        .map(RestApiRuleHelp::normalizeMediaType)
                        .anyMatch(media -> Set.of(
                                        "text/event-stream",
                                        "application/x-ndjson",
                                        "application/stream+json",
                                        "application/json-seq")
                                .contains(media));
    }
}

final class ReturnPagedTypeRule extends AbstractRestApiRule {
    ReturnPagedTypeRule() {
        super(new RestApiRuleDefinition(
                "RAPI-PAGE-002",
                "Pageable handlers return a paged type",
                RestApiCategory.PAGINATION,
                "LOW",
                "A Spring Pageable handler returning a collection exposes no paging metadata in that body signature."
                        + " Headers may supply links; Slice does not promise totals.",
                "Use a stable pagination representation such as PagedModel or documented pagination headers."
                        + " Do not rely on direct PageImpl serialization as a stable public contract.",
                RestApiRuleHelp.PAGINATION_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (context.jaxRs()) return RestApiRuleSupport.springDataPagination(definition());
        return handlersMatching(
                context,
                HandlerMethodModel::hasPageable,
                handler -> !handler.jaxRs()
                        && handler.hasPageable()
                        && handler.returnsCollection()
                        && !handler.returnsPageOrSlice(),
                "accepts Pageable but returns a raw collection");
    }
}

final class ConsistentPaginationVocabularyRule extends AbstractRestApiRule {
    ConsistentPaginationVocabularyRule() {
        super(new RestApiRuleDefinition(
                "RAPI-PAGE-003",
                "Consistent pagination parameter vocabulary across handlers",
                RestApiCategory.PAGINATION,
                "INFO",
                "Different declared pagination vocabularies may increase client learning cost, but can suit different"
                        + " workloads. Detection selects one family per handler by priority, not every accepted dialect.",
                "Review page/size, offset/limit and cursor conventions for consistency where workloads permit.",
                RestApiRuleHelp.PAGINATION_VOCABULARY_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (context.handlers().isEmpty()) {
            return RestApiRuleSupport.pass(definition());
        }
        Set<String> families = new LinkedHashSet<>();
        for (HandlerMethodModel handler : context.handlers()) {
            if (!handler.paginationParamFamily().isEmpty()) {
                context.evidence().applicable = true;
                families.add(handler.paginationParamFamily());
            }
        }
        if (families.size() <= 1) {
            return RestApiRuleSupport.pass(definition());
        }
        return RestApiRuleSupport.fromViolations(
                definition(),
                List.of("Mixed pagination parameter vocabularies detected: " + families
                        + ". Review whether different workloads justify these vocabularies."));
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
                "Review the API's compatibility policy; versioning is optional. If needed, choose a documented"
                        + " path, header, query or media-type strategy.",
                RestApiRuleHelp.API_VERSIONING_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (!context.jaxRs() && !context.evidence().versioningKnown) {
            return missingEvidence(context, "Required framework evidence could not be read.");
        }
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
            context.evidence().applicable = true;
            if (!RestApiRuleHelp.hasVersionSignal(handler)
                    && (handler.jaxRs() || !context.globalVersioningConfigured())) {
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
                "A POST/PUT/PATCH request entity without a consumes constraint has no explicit mapping-level media"
                        + " contract. Framework converters/readers still restrict which content types are readable.",
                "Declare consumes (e.g. application/json) on mutating endpoints that accept a request body.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> isMutating(handler) && handler.hasRequestBody(),
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
                "Review wildcard media-type scope",
                RestApiCategory.VERSIONING,
                "INFO",
                "Wildcard media ranges are valid negotiation behavior and may be intentional."
                        + " Review whether their breadth expresses the intended contract.",
                "Use concrete media types where appropriate, or document intentional wildcard negotiation.",
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
                "A PATCH without a positive concrete consumes declaration leaves the patch format unspecified by"
                        + " the mapping. RFC 5789 does not mandate JSON or any single patch format.",
                "Declare and document the accepted patch format: JSON, XML, vendor and binary formats can all be"
                        + " valid.",
                RestApiRuleHelp.PATCH_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.targets(
                context.handlers(), candidate -> candidate.httpMethods().contains("PATCH"))) {
            if (!handler.httpMethods().contains("PATCH")) {
                continue;
            }
            if (handler.effectiveConsumes().stream().noneMatch(PatchUsesPatchMediaTypeRule::isConcreteMediaType)) {
                violations.add(handler.describe() + " — PATCH declares no positive concrete consumes media type");
            }
        }
        return RestApiRuleSupport.fromViolations(definition(), violations);
    }

    private static boolean isConcreteMediaType(String mediaType) {
        String normalized = RestApiRuleHelp.normalizeMediaType(mediaType);
        return !normalized.startsWith("!") && !normalized.contains("*") && normalized.matches("[^\\s/]+/[^\\s/]+");
    }
}

final class CentralizedExceptionHandlingRule extends AbstractRestApiRule {
    CentralizedExceptionHandlingRule() {
        super(new RestApiRuleDefinition(
                "RAPI-ERR-001",
                "Centralized exception handling exists",
                RestApiCategory.ERROR_HANDLING,
                "INFO",
                "No application-wide advice or registered exception mapper was found in the imported model."
                        + " Framework defaults and local handlers may already provide an intentional error policy.",
                "Review the existing error policy before adding native application-wide advice or an exception mapper.",
                RestApiRuleHelp.PROBLEM_DETAIL_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (!context.evidence().completeExceptionModel) {
            return missingEvidence(
                    context,
                    "Controller and exception metadata is incomplete; missing handler declarations cannot be"
                            + " inferred.");
        }
        if (context.controllers().isEmpty()) {
            return RestApiRuleSupport.pass(definition());
        }
        context.evidence().applicable = true;
        if (context.hasExceptionHandling()) {
            boolean springDeclarations = context.controllers().stream().anyMatch(controller -> !controller.jaxRs())
                    || context.exceptionHandlers().stream().anyMatch(handler -> !handler.jaxRs());
            boolean jaxRsDeclarations = context.controllers().stream().anyMatch(ControllerModel::jaxRs)
                    || context.exceptionHandlers().stream().anyMatch(ExceptionHandlerModel::jaxRs);
            if (springDeclarations && jaxRsDeclarations) {
                return missingEvidence(
                        context,
                        "Application-wide error-handling presence cannot be attributed per framework in this mixed"
                                + " Spring/Jakarta REST model; advice or mapper presence is not transferred between"
                                + " stacks.");
            }
            if (jaxRsDeclarations && context.exceptionHandlers().stream().noneMatch(ExceptionHandlerModel::jaxRs)) {
                return missingEvidence(
                        context,
                        "The aggregate error-handling flag has no corresponding Jakarta REST mapper declaration;"
                                + " native handling presence cannot be established.");
            }
            return RestApiRuleSupport.pass(definition());
        }
        return RestApiRuleSupport.fromViolations(
                definition(),
                List.of("No application-wide advice or registered exception mapper was found in the imported model for "
                        + context.controllers().size() + " controller/resource declaration(s)."));
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
                "Prefer specific declared failures and native exception handlers where useful; this is maintainability"
                        + " guidance, not an HTTP requirement. Missing Kotlin throws declarations do not prove no"
                        + " failures.",
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
                "Spring's ProblemDetail/ErrorResponse types can simplify optional RFC 9457 adoption. A custom error"
                        + " DTO may already implement that contract; declarations alone do not prove nonconformance.",
                "Consider Spring ProblemDetail/ErrorResponse if RFC 9457 fits the error policy; custom contracts"
                        + " remain valid.",
                RestApiRuleHelp.PROBLEM_DETAIL_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (context.jaxRs()) return RestApiRuleSupport.springProblemDetails(definition());
        if (context.exceptionHandlers().isEmpty()) {
            return RestApiRuleSupport.pass(definition());
        }
        List<String> violations = new ArrayList<>();
        for (ExceptionHandlerModel handler : context.targets(
                context.exceptionHandlers(),
                candidate -> !candidate.jaxRs()
                        && candidate.rendersBody()
                        && !candidate.returnsVoid()
                        && !candidate.hasResponseParam())) {
            if (RestApiRuleHelp.hasUnknownBody(handler)) context.evidence().requiredUnknown = true;
            if (!handler.jaxRs()
                    && !handler.returnsProblemType()
                    && handler.rendersBody()
                    && !handler.returnsVoid()
                    && !handler.hasResponseParam()
                    && !RestApiRuleHelp.hasUnknownBody(handler)) {
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
                        + " @ResponseStatus has no explicit status selection visible in the Spring declaration."
                        + " Runtime response rewriting is not observed.",
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
        for (ExceptionHandlerModel handler : context.targets(
                context.exceptionHandlers(),
                candidate -> !candidate.jaxRs()
                        && candidate.rendersBody()
                        && !candidate.returnsVoid()
                        && !candidate.hasResponseParam())) {
            if (RestApiRuleHelp.hasUnknownBody(handler)) context.evidence().requiredUnknown = true;
            if (!handler.jaxRs()
                    && handler.rendersBody()
                    && !handler.returnsResponseEntity()
                    && !handler.hasResponseStatus()
                    && !handler.returnsProblemType()
                    && !handler.returnsVoid()
                    && !RestApiRuleHelp.hasUnknownBody(handler)
                    && !handler.hasResponseParam()) {
                violations.add(simpleName(handler.declaringClassName()) + "#" + handler.methodName()
                        + " declares a body without explicit status selection; review the Spring default status");
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
                "Consider explicit operation documentation enrichment",
                RestApiCategory.ERROR_HANDLING,
                "INFO",
                "OpenAPI annotations are available but some handlers have no explicit @Operation enrichment."
                        + " Generated, static or filtered documentation may already describe them.",
                "Consider @Operation summaries/descriptions where they add useful information beyond generated"
                        + " documentation.",
                RestApiRuleHelp.OPENAPI_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (!context.evidence().openApiKnown)
            return missingEvidence(context, "Required framework evidence could not be read.");
        if (!context.openApiAnnotationsPresent()) {
            return RestApiRuleSupport.skipped(definition(), "No OpenAPI annotations were found on the host classpath.");
        }
        return handlersMatching(
                context,
                handler -> !handler.hidden(),
                handler -> !handler.hasOperationAnnotation(),
                "no @Operation annotation");
    }
}

final class ControllersAreTaggedRule extends AbstractRestApiRule {
    ControllersAreTaggedRule() {
        super(new RestApiRuleDefinition(
                "RAPI-DOC-002",
                "Consider explicit OpenAPI grouping",
                RestApiCategory.ERROR_HANDLING,
                "INFO",
                "No explicit tag or operation-tag grouping was found on some controllers/resources."
                        + " Generators and static documents can supply grouping without these annotations.",
                "Consider explicit tags when they improve the generated or supplied OpenAPI grouping.",
                RestApiRuleHelp.OPENAPI_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (!context.evidence().openApiKnown)
            return missingEvidence(context, "Required framework evidence could not be read.");
        if (!context.openApiAnnotationsPresent()) {
            return RestApiRuleSupport.skipped(definition(), "No OpenAPI annotations were found on the host classpath.");
        }
        Set<String> controllersWithTaggedHandler = new LinkedHashSet<>();
        for (HandlerMethodModel handler : context.handlers()) {
            if (handler.hasTag()) {
                controllersWithTaggedHandler.add(handler.controllerClassName());
            }
        }
        List<String> violations = new ArrayList<>();
        for (ControllerModel controller : context.targets(context.controllers(), candidate -> !candidate.hidden())) {
            if (controller.hidden()) {
                continue;
            }
            if (!controller.hasTag() && !controllersWithTaggedHandler.contains(controller.className())) {
                violations.add(controller.simpleName() + " has no explicit tag/operation-tag grouping");
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
                "Retired heuristic: a literal URI can identify a resource without a template variable.",
                "Choose URI templates according to the resource model; a literal mutation target is valid.",
                RestApiRuleHelp.REST_GUIDELINES));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return RestApiRuleSupport.skipped(
                definition(),
                "Retired heuristic: literal URIs identify resources; missing template variables do not establish a"
                        + " defect.");
    }
}

final class CreatedResponsesExposeLocationRule extends AbstractRestApiRule {
    CreatedResponsesExposeLocationRule() {
        super(new RestApiRuleDefinition(
                "RAPI-RESP-008",
                "Review discoverability of created resources",
                RestApiCategory.RESPONSES,
                "INFO",
                "A declared 201 is an optional discoverability review opportunity, not proof of a missing Location."
                        + " RFC 9110 identifies the primary resource by Location when present, otherwise the target URI."
                        + " Filters/advice may supply headers.",
                "Consider Location when it helps clients discover a newly created resource distinct from the target"
                        + " URI.",
                RestApiRuleHelp.CREATED_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> "CREATED".equals(handler.responseStatusValue()),
                handler -> "CREATED".equals(handler.responseStatusValue())
                        && !handler.returnsResponseEntity()
                        && !handler.hasResponseParam(),
                "declares 201 Created; optionally review resource discoverability (Location is not universally"
                        + " required)");
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
        for (HandlerMethodModel handler : context.targets(
                context.handlers(), ResponseProducingEndpointsDeclareProducesRule::serializesRepresentation)) {
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
        return handler.serializesBody() && !handler.returnsVoid() && !handler.hasResponseParam();
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
                "Spring path templates reject duplicate capture names such as /users/{id}/orders/{id}."
                        + " Jakarta REST has different scoped binding semantics and is not evaluated.",
                "Use distinct token names for each path variable (e.g. /users/{userId}/orders/{orderId}).",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (context.jaxRs()) return RestApiRuleSupport.springPathBinding(definition());
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.handlers()) {
            if (handler.jaxRs()) {
                continue;
            }
            for (String path : context.targets(handler.effectivePaths())) {
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
        if (path.contains("${") || path.contains("#{")) {
            return List.of();
        }
        for (String token : RestApiRuleHelp.pathVariableTokenOccurrences(path)) {
            count.merge(token, 1, Integer::sum);
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
                "INFO",
                "A /** or {*path} catch-all (Spring) or an all-matching {token:.*}/{token:.+} regex path template"
                        + " (JAX-RS) broadens the declared routing surface. It does not prove shadowing or a 200 response;"
                        + " Spring orders catch-alls after more-specific mappings.",
                "Review catch-all intent and unmatched-path handling; generic forwarding may legitimately need it.",
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
        return RestApiRuleHelp.hasCatchAllRegexPathVariable(handler);
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
                "More than 3 collection/{id} pairs exceeds this advisor's optional readability threshold,"
                        + " not an HTTP or URI limit.",
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
                "Retired heuristic: explicitly mapped dotted paths are valid and do not depend on implicit suffix"
                        + " matching.",
                "Keep intentional literal suffixes; choose Accept negotiation only when it suits the representation"
                        + " contract.",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return RestApiRuleSupport.skipped(
                definition(),
                "Retired heuristic: literal dotted mappings remain valid independently of implicit suffix"
                        + " negotiation.");
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
                "Review whether path, header, query or versioned-media transports should align. Spring's native version"
                        + " condition uses its configured resolver and is not a separate transport.",
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
            context.evidence().applicable = true;
            strategies.addAll(RestApiRuleHelp.versioningStrategies(handler));
        }
        if (strategies.size() <= 1) {
            return RestApiRuleSupport.pass(definition());
        }
        return RestApiRuleSupport.fromViolations(
                definition(),
                List.of("Mixed versioning strategies detected: " + strategies
                        + ". Standardise on one strategy across all API handlers."));
    }
}

final class BroadExceptionHandlerRule extends AbstractRestApiRule {
    BroadExceptionHandlerRule() {
        super(new RestApiRuleDefinition(
                "RAPI-ERR-005",
                "Broad @ExceptionHandler should not collapse all errors to one status",
                RestApiCategory.ERROR_HANDLING,
                "LOW",
                "A broad Exception/Throwable handler with a declared fixed non-5xx status warrants review."
                        + " Dynamic status envelopes, imperative response arguments and 5xx fallbacks do not establish"
                        + " that different errors collapse to one inappropriate status.",
                "Catch specific exception types and map each to its appropriate status (e.g. 400, 404, 409), and"
                        + " keep any Exception/Throwable catch-all as a last-resort fallback mapped to a 5xx status.",
                RestApiRuleHelp.PROBLEM_DETAIL_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (context.exceptionHandlers().isEmpty()) {
            return RestApiRuleSupport.pass(definition());
        }
        List<String> violations = new ArrayList<>();
        for (ExceptionHandlerModel handler : context.targets(
                context.exceptionHandlers(),
                candidate -> !candidate.jaxRs() && candidate.catchesExceptionOrThrowable())) {
            if (handler.jaxRs()
                    || !handler.catchesExceptionOrThrowable()
                    || !handler.hasResponseStatus()
                    || handler.returnsResponseEntity()
                    || handler.hasResponseParam()
                    || handler.returnsProblemType()) {
                continue;
            }
            boolean mapsToNonServerErrorStatus = handler.hasResponseStatus()
                    && !handler.responseStatusValue().isEmpty()
                    && !RestApiRuleHelp.SERVER_ERROR_STATUS_NAMES.contains(handler.responseStatusValue());
            if (mapsToNonServerErrorStatus) {
                violations.add(simpleName(handler.declaringClassName()) + "#" + handler.methodName()
                        + " catches Exception/Throwable and maps it to a fixed non-5xx status ("
                        + handler.responseStatusValue() + ")");
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
                "ResponseStatus exception annotations coexist with typed Spring problem declarations."
                        + " This is optional migration guidance, not proof of inconsistent runtime payloads.",
                "Review direct exception declarations against the chosen policy; adopting Spring ErrorResponseException"
                        + " is optional and introduces framework coupling.",
                RestApiRuleHelp.PROBLEM_DETAIL_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (context.jaxRs()) return RestApiRuleSupport.springProblemDetails(definition());
        if (context.responseStatusExceptionClasses().isEmpty()) {
            return RestApiRuleSupport.pass(definition());
        }
        boolean projectUsesProblemDetail = context.exceptionHandlers().stream()
                .anyMatch(handler -> !handler.jaxRs() && handler.returnsProblemType());
        if (!projectUsesProblemDetail) {
            return RestApiRuleSupport.pass(definition());
        }
        List<String> violations = new ArrayList<>();
        for (String className : context.targets(
                context.responseStatusExceptionClasses(),
                candidate -> context.thrownExceptions().stream()
                        .anyMatch(thrown -> thrown.exceptionTypeName().equals(candidate)))) {
            if (context.thrownExceptions().stream()
                    .noneMatch(thrown -> thrown.exceptionTypeName().equals(className))) {
                continue;
            }
            if (context.exceptionHandlers().stream()
                    .anyMatch(handler -> !handler.jaxRs()
                            && (handler.catchesExceptionOrThrowable()
                                    || handler.handledExceptionTypes().contains(className)))) {
                continue;
            }
            boolean knownCovered = context.thrownExceptions().stream()
                    .filter(thrown -> thrown.exceptionTypeName().equals(className))
                    .anyMatch(thrown -> context.exceptionHandlers().stream()
                            .filter(handler -> !handler.jaxRs())
                            .anyMatch(handler -> handler.handledExceptionTypes().stream()
                                    .anyMatch(thrown.exceptionSuperTypeNames()::contains)));
            if (knownCovered) {
                continue;
            }
            violations.add(simpleName(className)
                    + " declares @ResponseStatus alongside typed problem declarations; optionally review migration");
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
                "An unnamed Spring @RequestParam Map/MultiValueMap aggregates query parameters. Its signature does not"
                        + " establish individual typing or allowlisting; explicit documentation and validation may exist.",
                "Declare each accepted query parameter explicitly with a typed @RequestParam so the contract is"
                        + " self-documenting and validatable.",
                RestApiRuleHelp.VALIDATION_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> !handler.jaxRs(),
                handler -> !handler.jaxRs() && handler.hasUnboundedMapRequestParam(),
                "aggregate @RequestParam Map/MultiValueMap; per-key typing and allowlisting are not visible");
    }
}

final class LegacyDateInDtoRule extends AbstractRestApiRule {
    LegacyDateInDtoRule() {
        super(new RestApiRuleDefinition(
                "RAPI-DTO-005",
                "Response DTOs should prefer java.time over Date/Calendar",
                RestApiCategory.PAYLOADS,
                "LOW",
                "Declared response DTO fields use legacy Date/Calendar types. java.time offers more explicit"
                        + " temporal concepts; the signature does not prove a serialization failure.",
                "Replace java.util.Date/Calendar fields with java.time equivalents (Instant, LocalDate,"
                        + " ZonedDateTime, etc.).",
                RestApiRuleHelp.SPRING_WEB_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler -> !handler.returnsVoid() && handler.serializesBody(),
                handler -> handler.bodyHasLegacyDateField() && !handler.returnsVoid() && handler.serializesBody(),
                "response DTO contains java.util.Date/Calendar fields — prefer java.time types");
    }
}

final class IdempotencyKeyOnCreationEndpointsRule extends AbstractRestApiRule {
    IdempotencyKeyOnCreationEndpointsRule() {
        super(new RestApiRuleDefinition(
                "RAPI-VALID-005",
                "Consider an Idempotency-Key header on creation endpoints",
                RestApiCategory.VALIDATION,
                "INFO",
                "A creation-like POST has no declared Idempotency-Key parameter. Filters, gateways, natural keys"
                        + " or application deduplication may already make retries safe. This is an optional convention,"
                        + " not an HTTP requirement or proof of duplicate creation.",
                "Worth a design review: accept an Idempotency-Key header (@RequestHeader/@HeaderParam) and"
                        + " de-duplicate retried requests by that key for non-idempotent creation endpoints.",
                RestApiRuleHelp.IDEMPOTENCY_KEY_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        return handlersMatching(
                context,
                handler ->
                        handler.httpMethods().contains("POST") && RestApiRuleHelp.isCreationName(handler.methodName()),
                handler -> handler.httpMethods().contains("POST")
                        && RestApiRuleHelp.isCreationName(handler.methodName())
                        && !handler.hasIdempotencyKeyHeader(),
                "POST creation endpoint has no Idempotency-Key header parameter");
    }
}

final class DeprecatedEndpointsSignalDeprecationRule extends AbstractRestApiRule {
    DeprecatedEndpointsSignalDeprecationRule() {
        super(new RestApiRuleDefinition(
                "RAPI-DOC-003",
                "Deprecated endpoints signal deprecation to HTTP clients",
                RestApiCategory.ERROR_HANDLING,
                "INFO",
                "Retired heuristic: generators can infer Java/Kotlin deprecation; static documents and filters can"
                        + " also supply it without an Operation annotation.",
                "Review generated documentation and optionally use Deprecation/Sunset response headers for lifecycle"
                        + " policy.",
                RestApiRuleHelp.DEPRECATION_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        if (!context.evidence().openApiKnown)
            return missingEvidence(context, "Required framework evidence could not be read.");
        return RestApiRuleSupport.skipped(
                definition(),
                "Retired heuristic: missing explicit OpenAPI deprecation annotations do not prove missing client"
                        + " signals.");
    }
}

final class RetryAfterOnThrottlingResponsesRule extends AbstractRestApiRule {
    private static final Set<String> THROTTLING_STATUS_NAMES = Set.of("TOO_MANY_REQUESTS", "SERVICE_UNAVAILABLE");

    RetryAfterOnThrottlingResponsesRule() {
        super(new RestApiRuleDefinition(
                "RAPI-ERR-007",
                "Review Retry-After for declared 429/503 statuses",
                RestApiCategory.ERROR_HANDLING,
                "INFO",
                "A 429/503 status annotation is a Retry-After design-review opportunity. Response headers and the"
                        + " actual status are not observed; a missing header is not established.",
                "Consider a Retry-After header (RFC 9110 §10.2.3) alongside 429 (RFC 6585 §4) or 503 responses so"
                        + " clients know when to retry. For quota visibility, RateLimit and RateLimit-Policy are"
                        + " complementary fields from an active IETF HTTPAPI Internet-Draft, not an RFC.",
                RestApiRuleHelp.RETRY_AFTER_DOCS));
    }

    @Override
    RestApiRuleResultDto doEvaluate(RestApiContext context) {
        List<String> violations = new ArrayList<>();
        for (HandlerMethodModel handler : context.targets(
                context.handlers(), candidate -> THROTTLING_STATUS_NAMES.contains(candidate.responseStatusValue()))) {
            if (THROTTLING_STATUS_NAMES.contains(handler.responseStatusValue())
                    && !handler.returnsResponseEntity()
                    && !handler.hasResponseParam()) {
                violations.add(handler.describe() + " declares " + handler.responseStatusValue()
                        + "; optionally review Retry-After policy");
            }
        }

        for (ExceptionHandlerModel handler : context.targets(
                context.exceptionHandlers(),
                candidate -> THROTTLING_STATUS_NAMES.contains(candidate.responseStatusValue()))) {
            if (THROTTLING_STATUS_NAMES.contains(handler.responseStatusValue())
                    && !handler.returnsResponseEntity()
                    && !handler.hasResponseParam()) {
                violations.add(simpleName(handler.declaringClassName()) + "#" + handler.methodName() + " declares "
                        + handler.responseStatusValue() + "; optionally review Retry-After policy");
            }
        }

        return RestApiRuleSupport.fromViolations(definition(), violations);
    }

    private static String simpleName(String fullName) {
        int lastDot = fullName.lastIndexOf('.');
        return lastDot >= 0 ? fullName.substring(lastDot + 1) : fullName;
    }
}
