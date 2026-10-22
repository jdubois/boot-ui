package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Builds {@link DriverEvidence} from what a datasource's own connection reports and what its pool supplies. The JDBC
 * URL passes through here only to be parsed: nothing but allow-listed settings leaves this class, and the URL is not
 * retained.
 *
 * <p>A setting that cannot be settled is reported {@link DriverSetting.State#CONFLICTING}, never guessed: the URL and
 * the pool disagree and the driver defines no winner, the pool spelled the name two ways with two values, the name was
 * spelled in a way the driver does not honor (pgjdbc reads exact names), or Connector/J gave it as a host-level
 * property whose scope is not modeled.</p>
 */
final class DriverEvidenceReader {

    private DriverEvidenceReader() {}

    /**
     * @param jdbcUrl {@code DatabaseMetaData.getURL()}, or {@code null} when it could not be read; parsed and dropped
     * @param poolDriverProperties the pool's driver properties (filtered again here so a third-party provider that
     *     returns more than the allow-list cannot leak it), or {@code null} when they could not be read
     */
    static DriverEvidence read(
            String driverName, String driverVersion, String jdbcUrl, Map<String, String> poolDriverProperties) {
        DriverSettingCatalog.Driver driver = DriverSettingCatalog.Driver.of(driverName);
        boolean urlKnown = jdbcUrl != null && !jdbcUrl.isBlank();
        JdbcUrlSettings.Parsed url = JdbcUrlSettings.parseDetailed(jdbcUrl, DriverSettingCatalog.NAMES);
        PoolSources pool = PoolSources.of(DriverSettingCatalog.allowListed(poolDriverProperties));
        return new DriverEvidence(
                driverName, driverVersion, merge(driver, url, pool), urlKnown, poolDriverProperties != null);
    }

    /** Test seam: a URL and pool that are already reduced to canonical names. */
    static Map<String, DriverSetting> merge(
            DriverSettingCatalog.Driver driver, Map<String, String> fromUrl, Map<String, String> fromPool) {
        return merge(
                driver,
                new JdbcUrlSettings.Parsed(fromUrl, Set.of(), Map.of()),
                new PoolSources(fromPool, Set.of(), Set.of()));
    }

    private static Map<String, DriverSetting> merge(
            DriverSettingCatalog.Driver driver, JdbcUrlSettings.Parsed url, PoolSources pool) {
        Map<String, DriverSetting> settings = new LinkedHashMap<>();
        for (String name : DriverSettingCatalog.NAMES) {
            String fromUrl = url.values().get(name);
            String fromHost = url.hostScoped().get(name);
            String fromPool = pool.values().get(name);
            if (fromUrl == null && fromHost == null && fromPool == null) {
                continue;
            }
            boolean unsettled = fromHost != null
                    || pool.ambiguous().contains(name)
                    || url.irregularSpelling().contains(name)
                    || pool.irregularSpelling().contains(name);
            boolean disagree = fromUrl != null
                    && fromPool != null
                    && !fromUrl.equalsIgnoreCase(fromPool)
                    && DriverSettingCatalog.precedence(driver) == DriverSettingCatalog.Precedence.UNDEFINED;
            DriverSetting.State state =
                    unsettled || disagree ? DriverSetting.State.CONFLICTING : DriverSetting.State.KNOWN;
            if (fromUrl != null || fromHost != null) {
                settings.put(
                        name, new DriverSetting(fromUrl != null ? fromUrl : fromHost, DriverSetting.Source.URL, state));
            } else {
                settings.put(name, new DriverSetting(fromPool, DriverSetting.Source.POOL, state));
            }
        }
        return settings;
    }

    /** The pool's allow-listed properties grouped by canonical name, remembering odd spellings and disagreements. */
    private record PoolSources(Map<String, String> values, Set<String> irregularSpelling, Set<String> ambiguous) {

        static PoolSources of(Map<String, String> spelled) {
            Map<String, String> values = new HashMap<>();
            Set<String> irregular = new HashSet<>();
            Set<String> ambiguous = new HashSet<>();
            spelled.forEach((spelling, value) -> {
                String canonical = DriverSettingCatalog.canonical(spelling);
                if (canonical == null) {
                    return;
                }
                if (!canonical.equals(spelling)) {
                    irregular.add(canonical);
                }
                String previous = values.put(canonical, value);
                if (previous != null && !previous.equalsIgnoreCase(value)) {
                    ambiguous.add(canonical);
                }
            });
            return new PoolSources(Map.copyOf(values), Set.copyOf(irregular), Set.copyOf(ambiguous));
        }
    }
}
