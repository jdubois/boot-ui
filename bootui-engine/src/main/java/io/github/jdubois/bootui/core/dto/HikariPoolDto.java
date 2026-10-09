package io.github.jdubois.bootui.core.dto;

/**
 * One database connection pool, its (masked) connection metadata, sizing and timeout settings, and the latest pool
 * snapshot when reachable.
 *
 * <p>A setting the pool library does not expose is {@code null}, never a sentinel: Agroal has no per-call validation
 * timeout, keepalive interval, or read-only flag, for example. A duration of {@code 0} is the library's own value,
 * which both HikariCP and Agroal use to mean disabled.</p>
 *
 * @param implementation the pool library: {@code HikariCP} or {@code Agroal}
 */
public record HikariPoolDto(
        String beanName,
        String poolName,
        String implementation,
        String jdbcUrl,
        String username,
        String driverClassName,
        int minimumIdle,
        int maximumPoolSize,
        Long connectionTimeoutMs,
        Long idleTimeoutMs,
        Long maxLifetimeMs,
        Long validationTimeoutMs,
        Long keepaliveTimeMs,
        Boolean readOnly,
        Boolean autoCommit,
        boolean available,
        String unavailableReason,
        HikariPoolSnapshotDto snapshot) {}
