package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * One Spring {@code CacheManager} bean and its currently known caches.
 */
public record CacheManagerDto(String name, String type, boolean noOp, List<CacheDto> caches) {}
