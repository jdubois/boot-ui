package io.github.jdubois.bootui.engine.restclienttrace;

import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceGroupDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shared grouping of already-correlated outbound HTTP calls by method, host, and normalized path, with
 * "chatty" flagging and bounded call-site aggregation.
 *
 * <p>Mirrors {@code SqlTraceGrouping}'s role for SQL executions, factored out of {@link
 * RestClientTraceRecorder#topCalls()} so any other consumer that needs the exact same definition of
 * "looks like a chatty access pattern" the REST Client panel uses can apply it over an explicit,
 * already-correlated subset of calls rather than the recorder's full buffer. Unlike {@code
 * SqlTraceGrouping}, this grouping is not (yet) reused by Live Activity: {@code LiveActivityService}
 * deliberately does not flag a chatty pattern on the correlated {@code REQUEST} entry the way it flags
 * SQL N+1 suspicion, so a chatty group is visible only inside the REST Client panel's own "most
 * frequent calls" table today.</p>
 *
 * <p>Unlike SQL's N+1 rule, which only flags repeated {@code SELECT}s (an {@code INSERT}/{@code UPDATE}
 * looping over rows is comparatively rare), a chatty pattern is flagged for calls of <em>any</em> HTTP
 * method: repeating a {@code POST}/{@code PUT} once per item in a loop is just as real and costly an
 * anti-pattern as repeating a {@code GET}.</p>
 */
public final class RestClientTraceGrouping {

    /** Default chatty-call threshold used where no configured value is supplied. */
    public static final int DEFAULT_CHATTY_THRESHOLD = 5;

    /** Maximum distinct call sites retained per group, most-recently-seen first. */
    public static final int MAX_CALL_SITES_PER_GROUP = 5;

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private RestClientTraceGrouping() {}

    /**
     * Groups {@code entries} by method + host + normalized path, ordered by call count descending,
     * flagging groups at or above {@code chattyThreshold} calls as a potential chatty (repeated-call)
     * pattern and aggregating each group's distinct call sites (bounded by {@link
     * #MAX_CALL_SITES_PER_GROUP}, in the order {@code entries} were supplied; entries with no captured
     * call site contribute none).
     *
     * <p>The path is normalized for grouping only (numeric and UUID path segments collapse to {@code
     * {id}}, so calls to {@code /orders/1}, {@code /orders/2}, ... group under {@code /orders/{id}});
     * individual entries keep their literal, un-normalized path for display.
     */
    public static List<RestClientTraceGroupDto> group(List<RestClientTraceEntryDto> entries, int chattyThreshold) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        Map<String, Aggregate> byCall = new LinkedHashMap<>();
        for (RestClientTraceEntryDto entry : entries) {
            String method = entry.method() == null ? "" : entry.method().toUpperCase(java.util.Locale.ROOT);
            String host = entry.host() == null ? "" : entry.host();
            String normalizedPath = normalizePath(entry.path());
            String key = method + ' ' + host + ' ' + normalizedPath;
            Aggregate aggregate = byCall.computeIfAbsent(key, k -> new Aggregate(method, host, normalizedPath));
            aggregate.executions++;
            aggregate.totalDuration += entry.durationMillis();
            aggregate.maxDuration = Math.max(aggregate.maxDuration, entry.durationMillis());
            aggregate.addCallSite(entry.callSite());
        }
        List<RestClientTraceGroupDto> groups = new ArrayList<>();
        for (Aggregate aggregate : byCall.values()) {
            groups.add(new RestClientTraceGroupDto(
                    aggregate.method,
                    aggregate.host,
                    aggregate.path,
                    aggregate.executions,
                    aggregate.totalDuration,
                    aggregate.maxDuration,
                    aggregate.executions >= chattyThreshold,
                    aggregate.callSites()));
        }
        groups.sort(
                Comparator.comparingLong(RestClientTraceGroupDto::executions).reversed());
        return groups;
    }

    /** Convenience check for a list-level flag: whether any group in {@code entries} looks chatty. */
    public static boolean anyChatty(List<RestClientTraceEntryDto> entries, int chattyThreshold) {
        return group(entries, chattyThreshold).stream().anyMatch(RestClientTraceGroupDto::chatty);
    }

    /**
     * Replaces numeric and UUID path segments with {@code {id}} so calls that only differ by a path
     * variable value (e.g. {@code /orders/1}, {@code /orders/2}) group under one template (e.g. {@code
     * /orders/{id}}). This is a best-effort heuristic: BootUI only sees the resolved URI, never the
     * original {@code @GetMapping}-style template, so it cannot distinguish a numeric/UUID path segment
     * that is genuinely a literal route segment from one that is a path variable value.
     */
    static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String[] segments = path.split("/");
        StringBuilder normalized = new StringBuilder();
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            normalized.append('/');
            normalized.append(looksLikeIdentifier(segment) ? "{id}" : segment);
        }
        return normalized.length() == 0 ? "/" : normalized.toString();
    }

    private static boolean looksLikeIdentifier(String segment) {
        if (segment.chars().allMatch(Character::isDigit)) {
            return true;
        }
        return UUID_PATTERN.matcher(segment).matches();
    }

    private static final class Aggregate {
        private final String method;
        private final String host;
        private final String path;
        private long executions;
        private long totalDuration;
        private long maxDuration;
        private final Set<String> callSites = new LinkedHashSet<>();

        private Aggregate(String method, String host, String path) {
            this.method = method;
            this.host = host;
            this.path = path;
        }

        private void addCallSite(String callSite) {
            if (callSite != null && callSites.size() < MAX_CALL_SITES_PER_GROUP) {
                callSites.add(callSite);
            }
        }

        private List<String> callSites() {
            return List.copyOf(callSites);
        }
    }
}
