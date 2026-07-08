package io.github.jdubois.bootui.autoconfigure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

class CacheActivityCacheManagerBeanPostProcessorTests {

    @Test
    void wrapsCacheManagerBeanWhenRecorderEnabled() {
        CacheActivityCacheManagerBeanPostProcessor bpp = new CacheActivityCacheManagerBeanPostProcessor(
                provider(new CacheActivityRecorder(true, 10)), provider(BootUiSelfDataFilter.defaults()));

        Object result = bpp.postProcessAfterInitialization(new ConcurrentMapCacheManager("orders"), "cacheManager");

        assertThat(result).isInstanceOf(CacheActivityAware.class);
    }

    @Test
    void leavesBeanUnwrappedWhenRecorderDisabled() {
        CacheActivityCacheManagerBeanPostProcessor bpp = new CacheActivityCacheManagerBeanPostProcessor(
                provider(new CacheActivityRecorder(false, 10)), provider(BootUiSelfDataFilter.defaults()));
        CacheManager original = new ConcurrentMapCacheManager("orders");

        Object result = bpp.postProcessAfterInitialization(original, "cacheManager");

        assertThat(result).isSameAs(original);
    }

    @Test
    void leavesBeanUnwrappedWhenRecorderAbsent() {
        CacheActivityCacheManagerBeanPostProcessor bpp = new CacheActivityCacheManagerBeanPostProcessor(
                provider(null), provider(BootUiSelfDataFilter.defaults()));
        CacheManager original = new ConcurrentMapCacheManager("orders");

        Object result = bpp.postProcessAfterInitialization(original, "cacheManager");

        assertThat(result).isSameAs(original);
    }

    @Test
    void ignoresNonCacheManagerBeans() {
        CacheActivityCacheManagerBeanPostProcessor bpp = new CacheActivityCacheManagerBeanPostProcessor(
                provider(new CacheActivityRecorder(true, 10)), provider(BootUiSelfDataFilter.defaults()));
        Object original = new Object();

        Object result = bpp.postProcessAfterInitialization(original, "notACacheManager");

        assertThat(result).isSameAs(original);
    }

    @Test
    void doesNotDoubleWrapAnAlreadyWrappedCacheManager() {
        CacheActivityCacheManagerBeanPostProcessor bpp = new CacheActivityCacheManagerBeanPostProcessor(
                provider(new CacheActivityRecorder(true, 10)), provider(BootUiSelfDataFilter.defaults()));
        CacheManager alreadyWrapped = new CacheActivityCacheManager(
                new ConcurrentMapCacheManager("orders"), new CacheActivityRecorder(true, 10), "cacheManager");

        Object result = bpp.postProcessAfterInitialization(alreadyWrapped, "cacheManager");

        assertThat(result).isSameAs(alreadyWrapped);
    }

    @Test
    void selfFilterExcludesBootUiOwnCacheManagerBeans() {
        CacheActivityCacheManagerBeanPostProcessor bpp = new CacheActivityCacheManagerBeanPostProcessor(
                provider(new CacheActivityRecorder(true, 10)), provider(BootUiSelfDataFilter.defaults()));
        CacheManager original = new BootUiInternalCacheManager();

        Object result = bpp.postProcessAfterInitialization(original, "cacheManager");

        assertThat(result).isSameAs(original);
    }

    /** Stands in for a CacheManager bean living under a BootUI-internal package, for self-filter testing. */
    private static final class BootUiInternalCacheManager extends ConcurrentMapCacheManager {
        BootUiInternalCacheManager() {
            super("orders");
        }
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfAvailable(java.util.function.Supplier<T> defaultSupplier) {
                return value != null ? value : defaultSupplier.get();
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }
}
