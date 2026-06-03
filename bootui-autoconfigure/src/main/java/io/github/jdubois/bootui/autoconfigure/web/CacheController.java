package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.autoconfigure.monitoring.BootUiSelfDataFilter;
import io.github.jdubois.bootui.core.dto.CacheClearRequest;
import io.github.jdubois.bootui.core.dto.CacheClearResult;
import io.github.jdubois.bootui.core.dto.CacheReport;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller exposing Cache Manager introspection and cache eviction capabilities.
 *
 * <p>Supports generic Spring Cache managers, with specialized metrics extraction
 * for Caffeine and Redis (via Micrometer, if available).</p>
 */
@RestController
@ConditionalOnClass(CacheManager.class)
@RequestMapping("/bootui/api/cache")
public class CacheController {

    private final CacheService service;

    public CacheController(
            ObjectProvider<ListableBeanFactory> beanFactoryProvider,
            ObjectProvider<CacheOperationSource> cacheOperationSources,
            ObjectProvider<MeterRegistry> meterRegistries,
            BootUiProperties properties) {
        this(beanFactoryProvider, cacheOperationSources, meterRegistries, properties, BootUiSelfDataFilter.defaults());
    }

    @Autowired
    public CacheController(
            ObjectProvider<ListableBeanFactory> beanFactoryProvider,
            ObjectProvider<CacheOperationSource> cacheOperationSources,
            ObjectProvider<MeterRegistry> meterRegistries,
            BootUiProperties properties,
            BootUiSelfDataFilter selfDataFilter) {
        this.service = new CacheService(
                beanFactoryProvider, cacheOperationSources, meterRegistries, properties, selfDataFilter);
    }

    @GetMapping
    public CacheReport cache() {
        return service.cache();
    }

    @PostMapping("/clear")
    public ResponseEntity<CacheClearResult> clear(@RequestBody(required = false) CacheClearRequest request) {
        return service.clear(request);
    }
}
