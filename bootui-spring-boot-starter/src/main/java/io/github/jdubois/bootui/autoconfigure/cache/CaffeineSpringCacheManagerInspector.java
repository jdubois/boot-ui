package io.github.jdubois.bootui.autoconfigure.cache;

import io.github.jdubois.bootui.spi.CacheManagerSnapshot;
import java.util.List;
import java.util.Optional;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;

/**
 * Classifies Spring's {@code CaffeineCacheManager}, instantiated by {@link SpringCacheInspectors} only when
 * both Caffeine and {@code spring-context-support} are on the classpath.
 *
 * <p>It is deliberately separate from {@link CaffeineSpringCacheInspector}: an application can carry Caffeine
 * for another purpose (a JCache region factory, for instance) without {@code spring-context-support}, and the
 * cache-level Caffeine inspection must still work there.</p>
 */
final class CaffeineSpringCacheManagerInspector implements SpringCacheManagerInspector {

    @Override
    public Optional<Structure> inspect(CacheManager manager) {
        if (manager instanceof CaffeineCacheManager) {
            // CaffeineCacheManager creates caches on demand unless an explicit cache-name set was configured,
            // and exposes no getter for that set, so the dynamic state is reported unknown rather than guessed.
            return Optional.of(new Structure(
                    CacheManagerSnapshot.COMPOSITION_SIMPLE, CacheManagerSnapshot.DYNAMIC_UNKNOWN, List.of()));
        }
        return Optional.empty();
    }
}
