package io.github.jdubois.bootui.spi;

/**
 * Framework-neutral, <em>unmasked</em> snapshot of one JDBC connection pool known to a
 * {@link ConnectionPoolProvider}. It carries the pool's identity, its (raw) connection metadata, sizing and
 * timeout settings, and — when the pool is reporting live counters — a {@link ConnectionPoolSnapshot}.
 *
 * <p>The {@code jdbcUrl} and {@code username} are the <strong>raw</strong> values: the engine
 * {@code ConnectionPoolService} masks them through {@link ExposurePolicy} before they reach the browser, so
 * the same masking serves both adapters and BootUI never leaks credentials.</p>
 *
 * <p>A setting the pool library does not expose is {@code null}, never a sentinel: the Quarkus/Agroal adapter has no
 * analogue of HikariCP's keepalive interval, per-call validation timeout, or read-only flag, so it reports
 * {@code null} for those (the UI renders them as "—"). A duration of {@code 0} is the library's own value, which both
 * HikariCP and Agroal use to mean disabled.</p>
 *
 * @param beanName the pool's bean/datasource name (the Spring bean name, or the Quarkus datasource name with
 *     the default datasource rendered as {@code "default"})
 * @param poolName the pool's own name when it exposes one, otherwise the datasource name
 * @param implementation the pool library, {@code HikariCP} or {@code Agroal}
 * @param jdbcUrl the <em>raw</em>, unmasked JDBC URL, or {@code null}
 * @param username the <em>raw</em>, unmasked pool username, or {@code null}
 * @param driverClassName the JDBC driver/connection-provider class name, or {@code null} when unknown
 * @param minimumIdle the minimum idle pool size, or {@code -1} when it could not be read
 * @param maximumPoolSize the maximum pool size, or {@code -1} when it could not be read
 * @param connectionTimeoutMs the max wait to acquire a connection, in millis, or {@code null}
 * @param idleTimeoutMs the idle-eviction threshold, in millis, or {@code null}
 * @param maxLifetimeMs the maximum connection lifetime, in millis, or {@code null}
 * @param validationTimeoutMs the validation timeout, in millis, or {@code null} when the pool library has no
 *     faithful equivalent
 * @param keepaliveTimeMs the keepalive interval, in millis, or {@code null} when the pool library has no
 *     faithful equivalent
 * @param readOnly whether the datasource is configured read-only, or {@code null} when the library exposes no flag
 * @param autoCommit whether connections default to auto-commit, or {@code null} when unknown
 * @param available whether the pool is reporting live counters (a non-null {@code snapshot})
 * @param unavailableReason a short reason when {@code available} is {@code false}, otherwise {@code null}
 * @param snapshot the live connection counts when reachable, otherwise {@code null}
 */
public record ConnectionPoolInfo(
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
        ConnectionPoolSnapshot snapshot) {

    /** HikariCP, the Spring Boot pool. */
    public static final String HIKARI = "HikariCP";

    /** Agroal, the Quarkus pool. */
    public static final String AGROAL = "Agroal";
}
