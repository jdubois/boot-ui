package io.github.jdubois.bootui.core.dto;

/**
 * Second-level cache hit/miss/put counters for one named cache region, as reported by Hibernate's
 * {@code org.hibernate.stat.Statistics}.
 */
public record HibernateCacheRegionStatisticsDto(String regionName, long hitCount, long missCount, long putCount) {}
