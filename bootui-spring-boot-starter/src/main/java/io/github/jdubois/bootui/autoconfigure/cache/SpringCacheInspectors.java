package io.github.jdubois.bootui.autoconfigure.cache;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.util.ClassUtils;

/**
 * Builds the ordered inspector chains {@code SpringCacheProvider} uses to describe cache tiers, native
 * statistics and cache-manager structure.
 *
 * <p>Optional-provider inspectors are only <em>instantiated</em> — and therefore only class-loaded — after a
 * {@link ClassUtils#isPresent} check, so an application without Caffeine or Spring Data Redis never links
 * those types. A marker class being present is not proof that the rest of the library links, though: a
 * version-skewed or partially shaded dependency can still raise {@link LinkageError} while the inspector's own
 * class is being verified. Each optional inspector is therefore built defensively and simply left out of the
 * chain when it cannot be loaded, which degrades to the core inspector rather than failing the panel.</p>
 *
 * <p>The chains are ordered most specific first, so a provider-specific inspector wins over the core
 * Spring/JDK fallback (Spring Data Redis' {@code RedisCacheManager}, for example, is also an
 * {@code AbstractCacheManager}).</p>
 */
final class SpringCacheInspectors {

    private static final String CAFFEINE_CACHE = "com.github.benmanes.caffeine.cache.Cache";

    private static final String SPRING_CAFFEINE_CACHE_MANAGER =
            "org.springframework.cache.caffeine.CaffeineCacheManager";

    private static final String REDIS_CACHE = "org.springframework.data.redis.cache.RedisCache";

    private static final Logger log = System.getLogger(SpringCacheInspectors.class.getName());

    private final List<SpringCacheInspector> cacheInspectors;

    private final List<SpringCacheManagerInspector> managerInspectors;

    private SpringCacheInspectors(
            List<SpringCacheInspector> cacheInspectors, List<SpringCacheManagerInspector> managerInspectors) {
        this.cacheInspectors = List.copyOf(cacheInspectors);
        this.managerInspectors = List.copyOf(managerInspectors);
    }

    static SpringCacheInspectors create(ClassLoader classLoader) {
        List<SpringCacheInspector> cacheInspectors = new ArrayList<>();
        List<SpringCacheManagerInspector> managerInspectors = new ArrayList<>();

        boolean caffeinePresent = ClassUtils.isPresent(CAFFEINE_CACHE, classLoader);
        if (caffeinePresent) {
            addIfLinkable(CaffeineSpringCacheInspector::new, cacheInspectors::add, CAFFEINE_CACHE);
        }
        if (caffeinePresent && ClassUtils.isPresent(SPRING_CAFFEINE_CACHE_MANAGER, classLoader)) {
            addIfLinkable(
                    CaffeineSpringCacheManagerInspector::new, managerInspectors::add, SPRING_CAFFEINE_CACHE_MANAGER);
        }
        if (ClassUtils.isPresent(REDIS_CACHE, classLoader)) {
            addIfLinkable(
                    RedisSpringCacheInspector::new,
                    redis -> {
                        cacheInspectors.add(redis);
                        managerInspectors.add(redis);
                    },
                    REDIS_CACHE);
        }

        CoreSpringCacheInspector core = new CoreSpringCacheInspector();
        cacheInspectors.add(core);
        managerInspectors.add(core);
        return new SpringCacheInspectors(cacheInspectors, managerInspectors);
    }

    /**
     * Builds one optional inspector and registers it, skipping it when constructing or linking it fails. The
     * core inspector still covers the cache, so a broken optional dependency costs detail, not the panel.
     */
    private static <T> void addIfLinkable(Supplier<T> factory, Consumer<T> register, String marker) {
        try {
            register.accept(factory.get());
        } catch (RuntimeException | LinkageError ex) {
            log.log(
                    Level.DEBUG,
                    () -> "BootUI skipped the cache inspector for " + marker + " because it could not be loaded: "
                            + ex);
        }
    }

    List<SpringCacheInspector> cacheInspectors() {
        return cacheInspectors;
    }

    List<SpringCacheManagerInspector> managerInspectors() {
        return managerInspectors;
    }
}
