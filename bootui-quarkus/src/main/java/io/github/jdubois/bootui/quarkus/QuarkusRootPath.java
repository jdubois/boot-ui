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
}
