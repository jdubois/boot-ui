package io.github.jdubois.bootui.engine.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorDataSourceDto;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.spi.DatabaseAdvisorDataSourceDiscovery;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end scanner behavior against a live in-memory H2 database, plus the failure paths that must never be
 * presented as a clean scan.
 */
class DatabaseAdvisorScannerTests {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    private H2DataSource dataSource;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource =
                new H2DataSource("jdbc:h2:mem:database-advisor-scanner-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("create table customers (id bigint primary key, name varchar(255) not null)");
            statement.execute("create table orders (id bigint primary key, customer_id bigint not null, "
                    + "constraint fk_orders_customer foreign key (customer_id) references customers(id))");
            statement.execute("create table audit_log (message varchar(255))");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("drop all objects");
        }
    }

    private DatabaseAdvisorScanner scannerFor(List<NamedDataSource> dataSources) {
        return DatabaseAdvisorScanner.using(() -> dataSources, () -> EntityDiscovery.empty(null), FIXED_CLOCK);
    }

    @Test
    void postgresScanKeepsMysqlAndOracleSkipsNeutralWithoutCompletionCredit() {
        var findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.POSTGRES_INVALID_INDEXES, List.of(), false))
                .add(VendorAugmentation.available(VendorFindingKinds.POSTGRES_SEQUENCES, List.of(), false))
                .add(VendorAugmentation.available(
                        VendorFindingKinds.POSTGRES_UNVALIDATED_CONSTRAINTS, List.of(), false))
                .add(VendorAugmentation.available(
                        VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES, List.of(), false))
                .build();
        var postgres = DatabaseAdvisorFixtures.schema("primary", Dialect.POSTGRESQL, List.of(), findings);
        try (var introspector = mockStatic(SchemaIntrospector.class)) {
            introspector
                    .when(() -> SchemaIntrospector.introspect(eq("primary"), any(DataSource.class), any(), any()))
                    .thenReturn(postgres);
            var report = scannerFor(List.of(new NamedDataSource("primary", dataSource)))
                    .scan();
            assertThat(report.scan().status()).isEqualTo("SCANNED");
            assertThat(report.evidence().coverageComplete()).isTrue();
            assertThat(report.evidence().limitations()).isEmpty();
            assertThat(report.rulesSkipped()).isPositive();
            assertThat(report.evidence().usable()).isTrue();
            assertThat(report.diagnostics())
                    .filteredOn(diagnostic -> diagnostic.source().startsWith("DB-MYSQL-")
                            || diagnostic.source().startsWith("DB-ORACLE-"))
                    .hasSize(6)
                    .allSatisfy(diagnostic -> {
                        assertThat(diagnostic.level()).isEqualTo("INFO");
                        assertThat(diagnostic.message()).startsWith("Not applicable:");
                    });
        }
    }

    @Test
    void initialReportIsNotScannedAndHasNoResults() {
        DatabaseAdvisorReport report = scannerFor(List.of()).initialReport();
        assertThat(report.scan().status()).isEqualTo("NOT_SCANNED");
        assertThat(report.results()).isEmpty();
        assertThat(report.diagnostics()).isEmpty();
        assertThat(report.dataSources()).isEmpty();
        assertThat(report.truncated()).isFalse();
        assertThat(report.disclaimer()).contains("PostgreSQL, MySQL/MariaDB, and Oracle catalog augmentation");
    }

    @Test
    void scanReportsDisabledWhenNoDataSourceIsAvailable() {
        DatabaseAdvisorReport report = scannerFor(List.of()).scan();
        assertThat(report.scan().status()).isEqualTo("DISABLED");
        assertThat(report.evidence().usable()).isFalse();
        assertThat(report.dataSourceNames()).isEmpty();
        assertThat(report.results()).isEmpty();
    }

    @Test
    void readableEmptySchemaDoesNotTurnSkippedRulesIntoCompletedChecks() throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("drop all objects");
        }
        DatabaseAdvisorReport report =
                scannerFor(List.of(new NamedDataSource("empty", dataSource))).scan();
        assertThat(report.tablesAnalyzed()).isZero();
        assertThat(report.rulesSkipped()).isPositive();
        assertThat(report.evidence().usable()).isFalse();
    }

    @Test
    void scanReportsErrorWithRedactedDiagnosticsWhenEveryDataSourceFailsToIntrospect() {
        DataSource broken = new FailingDataSource(
                "connection refused for jdbc:postgresql://app:sup3rs3cret@db.internal:5432/orders");
        DatabaseAdvisorReport report =
                scannerFor(List.of(new NamedDataSource("broken", broken))).scan();

        assertThat(report.scan().status()).isEqualTo("ERROR");
        assertThat(report.evidence().usable()).isFalse();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.dataSourceNames()).containsExactly("broken");
        assertThat(report.results()).isEmpty();
        assertThat(report.dataSources())
                .singleElement()
                .satisfies(status -> assertThat(status.status()).isEqualTo("FAILED"));
        assertThat(report.diagnostics()).isNotEmpty();
        assertThat(report.diagnostics().get(0).message())
                .doesNotContain("sup3rs3cret")
                .contains("******@db.internal");
    }

    @Test
    void scanIntrospectsThePhysicalSchemaAndFlagsAMissingPrimaryKey() {
        DatabaseAdvisorReport report =
                scannerFor(List.of(new NamedDataSource("primary", dataSource))).scan();

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.source()).isEqualTo("DB-SCHEMA-002");
            assertThat(diagnostic.level()).isEqualTo("WARNING");
            assertThat(diagnostic.message()).contains("access path", "cannot be established");
        });
        assertThat(report.dataSourceNames()).containsExactly("primary");
        assertThat(report.tablesAnalyzed()).isEqualTo(3);
        assertThat(report.results()).isNotEmpty();
        assertThat(report.evidence().usable()).isTrue();

        // H2 automatically creates a supporting index for the "orders.customer_id" foreign key column, so
        // DB-SCHEMA-002 (missing FK index) is exercised directly against synthetic models in
        // DatabaseAdvisorSchemaRulesTests instead; this end-to-end scan only asserts the primary-key check,
        // which genuinely requires the JDBC DatabaseMetaData round trip this test exists to cover.
        assertThat(report.results()).anySatisfy(result -> {
            assertThat(result.id()).isEqualTo("DB-SCHEMA-001");
            assertThat(result.status()).isEqualTo("VIOLATION");
            assertThat(result.sampleViolations().get(0)).containsIgnoringCase("audit_log");
        });
    }

    @Test
    void scanReportsTheDatasourceProductAndDialectItRead() {
        DatabaseAdvisorReport report =
                scannerFor(List.of(new NamedDataSource("primary", dataSource))).scan();

        assertThat(report.dataSources()).singleElement().satisfies(status -> {
            assertThat(status.name()).isEqualTo("primary");
            assertThat(status.status()).isEqualTo("AVAILABLE");
            assertThat(status.product()).containsIgnoringCase("H2");
            assertThat(status.dialect()).isEqualTo("Generic JDBC");
            assertThat(status.tablesAnalyzed()).isEqualTo(3);
        });
    }

    @Test
    void scanReportsPartialWhenOnlySomeDatasourcesCouldBeRead() {
        DatabaseAdvisorReport report = scannerFor(List.of(
                        new NamedDataSource("primary", dataSource),
                        new NamedDataSource("secondary", new FailingDataSource("connection refused"))))
                .scan();

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.scan().message()).contains("1 datasource(s) could not be read");
        assertThat(report.evidence().usable()).isTrue();
        assertThat(report.evidence().coverageComplete()).isFalse();
        assertThat(report.results()).isNotEmpty();
        assertThat(report.dataSources())
                .extracting(DatabaseAdvisorDataSourceDto::status)
                .containsExactlyInAnyOrder("AVAILABLE", "FAILED");
        assertThat(report.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.source()).isEqualTo("secondary"));
    }

    @Test
    void scanReportsTruncationAsPartialInsteadOfSilentlyDroppingTables() {
        DatabaseAdvisorLimits tightLimits =
                new DatabaseAdvisorLimits(1, 300, 100, 500, Duration.ofSeconds(20), Duration.ofSeconds(5));
        DatabaseAdvisorScanner scanner = DatabaseAdvisorScanner.using(
                () -> List.of(new NamedDataSource("primary", dataSource)),
                () -> EntityDiscovery.empty(null),
                FIXED_CLOCK,
                tightLimits);

        DatabaseAdvisorReport report = scanner.scan();

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.truncated()).isTrue();
        // H2 returns a filtered system relation first; rejected rows still consume the raw-row bound.
        assertThat(report.tablesAnalyzed()).isZero();
        assertThat(report.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message())
                        .contains("table metadata row bound", "0 scoped relations", "coverage is incomplete"));
        assertThat(report.dataSources()).singleElement().satisfies(status -> {
            assertThat(status.truncated()).isTrue();
            assertThat(status.status()).isEqualTo("PARTIAL");
        });
    }

    @Test
    void scanNeverIncludesPassingOrSkippedRulesInTheResultsList() {
        DatabaseAdvisorReport report =
                scannerFor(List.of(new NamedDataSource("primary", dataSource))).scan();
        assertThat(report.results())
                .allSatisfy(result -> assertThat(result.status()).isEqualTo("VIOLATION"));
        assertThat(report.rulesEvaluated())
                .isEqualTo(DatabaseAdvisorRuleRegistry.activeRules().size());
    }

    @Test
    void scanReportsSkippedRulesAsDiagnosticsWithoutCountingThemAsViolations() {
        DatabaseAdvisorReport report =
                scannerFor(List.of(new NamedDataSource("primary", dataSource))).scan();

        // The PostgreSQL/MySQL vendor rules cannot run against H2, and the Hibernate cross-reference rules
        // have no metamodel here: all of them must be visible as skipped, none as findings.
        assertThat(report.rulesSkipped()).isGreaterThan(0);
        assertThat(report.rulesErrored()).isZero();
        assertThat(report.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.source()).isEqualTo("DB-PG-001"));
        assertThat(report.diagnostics())
                .filteredOn(diagnostic -> diagnostic.source().startsWith("DB-"))
                .allSatisfy(diagnostic -> assertThat(diagnostic.level()).isIn("INFO", "WARNING"));
        assertThat(report.violationsFound()).isEqualTo(report.results().size());
    }

    @Test
    void applyDismissalsMarksDismissedResultsAndPreservesDiagnostics() {
        DatabaseAdvisorScanner scanner = scannerFor(List.of(new NamedDataSource("primary", dataSource)));
        DatabaseAdvisorReport report = scanner.scan();
        String dismissedId = report.results().get(0).id();

        DatabaseAdvisorReport updated = scanner.applyDismissals(report, Set.of(dismissedId));

        assertThat(updated.results())
                .filteredOn(result -> result.id().equals(dismissedId))
                .allSatisfy(result -> assertThat(result.dismissed()).isTrue());
        assertThat(updated.violationsFound()).isEqualTo(report.violationsFound() - 1);
        assertThat(updated.diagnostics()).isEqualTo(report.diagnostics());
        assertThat(updated.evidence()).isEqualTo(report.evidence());
        assertThat(updated.evidence().usable()).isTrue();
        assertThat(updated.dataSources()).isEqualTo(report.dataSources());
        assertThat(updated.rulesSkipped()).isEqualTo(report.rulesSkipped());
    }

    @Test
    void scanSurvivesADataSourceSupplierThatThrows() {
        DatabaseAdvisorScanner scanner = DatabaseAdvisorScanner.using(
                () -> {
                    throw new IllegalStateException("bean factory unavailable");
                },
                () -> EntityDiscovery.empty(null),
                FIXED_CLOCK);
        DatabaseAdvisorReport report = scanner.scan();
        assertThat(report.scan().status()).isEqualTo("ERROR");
        assertThat(report.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.message()).contains("Datasource discovery failed"));
    }

    @Test
    void nullDiscoveryIsNotASuccessfullyEmptyInventory() {
        DatabaseAdvisorReport report = DatabaseAdvisorScanner.using(
                        () -> null, () -> EntityDiscovery.empty(null), FIXED_CLOCK)
                .scan();
        assertThat(report.scan().status()).isEqualTo("ERROR");
    }

    @Test
    void partialBeanDiscoveryRetainsReadableSchemasAndFailedNames() {
        DatabaseAdvisorReport report = DatabaseAdvisorScanner.usingDiscovery(
                        () -> new DatabaseAdvisorDataSourceDiscovery(
                                List.of(new NamedDataSource("primary", dataSource)),
                                List.of(new DatabaseAdvisorDataSourceDiscovery.Failure(
                                        "secondary", "Bean initialization failed"))),
                        () -> EntityDiscovery.empty(null),
                        List::of,
                        FIXED_CLOCK)
                .scan();

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.dataSourceNames()).containsExactly("primary", "secondary");
        assertThat(report.dataSources())
                .extracting(DatabaseAdvisorDataSourceDto::status)
                .containsExactly("AVAILABLE", "FAILED");
        assertThat(report.results()).anyMatch(result -> result.id().equals("DB-SCHEMA-001"));
    }

    @Test
    void failingOptionalEvidenceDoesNotEraseSchemaFindingsOrClaimACompleteScan() {
        DatabaseAdvisorReport report = DatabaseAdvisorScanner.using(
                        () -> List.of(new NamedDataSource("primary", dataSource)),
                        () -> {
                            throw new IllegalStateException("metamodel unavailable");
                        },
                        () -> {
                            throw new IllegalStateException("trace unavailable");
                        },
                        FIXED_CLOCK)
                .scan();

        assertThat(report.scan().status()).isEqualTo("PARTIAL");
        assertThat(report.results()).anyMatch(result -> result.id().equals("DB-SCHEMA-001"));
        assertThat(report.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.source()).isEqualTo("Hibernate metadata");
            assertThat(diagnostic.level()).isEqualTo("WARNING");
        });
        assertThat(report.diagnostics()).anySatisfy(diagnostic -> {
            assertThat(diagnostic.source()).isEqualTo("SQL Trace");
            assertThat(diagnostic.level()).isEqualTo("WARNING");
        });
    }

    @Test
    void retiredIdsAreNotRegisteredOrReusedAndOldDismissalsAreHarmless() {
        Set<String> retired = Set.of("DB-SCHEMA-008", "DB-SCHEMA-009", "DB-HIB-001", "DB-HIB-008");
        assertThat(DatabaseAdvisorRuleRegistry.activeRules()).hasSize(24);
        assertThat(DatabaseAdvisorRuleRegistry.activeRules())
                .noneMatch(rule -> retired.contains(rule.definition().id()));
        DatabaseAdvisorScanner scanner = scannerFor(List.of(new NamedDataSource("primary", dataSource)));
        DatabaseAdvisorReport report = scanner.scan();
        DatabaseAdvisorReport dismissed = scanner.applyDismissals(report, retired);
        assertThat(dismissed.results()).isEqualTo(report.results());
        assertThat(dismissed.severityCounts()).isEqualTo(report.severityCounts());
        assertThat(dismissed.scan().status()).isEqualTo(report.scan().status());
    }

    /** Minimal H2-backed {@link DataSource} that opens a fresh connection per call, like a real pool. */
    private static final class H2DataSource extends TestDataSource {

        private final String url;

        private H2DataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }
    }

    /** A datasource that always refuses to hand out a connection. */
    private static final class FailingDataSource extends TestDataSource {

        private final String message;

        private FailingDataSource(String message) {
            this.message = message;
        }

        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException(message);
        }
    }

    private abstract static class TestDataSource implements DataSource {

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            return iface.cast(this);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}
