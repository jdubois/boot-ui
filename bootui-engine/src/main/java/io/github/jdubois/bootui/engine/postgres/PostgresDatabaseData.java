package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.core.dto.PostgresIndexDto;
import io.github.jdubois.bootui.core.dto.PostgresReplicationDto;
import io.github.jdubois.bootui.core.dto.PostgresSectionDto;
import io.github.jdubois.bootui.core.dto.PostgresSessionDto;
import io.github.jdubois.bootui.core.dto.PostgresSettingDto;
import io.github.jdubois.bootui.core.dto.PostgresStatementDto;
import io.github.jdubois.bootui.core.dto.PostgresTableDto;
import io.github.jdubois.bootui.core.dto.PostgresVacuumDto;
import io.github.jdubois.bootui.core.dto.PostgresVitalSignsDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The mutable accumulator one datasource's collectors fill in.
 *
 * <p>It is deliberately not a DTO: it also carries the raw {@code pg_settings} values a later collector
 * needs — the autovacuum threshold is computed against the server's real configuration rather than assumed
 * defaults.</p>
 */
final class PostgresDatabaseData {

    private final String dataSourceName;
    private final List<PostgresSectionDto> sections = new ArrayList<>();
    private final Map<String, String> settingValues = new LinkedHashMap<>();

    private PostgresVitalSignsDto vitalSigns;
    private List<PostgresSessionDto> sessions = List.of();
    private List<PostgresStatementDto> statements = List.of();
    private List<PostgresIndexDto> indexes = List.of();
    private List<PostgresTableDto> tables = List.of();
    private List<PostgresVacuumDto> vacuum = List.of();
    private List<PostgresSettingDto> settings = List.of();
    private PostgresReplicationDto replication;
    private boolean truncated;
    private String unpinnedReason;
    private boolean statisticsRestricted;

    PostgresDatabaseData(String dataSourceName) {
        this.dataSourceName = dataSourceName;
    }

    String dataSourceName() {
        return dataSourceName;
    }

    /**
     * Records whether the connected role lacks {@code pg_read_all_stats}.
     *
     * <p>This is the only reliable signal a collector has. PostgreSQL restricts its statistics views in two
     * different and equally invisible ways: {@code pg_stat_activity} removes the rows of backends the role
     * does not own, while {@code pg_stat_statements} keeps the rows and replaces the statement text with the
     * literal {@code <insufficient privilege>}. Neither leaves a null a collector could notice, so a
     * restricted read would otherwise produce a short, confident, complete-looking table.</p>
     */
    void markStatisticsRestricted(boolean restricted) {
        this.statisticsRestricted = restricted;
    }

    /** Whether the connected role cannot see other roles' statistics. */
    boolean statisticsRestricted() {
        return statisticsRestricted;
    }

    void addSection(PostgresSectionDto section) {
        sections.add(section);
        if (section.truncated()) {
            truncated = true;
        }
    }

    List<PostgresSectionDto> sections() {
        return List.copyOf(sections);
    }

    /** The named section as recorded, or {@code null} when no collector reported it. */
    PostgresSectionDto section(String sectionId) {
        return sections.stream()
                .filter(section -> section.id().equals(sectionId))
                .findFirst()
                .orElse(null);
    }

    PostgresVitalSignsDto vitalSigns() {
        return vitalSigns;
    }

    void vitalSigns(PostgresVitalSignsDto value) {
        this.vitalSigns = value;
    }

    List<PostgresSessionDto> sessions() {
        return sessions;
    }

    void sessions(List<PostgresSessionDto> value) {
        this.sessions = List.copyOf(value);
    }

    List<PostgresStatementDto> statements() {
        return statements;
    }

    void statements(List<PostgresStatementDto> value) {
        this.statements = List.copyOf(value);
    }

    List<PostgresIndexDto> indexes() {
        return indexes;
    }

    void indexes(List<PostgresIndexDto> value) {
        this.indexes = List.copyOf(value);
    }

    List<PostgresTableDto> tables() {
        return tables;
    }

    void tables(List<PostgresTableDto> value) {
        this.tables = List.copyOf(value);
    }

    List<PostgresVacuumDto> vacuum() {
        return vacuum;
    }

    void vacuum(List<PostgresVacuumDto> value) {
        this.vacuum = List.copyOf(value);
    }

    List<PostgresSettingDto> settings() {
        return settings;
    }

    void settings(List<PostgresSettingDto> value) {
        this.settings = List.copyOf(value);
    }

    PostgresReplicationDto replication() {
        return replication;
    }

    void replication(PostgresReplicationDto value) {
        this.replication = value;
    }

    Map<String, String> settingValues() {
        return settingValues;
    }

    String setting(String name) {
        return settingValues.get(name);
    }

    double settingAsDouble(String name, double fallback) {
        String value = settingValues.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.strip());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    boolean truncated() {
        return truncated;
    }

    void markTruncated() {
        truncated = true;
    }

    /**
     * Why the session could not be pinned, or {@code null} when every pin was accepted.
     *
     * <p>An unpinned session is not a cosmetic failure: the documented statement, lock and idle bounds are
     * what make this read safe to run against a live server, so a read taken without them must not be
     * presented as a complete, bounded scan.</p>
     */
    String unpinnedReason() {
        return unpinnedReason;
    }

    void markSessionUnpinned(String reason) {
        if (reason != null && unpinnedReason == null) {
            unpinnedReason = reason;
        }
    }
}
