package io.github.jdubois.bootui.engine.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import io.github.jdubois.bootui.core.dto.AdvisorRuleViolationsDto;
import io.github.jdubois.bootui.core.dto.AdvisorSuppressedFindingDto;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import io.github.jdubois.bootui.core.dto.DismissedFindingDto;
import io.github.jdubois.bootui.engine.advisor.AdvisorSuppression;
import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class DatabaseFindingSuppressionTests {

    private static final String RULE = "DB-SCHEMA-001";
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void dismissingOneTableLowersTheActiveCountAndKeepsTheScanIntact() {
        scanned(16, (fixture) -> {
            DatabaseAdvisorReport raw = fixture.report();
            String scanId = raw.violationDetails().scanId();
            List<String> identities = fixture.identities();
            String dismissedText = fixture.scanner()
                    .ruleViolations(RULE, scanId, 0, 100)
                    .violations()
                    .get(1);

            DatabaseAdvisorReport report =
                    fixture.scanner().dismissedReport(Set.of(), List.of(finding(identities.get(1))));

            assertThat(report.results())
                    .filteredOn(result -> result.id().equals(RULE))
                    .singleElement()
                    .satisfies(result -> {
                        assertThat(result.violationCount()).isEqualTo(15);
                        assertThat(result.suppressedCount()).isEqualTo(1);
                        assertThat(result.sampleViolations()).hasSize(10).doesNotContain(dismissedText);
                    });
            assertThat(report.suppression().suppressedFindings()).isEqualTo(1);
            assertThat(report.suppression().findings())
                    .containsExactly(
                            new AdvisorSuppressedFindingDto(RULE, identities.get(1), AdvisorSuppression.MATCHED));
            assertThat(report.violationDetails()).isEqualTo(raw.violationDetails());
            AdvisorRuleViolationsDto page =
                    fixture.scanner().ruleViolations(RULE, scanId, 0, 100, List.of(finding(identities.get(1))));
            assertThat(page.violationCount()).isEqualTo(16);
            assertThat(page.suppressedCount()).isEqualTo(1);
            assertThat(page.suppressed().get(1)).isTrue();
            assertThat(fixture.scanner().dismissedReport(Set.of(), List.of())).isEqualTo(raw);
        });
    }

    @Test
    void everyTableDismissedTakesTheRuleOutOfTheTotalsButNotTheList() {
        scanned(16, (fixture) -> {
            DatabaseAdvisorReport report = fixture.scanner()
                    .dismissedReport(
                            Set.of(),
                            fixture.identities().stream()
                                    .map(DatabaseFindingSuppressionTests::finding)
                                    .toList());

            assertThat(report.violationsFound())
                    .isEqualTo(report.results().stream()
                            .filter(result -> result.violationCount() > 0)
                            .count());
            assertThat(report.results())
                    .filteredOn(result -> result.id().equals(RULE))
                    .singleElement()
                    .satisfies(result -> {
                        assertThat(result.violationCount()).isZero();
                        assertThat(result.suppressedCount()).isEqualTo(16);
                        assertThat(result.sampleViolations()).isEmpty();
                    });
            assertThat(report.severityCounts())
                    .allSatisfy(count -> assertThat(count.count()).isZero());
        });
    }

    @Test
    void aRuleDismissedWholeKeepsPrecedenceOverItsDismissedFindings() {
        scanned(16, (fixture) -> {
            DatabaseAdvisorReport report = fixture.scanner()
                    .dismissedReport(
                            Set.of(RULE), List.of(finding(fixture.identities().get(0))));

            assertThat(report.results())
                    .filteredOn(result -> result.id().equals(RULE))
                    .singleElement()
                    .satisfies(result -> {
                        assertThat(result.dismissed()).isTrue();
                        assertThat(result.suppressedCount()).isZero();
                        assertThat(result.violationCount()).isEqualTo(16);
                    });
            assertThat(report.suppression().suppressedFindings()).isZero();
        });
    }

    @Test
    void aTruncatedScanMatchesOnlyRetainedTablesAndFlagsTheRule() {
        scanned(
                16,
                (fixture) -> {
                    List<String> retained = fixture.identities();
                    assertThat(retained).hasSize(7);

                    DatabaseAdvisorReport report = fixture.scanner()
                            .dismissedReport(
                                    Set.of(),
                                    List.of(
                                            finding(retained.get(0)),
                                            finding("v1:ds=primary|table=PUBLIC.UNRETAINED")));

                    assertThat(report.suppression().incompleteRules()).containsExactly(RULE);
                    assertThat(report.suppression().findings())
                            .extracting(AdvisorSuppressedFindingDto::state)
                            .containsExactly(AdvisorSuppression.MATCHED, AdvisorSuppression.UNVERIFIED);
                    assertThat(report.results())
                            .filteredOn(result -> result.id().equals(RULE))
                            .singleElement()
                            .satisfies(result ->
                                    assertThat(result.violationCount()).isEqualTo(15));
                },
                7);
    }

    @Test
    void aTableThatNoLongerFiresIsOrphanedAndAnotherAdvisorsFindingIsNotListed() {
        scanned(16, (fixture) -> {
            DatabaseAdvisorReport report = fixture.scanner()
                    .dismissedReport(
                            Set.of(),
                            List.of(
                                    finding("v1:ds=primary|table=PUBLIC.GONE"),
                                    new DismissedFindingDto("HIB-ID-001", "v1:unit=main|entity=x|member=y", null)));

            assertThat(report.suppression().findings())
                    .containsExactly(new AdvisorSuppressedFindingDto(
                            RULE, "v1:ds=primary|table=PUBLIC.GONE", AdvisorSuppression.ORPHANED));
        });
    }

    private static DismissedFindingDto finding(String identity) {
        return new DismissedFindingDto(RULE, identity, null);
    }

    private static void scanned(int tableCount, Consumer<Fixture> body) {
        scanned(tableCount, body, 10_000);
    }

    private static void scanned(int tableCount, Consumer<Fixture> body, int retention) {
        DataSource dataSource = mock(DataSource.class);
        var tables = java.util.stream.IntStream.range(0, tableCount)
                .mapToObj(index ->
                        DatabaseAdvisorFixtures.table("events_" + index, List.of(), List.of(), List.of(), List.of()))
                .toList();
        var schema = DatabaseAdvisorFixtures.schema("primary", Dialect.GENERIC, tables);
        DatabaseAdvisorScanner scanner = DatabaseAdvisorScanner.using(
                () -> List.of(new NamedDataSource("primary", dataSource)),
                () -> EntityDiscovery.empty(null),
                FIXED_CLOCK);
        scanner.setViolationRetentionLimit(() -> retention);
        try (var introspector = mockStatic(SchemaIntrospector.class)) {
            introspector
                    .when(() -> SchemaIntrospector.introspect(eq("primary"), any(DataSource.class), any(), any()))
                    .thenReturn(schema);
            DatabaseAdvisorReport report = scanner.scan();
            body.accept(new Fixture(scanner, report));
        }
    }

    private record Fixture(DatabaseAdvisorScanner scanner, DatabaseAdvisorReport report) {

        List<String> identities() {
            return scanner.ruleViolations(RULE, report.violationDetails().scanId(), 0, 100)
                    .identities();
        }
    }
}
