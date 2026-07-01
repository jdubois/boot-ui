package io.github.jdubois.bootui.engine.cache;

import io.github.jdubois.bootui.core.dto.CacheClearResult;

/**
 * Framework-neutral outcome of a {@link CacheService#clear} call: the HTTP status the adapter should render
 * together with the {@link CacheClearResult} body. The engine owns the status decision so both adapters
 * report identical codes; each adapter maps {@link #status()} onto its native response type
 * ({@code ResponseEntity} on Spring, {@code Response} on Quarkus).
 *
 * @param status the HTTP status code (200, 400, 404, 409 or 500)
 * @param body the result body to serialize
 */
public record CacheClearResponse(int status, CacheClearResult body) {}
