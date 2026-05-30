package io.github.jdubois.bootui.autoconfigure.monitoring;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.otlp.AttributeValue;
import io.github.jdubois.bootui.autoconfigure.otlp.NormalizedSpan;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Central classifier for runtime data produced by BootUI itself.
 */
public final class BootUiSelfDataFilter {

    private static final List<String> INTERNAL_PACKAGES =
            List.of("io.github.jdubois.bootui.autoconfigure", "io.github.jdubois.bootui.core");

    private static final Set<String> PATH_TAG_KEYS = Set.of(
            "uri", "path", "endpoint", "http.route", "http.target", "http.path", "http.url", "url.path", "url.full");

    private static final Set<String> STARTUP_CLASS_TAGS =
            Set.of("bean.type", "beanType", "class", "configurationClass", "target.type", "targetType");

    private static final Set<String> STARTUP_BEAN_TAGS = Set.of("bean.name", "beanName");

    private final boolean excludeSelf;

    private final Set<String> selfPaths;

    public BootUiSelfDataFilter(BootUiProperties properties) {
        this(properties.getMonitoring().isExcludeSelf(), properties.getPath(), properties.getApiPath());
    }

    private BootUiSelfDataFilter(boolean excludeSelf, String path, String apiPath) {
        this.excludeSelf = excludeSelf;
        this.selfPaths = selfPaths(path, apiPath);
    }

    public static BootUiSelfDataFilter defaults() {
        return new BootUiSelfDataFilter(new BootUiProperties());
    }

    public static BootUiSelfDataFilter disabled() {
        return new BootUiSelfDataFilter(false, "/bootui", "/bootui/api");
    }

    public static BootUiSelfDataFilter forPaths(String path, String apiPath) {
        return new BootUiSelfDataFilter(true, path, apiPath);
    }

    public boolean shouldExcludeSelf() {
        return excludeSelf;
    }

    public boolean shouldInclude(boolean selfData) {
        return !excludeSelf || !selfData;
    }

    public boolean shouldIncludeBean(String beanName, Class<?> type, String resource) {
        return shouldInclude(isBootUiBean(beanName, type, resource));
    }

    public boolean shouldIncludeMapping(Collection<String> patterns, String predicate, String handler) {
        return shouldInclude(isBootUiMapping(patterns, predicate, handler));
    }

    public boolean shouldIncludeLogger(String loggerName) {
        return shouldInclude(isBootUiLoggerName(loggerName));
    }

    public boolean shouldIncludeMeter(Meter meter) {
        return shouldInclude(isBootUiMeter(meter));
    }

    public boolean shouldIncludeTrace(Collection<NormalizedSpan> spans) {
        return shouldInclude(isBootUiTrace(spans));
    }

    public boolean shouldIncludeCacheOperation(String beanName, Class<?> type) {
        return shouldInclude(isBootUiBean(beanName, type, null));
    }

    public boolean shouldIncludeScheduledTask(String runnable) {
        return shouldInclude(isBootUiClassOrResource(runnable));
    }

    public boolean shouldIncludeSecurityEndpoint(Collection<String> patterns, String handler) {
        return shouldInclude(isBootUiMapping(patterns, null, handler));
    }

    public boolean shouldIncludeSecurityChain(String matcherDescription) {
        return shouldInclude(isBootUiPath(matcherDescription));
    }

    public boolean shouldIncludeConditionClass(String className) {
        return shouldInclude(isBootUiClassOrResource(className));
    }

    public boolean shouldIncludeStartupStep(String name, Iterable<? extends Map.Entry<String, String>> tags) {
        return shouldInclude(isBootUiStartupStep(name, tags));
    }

    public boolean isBootUiBean(String beanName, Class<?> type, String resource) {
        return isBootUiClass(type) || isBootUiClassOrResource(resource) || isInternalPackageValue(beanName);
    }

    public boolean isBootUiClass(Class<?> type) {
        return type != null && isInternalPackageName(type.getName());
    }

    public boolean isBootUiClassOrResource(String value) {
        return isInternalPackageValue(value);
    }

    public boolean isBootUiLoggerName(String loggerName) {
        return isInternalPackageName(loggerName);
    }

    public boolean isBootUiMapping(Collection<String> patterns, String predicate, String handler) {
        if (patterns != null) {
            for (String pattern : patterns) {
                if (isBootUiPath(pattern)) {
                    return true;
                }
            }
        }
        return isBootUiPath(predicate) || isInternalPackageValue(handler);
    }

    public boolean isBootUiMeter(Meter meter) {
        if (meter == null || meter.getId() == null) {
            return false;
        }
        for (Tag tag : meter.getId().getTags()) {
            if (isPathTag(tag.getKey()) && isBootUiPath(tag.getValue())) {
                return true;
            }
        }
        return false;
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

    private boolean isBootUiStartupStep(String name, Iterable<? extends Map.Entry<String, String>> tags) {
        if (isBootUiPath(name) || isInternalPackageValue(name)) {
            return true;
        }
        if (tags == null) {
            return false;
        }
        for (Map.Entry<String, String> tag : tags) {
            String key = tag.getKey();
            String value = tag.getValue();
            if ((STARTUP_CLASS_TAGS.contains(key) && isInternalPackageValue(value))
                    || (STARTUP_BEAN_TAGS.contains(key)
                            && (isBootUiBeanName(value) || isInternalPackageValue(value)))) {
                return true;
            }
        }
        return false;
    }

    private boolean isBootUiBeanName(String value) {
        return value != null && value.startsWith("bootUi");
    }

    private boolean isPathTag(String key) {
        return key != null && PATH_TAG_KEYS.contains(key);
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

    private boolean isInternalPackageValue(String value) {
        if (isBlank(value)) {
            return false;
        }
        String normalized = value.replace('/', '.').replace('$', '.');
        for (String packageName : INTERNAL_PACKAGES) {
            if (containsPackage(normalized, packageName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInternalPackageName(String value) {
        if (isBlank(value)) {
            return false;
        }
        String normalized = value.replace('$', '.');
        for (String packageName : INTERNAL_PACKAGES) {
            if (normalized.equals(packageName) || normalized.startsWith(packageName + ".")) {
                return true;
            }
        }
        return false;
    }

    private boolean containsPackage(String value, String packageName) {
        int index = value.indexOf(packageName);
        while (index >= 0) {
            int afterIndex = index + packageName.length();
            boolean beforeBoundary = index == 0 || !isPackageChar(value.charAt(index - 1));
            boolean afterBoundary = afterIndex >= value.length()
                    || value.charAt(afterIndex) == '.'
                    || !isPackageChar(value.charAt(afterIndex));
            if (beforeBoundary && afterBoundary) {
                return true;
            }
            index = value.indexOf(packageName, index + 1);
        }
        return false;
    }

    private boolean isPackageChar(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '.';
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
