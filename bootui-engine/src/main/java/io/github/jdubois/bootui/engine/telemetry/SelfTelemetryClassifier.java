package io.github.jdubois.bootui.engine.telemetry;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Framework-neutral classifier that recognizes telemetry produced by BootUI itself, so a console
 * never reports on its own {@code /bootui/**} traffic.
 *
 * <p>Each adapter builds <strong>exactly one</strong> instance from the operator-configured
 * {@code bootui.path} / {@code bootui.api-path} and shares it between every call site that needs
 * self-traffic recognition &mdash; capture ({@link BootUiSpanExporter}, the OTLP receiver) and
 * transform (the Traces read API, and, via each adapter's broader self-data filter, the Beans,
 * Mappings, Loggers, and Metrics panels). A single shared instance guarantees capture and transform
 * can never disagree on which paths are BootUI's own, which two independently-configured instances
 * previously could when {@code bootui.path} was customized.</p>
 *
 * <p>The two call sites still apply <em>different flags</em> on top of the same path-matching logic,
 * because they answer different questions: capture (via {@link BootUiSpanExporter}) ANDs
 * {@link #isBootUiSpan(NormalizedSpan)} with the telemetry-specific
 * {@code bootui.telemetry.exclude-self-spans} flag to decide whether to keep a span in the bounded
 * store at all, while {@link #shouldInclude(boolean)} / {@link #shouldIncludeTrace(Collection)} use
 * this classifier's own {@code excludeSelf} field &mdash; sourced from the general
 * {@code bootui.monitoring.exclude-self} flag shared by every monitoring panel &mdash; to decide
 * whether already-stored data is displayed.</p>
 */
public final class SelfTelemetryClassifier {

    private static final Set<String> PATH_TAG_KEYS = Set.of(
            "uri", "path", "endpoint", "http.route", "http.target", "http.path", "http.url", "url.path", "url.full");

    private final boolean excludeSelf;

    private final Set<String> selfPaths;

    public SelfTelemetryClassifier(boolean excludeSelf, String path, String apiPath) {
        this.excludeSelf = excludeSelf;
        this.selfPaths = selfPaths(path, apiPath);
    }

    /** Classifier that includes everything (self-exclusion disabled). */
    public static SelfTelemetryClassifier disabled() {
        return new SelfTelemetryClassifier(false, "/bootui", "/bootui/api");
    }

    public boolean shouldExcludeSelf() {
        return excludeSelf;
    }

    public boolean shouldInclude(boolean selfData) {
        return !excludeSelf || !selfData;
    }

    public boolean shouldIncludeTrace(Collection<NormalizedSpan> spans) {
        return shouldInclude(isBootUiTrace(spans));
    }

    /** Whether the given attribute/tag key can carry an HTTP request path. */
    public boolean isPathTag(String key) {
        return key != null && PATH_TAG_KEYS.contains(key);
    }

    public boolean isBootUiSpan(NormalizedSpan span) {
        if (span == null) {
            return false;
        }
        if (isBootUiPath(span.name())) {
            return true;
        }
        Map<String, AttributeValue> attributes = span.attributes();
        if (attributes == null) {
            return false;
        }
        for (Map.Entry<String, AttributeValue> entry : attributes.entrySet()) {
            if (!isPathTag(entry.getKey())) {
                continue;
            }
            AttributeValue value = entry.getValue();
            if (value != null && isBootUiPath(value.asString())) {
                return true;
            }
        }
        return false;
    }

    public boolean isBootUiTrace(Collection<NormalizedSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return false;
        }
        boolean allSelf = true;
        NormalizedSpan root = null;
        for (NormalizedSpan span : spans) {
            boolean self = isBootUiSpan(span);
            allSelf = allSelf && self;
            if (span != null && span.parentSpanId() == null) {
                if (root == null || span.startEpochNanos() < root.startEpochNanos()) {
                    root = span;
                }
            }
        }
        return allSelf || (root != null && isBootUiSpan(root));
    }

    public boolean isBootUiPath(String value) {
        if (isBlank(value)) {
            return false;
        }
        String candidate = value.trim();
        String uriPath = uriPath(candidate);
        if (matchesAnySelfPath(uriPath)) {
            return true;
        }
        return matchesAnySelfPath(candidate);
    }

    private boolean matchesAnySelfPath(String value) {
        for (String selfPath : selfPaths) {
            if (containsPathWithBoundaries(value, selfPath)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsPathWithBoundaries(String value, String path) {
        if (isBlank(value)) {
            return false;
        }
        int index = value.indexOf(path);
        while (index >= 0) {
            int afterIndex = index + path.length();
            if (hasPathBoundaryBefore(value, index) && hasPathBoundaryAfter(value, afterIndex)) {
                return true;
            }
            index = value.indexOf(path, index + 1);
        }
        return false;
    }

    private boolean hasPathBoundaryBefore(String value, int index) {
        if (index == 0) {
            return true;
        }
        char previous = value.charAt(index - 1);
        return !Character.isLetterOrDigit(previous) && previous != '_' && previous != '-' && previous != '.';
    }

    private boolean hasPathBoundaryAfter(String value, int index) {
        if (index >= value.length()) {
            return true;
        }
        char next = value.charAt(index);
        return next == '/' || next == '?' || next == '#' || Character.isWhitespace(next) || isClosingDelimiter(next);
    }

    private boolean isClosingDelimiter(char value) {
        return value == ']' || value == ')' || value == '}' || value == ',' || value == '\'' || value == '"';
    }

    private String uriPath(String value) {
        if (!value.contains("://")) {
            return value;
        }
        try {
            URI uri = URI.create(value);
            String path = uri.getRawPath();
            return path == null ? value : path;
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }

    private static Set<String> selfPaths(String path, String apiPath) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        addPath(paths, path);
        addPath(paths, apiPath);
        addPath(paths, "/bootui");
        addPath(paths, "/bootui/api");
        return Set.copyOf(paths);
    }

    private static void addPath(Set<String> paths, String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        String normalized = path.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        paths.add(normalized);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
