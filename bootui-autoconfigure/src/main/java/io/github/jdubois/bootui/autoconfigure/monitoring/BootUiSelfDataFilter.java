package io.github.jdubois.bootui.autoconfigure.monitoring;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.engine.telemetry.NormalizedSpan;
import io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Central classifier for runtime data produced by BootUI itself.
 *
 * <p>The HTTP path / span / trace matching is delegated to the framework-neutral
 * {@link SelfTelemetryClassifier} in {@code bootui-engine} (shared with the telemetry capture path);
 * this adapter class keeps the Spring-coupled bean, mapping, logger, meter, security and startup-step
 * orchestration on top of it.</p>
 */
public final class BootUiSelfDataFilter {

    private static final List<String> INTERNAL_PACKAGES =
            List.of("io.github.jdubois.bootui.autoconfigure", "io.github.jdubois.bootui.core");

    private static final Set<String> STARTUP_CLASS_TAGS =
            Set.of("bean.type", "beanType", "class", "configurationClass", "target.type", "targetType");

    private static final Set<String> STARTUP_BEAN_TAGS = Set.of("bean.name", "beanName");

    private final boolean excludeSelf;

    private final SelfTelemetryClassifier classifier;

    public BootUiSelfDataFilter(BootUiProperties properties) {
        this(properties.getMonitoring().isExcludeSelf(), properties.getPath(), properties.getApiPath());
    }

    private BootUiSelfDataFilter(boolean excludeSelf, String path, String apiPath) {
        this.excludeSelf = excludeSelf;
        this.classifier = new SelfTelemetryClassifier(excludeSelf, path, apiPath);
    }

    public static BootUiSelfDataFilter defaults() {
        return new BootUiSelfDataFilter(new BootUiProperties());
    }

    public static BootUiSelfDataFilter disabled() {
        return new BootUiSelfDataFilter(false, "/bootui", "/bootui/api");
    }

    /**
     * The neutral self-traffic classifier this filter composes, for engine services (such as the
     * Traces read model) that need the same transform-side self-trace filtering without the
     * Spring-coupled orchestration.
     */
    public SelfTelemetryClassifier telemetryClassifier() {
        return classifier;
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
        return classifier.isBootUiSpan(span);
    }

    public boolean isBootUiTrace(Collection<NormalizedSpan> spans) {
        return classifier.isBootUiTrace(spans);
    }

    public boolean isBootUiPath(String value) {
        return classifier.isBootUiPath(value);
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
        return classifier.isPathTag(key);
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
