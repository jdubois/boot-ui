package io.github.jdubois.bootui.autoconfigure.cache;

import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.engine.cache.CacheActivityRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cache.CacheManager;

/**
 * Wraps every {@link CacheManager} bean with {@link CacheActivityCacheManager} after initialization, so
 * cache hit/miss/put/evict/clear are captured for the Live Activity panel's {@code CACHE} event type
 * without any third-party AOP hook — mirroring {@code SqlTraceDataSourceBeanPostProcessor}'s approach for
 * JDBC.
 *
 * <p>The recorder is resolved lazily through an {@link ObjectProvider} so this post-processor does not
 * force early creation of unrelated beans, and wrapping is skipped entirely when capture is disabled or
 * the bean is BootUI's own. It fails open: if wrapping a {@code CacheManager} throws, the original bean is
 * returned unchanged so application caching is never compromised.</p>
 */
public final class CacheActivityCacheManagerBeanPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(CacheActivityCacheManagerBeanPostProcessor.class);

    private final ObjectProvider<CacheActivityRecorder> recorderProvider;
    private final ObjectProvider<BootUiSelfDataFilter> selfDataFilterProvider;

    public CacheActivityCacheManagerBeanPostProcessor(
            ObjectProvider<CacheActivityRecorder> recorderProvider,
            ObjectProvider<BootUiSelfDataFilter> selfDataFilterProvider) {
        this.recorderProvider = recorderProvider;
        this.selfDataFilterProvider = selfDataFilterProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof CacheManager cacheManager) || bean instanceof CacheActivityAware) {
            return bean;
        }
        CacheActivityRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null || !recorder.isEnabled()) {
            return bean;
        }
        BootUiSelfDataFilter selfDataFilter = selfDataFilterProvider.getIfAvailable(BootUiSelfDataFilter::defaults);
        if (!selfDataFilter.shouldIncludeCacheOperation(beanName, cacheManager.getClass())) {
            return bean;
        }
        try {
            return new CacheActivityCacheManager(cacheManager, recorder, beanName);
        } catch (Throwable ex) {
            if (ex instanceof VirtualMachineError vme) {
                throw vme;
            }
            log.warn(
                    "BootUI could not enable cache activity capture for CacheManager bean '{}'; leaving it unwrapped",
                    beanName,
                    ex);
            return bean;
        }
    }
}
