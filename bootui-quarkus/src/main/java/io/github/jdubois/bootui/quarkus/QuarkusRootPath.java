package io.github.jdubois.bootui.quarkus;

/**
 * Shared helper for stripping the configured {@code quarkus.http.root-path} prefix from a request path.
 *
 * <p>Quarkus mounts the whole application — including BootUI's JAX-RS resources and its static UI —
 * under {@code quarkus.http.root-path}, so under a non-default root-path (e.g. {@code /app}) the console
 * is served at {@code /app/bootui/**} while the global Vert.x filters that guard it still see the full
 * path. Both {@link BootUiQuarkusSafetyFilter} and {@link QuarkusPanelAccessFilter} need to strip this
 * prefix before applying their own path-based checks; this class is the single implementation so the two
 * filters can never silently drift in how they interpret a non-default root-path.
 */
final class QuarkusRootPath {

    static final String ROOT_PATH_KEY = "quarkus.http.root-path";

    private QuarkusRootPath() {}

    /**
     * Normalizes a {@code quarkus.http.root-path} value to a strip-prefix ({@code ""} for the default). A
     * missing/blank value normalizes to {@code ""} (no prefix), which still guards the default
     * {@code /bootui} surface — the root-path is read live and <em>fails closed</em>.
     */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.equals("/") ? "" : trimmed;
    }

    /**
     * Removes {@code rootPathPrefix} (already {@link #normalize(String) normalized}) from
     * {@code normalizedPath} so path-based checks are root-path-relative. Without stripping, a filter
     * matching on a literal {@code /bootui} prefix would not recognize the root-path-prefixed path and
     * would be skipped entirely (fail-open).
     */
    static String stripPrefix(String normalizedPath, String rootPathPrefix) {
        if (normalizedPath == null) {
            return null;
        }
        normalizedPath = withoutMatrixParameters(normalizedPath);
        if (rootPathPrefix.isEmpty()) {
            return normalizedPath;
        }
        if (normalizedPath.equals(rootPathPrefix)) {
            return "/";
        }
        if (normalizedPath.startsWith(rootPathPrefix + "/")) {
            return normalizedPath.substring(rootPathPrefix.length());
        }
        return normalizedPath;
    }

    /**
     * Rewrites a path the way the JAX-RS router sees it. Vert.x leaves {@code ;name=value} matrix parameters in
     * {@code normalizedPath()} and RESTEasy Reactive drops them before it dispatches; once a path has any, it also
     * percent-decodes every segment, so {@code %2F} then separates segments for the router but not for the guards.
     * Without mirroring both, a request to {@code /bootui/api%2Fdismissed-findings;x=1} would reach its resource
     * without matching the path the guards check (fail-open). A path without a matrix parameter is returned as is.
     */
    static String withoutMatrixParameters(String path) {
        if (path.indexOf(';') < 0) {
            return path;
        }
        StringBuilder out = new StringBuilder(path.length());
        boolean skipping = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/') {
                skipping = false;
            } else if (c == ';') {
                skipping = true;
            }
            if (!skipping) {
                out.append(c);
            }
        }
        return percentDecoded(out.toString());
    }

    /** Decodes every {@code %XX} escape as UTF-8; a malformed escape stays as written. */
    private static String percentDecoded(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); ) {
            char c = value.charAt(i);
            int high = c == '%' && i + 2 < value.length() ? Character.digit(value.charAt(i + 1), 16) : -1;
            int low = high >= 0 ? Character.digit(value.charAt(i + 2), 16) : -1;
            if (high >= 0 && low >= 0) {
                bytes.write(high * 16 + low);
                i += 3;
            } else {
                byte[] literal = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                bytes.write(literal, 0, literal.length);
                i++;
            }
        }
        return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
