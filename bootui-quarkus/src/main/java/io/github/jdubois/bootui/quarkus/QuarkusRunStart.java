package io.github.jdubois.bootui.quarkus;

import io.github.jdubois.bootui.engine.journal.RunStartEvents;
import io.github.jdubois.bootui.engine.journal.RuntimeJournal;
import io.github.jdubois.bootui.spi.ConnectionPoolInfo;
import io.github.jdubois.bootui.spi.ConnectionPoolProvider;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

/**
 * Publishes the run's {@code RUN_STARTED} lifecycle event when the application starts, including after a live reload
 * ({@code docs/PLAN-v2.md} §5.18): its comparability facts, which the run summary keeps. Quarkus reports no time to
 * ready or startup steps here, so the event carries none.
 */
@ApplicationScoped
public class QuarkusRunStart {

    /** Whether OpenTelemetry tracing is on the classpath, which the deployment processor records at build time. */
    public static final String OTEL_TRACER_PRESENT_KEY = "bootui.internal.otel-tracer-present";

    private static final Logger LOG = Logger.getLogger(QuarkusRunStart.class);

    private final RuntimeJournal journal;
    private final Config config;
    private final Instance<ConnectionPoolProvider> pools;

    @Inject
    public QuarkusRunStart(RuntimeJournal journal, Config config, @Any Instance<ConnectionPoolProvider> pools) {
        this.journal = journal;
        this.config = config;
        this.pools = pools;
    }

    void onStart(@Observes StartupEvent event) {
        try {
            RunStartEvents.publish(
                    journal,
                    System.currentTimeMillis(),
                    null,
                    List.of(),
                    profiles(),
                    jdbcUrls(),
                    cacheType(),
                    tracing());
        } catch (RuntimeException ex) {
            LOG.debugf(ex, "BootUI could not record the run's start.");
        }
    }

    private List<String> profiles() {
        try {
            return List.copyOf(config.unwrap(SmallRyeConfig.class).getProfiles());
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private Map<String, String> jdbcUrls() {
        Map<String, String> urls = new LinkedHashMap<>();
        for (ConnectionPoolProvider provider : pools) {
            for (ConnectionPoolInfo pool : provider.pools()) {
                String name = pool.beanName() != null ? pool.beanName() : pool.poolName();
                if (name != null) {
                    urls.put(name, pool.jdbcUrl());
                }
            }
        }
        return urls;
    }

    private String cacheType() {
        if (!flag(QuarkusPanelAvailability.CACHE_PRESENT_KEY, false) || !flag("quarkus.cache.enabled", true)) {
            return null;
        }
        return config.getOptionalValue("quarkus.cache.type", String.class).orElse("caffeine");
    }

    private boolean tracing() {
        return flag(OTEL_TRACER_PRESENT_KEY, false)
                && flag("quarkus.otel.enabled", true)
                && flag("quarkus.otel.traces.enabled", true);
    }

    private boolean flag(String key, boolean fallback) {
        try {
            return config.getOptionalValue(key, Boolean.class).orElse(fallback);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}
