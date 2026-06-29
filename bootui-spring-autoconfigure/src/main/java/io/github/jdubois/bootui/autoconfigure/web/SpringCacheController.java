package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.core.dto.CacheClearRequest;
import io.github.jdubois.bootui.core.dto.CacheClearResult;
import io.github.jdubois.bootui.core.dto.CacheReport;
import io.github.jdubois.bootui.engine.cache.CacheClearResponse;
import io.github.jdubois.bootui.engine.cache.CacheService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cache.CacheManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller exposing Spring Cache manager introspection and cache eviction capabilities.
 *
 * <p>Supports generic Spring Cache managers, with specialized metrics extraction
 * for Caffeine and Redis (via Micrometer, if available).</p>
 *
 * <p>The behaviour lives in the framework-neutral engine {@link CacheService}; this controller is a thin
 * binding that maps the engine's {@link CacheClearResponse} status onto a Spring {@code ResponseEntity}.</p>
 */
@RestController
@ConditionalOnClass(CacheManager.class)
@RequestMapping("/bootui/api/cache")
public class SpringCacheController {

    private final CacheService service;

    public SpringCacheController(CacheService service) {
        this.service = service;
    }

    @GetMapping
    public CacheReport springCache() {
        return service.report();
    }

    @PostMapping("/clear")
    public ResponseEntity<CacheClearResult> clear(@RequestBody(required = false) CacheClearRequest request) {
        CacheClearResponse response = service.clear(request);
        return ResponseEntity.status(response.status()).body(response.body());
    }
}
