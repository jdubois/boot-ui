package io.github.jdubois.bootui.engine.sqltrace;

import java.util.Locale;
import java.util.Set;

/**
 * Derives a safe, low-cardinality route label from a concrete request path when no route template is
 * available from the adapter.
 *
 * <p>A route template such as {@code /api/orders/{id}} is always preferable, because the runtime knows
 * exactly which segments are parameters. Where BootUI cannot obtain one, grouping by the raw path would
 * do two unacceptable things: it would put a path-parameter value on screen as a group label, and it would
 * explode the ranking into one row per identifier. This masker therefore replaces every segment that looks
 * like a value rather than a route word with {@code {value}}.</p>
 *
 * <p>The test is deliberately biased towards masking. A segment is kept only when it reads like a fixed
 * route word: reasonably short, letters with at most hyphens, underscores and a file extension, and no
 * digits. Anything numeric, hex, UUID-shaped, encoded, e-mail-like, mixed with digits, or simply long is
 * masked. Over-masking merges two sibling routes into one honestly-labelled group; under-masking would
 * leak a value, so the bias is the safe direction.</p>
 *
 * <p>Query strings never reach this class: callers pass a path only, and the result never contains one.</p>
 *
 * <p>One limitation is irreducible without a template: a path parameter whose value happens to be a plain
 * word — a slug, a status name, a username — is lexically indistinguishable from a fixed route word, so it
 * is kept. That is why every group derived this way is labelled {@code MASKED_PATH} in the contract: the
 * label is a masked observation of a path BootUI already displays in HTTP Exchanges, not a declared
 * route.</p>
 */
public final class RoutePathMasker {

    /** Placeholder substituted for any segment that does not read like a fixed route word. */
    public static final String PLACEHOLDER = "{value}";

    /** Longest segment kept verbatim; anything longer is a value, not a route word. */
    private static final int MAX_SEGMENT_LENGTH = 40;

    /** Segments kept before the label is truncated, bounding pathological deep paths. */
    private static final int MAX_SEGMENTS = 12;

    private RoutePathMasker() {}

    /**
     * Masks {@code path}, returning {@code "/"} for a null, blank or root path. The result always starts
     * with {@code /} and never contains a query string or fragment.
     */
    public static String mask(String path) {
        return mask(path, true, Set.of());
    }

    /**
     * Masks a path observed on a real request. Unlike {@link #mask(String)}, a brace-delimited segment is not
     * trusted as template syntax — a request can carry a literal, percent-decoded {@code {alice}} — and every
     * segment position in {@code valuePositions} is masked even when it reads like a route word, because a
     * declared route says a parameter sits there. Positions count the non-empty segments from zero, as
     * {@link RouteTemplateResolver#parameterPositions(String)} reports them.
     */
    public static String maskObserved(String path, Set<Integer> valuePositions) {
        return mask(path, false, valuePositions == null ? Set.of() : valuePositions);
    }

    private static String mask(String path, boolean trustTemplateSegments, Set<Integer> valuePositions) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String withoutQuery = stripQueryAndFragment(path.trim());
        if (withoutQuery.isEmpty() || "/".equals(withoutQuery)) {
            return "/";
        }
        String[] segments = withoutQuery.split("/", -1);
        StringBuilder out = new StringBuilder();
        int kept = 0;
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            if (kept == MAX_SEGMENTS) {
                out.append("/…");
                break;
            }
            boolean keep = !valuePositions.contains(kept) && isRouteWord(segment, trustTemplateSegments);
            out.append('/').append(keep ? segment : PLACEHOLDER);
            kept++;
        }
        return out.length() == 0 ? "/" : out.toString();
    }

    private static String stripQueryAndFragment(String path) {
        int cut = path.length();
        int query = path.indexOf('?');
        if (query >= 0) {
            cut = query;
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0 && fragment < cut) {
            cut = fragment;
        }
        return path.substring(0, cut);
    }

    /**
     * Whether a segment reads like a fixed part of a route rather than a value. Letters, hyphens,
     * underscores and a single dot-extension are allowed; digits, percent-encoding, {@code @}, and any
     * other character mean the segment carries data.
     */
    private static boolean isRouteWord(String segment, boolean trustTemplateSegments) {
        if (segment.length() > MAX_SEGMENT_LENGTH) {
            return false;
        }
        // A template segment supplied verbatim by an adapter is already safe and must survive unchanged.
        if (trustTemplateSegments && segment.startsWith("{") && segment.endsWith("}")) {
            return true;
        }
        boolean letterSeen = false;
        boolean dotSeen = false;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (Character.isLetter(c) && c < 128) {
                letterSeen = true;
            } else if (c == '-' || c == '_') {
                continue;
            } else if (c == '.') {
                if (dotSeen) {
                    return false;
                }
                dotSeen = true;
            } else {
                return false;
            }
        }
        return letterSeen && !isReservedValueWord(segment);
    }

    /**
     * Two all-letter segments that are never route words: a {@code null} or {@code undefined} that leaked
     * from a client into a path is a value, and keeping it verbatim would split one route into three.
     */
    private static boolean isReservedValueWord(String segment) {
        String lower = segment.toLowerCase(Locale.ROOT);
        return "null".equals(lower) || "undefined".equals(lower);
    }
}
