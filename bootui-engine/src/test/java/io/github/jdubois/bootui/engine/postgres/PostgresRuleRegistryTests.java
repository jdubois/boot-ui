package io.github.jdubois.bootui.engine.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.PostgresIndexDto;
import io.github.jdubois.bootui.core.dto.PostgresReplicationDto;
import io.github.jdubois.bootui.core.dto.PostgresSectionDto;
import io.github.jdubois.bootui.core.dto.PostgresStatementDto;
import io.github.jdubois.bootui.core.dto.PostgresTableDto;
import io.github.jdubois.bootui.core.dto.PostgresVacuumDto;
import io.github.jdubois.bootui.core.dto.PostgresVitalSignsDto;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class PostgresRuleRegistryTests {

    @Test
    void ruleDefinitionsHaveStableNonBlankUniqueMetadata() {
        assertThat(PostgresRuleRegistry.rules()).hasSize(19);
        assertThat(PostgresRuleRegistry.rules())
                .extracting(rule -> rule.definition().id())
                .doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id).isNotBlank());
        assertThat(PostgresRuleRegistry.rules()).allSatisfy(rule -> {
            PostgresRuleDefinition definition = rule.definition();
            assertThat(definition.title()).isNotBlank();
            assertThat(definition.sectionId()).isNotBlank();
            assertThat(definition.category()).isNotBlank();
            assertThat(definition.severity()).isIn("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");
            assertThat(definition.description()).isNotBlank();
            assertThat(definition.recommendation()).isNotBlank();
            assertThat(definition.caveat()).isNotBlank();
            assertThat(definition.learnMoreUrl()).startsWith("https://");
        });
    }

    @TestFactory
    List<DynamicTest> everyRuleFiresAtTheThresholdAndStaysSilentBelowIt() {
        return cases().stream()
                .map(testCase -> DynamicTest.dynamicTest(testCase.id(), () -> {
                    PostgresRule rule = rule(testCase.id());
                    PostgresDatabaseData clean = data(testCase.sectionId());
                    testCase.clean().accept(clean);
                    PostgresDatabaseData violation = data(testCase.sectionId());
                    testCase.violation().accept(violation);

                    assertThat(rule.evaluate(clean)).as("clean fixture").isNull();
                    assertThat(rule.evaluate(violation)).as("violating fixture").isNotNull();
                }))
                .toList();
    }

    @Test
    void unavailableSectionsDoNotEvaluateRules() {
        PostgresDatabaseData data = data(PostgresSectionIds.VITAL_SIGNS);
        data.statements(List.of(new PostgresStatementDto("1", "select 1", 10L, 1000d, 100d, 100d, 1L, null)));

        assertThat(data.sectionAvailable(PostgresSectionIds.STATEMENTS)).isFalse();
        assertThat(PostgresRuleRegistry.rules())
                .filteredOn(rule -> data.sectionAvailable(rule.definition().sectionId()))
                .noneMatch(rule -> rule.definition().id().startsWith("PG-STATEMENTS-"));
    }

    @Test
    void aStatementWithNoTimingDoesNotCountTowardsTheDominantShareEvidence() {
        // pg_stat_statements without timing tracking returns rows with no total time. They contribute
        // nothing to the share, so counting them would let a two-statement comparison — where a share above
        // half is arithmetic rather than evidence — pass the five-statement minimum.
        PostgresDatabaseData data = data(PostgresSectionIds.STATEMENTS);
        data.statements(List.of(
                statement(1L, 900d, 10d),
                statement(1L, 200d, 10d),
                untimedStatement(),
                untimedStatement(),
                untimedStatement()));

        assertThat(PostgresRuleRegistry.rules())
                .filteredOn(rule -> rule.definition().id().equals("PG-STATEMENTS-002"))
                .singleElement()
                .satisfies(rule -> assertThat(rule.evaluate(data)).isNull());
    }

    private static List<RuleCase> cases() {
        return List.of(
                ruleCase(
                        "PG-VITALS-001",
                        PostgresSectionIds.VITAL_SIGNS,
                        data -> data.vitalSigns(vitals(0.90, 0.0, 1, 0, 1L, 0L, 0L, 0L)),
                        data -> data.vitalSigns(vitals(0.899, 0.0, 1, 0, 1L, 0L, 0L, 0L))),
                ruleCase(
                        "PG-VITALS-002",
                        PostgresSectionIds.VITAL_SIGNS,
                        data -> data.vitalSigns(vitals(0.99, 0.05, 1, 0, 1L, 0L, 0L, 0L)),
                        data -> data.vitalSigns(vitals(0.99, 0.051, 1, 0, 1L, 0L, 0L, 0L))),
                ruleCase(
                        "PG-VITALS-003",
                        PostgresSectionIds.VITAL_SIGNS,
                        data -> data.vitalSigns(vitals(0.99, 0.0, 79, 0, 1L, 0L, 0L, 0L)),
                        data -> data.vitalSigns(vitals(0.99, 0.0, 80, 0, 1L, 0L, 0L, 0L))),
                ruleCase(
                        "PG-VITALS-004",
                        PostgresSectionIds.VITAL_SIGNS,
                        data -> data.vitalSigns(vitals(0.99, 0.0, 1, 0, 1L, 0L, 0L, 0L)),
                        data -> data.vitalSigns(vitals(0.99, 0.0, 1, 1, 1L, 0L, 0L, 0L))),
                ruleCase(
                        "PG-VITALS-005",
                        PostgresSectionIds.VITAL_SIGNS,
                        data -> data.vitalSigns(vitals(0.99, 0.0, 1, 0, 1L, 0L, 0L, 0L)),
                        data -> data.vitalSigns(vitals(0.99, 0.0, 1, 0, 1L, 1L, 0L, 0L))),
                ruleCase(
                        "PG-VITALS-006",
                        PostgresSectionIds.VITAL_SIGNS,
                        data -> data.vitalSigns(vitals(0.99, 0.0, 1, 0, 999L, 0L, 0L, 0L)),
                        data -> data.vitalSigns(vitals(0.99, 0.0, 1, 0, 1000L, 0L, 0L, 0L))),
                ruleCase(
                        "PG-VITALS-007",
                        PostgresSectionIds.VITAL_SIGNS,
                        data -> data.vitalSigns(vitals(0.99, 0.0, 1, 0, 1L, 0L, 0L, 0L)),
                        data -> data.vitalSigns(vitals(0.99, 0.0, 1, 0, 1L, 0L, 1L, 0L))),
                ruleCase(
                        "PG-VITALS-008",
                        PostgresSectionIds.VITAL_SIGNS,
                        data -> data.vitalSigns(vitals(0.99, 0.0, 1, 0, 1L, 0L, 0L, 0L)),
                        data -> data.vitalSigns(vitals(0.99, 0.0, 1, 0, 1L, 0L, 0L, 1L))),
                ruleCase(
                        "PG-STATEMENTS-001",
                        PostgresSectionIds.STATEMENTS,
                        data -> data.statements(List.of(statement(10L, 100d, 99.9d))),
                        data -> data.statements(List.of(statement(10L, 1000d, 100d)))),
                ruleCase(
                        "PG-STATEMENTS-002",
                        PostgresSectionIds.STATEMENTS,
                        data -> data.statements(List.of(
                                statement(1L, 200d, 10d),
                                statement(1L, 200d, 10d),
                                statement(1L, 200d, 10d),
                                statement(1L, 200d, 10d),
                                statement(1L, 200d, 10d))),
                        data -> data.statements(List.of(
                                statement(1L, 600d, 10d),
                                statement(1L, 100d, 10d),
                                statement(1L, 100d, 10d),
                                statement(1L, 100d, 10d),
                                statement(1L, 100d, 10d)))),
                ruleCase(
                        "PG-INDEX-001",
                        PostgresSectionIds.INDEXES,
                        data -> data.indexes(List.of(index(0L, 1024L * 1024L - 1))),
                        data -> data.indexes(List.of(index(0L, 1024L * 1024L)))),
                ruleCase(
                        "PG-TABLE-001",
                        PostgresSectionIds.TABLES,
                        data -> data.tables(List.of(table(50L * 1024L * 1024L, 49L, 5L))),
                        data -> data.tables(List.of(table(50L * 1024L * 1024L, 50L, 5L)))),
                ruleCase(
                        "PG-VACUUM-001",
                        PostgresSectionIds.VACUUM,
                        data -> data.vacuum(List.of(vacuum(1000L, 999L, 0.5, false))),
                        data -> data.vacuum(List.of(vacuum(1000L, 1000L, 0.5, true)))),
                ruleCase(
                        "PG-VACUUM-002",
                        PostgresSectionIds.VACUUM,
                        data -> data.vacuum(List.of(vacuum(4001L, 1000L, 0.19996, true))),
                        data -> data.vacuum(List.of(vacuum(4000L, 1000L, 0.2, true)))),
                ruleCase(
                        "PG-REPLICATION-001",
                        PostgresSectionIds.REPLICATION,
                        data -> data.replication(replication(10L, 4L, 0L)),
                        data -> data.replication(replication(4L, 5L, 0L))),
                ruleCase(
                        "PG-REPLICATION-002",
                        PostgresSectionIds.REPLICATION,
                        data -> data.replication(replication(10L, 0L, 0L)),
                        data -> data.replication(replication(10L, 0L, 1L))),
                ruleCase(
                        "PG-SETTINGS-001",
                        PostgresSectionIds.SETTINGS,
                        data -> data.settingValues().put("autovacuum", "on"),
                        data -> data.settingValues().put("autovacuum", "off")),
                ruleCase(
                        "PG-SETTINGS-002",
                        PostgresSectionIds.SETTINGS,
                        data -> data.settingValues().put("fsync", "on"),
                        data -> data.settingValues().put("fsync", "off")),
                ruleCase(
                        "PG-SETTINGS-003",
                        PostgresSectionIds.SETTINGS,
                        data -> data.settingValues().put("track_io_timing", "on"),
                        data -> data.settingValues().put("track_io_timing", "off")));
    }

    private static PostgresRule rule(String id) {
        return PostgresRuleRegistry.rules().stream()
                .filter(rule -> rule.definition().id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static RuleCase ruleCase(
            String id,
            String sectionId,
            Consumer<PostgresDatabaseData> clean,
            Consumer<PostgresDatabaseData> violation) {
        return new RuleCase(id, sectionId, clean, violation);
    }

    private static PostgresDatabaseData data(String sectionId) {
        PostgresDatabaseData data = new PostgresDatabaseData("primary");
        data.addSection(new PostgresSectionDto(sectionId, sectionId, "AVAILABLE", null, null, 1, 0, false));
        return data;
    }

    private static PostgresVitalSignsDto vitals(
            Double cacheHitRatio,
            Double rollbackRatio,
            int connections,
            int idleInTransaction,
            long xidAge,
            long blockedSessions,
            long deadlocks,
            long tempFiles) {
        return new PostgresVitalSignsDto(
                "app",
                cacheHitRatio,
                rollbackRatio,
                10_000L,
                500L,
                connections,
                100,
                connections / 100d,
                1,
                idleInTransaction,
                30d,
                (int) blockedSessions,
                xidAge,
                2000L,
                xidAge / 2000d,
                1024L,
                deadlocks,
                tempFiles,
                2048L);
    }

    private static PostgresStatementDto statement(long calls, double totalTime, double meanTime) {
        return new PostgresStatementDto("1", "select * from orders", calls, totalTime, meanTime, meanTime, 1L, null);
    }

    private static PostgresStatementDto untimedStatement() {
        return new PostgresStatementDto("1", "select * from orders", 1L, null, null, null, 1L, null);
    }

    private static PostgresIndexDto index(long scans, long sizeBytes) {
        return new PostgresIndexDto("public", "orders", "orders_idx", scans, 0L, sizeBytes, false, false, false);
    }

    private static PostgresTableDto table(long sizeBytes, long sequentialScans, long indexScans) {
        double ratio = sequentialScans / (double) (sequentialScans + indexScans);
        return new PostgresTableDto(
                "public", "orders", sizeBytes, sizeBytes, 0L, 1000L, 0L, sequentialScans, 1000L, indexScans, ratio);
    }

    private static PostgresVacuumDto vacuum(long live, long dead, double ratio, boolean due) {
        return new PostgresVacuumDto("public", "orders", live, dead, ratio, 1000L, due, true, null, null, null, null);
    }

    private static PostgresReplicationDto replication(long timed, long requested, long inactiveSlots) {
        return new PostgresReplicationDto(false, List.of(), timed, requested, 0d, 1L, inactiveSlots, "replica");
    }

    private record RuleCase(
            String id,
            String sectionId,
            Consumer<PostgresDatabaseData> clean,
            Consumer<PostgresDatabaseData> violation) {}
}
