package io.github.jdubois.bootui.autoconfigure.cache;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.CacheOperationDto;
import io.github.jdubois.bootui.engine.support.BlankStrings;
import io.github.jdubois.bootui.spi.CacheManagerSnapshot;
import io.github.jdubois.bootui.spi.CacheOperationDiscovery;
import io.github.jdubois.bootui.spi.CacheProvider;
import io.github.jdubois.bootui.spi.CacheSnapshot;
import io.github.jdubois.bootui.spi.CacheStatisticsSnapshot;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.*;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.util.ClassUtils;

/**
 * Spring Boot {@link CacheProvider}: the framework-specific seam behind the shared engine
 * {@code CacheService}. It owns everything that is Spring-specific — discovering {@code CacheManager} beans
 * and their caches via the {@code ListableBeanFactory}, estimating native cache sizes, and enumerating
 * {@code @Cacheable}/{@code @CachePut}/{@code @CacheEvict} operations via {@code CacheOperationSource} — while
 * the engine owns the neutral concerns (metric overlay, ordering, counting, clear orchestration).
 *
 * <p>This is the byte-identical extraction of the former {@code SpringCacheService}: the manager/cache
 * discovery, size estimation and operation scanning are reproduced verbatim, so the Spring Cache panel's
 * wire contract is unchanged.</p>
 */
public class SpringCacheProvider implements CacheProvider {

    private static final int MAX_SCANNED_METHODS = 5_000;

    private static final int MAX_CACHE_OPERATIONS = 1_000;

    private final ObjectProvider<ListableBeanFactory> beanFactoryProvider;

    private final ObjectProvider<CacheOperationSource> cacheOperationSources;

    private final BootUiProperties properties;

    private final BootUiSelfDataFilter selfDataFilter;

    private final SpringCacheInspectors inspectors;

    public SpringCacheProvider(
            ObjectProvider<ListableBeanFactory> beanFactoryProvider,
            ObjectProvider<CacheOperationSource> cacheOperationSources,
            BootUiProperties properties) {
        this(beanFactoryProvider, cacheOperationSources, properties, BootUiSelfDataFilter.defaults());
    }

    public SpringCacheProvider(
            ObjectProvider<ListableBeanFactory> beanFactoryProvider,
            ObjectProvider<CacheOperationSource> cacheOperationSources,
            BootUiProperties properties,
            BootUiSelfDataFilter selfDataFilter) {
        this.beanFactoryProvider = beanFactoryProvider;
        this.cacheOperationSources = cacheOperationSources;
        this.properties = properties;
        this.selfDataFilter = selfDataFilter;
        this.inspectors = SpringCacheInspectors.create(SpringCacheProvider.class.getClassLoader());
    }

    @Override
    public boolean available() {
        return beanFactoryProvider.getIfAvailable() != null;
    }

    @Override
    public boolean clearEnabled() {
        return properties.getCache().isClearEnabled();
    }

    @Override
    public List<CacheManagerSnapshot> managers() {
        ListableBeanFactory factory = beanFactoryProvider.getIfAvailable();
        if (factory == null) {
            return List.of();
        }
        List<CacheManagerSnapshot> managers = new ArrayList<>();
        for (CacheManagerEntry entry : discoverManagers(factory)) {
            managers.add(toManagerSnapshot(entry));
        }
        return managers;
    }

    @Override
    public CacheOperationDiscovery operations() {
        ListableBeanFactory factory = beanFactoryProvider.getIfAvailable();
        if (factory == null) {
            return CacheOperationDiscovery.empty();
        }
        return discoverOperations(factory);
    }

    @Override
    public Optional<String> clearUnavailableReason() {
        ListableBeanFactory factory = beanFactoryProvider.getIfAvailable();
        if (factory == null) {
            return Optional.of("No bean factory is available to discover cache managers.");
        }
        if (discoverManagers(factory).isEmpty()) {
            return Optional.of("No CacheManager beans are available.");
        }
        return Optional.empty();
    }

    @Override
    public boolean evict(String managerName, String cacheName) {
        ListableBeanFactory factory = beanFactoryProvider.getIfAvailable();
        if (factory == null) {
            throw new IllegalStateException("No bean factory is available to discover cache managers.");
        }
        CacheManager manager = factory.getBean(managerName, CacheManager.class);
        Cache cache = manager.getCache(cacheName);
        if (cache == null) {
            return false;
        }
        cache.clear();
        return true;
    }

    private CacheManagerSnapshot toManagerSnapshot(CacheManagerEntry entry) {
        List<CacheSnapshot> caches = new ArrayList<>();
        for (String cacheName : cacheNames(entry.manager())) {
            Cache cache = entry.manager().getCache(cacheName);
            if (cache == null) {
                continue;
            }
            caches.add(toCacheSnapshot(cacheName, cache));
        }
        // Unwrap a CacheActivityCacheManager (installed only when activity capture is enabled) so the
        // reported type/no-op status describes the real CacheManager, not the recording decorator.
        CacheManager realManager = CacheActivityAware.unwrap(entry.manager());
        SpringCacheManagerInspector.Structure structure = structureOf(realManager);
        return new CacheManagerSnapshot(
                entry.name(),
                realManager.getClass().getName(),
                isNoOp(realManager),
                structure.composition(),
                structure.dynamicCaches(),
                structure.delegateTypes(),
                caches);
    }

    private SpringCacheManagerInspector.Structure structureOf(CacheManager manager) {
        for (SpringCacheManagerInspector inspector : inspectors.managerInspectors()) {
            Optional<SpringCacheManagerInspector.Structure> structure = safeInspect(inspector, manager);
            if (structure.isPresent()) {
                return structure.get();
            }
        }
        return new SpringCacheManagerInspector.Structure(
                CacheManagerSnapshot.COMPOSITION_UNKNOWN, CacheManagerSnapshot.DYNAMIC_UNKNOWN, List.of());
    }

    private Optional<SpringCacheManagerInspector.Structure> safeInspect(
            SpringCacheManagerInspector inspector, CacheManager manager) {
        try {
            return inspector.inspect(manager);
        } catch (RuntimeException | LinkageError ex) {
            return Optional.empty();
        }
    }

    /**
     * Describes one cache with the first inspector that recognises it. A cache no inspector recognises is
     * reported opaque — with the reason shown in the panel — rather than having a tier invented for it from
     * its class name or reflected out of the provider's internals.
     */
    private CacheSnapshot toCacheSnapshot(String cacheName, Cache cache) {
        // Look past BootUI's own activity-capture decorator so an inspector sees the provider's real cache.
        Cache realCache = CacheActivityAware.unwrap(cache);
        Object nativeCache = nativeCache(realCache);
        String nativeType = nativeCache == null ? null : nativeCache.getClass().getName();
        if (nativeCache == null) {
            return new CacheSnapshot(
                    cacheName,
                    null,
                    null,
                    List.of(),
                    CacheStatisticsSnapshot.unavailable(null, "This cache exposes no native cache to inspect."),
                    "This cache exposes no native cache to inspect.");
        }
        for (SpringCacheInspector inspector : inspectors.cacheInspectors()) {
            Optional<SpringCacheInspector.Inspection> inspection;
            try {
                inspection = inspector.inspect(realCache, nativeCache);
            } catch (RuntimeException | LinkageError ex) {
                continue;
            }
            if (inspection.isPresent() && !inspection.get().tiers().isEmpty()) {
                SpringCacheInspector.Inspection found = inspection.get();
                return new CacheSnapshot(cacheName, nativeType, found.size(), found.tiers(), found.statistics(), null);
            }
        }
        String reason = "BootUI cannot describe the storage of " + ClassUtils.getShortName(nativeCache.getClass())
                + " through a public API, so its tiers and statistics are unknown.";
        return new CacheSnapshot(
                cacheName, nativeType, null, List.of(), CacheStatisticsSnapshot.unavailable(null, reason), reason);
    }

    private List<CacheManagerEntry> discoverManagers(ListableBeanFactory factory) {
        String[] beanNames = factory.getBeanNamesForType(CacheManager.class, false, false);
        Arrays.sort(beanNames);
        List<CacheManagerEntry> managers = new ArrayList<>(beanNames.length);
        for (String beanName : beanNames) {
            CacheManager manager = factory.getBean(beanName, CacheManager.class);
            // Self-filtering must inspect the real backing type, not the activity-capture decorator
            // (whose class lives under io.github.jdubois.bootui and would otherwise be mistaken for
            // BootUI's own cache manager and filtered out entirely).
            if (!selfDataFilter.shouldIncludeCacheOperation(
                    beanName, CacheActivityAware.unwrap(manager).getClass())) {
                continue;
            }
            managers.add(new CacheManagerEntry(beanName, manager));
        }
        return managers;
    }

    private List<String> cacheNames(CacheManager manager) {
        return manager.getCacheNames().stream().sorted().toList();
    }

    /**
     * Reads the native store of a cache defensively. A broken or half-present provider can throw anything
     * here, including a {@link LinkageError} when one of its own classes is missing, and a single bad cache
     * must never take the whole panel down.
     */
    private Object nativeCache(Cache cache) {
        try {
            return cache.getNativeCache();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private boolean isNoOp(CacheManager manager) {
        return manager instanceof NoOpCacheManager;
    }

    private CacheOperationDiscovery discoverOperations(ListableBeanFactory factory) {
        List<CacheOperationSource> sources =
                cacheOperationSources.orderedStream().toList();
        if (sources.isEmpty()) {
            return CacheOperationDiscovery.empty();
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
            if (!selfDataFilter.shouldIncludeCacheOperation(beanName, userType)) {
                continue;
            }
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
                    } catch (RuntimeException ex) {
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
        return new CacheOperationDiscovery(operations, warnings);
    }

    private Class<?> safeGetType(ListableBeanFactory factory, String beanName) {
        try {
            return factory.getType(beanName, false);
        } catch (BeansException ex) {
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

    private CacheOperationDto toOperationDto(
            String beanName, Class<?> targetType, Method method, CacheOperation operation) {
        String unless = null;
        boolean allEntries = false;
        boolean beforeInvocation = false;
        if (operation instanceof CacheableOperation cacheable) {
            unless = BlankStrings.blankToNull(cacheable.getUnless());
        } else if (operation instanceof CachePutOperation cachePut) {
            unless = BlankStrings.blankToNull(cachePut.getUnless());
        } else if (operation instanceof CacheEvictOperation cacheEvict) {
            allEntries = cacheEvict.isCacheWide();
            beforeInvocation = cacheEvict.isBeforeInvocation();
        }

        return new CacheOperationDto(
                beanName,
                targetType.getName(),
                signatureOf(method),
                operationName(operation),
                operation.getCacheNames().stream().sorted().toList(),
                BlankStrings.blankToNull(operation.getKey()),
                BlankStrings.blankToNull(operation.getCondition()),
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

    private record CacheManagerEntry(String name, CacheManager manager) {}
}
