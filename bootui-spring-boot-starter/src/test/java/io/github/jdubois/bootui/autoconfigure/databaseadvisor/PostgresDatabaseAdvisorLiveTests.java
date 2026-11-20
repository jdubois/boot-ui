package io.github.jdubois.bootui.autoconfigure.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorScanner;
import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.engine.hibernate.HibernateAttributeModel;
import io.github.jdubois.bootui.engine.hibernate.HibernateEntityModel;
import io.github.jdubois.bootui.spi.NamedDataSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Live PostgreSQL coverage for the Database Advisor, against a real server rather than a hand-built model.
 *
 * <p>Three things can only be proven against a real PostgreSQL catalog, and all three were previously wrong:
 * a declaratively partitioned table must be reported once on its parent instead of once per child partition,
 * a sequence's headroom must be measured against its owning column's capacity rather than the sequence's own
 * maximum, and a {@code NOT VALID} constraint must be visible at all — {@code getImportedKeys()} reports it as
 * if it were fully enforced.</p>
 *
 * <p>It reuses the Testcontainers PostgreSQL setup already used by {@code SpringFlywayProviderTests} and skips
 * cleanly when Docker is unavailable.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresDatabaseAdvisorLiveTests {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18-alpine");

    private static DataSource dataSource;

    @BeforeAll
    static void createSchema() throws SQLException {
        DriverManagerDataSource source =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        source.setDriverClassName("org.postgresql.Driver");
        dataSource = source;
        try (Connection connection = source.getConnection();
                Statement statement = connection.createStatement()) {
            // A partitioned parent with two child partitions, none of which declares a primary key.
            statement.execute(
                    "create table events (id bigint, occurred_on date not null) partition by range (occurred_on)");
            statement.execute("create table events_2024_01 partition of events "
                    + "for values from ('2024-01-01') to ('2024-02-01')");
            statement.execute("create table events_2024_02 partition of events "
                    + "for values from ('2024-02-01') to ('2024-03-01')");

            // An int4 column fed by an explicitly bigint sequence: the sequence's own range is barely touched
            // (0%), but the column it feeds dies at 2,147,483,647 — the case the previous implementation missed.
            statement.execute("create table tickets (id integer primary key, label varchar(64) not null)");
            statement.execute("create sequence tickets_id_seq as bigint owned by tickets.id");
            statement.execute("alter table tickets alter column id set default nextval('tickets_id_seq')");
            statement.execute("select setval('tickets_id_seq', 2100000000)");

            // A foreign key added NOT VALID and never validated: enforced for new rows only.
            statement.execute("create table customers (id bigint primary key)");
            statement.execute("create table orders (id bigint primary key, customer_id bigint not null)");
            statement.execute("alter table orders add constraint fk_orders_customer "
                    + "foreign key (customer_id) references customers(id) not valid");

            statement.execute("create table insert_only_log (id bigint)");
            statement.execute("create publication inserts_only for table insert_only_log with (publish = 'insert')");
            statement.execute("create table published_log (id bigint)");
            statement.execute("create publication changes for table published_log with (publish = 'update, delete')");
            statement.execute("create schema published_schema");
            statement.execute("create table published_schema.schema_log (id bigint)");
            statement.execute("create publication schema_changes for tables in schema published_schema "
                    + "with (publish = 'update')");
            statement.execute("create publication root_changes for table events "
                    + "with (publish = 'delete', publish_via_partition_root = true)");
            statement.execute("create publication all_inserts for all tables with (publish = 'insert')");

            // Real pg_index rows for DB-HIB-005: indcollation is 0 or 100 rather than NULL for ordinary keys.
            statement.execute("create table games (id uuid primary key, code varchar(50) not null unique, "
                    + "slug varchar(100), nick varchar(50) collate \"C\" unique, handle varchar(50))");
            statement.execute("create unique index games_handle_key on games (handle text_pattern_ops)");

            // DB-PG-005, DB-HIB-009 and DB-HIB-010 against pgjdbc's real IS_AUTOINCREMENT/COLUMN_DEF metadata.
            statement.execute("create unlogged table scratch_cache (id bigint primary key)");
            statement.execute("create table manual_keys (id bigint primary key, amount numeric(10,2))");
            statement.execute("create table identity_keys (id bigint generated by default as identity primary key)");
            statement.execute("create table serial_keys (id bigserial primary key)");
            statement.execute("create table serial_refs (id bigint primary key, "
                    + "valid_serial_id bigint not null references serial_keys(id), "
                    + "narrow_serial_id integer not null references serial_keys(id))");
            statement.execute("create index ix_serial_refs_valid on serial_refs (valid_serial_id)");
            statement.execute("create index ix_serial_refs_narrow on serial_refs (narrow_serial_id)");
            statement.execute("alter table orders add column notes text");
            statement.execute("alter table orders add constraint nn_orders_notes not null notes not valid");

            statement.execute(
                    "create sequence descending_seq increment by -1 minvalue -1000 maxvalue -1 start with -1");
            statement.execute("select setval('descending_seq', -900)");
        }
    }

    private static DatabaseAdvisorReport scan() {
        return DatabaseAdvisorScanner.using(
                        () -> List.of(new NamedDataSource("primary", dataSource)),
                        () -> EntityDiscovery.absent(null),
                        Clock.systemUTC())
                .scan();
    }

    @Entity
    @Table(name = "games")
    static class Game {
        @Id
        @Column(name = "id")
        UUID id;

        @Column(name = "code", nullable = false, unique = true, length = 50)
        String code;

        @Column(name = "slug", unique = true, length = 100)
        String slug;

        @Column(name = "nick", unique = true, length = 50)
        String nick;

        @Column(name = "handle", unique = true, length = 50)
        String handle;
    }

    @Entity
    @Table(name = "manual_keys")
    static class ManualKey {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        Long id;

        @Column(name = "amount", precision = 12, scale = 4)
        BigDecimal amount;
    }

    @Entity
    @Table(name = "identity_keys")
    static class IdentityKey {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        Long id;
    }

    @Entity
    @Table(name = "serial_keys")
    static class SerialKey {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        Long id;
    }

    @Entity
    @Table(name = "parent", schema = "fk_evidence")
    static class ForeignKeyParent {
        @Id
        @Column(name = "id")
        Integer id;
    }

    @Entity
    @Table(name = "child", schema = "fk_evidence")
    static class ForeignKeyChild {
        @Id
        @Column(name = "id")
        Integer id;

        @ManyToOne
        @JoinColumn(name = "parent_id", referencedColumnName = "id")
        ForeignKeyParent parent;
    }

    @Entity
    @Table(name = "self_ref", schema = "fk_evidence")
    static class SelfReferencingForeignKey {
        @Id
        @Column(name = "id")
        Integer id;

        @ManyToOne
        @JoinColumn(name = "parent_id", referencedColumnName = "id")
        SelfReferencingForeignKey parent;
    }

    private static DatabaseAdvisorReport scanWithGameEntity() {
        return scanWithEntities(Game.class);
    }

    private static DatabaseAdvisorReport scanWithEntities(Class<?>... entityTypes) {
        return scanWithEntities(dataSource, entityTypes);
    }

    private static DatabaseAdvisorReport scanWithEntities(DataSource source, Class<?>... entityTypes) {
        List<HibernateEntityModel> models = new java.util.ArrayList<>();
        for (Class<?> entityType : entityTypes) {
            String name = entityType.getSimpleName();
            List<HibernateAttributeModel> attributes = new java.util.ArrayList<>();
            for (Field field : entityType.getDeclaredFields()) {
                attributes.add(new HibernateAttributeModel(
                        name,
                        field.getName(),
                        field.getType(),
                        field.getGenericType(),
                        "BASIC",
                        false,
                        true,
                        Arrays.asList(field.getAnnotations())));
            }
            models.add(new HibernateEntityModel(name, entityType, attributes));
        }
        EntityDiscovery entities = new EntityDiscovery(models, List.of(), List.of());
        return DatabaseAdvisorScanner.using(
                        () -> List.of(new NamedDataSource("primary", source)), () -> entities, Clock.systemUTC())
                .scan();
    }

    @FunctionalInterface
    private interface ForeignKeyFixture {
        void run(Statement statement) throws SQLException;
    }

    private static void withForeignKeyFixture(ForeignKeyFixture fixture) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("create schema fk_evidence");
            try {
                statement.execute("create table fk_evidence.parent (id integer primary key)");
                statement.execute("create table fk_evidence.child (id integer primary key, parent_id integer, "
                        + "constraint fk_child foreign key (parent_id) references fk_evidence.parent(id))");
                statement.execute("create table fk_evidence.self_ref (id integer primary key, parent_id integer, "
                        + "constraint fk_self foreign key (parent_id) references fk_evidence.self_ref(id))");
                fixture.run(statement);
            } finally {
                statement.execute("drop schema fk_evidence cascade");
            }
        }
    }

    private static void assertForeignKeyObservation(DataSource source, boolean enforced) {
        DatabaseAdvisorReport report = scanWithEntities(source, ForeignKeyChild.class, ForeignKeyParent.class);
        assertThat(report.rulesErrored()).as("%s", report.diagnostics()).isZero();
        assertThat(report.dataSources())
                .singleElement()
                .satisfies(status -> assertThat(status.status())
                        .as("%s", report.diagnostics())
                        .isEqualTo("AVAILABLE"));
        assertThat(report.scan().status()).as("%s", report.diagnostics()).isEqualTo(enforced ? "SCANNED" : "PARTIAL");
        assertThat(finding(report, "DB-HIB-007")).isEmpty();
        if (enforced) {
            assertThat(report.diagnostics())
                    .as("%s", report.diagnostics())
                    .noneMatch(diagnostic -> "DB-HIB-007".equals(diagnostic.source()));
        } else {
            assertThat(report.diagnostics()).anySatisfy(diagnostic -> {
                assertThat(diagnostic.source()).isEqualTo("DB-HIB-007");
                assertThat(diagnostic.level()).isEqualTo("WARNING");
                assertThat(diagnostic.message()).contains("ForeignKeyChild#parent", "not enforced");
            });
        }
    }

    private static DataSource foreignKeyRoleDataSource(String role) {
        DriverManagerDataSource source =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Properties properties = new Properties();
        properties.setProperty("options", "-c session_replication_role=" + role);
        source.setConnectionProperties(properties);
        return source;
    }

    private static void setForeignKeyTriggerMode(Statement statement, String mode) throws SQLException {
        for (String relation : List.of("child", "parent")) {
            List<String> names = new java.util.ArrayList<>();
            try (var triggers = statement.executeQuery("select tgname from pg_trigger where tgrelid = 'fk_evidence."
                    + relation + "'::regclass and tgisinternal")) {
                while (triggers.next()) {
                    names.add(triggers.getString(1));
                }
            }
            for (String name : names) {
                statement.execute(
                        "alter table fk_evidence." + relation + " enable " + mode + " trigger \"" + name + "\"");
            }
        }
    }

    @Test
    void observesAnEnabledForeignKeyThroughTheLiveJdbcReader() throws SQLException {
        withForeignKeyFixture(statement -> assertForeignKeyObservation(dataSource, true));
    }

    @Test
    void foreignKeyDeclarationsDoNotProveDisabledChildTriggersAreEnforcing() throws SQLException {
        withForeignKeyFixture(statement -> {
            statement.execute("alter table fk_evidence.child disable trigger all");
            assertForeignKeyObservation(dataSource, false);
        });
    }

    @Test
    void foreignKeyDeclarationsDoNotProveDisabledParentTriggersAreEnforcing() throws SQLException {
        withForeignKeyFixture(statement -> {
            statement.execute("alter table fk_evidence.parent disable trigger all");
            assertForeignKeyObservation(dataSource, false);
        });
    }

    @Test
    void retainsDeleteSetNullFailureWhenOnlyChildChecksAreDisabled() throws SQLException {
        assertSetNullActionRetention(true, true);
    }

    @Test
    void retainsUpdateSetNullFailureWhenOnlyChildChecksAreDisabled() throws SQLException {
        assertSetNullActionRetention(false, true);
    }

    @Test
    void doesNotReportDeleteSetNullFailureWhenItsParentActionIsDisabled() throws SQLException {
        assertSetNullActionRetention(true, false);
    }

    @Test
    void doesNotReportUpdateSetNullFailureWhenItsParentActionIsDisabled() throws SQLException {
        assertSetNullActionRetention(false, false);
    }

    private static void assertSetNullActionRetention(boolean delete, boolean disableChildChecks) throws SQLException {
        withForeignKeyFixture(statement -> {
            String action = delete ? "DELETE" : "UPDATE";
            statement.execute("alter table fk_evidence.child alter column parent_id set not null");
            statement.execute("alter table fk_evidence.child drop constraint fk_child");
            statement.execute("alter table fk_evidence.child add constraint fk_child foreign key (parent_id) "
                    + "references fk_evidence.parent(id) on " + action + " set null");
            statement.executeUpdate("insert into fk_evidence.parent values (1)");
            statement.executeUpdate("insert into fk_evidence.child values (1, 1)");
            if (disableChildChecks) {
                statement.execute("alter table fk_evidence.child disable trigger all");
            } else {
                String trigger;
                try (var triggers = statement.executeQuery(
                        "select tgname from pg_trigger where tgrelid = 'fk_evidence.parent'::regclass "
                                + "and tgconstraint = (select oid from pg_constraint "
                                + "where conrelid = 'fk_evidence.child'::regclass and conname = 'fk_child') "
                                + "and tgisinternal and tgtype = " + (delete ? 9 : 17))) {
                    assertThat(triggers.next()).isTrue();
                    trigger = triggers.getString(1);
                    assertThat(triggers.next()).isFalse();
                }
                statement.execute("alter table fk_evidence.parent disable trigger \"" + trigger + "\"");
            }
            String mutation = delete
                    ? "delete from fk_evidence.parent where id = 1"
                    : "update fk_evidence.parent set id = 2 where id = 1";
            if (disableChildChecks) {
                assertThatThrownBy(() -> statement.executeUpdate(mutation))
                        .isInstanceOfSatisfying(SQLException.class, error -> {
                            assertThat(error.getSQLState()).isEqualTo("23502");
                            assertThat(error.getMessage()).contains("parent_id", "not-null constraint");
                        });
            } else {
                assertThat(statement.executeUpdate(mutation)).isEqualTo(1);
            }
            try (var rows = statement.executeQuery(
                    "select (select count(*) from fk_evidence.parent where id = 1) as old_parent, "
                            + "(select count(*) from fk_evidence.parent where id = 2) as new_parent, "
                            + "(select parent_id from fk_evidence.child where id = 1) as child_parent")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("old_parent")).isEqualTo(disableChildChecks ? 1 : 0);
                assertThat(rows.getInt("new_parent")).isEqualTo(!disableChildChecks && !delete ? 1 : 0);
                assertThat(rows.getInt("child_parent")).isEqualTo(1);
                assertThat(rows.wasNull()).isFalse();
            }
            DatabaseAdvisorReport report = scanWithEntities(ForeignKeyChild.class, ForeignKeyParent.class);
            assertThat(report.rulesErrored()).isZero();
            assertThat(report.dataSources())
                    .singleElement()
                    .satisfies(status -> assertThat(status.status()).isEqualTo("AVAILABLE"));
            assertThat(report.scan().status()).as("%s", report.diagnostics()).isEqualTo("PARTIAL");
            assertThat(report.diagnostics()).anySatisfy(diagnostic -> {
                assertThat(diagnostic.source()).isEqualTo("DB-HIB-007");
                assertThat(diagnostic.level()).isEqualTo("WARNING");
                assertThat(diagnostic.message()).contains("ForeignKeyChild#parent", "not enforced");
            });
            Optional<DatabaseAdvisorRuleResultDto> setNull = finding(report, "DB-SCHEMA-011");
            System.out.printf(
                    "SET NULL retention: action=%s childChecksDisabled=%s native=%s readback=verified DB-SCHEMA-011=%s%n",
                    action, disableChildChecks, disableChildChecks ? "23502" : "mutation succeeded", setNull);
            if (disableChildChecks) {
                assertThat(setNull)
                        .as("Active parent %s SET NULL must retain its observed NOT NULL failure", action)
                        .hasValueSatisfying(result -> {
                            assertThat(result.status()).isEqualTo("VIOLATION");
                            assertThat(result.severity()).isEqualTo("HIGH");
                            assertThat(result.sampleViolations())
                                    .anySatisfy(detail -> assertThat(detail)
                                            .contains("fk_evidence.child", "fk_child", "ON " + action + " SET NULL"));
                        });
            } else {
                assertThat(setNull).isEmpty();
            }
        });
    }

    @Test
    void selfReferencingParentUpdateActionIsNotConfusedWithItsDisabledChildUpdateCheck() throws SQLException {
        withForeignKeyFixture(statement -> {
            statement.execute("alter table fk_evidence.self_ref alter column parent_id set not null");
            statement.execute("alter table fk_evidence.self_ref drop constraint fk_self");
            statement.execute("alter table fk_evidence.self_ref add constraint fk_self foreign key (parent_id) "
                    + "references fk_evidence.self_ref(id) on update set null");
            statement.executeUpdate("insert into fk_evidence.self_ref values (1, 1)");
            List<String> checks = new java.util.ArrayList<>();
            try (var triggers = statement.executeQuery(
                    "select tgname from pg_trigger where tgrelid = 'fk_evidence.self_ref'::regclass "
                            + "and tgisinternal and tgfoid in ('pg_catalog.\"RI_FKey_check_ins\"()'::regprocedure, "
                            + "'pg_catalog.\"RI_FKey_check_upd\"()'::regprocedure)")) {
                while (triggers.next()) {
                    checks.add(triggers.getString(1));
                }
            }
            assertThat(checks).hasSize(2);
            for (String check : checks) {
                statement.execute("alter table fk_evidence.self_ref disable trigger \"" + check + "\"");
            }
            assertThatThrownBy(() -> statement.executeUpdate("update fk_evidence.self_ref set id = 2 where id = 1"))
                    .isInstanceOfSatisfying(
                            SQLException.class,
                            error -> assertThat(error.getSQLState()).isEqualTo("23502"));
            try (var rows = statement.executeQuery("select id, parent_id from fk_evidence.self_ref")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("id")).isEqualTo(1);
                assertThat(rows.getInt("parent_id")).isEqualTo(1);
                assertThat(rows.wasNull()).isFalse();
                assertThat(rows.next()).isFalse();
            }
            DatabaseAdvisorReport report = scanWithEntities(SelfReferencingForeignKey.class);
            assertThat(report.rulesErrored()).isZero();
            assertThat(report.dataSources())
                    .singleElement()
                    .satisfies(status -> assertThat(status.status()).isEqualTo("AVAILABLE"));
            assertThat(report.scan().status()).as("%s", report.diagnostics()).isEqualTo("PARTIAL");
            assertThat(report.diagnostics()).anySatisfy(diagnostic -> {
                assertThat(diagnostic.source()).isEqualTo("DB-HIB-007");
                assertThat(diagnostic.level()).isEqualTo("WARNING");
                assertThat(diagnostic.message()).contains("SelfReferencingForeignKey#parent", "not enforced");
            });
            assertThat(finding(report, "DB-SCHEMA-011")).hasValueSatisfying(result -> {
                assertThat(result.severity()).isEqualTo("HIGH");
                assertThat(result.sampleViolations())
                        .singleElement()
                        .asString()
                        .contains("fk_evidence.self_ref", "fk_self", "ON UPDATE SET NULL")
                        .doesNotContain("ON DELETE SET NULL");
            });
        });
    }

    @Test
    void foreignKeyTriggerModesUseTheObservedConnectionRole() throws SQLException {
        withForeignKeyFixture(statement -> {
            DataSource replica = foreignKeyRoleDataSource("replica");
            DataSource local = foreignKeyRoleDataSource("local");
            assertForeignKeyObservation(replica, false);
            assertForeignKeyObservation(local, true);
            setForeignKeyTriggerMode(statement, "replica");
            assertForeignKeyObservation(dataSource, false);
            assertForeignKeyObservation(replica, true);
            setForeignKeyTriggerMode(statement, "always");
            assertForeignKeyObservation(dataSource, true);
            assertForeignKeyObservation(replica, true);
        });
    }

    @Test
    void foreignKeyValidationSelfReferencesAndPartitionUncertaintyRemainDistinct() throws SQLException {
        withForeignKeyFixture(statement -> {
            statement.execute("alter table fk_evidence.child drop constraint fk_child");
            statement.execute("alter table fk_evidence.child add constraint fk_child foreign key (parent_id) "
                    + "references fk_evidence.parent(id) not valid");
            assertForeignKeyObservation(dataSource, true);
            DatabaseAdvisorReport self = scanWithEntities(SelfReferencingForeignKey.class);
            assertThat(self.scan().status()).as("%s", self.diagnostics()).isEqualTo("SCANNED");
            assertThat(finding(self, "DB-HIB-007")).isEmpty();
            assertThat(self.diagnostics()).noneMatch(diagnostic -> "DB-HIB-007".equals(diagnostic.source()));
            statement.execute("alter table fk_evidence.child drop constraint fk_child");
            statement.execute("alter table fk_evidence.child add constraint fk_child foreign key (parent_id) "
                    + "references fk_evidence.parent(id) not enforced");
            assertForeignKeyObservation(dataSource, false);
            statement.execute("drop table fk_evidence.child");
            statement.execute("create table fk_evidence.child (id integer, parent_id integer, constraint fk_child "
                    + "foreign key (parent_id) references fk_evidence.parent(id)) partition by range (id)");
            statement.execute("create table fk_evidence.child_leaf partition of fk_evidence.child "
                    + "for values from (0) to (100)");
            DatabaseAdvisorReport partitioned = scanWithEntities(ForeignKeyChild.class, ForeignKeyParent.class);
            assertThat(partitioned.rulesErrored()).isZero();
            assertThat(partitioned.dataSources())
                    .singleElement()
                    .satisfies(status -> assertThat(status.status()).isEqualTo("AVAILABLE"));
            assertThat(partitioned.scan().status())
                    .as("%s", partitioned.diagnostics())
                    .isEqualTo("PARTIAL");
            assertThat(finding(partitioned, "DB-HIB-007")).isEmpty();
            assertThat(partitioned.diagnostics()).anySatisfy(diagnostic -> {
                assertThat(diagnostic.source()).isEqualTo("DB-HIB-007");
                assertThat(diagnostic.level()).isEqualTo("WARNING");
                assertThat(diagnostic.message()).contains("enforcement is unknown");
            });
        });
    }

    private static Optional<DatabaseAdvisorRuleResultDto> finding(DatabaseAdvisorReport report, String ruleId) {
        return report.results().stream()
                .filter(result -> result.id().equals(ruleId))
                .findFirst();
    }

    @Test
    void scansTheLivePostgresSchemaWithoutTruncationOrErrors() {
        DatabaseAdvisorReport report = scan();

        assertThat(report.scan().status()).as("%s", report.diagnostics()).isEqualTo("SCANNED");
        assertThat(report.truncated()).isFalse();
        assertThat(report.rulesErrored()).isZero();
        assertThat(report.dataSources()).singleElement().satisfies(status -> {
            assertThat(status.status()).isEqualTo("AVAILABLE");
            assertThat(status.dialect()).isEqualTo("PostgreSQL");
            assertThat(status.product()).containsIgnoringCase("PostgreSQL");
        });
    }

    @Test
    void reportsAPartitionedTableOnceOnItsParentInsteadOfOncePerPartition() {
        DatabaseAdvisorReport report = scan();

        assertThat(finding(report, "DB-SCHEMA-001")).hasValueSatisfying(result -> {
            assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("public.events"));
            assertThat(result.sampleViolations()).noneMatch(detail -> detail.contains("events_2024_01"));
            assertThat(result.sampleViolations()).noneMatch(detail -> detail.contains("events_2024_02"));
        });
    }

    @Test
    void measuresSequenceHeadroomAgainstTheOwningColumnCapacity() {
        DatabaseAdvisorReport report = scan();

        // tickets_id_seq is a bigint sequence (0% of its own range) feeding an int4 column at ~97%.
        assertThat(finding(report, "DB-PG-002")).hasValueSatisfying(result -> {
            assertThat(result.sampleViolations())
                    .anyMatch(detail -> detail.contains("tickets_id_seq")
                            && detail.contains("public.tickets.id")
                            && detail.contains("limited by its owning column type"));
        });
    }

    @Test
    void reportsCurrentUnvalidatedConstraintStateWithoutInferringHistory() {
        DatabaseAdvisorReport report = scan();

        assertThat(finding(report, "DB-PG-003")).hasValueSatisfying(result -> {
            assertThat(result.sampleViolations())
                    .anyMatch(detail -> detail.contains("fk_orders_customer") && detail.contains("public.orders"));
            assertThat(result.sampleViolations())
                    .anyMatch(detail ->
                            detail.contains("not-null constraint nn_orders_notes") && detail.contains("public.orders"));
        });
    }

    @Test
    void comparesForeignKeysAgainstLivePostgresBigserialPrimaryKeyWithoutFalseUnknowns() {
        DatabaseAdvisorReport report = scan();

        assertThat(finding(report, "DB-SCHEMA-004")).hasValueSatisfying(result -> {
            assertThat(result.sampleViolations())
                    .anyMatch(detail -> detail.contains("public.serial_refs.narrow_serial_id")
                            && detail.contains("public.serial_keys.id")
                            && detail.contains("narrower integer type"));
            assertThat(result.sampleViolations()).noneMatch(detail -> detail.contains("valid_serial_id"));
        });
        assertThat(report.diagnostics()).noneMatch(diagnostic -> "DB-SCHEMA-004".equals(diagnostic.source()));
    }

    @Test
    void publicationActionsExpandedSchemaMembershipAndPartitionRootAreRespected() {
        assertThat(finding(scan(), "DB-PG-004")).hasValueSatisfying(result -> {
            assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("public.published_log"));
            assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("published_schema.schema_log"));
            assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("public.events "));
            assertThat(result.sampleViolations()).noneMatch(detail -> detail.contains("insert_only_log"));
            assertThat(result.sampleViolations()).noneMatch(detail -> detail.contains("events_2024_"));
        });
    }

    @Test
    void descendingSequenceUsesItsMinimumRatherThanPositiveMaximum() {
        assertThat(finding(scan(), "DB-PG-002"))
                .hasValueSatisfying(result -> assertThat(result.sampleViolations())
                        .anyMatch(detail ->
                                detail.contains("descending_seq") && detail.contains("effective bound -1000")));
    }

    @Test
    void skipsTheMySqlRulesWithAnExplicitReasonInsteadOfReportingThemClean() {
        DatabaseAdvisorReport report = scan();

        assertThat(report.rulesSkipped()).isGreaterThan(0);
        assertThat(report.diagnostics())
                .anySatisfy(diagnostic -> assertThat(diagnostic.source()).isEqualTo("DB-MYSQL-001"));
        assertThat(report.results()).noneMatch(result -> result.id().startsWith("DB-MYSQL"));
    }

    @Test
    void declaredUniquenessConcludesAgainstTheLivePostgresCatalog() {
        DatabaseAdvisorReport report = scanWithGameEntity();

        assertThat(finding(report, "DB-HIB-005")).hasValueSatisfying(result -> {
            assertThat(result.status()).isEqualTo("VIOLATION");
            assertThat(result.sampleViolations()).singleElement().asString().contains("Game#slug", "no enforcing key");
        });
        List<String> uniquenessDiagnostics = report.diagnostics().stream()
                .filter(diagnostic -> "DB-HIB-005".equals(diagnostic.source()))
                .map(diagnostic -> diagnostic.message())
                .toList();
        // Only the non-default operator class stays unproven; the default and "C" collations conclude.
        assertThat(uniquenessDiagnostics).singleElement().asString().contains("Game#handle");
    }

    @Test
    void unloggedTablesAreReportedFromTheLiveCatalog() {
        assertThat(finding(scan(), "DB-PG-005")).hasValueSatisfying(result -> {
            assertThat(result.status()).isEqualTo("VIOLATION");
            assertThat(result.sampleViolations())
                    .singleElement()
                    .asString()
                    .contains("table public.scratch_cache is UNLOGGED");
        });
    }

    @Test
    void identityAndNumericDeclarationsAreComparedWithLiveDriverMetadata() {
        DatabaseAdvisorReport report = scanWithEntities(ManualKey.class, IdentityKey.class, SerialKey.class);

        // pgjdbc reports IS_AUTOINCREMENT = YES for both the identity and the nextval-defaulted bigserial key.
        assertThat(finding(report, "DB-HIB-009")).hasValueSatisfying(result -> {
            assertThat(result.status()).isEqualTo("VIOLATION");
            assertThat(result.sampleViolations()).singleElement().asString().contains("ManualKey#id", "manual_keys.id");
        });
        assertThat(finding(report, "DB-HIB-010")).hasValueSatisfying(result -> {
            assertThat(result.status()).isEqualTo("VIOLATION");
            assertThat(result.sampleViolations())
                    .singleElement()
                    .asString()
                    .contains("ManualKey#amount", "precision=12, scale=4", "numeric(10,2)");
        });
        assertThat(report.diagnostics())
                .noneMatch(diagnostic ->
                        "DB-HIB-009".equals(diagnostic.source()) || "DB-HIB-010".equals(diagnostic.source()));
    }
}
