package io.github.jdubois.bootui.autoconfigure.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import io.github.jdubois.bootui.spi.CacheManagerSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheOperationSource;

/**
 * Verifies {@code SpringCacheProvider} reports the real, undecorated {@link CacheManager} type/no-op
 * status and self-filtering decision even when the bean is wrapped by {@link CacheActivityCacheManager}
 * (installed only when cache activity capture is enabled) — the critical unwrap fix that keeps the Cache
 * panel's reported type/no-op status and self-filtering correct once capture is on.
 */
class SpringCacheProviderTests {

    @SuppressWarnings("unchecked")
    private static SpringCacheProvider provider(ListableBeanFactory factory) {
        ObjectProvider<ListableBeanFactory> factoryProvider = mock(ObjectProvider.class);
        when(factoryProvider.getIfAvailable()).thenReturn(factory);
        ObjectProvider<CacheOperationSource> operationSourceProvider = mock(ObjectProvider.class);
        when(operationSourceProvider.orderedStream())
                .thenReturn(java.util.stream.Stream.of(new AnnotationCacheOperationSource()));
        return new SpringCacheProvider(
                factoryProvider, operationSourceProvider, new BootUiProperties(), BootUiSelfDataFilter.defaults());
    }

    @Test
    void reportsTheRealManagerTypeAndNoOpStatusThroughTheActivityWrapper() {
        ConcurrentMapCacheManager real = new ConcurrentMapCacheManager("orders");
        CacheManager wrapped = new CacheActivityCacheManager(real, new CacheActivityRecorder(true, 10), "cacheManager");

        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("cacheManager", wrapped);

        List<CacheManagerSnapshot> managers = provider(factory).managers();

        assertThat(managers).hasSize(1);
        assertThat(managers.get(0).type()).isEqualTo(ConcurrentMapCacheManager.class.getName());
        assertThat(managers.get(0).noOp()).isFalse();
    }

    @Test
    void doesNotMistakeTheActivityWrapperItselfForABootUiInternalBean() {
        ConcurrentMapCacheManager real = new ConcurrentMapCacheManager("orders");
        CacheManager wrapped = new CacheActivityCacheManager(real, new CacheActivityRecorder(true, 10), "cacheManager");

        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("cacheManager", wrapped);

        // Before the unwrap fix, self-filtering inspected the wrapper's class (which lives under
        // io.github.jdubois.bootui.autoconfigure.cache) and would have silently hidden every wrapped
        // manager from the Cache panel once activity capture was enabled.
        assertThat(provider(factory).managers()).isNotEmpty();
    }
}
