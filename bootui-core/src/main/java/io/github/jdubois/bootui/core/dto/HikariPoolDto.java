package io.github.jdubois.bootui.core.dto;

/**
 * One database connection pool bean, its (masked) connection metadata, sizing
 * and timeout settings, and the latest pool snapshot when reachable.
 */
public record HikariPoolDto(
        String beanName,
        String poolName,
        String jdbcUrl,
        String username,
        String driverClassName,
        int minimumIdle,
        int maximumPoolSize,
        long connectionTimeoutMs,
        long idleTimeoutMs,
        long maxLifetimeMs,
        long validationTimeoutMs,
        long keepaliveTimeMs,
        boolean readOnly,
        boolean autoCommit,
        boolean available,
        String unavailableReason,
        HikariPoolSnapshotDto snapshot) {}
