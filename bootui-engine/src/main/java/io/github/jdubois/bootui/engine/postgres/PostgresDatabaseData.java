package io.github.jdubois.bootui.engine.postgres;

import io.github.jdubois.bootui.core.dto.PostgresIndexDto;
import io.github.jdubois.bootui.core.dto.PostgresReplicationDto;
import io.github.jdubois.bootui.core.dto.PostgresSectionDto;
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
 * The mutable accumulator one datasource's collectors fill in, and the rules then read.
 *
 * <p>It is deliberately not a DTO: it also carries the raw {@code pg_settings} values the rules need to
 * compute an autovacuum threshold against the server's real configuration rather than assumed defaults.</p>
 */
final class PostgresDatabaseData {

    private final String dataSourceName;
    private final List<PostgresSectionDto> sections = new ArrayList<>();
    private final Map<String, String> settingValues = new LinkedHashMap<>();

    private PostgresVitalSignsDto vitalSigns;
    private List<PostgresStatementDto> statements = List.of();
    private List<PostgresIndexDto> indexes = List.of();
    private List<PostgresTableDto> tables = List.of();
    private List<PostgresVacuumDto> vacuum = List.of();
    private List<PostgresSettingDto> settings = List.of();
    private PostgresReplicationDto replication;
    private boolean truncated;

    PostgresDatabaseData(String dataSourceName) {
        this.dataSourceName = dataSourceName;
    }

    String dataSourceName() {
        return dataSourceName;
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

    /** True when the named section was read; rules whose evidence is missing must skip, not pass. */
    boolean sectionAvailable(String sectionId) {
        return sections.stream()
                .anyMatch(section -> section.id().equals(sectionId) && "AVAILABLE".equals(section.status()));
    }

    /** Replaces each section's finding count once the rules have run. */
    void recordFindingCounts(Map<String, Integer> countsBySection) {
        for (int i = 0; i < sections.size(); i++) {
            PostgresSectionDto section = sections.get(i);
            int count = countsBySection.getOrDefault(section.id(), 0);
            sections.set(
                    i,
                    new PostgresSectionDto(
                            section.id(),
                            section.title(),
                            section.status(),
                            section.reason(),
                            section.hint(),
                            section.rowCount(),
                            count,
                            section.truncated()));
        }
    }

    PostgresVitalSignsDto vitalSigns() {
        return vitalSigns;
    }

    void vitalSigns(PostgresVitalSignsDto value) {
        this.vitalSigns = value;
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
}
