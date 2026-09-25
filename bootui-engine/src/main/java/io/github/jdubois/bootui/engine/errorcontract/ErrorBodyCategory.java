package io.github.jdubois.bootui.engine.errorcontract;

import java.util.Set;

/**
 * The one place that decides what an error response body looks like from a declaration alone.
 *
 * <p>The REST API panel's error-contract catalogue and the {@code RAPI-ERR-010} advisor rule both classify
 * the same declarations. Keeping the type sets and the decision order here means the panel and the rule can
 * never disagree about whether two handlers share a contract.</p>
 *
 * <p>Types are matched by name so the engine stays free of Spring, Quarkus and Jakarta REST dependencies.</p>
 */
public final class ErrorBodyCategory {

    /** An RFC 9457 problem-details document. */
    public static final String PROBLEM_DETAIL = "PROBLEM_DETAIL";

    /** An application type with a statically known shape. */
    public static final String CUSTOM_OBJECT = "CUSTOM_OBJECT";

    /** A plain string or byte payload. */
    public static final String STRING = "STRING";

    /** No body at all. */
    public static final String EMPTY = "EMPTY";

    /** A body is produced but its shape is only decided at runtime. */
    public static final String DYNAMIC = "DYNAMIC";

    /** The declaration proves nothing about the body. */
    public static final String UNRESOLVED = "UNRESOLVED";

    /**
     * Types whose value is an RFC 9457 problem-details document, or a framework contract that always
     * renders one.
     */
    private static final Set<String> PROBLEM_DETAIL_TYPES = Set.of(
            "org.springframework.http.ProblemDetail",
            "org.springframework.web.ErrorResponse",
            "org.springframework.web.ErrorResponseException",
            "org.springframework.web.server.ResponseStatusException");

    /** Body types that are declared but carry no schema, so the real payload stays runtime-dependent. */
    private static final Set<String> UNTYPED_BODY_TYPES = Set.of(
            "java.lang.Object",
            "java.util.Map",
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.TreeMap",
            "jakarta.ws.rs.core.Response",
            "org.jboss.resteasy.reactive.RestResponse",
            "org.springframework.web.reactive.function.server.ServerResponse",
            "org.springframework.web.servlet.function.ServerResponse",
            "?");

    private static final Set<String> EMPTY_BODY_TYPES = Set.of("void", "java.lang.Void");

    private static final Set<String> STRING_BODY_TYPES = Set.of("java.lang.String", "java.lang.CharSequence", "byte[]");

    private ErrorBodyCategory() {}

    /**
     * Classifies a declared error response body.
     *
     * @param returnTypeName erased return type of the handler, or {@code null} when it is unknown
     * @param bodyTypeName the body type the signature exposes, or {@code null} when the signature hides it
     * @param bodyProven {@code true} when the declaration proves a body is produced even though its shape is
     *     not readable (for example a {@code ResponseEntity} or {@code Response} built at runtime)
     */
    public static String classify(String returnTypeName, String bodyTypeName, boolean bodyProven) {
        if (returnTypeName != null && EMPTY_BODY_TYPES.contains(returnTypeName)) {
            return EMPTY;
        }
        if (bodyTypeName == null || bodyTypeName.isBlank()) {
            return bodyProven ? DYNAMIC : UNRESOLVED;
        }
        if (EMPTY_BODY_TYPES.contains(bodyTypeName)) {
            return EMPTY;
        }
        if (PROBLEM_DETAIL_TYPES.contains(bodyTypeName)) {
            return PROBLEM_DETAIL;
        }
        if (STRING_BODY_TYPES.contains(bodyTypeName)) {
            return STRING;
        }
        if (UNTYPED_BODY_TYPES.contains(bodyTypeName)) {
            return DYNAMIC;
        }
        return CUSTOM_OBJECT;
    }
}
