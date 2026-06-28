package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * Framework-neutral seam behind the Database Connection Pools panel: it reports the host application's JDBC
 * connection pools as <em>unmasked</em>, framework-neutral snapshots, while the engine
 * {@code ConnectionPoolService} owns the neutral concerns — masking the JDBC URL and username (behind the
 * {@link ExposurePolicy} SPI), sorting, counting, and assembling the wire DTOs.
 *
 * <p>The split mirrors the Cache panel's {@link CacheProvider}. <strong>Pool discovery</strong> (which pools
 * exist, their configuration and live connection counts) is framework-specific and lives here: the Spring
 * adapter implements it over {@code com.zaxxer.hikari.HikariDataSource} beans and their
 * {@code HikariPoolMXBean}; the Quarkus adapter over {@code io.agroal.api.AgroalDataSource} and its
 * {@code AgroalDataSourceMetrics}. <strong>Masking</strong> is identical on both frameworks (it is purely a
 * function of the {@link ExposurePolicy} decision plus the URL/credential text), so it is a sanctioned engine
 * concern and is shared — keeping the Spring panel's wire output byte-identical after the extraction.</p>
 *
 * <p>The panel is strictly read-only: implementations only read pool configuration getters and live counters,
 * never borrowing a connection, executing SQL, or mutating the pool. There is therefore no state-changing
 * action and no write gate. When no pool backend is present the adapter supplies no {@code ConnectionPoolProvider}
 * at all (the engine is given {@code null} and renders the panel unavailable); a present backend with zero
 * pools configured returns an empty {@link #pools()} list and the panel renders its empty state.</p>
 */
public interface ConnectionPoolProvider {

    /**
     * The application's connection pools as framework-neutral, <em>unsorted</em> and <em>unmasked</em>
     * snapshots. The engine sorts them by pool name, masks the JDBC URL and username, and assembles the wire
     * report. Returns an empty list when no pools are configured.
     */
    List<ConnectionPoolInfo> pools();
}
