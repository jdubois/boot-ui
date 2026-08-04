package io.github.jdubois.bootui.core;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalizes and validates the {@code bootui.path} configuration property.
 *
 * <p>Rules (fail-closed — an invalid value throws {@link IllegalArgumentException}):</p>
 * <ul>
 *   <li>Must start with {@code /}.</li>
 *   <li>Must not be {@code /} (the root path would intercept every application request).</li>
 *   <li>Must not contain {@code ..} (path-traversal prevention).</li>
 *   <li>Must not contain {@code ?} or {@code #} (no query/fragment components).</li>
 *   <li>Must not contain encoded characters or backslashes (routing ambiguity prevention).</li>
 *   <li>Must contain only RFC 3986 unreserved path-segment characters.</li>
 *   <li>Must not use the reserved internal {@code /bootui/**} namespace unless it is exactly {@code /bootui}.</li>
 *   <li>Must not contain consecutive slashes ({@code //}) after trailing slashes are removed.</li>
 *   <li>Trailing slashes are silently stripped during normalization.</li>
 *   <li>Blank/null input is rejected.</li>
 * </ul>
 *
 * <p>The default {@code /bootui} path is accepted and returned unchanged. This class is pure Java with
 * no framework dependencies and may be used by any adapter.</p>
 */
public final class BootUiPathNormalizer {

    /** Default BootUI base path. */
    public static final String DEFAULT_PATH = "/bootui";

    private static final Pattern SAFE_PATH = Pattern.compile("/[A-Za-z0-9._~-]+(?:/[A-Za-z0-9._~-]+)*");

    private BootUiPathNormalizer() {}

    /**
     * Normalizes and validates a configured base path.
     *
     * @param path the raw {@code bootui.path} value from configuration
     * @return the normalized path (trailing slash stripped, otherwise unchanged)
     * @throws IllegalArgumentException when the path fails validation
     */
    public static String normalize(String path) {
        return normalize(path, true, "bootui.path");
    }

    /**
     * Normalizes and validates the optional {@code bootui.api-path} property.
     *
     * <p>The API path follows the same syntax rules as the UI path but may live below the internal
     * {@code /bootui} mount, as the default {@code /bootui/api} does.</p>
     *
     * @param path the raw {@code bootui.api-path} value
     * @return the normalized API path
     * @throws IllegalArgumentException when the path fails validation
     */
    public static String normalizeApiPath(String path) {
        return normalize(path, false, "bootui.api-path");
    }

    private static String normalize(String path, boolean rejectInternalChild, String propertyName) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must not be blank. Use an absolute application path.");
        }

        String trimmed = path.strip();

        if (!trimmed.startsWith("/")) {
            throw new IllegalArgumentException(propertyName + " must start with '/' but was: '" + trimmed + "'");
        }
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        if (trimmed.equals("/")) {
            throw new IllegalArgumentException(
                    propertyName + " must not be '/' (the root path would intercept every application request).");
        }
        if (hasDotSegment(trimmed)) {
            throw new IllegalArgumentException(
                    propertyName + " must not contain '.' or '..' path segments: '" + trimmed + "'");
        }
        if (trimmed.contains("?")) {
            throw new IllegalArgumentException(
                    propertyName + " must not contain a query component ('?'): '" + trimmed + "'");
        }
        if (trimmed.contains("#")) {
            throw new IllegalArgumentException(
                    propertyName + " must not contain a fragment component ('#'): '" + trimmed + "'");
        }
        String lowercase = trimmed.toLowerCase(Locale.ROOT);
        if (lowercase.contains("%2f") || lowercase.contains("%5c")) {
            throw new IllegalArgumentException(
                    propertyName + " must not contain encoded path separators ('%2F' or '%5C'): '" + trimmed + "'");
        }
        if (trimmed.contains("//")) {
            throw new IllegalArgumentException(
                    propertyName + " must not contain consecutive slashes ('//'): '" + trimmed + "'");
        }
        if (!SAFE_PATH.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    propertyName + " may contain only letters, digits, '-', '_', '.', '~', and '/' path separators: '"
                            + trimmed
                            + "'");
        }
        if (rejectInternalChild && !trimmed.equals(DEFAULT_PATH) && trimmed.startsWith(DEFAULT_PATH + "/")) {
            throw new IllegalArgumentException(
                    propertyName + " must not use the reserved internal '/bootui/**' namespace: '" + trimmed + "'");
        }

        return trimmed;
    }

    private static boolean hasDotSegment(String path) {
        for (String segment : path.substring(1).split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return true;
            }
        }
        return false;
    }
}
