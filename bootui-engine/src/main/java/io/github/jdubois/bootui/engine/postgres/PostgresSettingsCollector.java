package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.core.dto.PostgresSectionDto;
import io.github.jdubois.bootui.core.dto.PostgresSettingDto;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a curated allow-list of operational {@code pg_settings} rows.
 *
 * <p>The allow-list is the privacy control: BootUI never reads {@code pg_settings} wholesale, so no
 * command-bearing or path-bearing setting (and no setting a future PostgreSQL release adds) can reach the
 * browser by accident. It runs first because the autovacuum collector computes its "due?" verdict against
 * these real values rather than assumed defaults.</p>
 */
final class PostgresSettingsCollector implements PostgresCollector {

    /** Setting name to the reason BootUI shows it. Order is the display order. */
    static final Map<String, String> NOTABLE_SETTINGS = notableSettings();

    private static final String SQL = "select name, setting, unit, source from pg_settings where name in ("
            + placeholders() + ") order by name limit ?";

    @Override
    public String id() {
        return PostgresSectionIds.SETTINGS;
    }

    @Override
    public String title() {
        return "Notable settings";
    }

    @Override
    public PostgresSectionDto collect(PostgresReadContext context, PostgresDatabaseData data) {
        PostgresRows<PostgresSettingDto> rows = PostgresQuery.readList(
                context, "Server settings", SQL, context.limits().maxSettings(), resultSet -> {
                    String name = resultSet.getString("name");
                    String value = resultSet.getString("setting");
                    return new PostgresSettingDto(
                            name,
                            PostgresQueryText.truncate(value, 120),
                            resultSet.getString("unit"),
                            resultSet.getString("source"),
                            NOTABLE_SETTINGS.get(name));
                });
        if (!rows.available()) {
            return failed(rows.reason());
        }
        List<PostgresSettingDto> settings = new ArrayList<>(rows.rows());
        data.settings(settings);
        for (PostgresSettingDto setting : settings) {
            data.settingValues().put(setting.name(), setting.value());
        }
        return available(settings.size(), rows.truncated());
    }

    private static Map<String, String> notableSettings() {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("autovacuum", "Autovacuum off means dead tuples and transaction ids are never reclaimed.");
        settings.put("autovacuum_analyze_scale_factor", "Share of a table that must change before ANALYZE runs.");
        settings.put("autovacuum_analyze_threshold", "Fixed row count added to the ANALYZE scale factor.");
        settings.put("autovacuum_freeze_max_age", "The transaction-id age that forces an anti-wraparound vacuum.");
        settings.put("autovacuum_vacuum_scale_factor", "Share of a table that must be dead before VACUUM runs.");
        settings.put("autovacuum_vacuum_threshold", "Fixed dead-row count added to the VACUUM scale factor.");
        settings.put("checkpoint_timeout", "How often a time-triggered checkpoint runs.");
        settings.put("default_statistics_target", "How much detail ANALYZE collects for the planner.");
        settings.put("effective_cache_size", "The planner's estimate of the cache available to one query.");
        settings.put("fsync", "Turning fsync off trades crash safety for speed.");
        settings.put("full_page_writes", "Protects against torn pages after a crash.");
        settings.put("idle_in_transaction_session_timeout", "Bounds how long an idle transaction can hold locks.");
        settings.put("log_min_duration_statement", "The slow-query log threshold.");
        settings.put("maintenance_work_mem", "Memory available to VACUUM, ANALYZE and index builds.");
        settings.put("max_connections", "The hard ceiling on concurrent backends.");
        settings.put("max_wal_size", "WAL volume that forces a checkpoint before the timeout.");
        settings.put("random_page_cost", "The planner's index-versus-sequential-scan trade-off.");
        settings.put("shared_buffers", "PostgreSQL's own buffer cache size.");
        settings.put("statement_timeout", "The server-side ceiling on one statement.");
        settings.put("synchronous_commit", "Whether COMMIT waits for WAL to reach durable storage.");
        settings.put("track_io_timing", "Off means per-statement I/O time is not measured at all.");
        settings.put("wal_level", "Determines what replication and recovery the WAL can support.");
        settings.put("work_mem", "Memory per sort or hash before it spills to temporary files.");
        return Collections.unmodifiableMap(settings);
    }

    private static String placeholders() {
        List<String> quoted = new ArrayList<>();
        for (String name : NOTABLE_SETTINGS.keySet()) {
            quoted.add("'" + name + "'");
        }
        return String.join(", ", quoted);
    }
}
