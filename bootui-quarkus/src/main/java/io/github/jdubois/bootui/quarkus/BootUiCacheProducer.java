package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.quarkus.cache.QuarkusCacheProvider;
import io.github.jdubois.bootui.spi.CacheProvider;
import io.quarkus.cache.CacheManager;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.Config;

/**
 * Cache-panel wiring for the Quarkus adapter: produces the cache-API-free {@link CacheProvider} the engine
 * {@code CacheService} reads, backed by the application's Quarkus {@link CacheManager}.
 *
 * <p><strong>It is deliberately not annotated with a CDI scope, and the deployment processor excludes it from
 * bean discovery when the {@code CACHE} capability is absent.</strong> The extension runtime jar is
 * Jandex-indexed (so Arc discovers the always-on beans), and Arc treats a {@code @Produces} method as
 * bean-defining — so this producer would be discovered whenever the jar is indexed, and Arc would try to
 * resolve its {@link CacheManager} parameter type even in an application without {@code quarkus-cache}, linking
 * an {@code io.quarkus.cache} type that must stay absent (R2). The processor therefore actively excludes this
 * class from discovery unless the {@code CACHE} capability is present (see
 * {@code BootUiQuarkusProcessor#registerCacheAdvisor}). This mirrors {@link BootUiHibernateProducer},
 * {@link BootUiHealthProducer} and {@link BootUiOtelProducer} exactly.</p>
 *
 * <p>When the capability is absent there is no {@code CacheProvider} bean, so the always-produced
 * {@code CacheService} (see {@link BootUiEngineProducer}) resolves an unsatisfied {@code Instance} to a
 * {@code null} provider and renders the panel unavailable.</p>
 */
public class BootUiCacheProducer {

    @Produces
    @Singleton
    public CacheProvider cacheProvider(CacheManager cacheManager, Config config) {
        return new QuarkusCacheProvider(cacheManager, config);
    }
}
