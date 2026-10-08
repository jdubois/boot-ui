package io.github.jdubois.bootui.core.dto;

/**
 * Request to clear one cache or every known cache.
 */
public record CacheClearRequest(String managerName, String cacheName, Boolean all, Boolean confirm) {}
