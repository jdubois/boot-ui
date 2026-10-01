package io.github.jdubois.bootui.engine.restapi;

import java.util.Map;

/**
 * Maps numeric HTTP status codes to the Spring {@code HttpStatus} constant names that the REST API rules compare
 * against, so a Quarkus REST {@code @ResponseStatus(201)} and a Spring {@code @ResponseStatus(CREATED)} reach the
 * same rule logic without depending on Spring. Unlisted codes keep their numeric form and match no named status.
 */
final class HttpStatusNames {

    private static final Map<Integer, String> NAMES = Map.ofEntries(
            Map.entry(200, "OK"),
            Map.entry(201, "CREATED"),
            Map.entry(202, "ACCEPTED"),
            Map.entry(203, "NON_AUTHORITATIVE_INFORMATION"),
            Map.entry(204, "NO_CONTENT"),
            Map.entry(205, "RESET_CONTENT"),
            Map.entry(206, "PARTIAL_CONTENT"),
            Map.entry(301, "MOVED_PERMANENTLY"),
            Map.entry(302, "FOUND"),
            Map.entry(303, "SEE_OTHER"),
            Map.entry(304, "NOT_MODIFIED"),
            Map.entry(307, "TEMPORARY_REDIRECT"),
            Map.entry(308, "PERMANENT_REDIRECT"),
            Map.entry(400, "BAD_REQUEST"),
            Map.entry(401, "UNAUTHORIZED"),
            Map.entry(403, "FORBIDDEN"),
            Map.entry(404, "NOT_FOUND"),
            Map.entry(405, "METHOD_NOT_ALLOWED"),
            Map.entry(406, "NOT_ACCEPTABLE"),
            Map.entry(409, "CONFLICT"),
            Map.entry(410, "GONE"),
            Map.entry(412, "PRECONDITION_FAILED"),
            Map.entry(413, "CONTENT_TOO_LARGE"),
            Map.entry(415, "UNSUPPORTED_MEDIA_TYPE"),
            Map.entry(422, "UNPROCESSABLE_ENTITY"),
            Map.entry(428, "PRECONDITION_REQUIRED"),
            Map.entry(429, "TOO_MANY_REQUESTS"),
            Map.entry(500, "INTERNAL_SERVER_ERROR"),
            Map.entry(501, "NOT_IMPLEMENTED"),
            Map.entry(502, "BAD_GATEWAY"),
            Map.entry(503, "SERVICE_UNAVAILABLE"),
            Map.entry(504, "GATEWAY_TIMEOUT"));

    private HttpStatusNames() {}

    static String name(int code) {
        return NAMES.getOrDefault(code, Integer.toString(code));
    }
}
