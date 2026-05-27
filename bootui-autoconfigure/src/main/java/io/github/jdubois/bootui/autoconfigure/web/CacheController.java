package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.BootUiDtos.CacheClearRequest;
import io.github.jdubois.bootui.core.BootUiDtos.CacheClearResult;
import io.github.jdubois.bootui.core.BootUiDtos.CacheDto;
import io.github.jdubois.bootui.core.BootUiDtos.CacheManagerDto;
import io.github.jdubois.bootui.core.BootUiDtos.CacheMetricsDto;
import io.github.jdubois.bootui.core.BootUiDtos.CacheOperationDto;
import io.github.jdubois.bootui.core.BootUiDtos.CacheReport;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheEvictOperation;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.cache.interceptor.CachePutOperation;
import org.springframework.cache.interceptor.CacheableOperation;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/bootui/api/cache")
public class CacheController {

    private static final Logger log = LoggerFactory.getLogger(CacheController.class);

    private static final int MAX_SCANNED_METHODS = 5_000;

    private static final int MAX_CACHE_OPERATIONS = 1_000;

    private final ObjectProvider<ListableBeanFactory> beanFactoryProvider;

    private final ObjectProvider<CacheOperationSource> cacheOperationSources;

    private final ObjectProvider<MeterRegistry> meterRegistries;

    private final BootUiProperties properties;

    public CacheController(ObjectProvider<ListableBeanFactory> beanFactoryProvider,
                           ObjectProvider<CacheOperationSource> cacheOperationSources,
                           ObjectProvider<MeterRegistry> meterRegistries,
                           BootUiProperties properties) {
        this.beanFactoryProvider = beanFactoryProvider;
        this.cacheOperationSources = cacheOperationSources;
        this.meterRegistries = meterRegistries;
        this.properties = properties;
    }

    @GetMapping
    public CacheReport cache() {
        ListableBeanFactory factory = beanFactoryProvider.getIfAvailable();
        OperationDiscovery operationDiscovery = factory == null
                ? new OperationDiscovery(List.of(), List.of())
                : discoverOperations(factory);
        if (factory == null) {
            return new CacheReport(false, clearEnabled(), 0, 0, operationDiscovery.operations().size(),
                    List.of(), operationDiscovery.operations(), operationDiscovery.warnings());
        }

        List<CacheManagerEntry> managers = discoverManagers(factory);
        Map<MetricKey, CacheMetricsAccumulator> metrics = cacheMetrics();
        List<CacheManagerDto> managerDtos = managers.stream()
                .map(manager -> toManagerDto(manager, metrics))
                .toList();
        int cacheCount = managerDtos.stream().mapToInt(manager -> manager.caches().size()).sum();
        return new CacheReport(
                !managers.isEmpty(),
                clearEnabled(),
                managers.size(),
                cacheCount,
                operationDiscovery.operations().size(),
                managerDtos,
                operationDiscovery.operations(),
                operationDiscovery.warnings());
    }

    @PostMapping("/clear")
    public ResponseEntity<CacheClearResult> clear(@RequestBody(required = false) CacheClearRequest request) {
        if (!clearEnabled()) {
            return result(HttpStatus.CONFLICT, "disabled",
                    "Cache clearing is disabled. Set bootui.cache.clear-enabled=true to allow it.",
                    List.of());
        }
        if (request == null || !Boolean.TRUE.equals(request.confirm())) {
            return result(HttpStatus.BAD_REQUEST, "confirmation_required",
                    "Cache clearing requires explicit confirmation.", List.of());
        }

        ListableBeanFactory factory = beanFactoryProvider.getIfAvailable();
        if (factory == null) {
            return result(HttpStatus.CONFLICT, "unavailable",
                    "No bean factory is available to discover cache managers.", List.of());
        }
        List<CacheManagerEntry> managers = discoverManagers(factory);
        if (managers.isEmpty()) {
            return result(HttpStatus.CONFLICT, "unavailable",
                    "No CacheManager beans are available.", List.of());
        }

        if (Boolean.TRUE.equals(request.all())) {
            return clearAll(managers);
        }
        if (isBlank(request.managerName()) || isBlank(request.cacheName())) {
            return result(HttpStatus.BAD_REQUEST, "invalid_request",
                    "managerName and cacheName are required when clearing one cache.", List.of());
        }
        return clearOne(managers, request.managerName(), request.cacheName());
    }

    private ResponseEntity<CacheClearResult> clearAll(List<CacheManagerEntry> managers) {
        List<String> cleared = new ArrayList<>();
        for (CacheManagerEntry manager : managers) {
            for (String cacheName : cacheNames(manager.manager())) {
                Cache cache = manager.manager().getCache(cacheName);
                if (cache == null) {
                    continue;
                }
                ResponseEntity<CacheClearResult> failure = clearCache(manager.name(), cacheName, cache, cleared);
                if (failure != null) {
                    return failure;
                }
            }
        }
        log.warn("BootUI cleared {} caches across all Spring Cache managers: {}", cleared.size(), cleared);
        return result(HttpStatus.OK, "cleared",
                "Cleared " + cleared.size() + " cache" + (cleared.size() == 1 ? "." : "s."),
                cleared);
    }

    private ResponseEntity<CacheClearResult> clearOne(List<CacheManagerEntry> managers,
                                                      String managerName,
                                                      String cacheName) {
        CacheManagerEntry manager = managers.stream()
                .filter(entry -> entry.name().equals(managerName))
                .findFirst()
                .orElse(null);
        if (manager == null) {
            return result(HttpStatus.NOT_FOUND, "not_found",
                    "Cache manager '" + managerName + "' was not found.", List.of());
        }
        if (!cacheNames(manager.manager()).contains(cacheName)) {
            return result(HttpStatus.NOT_FOUND, "not_found",
                    "Cache '" + cacheName + "' was not found in manager '" + managerName + "'.", List.of());
        }
        Cache cache = manager.manager().getCache(cacheName);
        if (cache == null) {
            return result(HttpStatus.NOT_FOUND, "not_found",
                    "Cache '" + cacheName + "' was not returned by manager '" + managerName + "'.", List.of());
        }

        List<String> cleared = new ArrayList<>();
        ResponseEntity<CacheClearResult> failure = clearCache(manager.name(), cacheName, cache, cleared);
        if (failure != null) {
            return failure;
        }
        log.warn("BootUI cleared Spring Cache entry {} / {}", manager.name(), cacheName);
        return result(HttpStatus.OK, "cleared",
                "Cleared cache '" + cacheName + "' from manager '" + manager.name() + "'.",
                cleared);
    }

    private ResponseEntity<CacheClearResult> clearCache(String managerName, String cacheName, Cache cache,
                                                        List<String> cleared) {
        try {
            cache.clear();
            cleared.add(managerName + "/" + cacheName);
            return null;
        }
        catch (RuntimeException ex) {
            return result(HttpStatus.INTERNAL_SERVER_ERROR, "failed",
                    "Failed to clear cache '" + cacheName + "' from manager '" + managerName
                            + "' (" + ex.getClass().getSimpleName() + ").",
                    cleared);
        }
    }

    private ResponseEntity<CacheClearResult> result(HttpStatus status, String resultStatus, String message,
                                                    List<String> caches) {
        return ResponseEntity.status(status)
                .body(new CacheClearResult(resultStatus, message, caches.size(), caches));
    }

    private List<CacheManagerEntry> discoverManagers(ListableBeanFactory factory) {
        String[] beanNames = factory.getBeanNamesForType(CacheManager.class, false, false);
        Arrays.sort(beanNames);
        List<CacheManagerEntry> managers = new ArrayList<>(beanNames.length);
        for (String beanName : beanNames) {
            CacheManager manager = factory.getBean(beanName, CacheManager.class);
            managers.add(new CacheManagerEntry(beanName, manager));
        }
        return managers;
    }

    private CacheManagerDto toManagerDto(CacheManagerEntry entry,
                                         Map<MetricKey, CacheMetricsAccumulator> metrics) {
        List<CacheDto> caches = new ArrayList<>();
        for (String cacheName : cacheNames(entry.manager())) {
            Cache cache = entry.manager().getCache(cacheName);
            if (cache == null) {
                continue;
            }
            caches.add(toCacheDto(entry.name(), cacheName, cache, metrics));
        }
        caches.sort(Comparator.comparing(CacheDto::name));
        return new CacheManagerDto(entry.name(), entry.manager().getClass().getName(), isNoOp(entry.manager()), caches);
    }

    private CacheDto toCacheDto(String managerName, String cacheName, Cache cache,
                                Map<MetricKey, CacheMetricsAccumulator> metrics) {
        Object nativeCache = nativeCache(cache);
        CacheMetricsAccumulator metric = metrics.get(new MetricKey(managerName, cacheName));
        if (metric == null) {
            metric = metrics.get(new MetricKey("*", cacheName));
        }
        return new CacheDto(
                managerName,
                cacheName,
                nativeCache == null ? null : nativeCache.getClass().getName(),
                estimateSize(nativeCache),
                metric == null ? new CacheMetricsDto(false, null, null, null, null, null, null, null) : metric.toDto());
    }

    private List<String> cacheNames(CacheManager manager) {
        return manager.getCacheNames().stream()
                .sorted()
                .toList();
    }

    private Object nativeCache(Cache cache) {
        try {
            return cache.getNativeCache();
        }
        catch (RuntimeException ex) {
            return null;
        }
    }

    private Long estimateSize(Object nativeCache) {
        if (nativeCache == null) {
            return null;
        }
        if (nativeCache instanceof Map<?, ?> map && isJdkLocalType(nativeCache)) {
            return (long) map.size();
        }
        if (nativeCache instanceof Collection<?> collection && isJdkLocalType(nativeCache)) {
            return (long) collection.size();
        }
        if (nativeCache.getClass().getName().startsWith("com.github.benmanes.caffeine.cache.")) {
            return invokeLong(nativeCache, "estimatedSize");
        }
        return null;
    }

    private boolean isJdkLocalType(Object value) {
        String name = value.getClass().getName();
        return name.startsWith("java.util.") || name.startsWith("java.util.concurrent.");
    }

    private Long invokeLong(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            return value instanceof Number number ? number.longValue() : null;
        }
        catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException ex) {
            return null;
        }
    }

    private boolean isNoOp(CacheManager manager) {
        return manager.getClass().getName().equals("org.springframework.cache.support.NoOpCacheManager");
    }

    private Map<MetricKey, CacheMetricsAccumulator> cacheMetrics() {
        MeterRegistry registry = meterRegistry();
        if (registry == null) {
            return Map.of();
        }
        Map<MetricKey, CacheMetricsAccumulator> metrics = new LinkedHashMap<>();
        for (Meter meter : registry.getMeters()) {
            String meterName = meter.getId().getName();
            String cacheName = meter.getId().getTag("cache");
            if (isBlank(cacheName)) {
                continue;
            }
            String managerName = firstNonBlank(
                    meter.getId().getTag("name"),
                    meter.getId().getTag("cacheManager"),
                    meter.getId().getTag("cache.manager"));
            MetricKey key = new MetricKey(isBlank(managerName) ? "*" : managerName, cacheName);
            CacheMetricsAccumulator accumulator = metrics.computeIfAbsent(key, ignored -> new CacheMetricsAccumulator());
            addMetric(accumulator, meterName, meter);
        }
        return metrics;
    }

    private MeterRegistry meterRegistry() {
        MeterRegistry registry = meterRegistries.getIfUnique();
        if (registry != null) {
            return registry;
        }
        return meterRegistries.orderedStream().findFirst().orElse(null);
    }

    private void addMetric(CacheMetricsAccumulator accumulator, String meterName, Meter meter) {
        OptionalDouble value = measurementValue(meter);
        if (value.isEmpty()) {
            return;
        }
        switch (meterName) {
            case "cache.gets" -> {
                String result = meter.getId().getTag("result");
                if ("hit".equalsIgnoreCase(result)) {
                    accumulator.addHits(value.getAsDouble());
                }
                else if ("miss".equalsIgnoreCase(result)) {
                    accumulator.addMisses(value.getAsDouble());
                }
            }
            case "cache.puts" -> accumulator.addPuts(value.getAsDouble());
            case "cache.evictions" -> accumulator.addEvictions(value.getAsDouble());
            case "cache.removals" -> accumulator.addRemovals(value.getAsDouble());
            case "cache.size" -> accumulator.setSize(value.getAsDouble());
            default -> {
            }
        }
    }

    private OptionalDouble measurementValue(Meter meter) {
        double sum = 0.0;
        boolean seen = false;
        for (Measurement measurement : meter.measure()) {
            double value = measurement.getValue();
            if (Double.isFinite(value)) {
                sum += value;
                seen = true;
            }
        }
        return seen ? OptionalDouble.of(sum) : OptionalDouble.empty();
    }

    private OperationDiscovery discoverOperations(ListableBeanFactory factory) {
        List<CacheOperationSource> sources = cacheOperationSources.orderedStream().toList();
        if (sources.isEmpty()) {
            return new OperationDiscovery(List.of(), List.of());
        }

        List<String> beanNames = Arrays.stream(BeanFactoryUtils.beanNamesIncludingAncestors(factory))
                .sorted()
                .toList();
        List<CacheOperationDto> operations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int scannedMethods = 0;

        scan:
        for (String beanName : beanNames) {
            Class<?> type = safeGetType(factory, beanName);
            if (type == null) {
                continue;
            }
            Class<?> userType = ClassUtils.getUserClass(type);
            Set<String> seenMethods = new HashSet<>();
            for (Method method : userType.getMethods()) {
                if (skipMethod(method)) {
                    continue;
                }
                scannedMethods++;
                if (scannedMethods > MAX_SCANNED_METHODS) {
                    warnings.add("Cache annotation scan stopped after " + MAX_SCANNED_METHODS
                            + " methods to avoid slowing the application.");
                    break scan;
                }
                Method bridged = BridgeMethodResolver.findBridgedMethod(method);
                if (!seenMethods.add(methodKey(bridged))) {
                    continue;
                }
                for (CacheOperationSource source : sources) {
                    Collection<CacheOperation> cacheOperations;
                    try {
                        cacheOperations = source.getCacheOperations(bridged, userType);
                    }
                    catch (RuntimeException ex) {
                        warnings.add("Could not inspect cache annotations on " + userType.getName() + "#"
                                + bridged.getName() + " (" + ex.getClass().getSimpleName() + ").");
                        continue;
                    }
                    if (cacheOperations == null || cacheOperations.isEmpty()) {
                        continue;
                    }
                    for (CacheOperation operation : cacheOperations) {
                        operations.add(toOperationDto(beanName, userType, bridged, operation));
                        if (operations.size() >= MAX_CACHE_OPERATIONS) {
                            warnings.add("Cache annotation report was truncated after " + MAX_CACHE_OPERATIONS
                                    + " operations.");
                            break scan;
                        }
                    }
                }
            }
        }
        operations.sort(Comparator.comparing(CacheOperationDto::beanName)
                .thenComparing(CacheOperationDto::method)
                .thenComparing(CacheOperationDto::operation));
        return new OperationDiscovery(operations, warnings);
    }

    private Class<?> safeGetType(ListableBeanFactory factory, String beanName) {
        try {
            return factory.getType(beanName, false);
        }
        catch (BeansException ex) {
            return null;
        }
    }

    private boolean skipMethod(Method method) {
        return method.getDeclaringClass() == Object.class
                || method.isBridge()
                || method.isSynthetic()
                || Modifier.isStatic(method.getModifiers());
    }

    private String methodKey(Method method) {
        return method.getName() + Arrays.toString(method.getParameterTypes());
    }

    private CacheOperationDto toOperationDto(String beanName, Class<?> targetType, Method method,
                                             CacheOperation operation) {
        String unless = null;
        boolean allEntries = false;
        boolean beforeInvocation = false;
        if (operation instanceof CacheableOperation cacheable) {
            unless = blankToNull(cacheable.getUnless());
        }
        else if (operation instanceof CachePutOperation cachePut) {
            unless = blankToNull(cachePut.getUnless());
        }
        else if (operation instanceof CacheEvictOperation cacheEvict) {
            allEntries = cacheEvict.isCacheWide();
            beforeInvocation = cacheEvict.isBeforeInvocation();
        }

        return new CacheOperationDto(
                beanName,
                targetType.getName(),
                signatureOf(method),
                operationName(operation),
                operation.getCacheNames().stream().sorted().toList(),
                blankToNull(operation.getKey()),
                blankToNull(operation.getCondition()),
                unless,
                allEntries,
                beforeInvocation);
    }

    private String operationName(CacheOperation operation) {
        if (operation instanceof CacheableOperation) {
            return "@Cacheable";
        }
        if (operation instanceof CachePutOperation) {
            return "@CachePut";
        }
        if (operation instanceof CacheEvictOperation) {
            return "@CacheEvict";
        }
        return operation.getClass().getSimpleName();
    }

    private String signatureOf(Method method) {
        String params = Arrays.stream(method.getParameterTypes())
                .map(Class::getSimpleName)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        return method.getReturnType().getSimpleName() + " " + method.getName() + "(" + params + ")";
    }

    private boolean clearEnabled() {
        return properties.getCache().isClearEnabled();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private record CacheManagerEntry(String name, CacheManager manager) {
    }

    private record MetricKey(String managerName, String cacheName) {
    }

    private record OperationDiscovery(List<CacheOperationDto> operations, List<String> warnings) {
    }

    private static final class CacheMetricsAccumulator {

        private double hits;

        private boolean hitsSeen;

        private double misses;

        private boolean missesSeen;

        private double puts;

        private boolean putsSeen;

        private double evictions;

        private boolean evictionsSeen;

        private double removals;

        private boolean removalsSeen;

        private double size;

        private boolean sizeSeen;

        void addHits(double value) {
            this.hits += value;
            this.hitsSeen = true;
        }

        void addMisses(double value) {
            this.misses += value;
            this.missesSeen = true;
        }

        void addPuts(double value) {
            this.puts += value;
            this.putsSeen = true;
        }

        void addEvictions(double value) {
            this.evictions += value;
            this.evictionsSeen = true;
        }

        void addRemovals(double value) {
            this.removals += value;
            this.removalsSeen = true;
        }

        void setSize(double value) {
            this.size = value;
            this.sizeSeen = true;
        }

        CacheMetricsDto toDto() {
            boolean available = hitsSeen || missesSeen || putsSeen || evictionsSeen || removalsSeen || sizeSeen;
            Double hitRatio = null;
            if (hitsSeen || missesSeen) {
                double total = hits + misses;
                hitRatio = total == 0.0 ? 0.0 : hits / total;
            }
            return new CacheMetricsDto(
                    available,
                    hitsSeen ? hits : null,
                    missesSeen ? misses : null,
                    hitRatio,
                    putsSeen ? puts : null,
                    evictionsSeen ? evictions : null,
                    removalsSeen ? removals : null,
                    sizeSeen ? size : null);
        }
    }
}
