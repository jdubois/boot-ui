package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Result of a cache clear operation.
 */
public record CacheClearResult(String status, String message, int clearedCaches, List<String> caches) {}
