package io.github.jdubois.bootui.engine.databaseadvisor;

import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.context;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.schema;
import static io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorFixtures.vendorSchema;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

/** PostgreSQL and MySQL/MariaDB catalog-augmentation rules (DB-PG-*, DB-MYSQL-*). */
class DatabaseAdvisorVendorRulesTests {

    private static final String PASS = DatabaseAdvisorRuleSupport.PASS;
    private static final String VIOLATION = DatabaseAdvisorRuleSupport.VIOLATION;
    private static final String SKIPPED = DatabaseAdvisorRuleSupport.SKIPPED;

    // --- DB-PG-001: invalid PostgreSQL indexes ---

    @Test
    void postgresInvalidIndexRuleSkipsWhenNoPostgresDatasourceIsPresent() {
        DatabaseAdvisorRuleResultDto result =
                new PostgresInvalidIndexRule().evaluate(context(schema("ds", Dialect.GENERIC, List.of())));
        assertThat(result.status()).isEqualTo(SKIPPED);
        assertThat(result.sampleViolations().get(0)).contains("No PostgreSQL datasource");
    }

    @Test
    void postgresInvalidIndexRuleSkipsWithTheCatalogReasonWhenPgIndexCannotBeRead() {
        SchemaSnapshot blocked = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.failed(
                        VendorFindingKinds.POSTGRES_INVALID_INDEXES,
                        "PostgreSQL invalid indexes could not be read: permission denied for table pg_index"));
        DatabaseAdvisorRuleResultDto result = new PostgresInvalidIndexRule().evaluate(context(blocked));
        assertThat(result.status()).isEqualTo(SKIPPED);
        assertThat(result.sampleViolations().get(0)).contains("permission denied");
    }

    @Test
    void postgresInvalidIndexRuleReportsSchemaQualifiedFindingsWithTheirFlags() {
        SchemaSnapshot postgres = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(
                        VendorFindingKinds.POSTGRES_INVALID_INDEXES,
                        List.of(new PostgresInvalidIndex("sales", "orders", "ix_broken", false, true, true, false)),
                        false));
        DatabaseAdvisorRuleResultDto result = new PostgresInvalidIndexRule().evaluate(context(postgres));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0))
                .contains("ix_broken")
                .contains("sales.orders")
                .contains("indisvalid=false");
    }

    @Test
    void postgresInvalidIndexRulePassesWhenTheCatalogReportsNoInvalidIndexes() {
        SchemaSnapshot postgres = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(VendorFindingKinds.POSTGRES_INVALID_INDEXES, List.of(), false));
        assertThat(new PostgresInvalidIndexRule().evaluate(context(postgres)).status())
                .isEqualTo(PASS);
    }

    // --- DB-PG-002: sequence nearing exhaustion ---

    @Test
    void postgresSequenceRuleMeasuresAgainstTheOwningColumnCapacityNotTheSequenceMaximum() {
        // A bigint sequence feeding an integer column: 0% of the sequence's own range, 93% of the column's.
        PostgresSequenceUsage usage = new PostgresSequenceUsage(
                "public",
                "orders_id_seq",
                BigInteger.valueOf(2_000_000_000L),
                BigInteger.valueOf(Long.MAX_VALUE),
                BigInteger.valueOf(Integer.MAX_VALUE),
                false,
                "public",
                "orders",
                "id",
                "int4",
                1L,
                BigInteger.ONE,
                BigInteger.ONE,
                BigInteger.valueOf(Integer.MIN_VALUE),
                BigInteger.ONE);
        SchemaSnapshot postgres = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(VendorFindingKinds.POSTGRES_SEQUENCES, List.of(usage), false));
        DatabaseAdvisorRuleResultDto result = new PostgresSequenceExhaustionRule().evaluate(context(postgres));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0))
                .contains("orders_id_seq")
                .contains("public.orders.id")
                .contains("limited by its owning column type");
    }

    @Test
    void postgresSequenceRuleIgnoresCyclingSequences() {
        PostgresSequenceUsage usage = new PostgresSequenceUsage(
                "public",
                "tickets_seq",
                BigInteger.valueOf(999),
                BigInteger.valueOf(1000),
                null,
                true,
                null,
                null,
                null,
                null,
                1L,
                BigInteger.ONE,
                BigInteger.ONE,
                null,
                BigInteger.ONE);
        SchemaSnapshot postgres = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(VendorFindingKinds.POSTGRES_SEQUENCES, List.of(usage), false));
        assertThat(new PostgresSequenceExhaustionRule()
                        .evaluate(context(postgres))
                        .status())
                .isEqualTo(PASS);
    }

    @Test
    void postgresSequenceRuleComputesPercentagesWithoutOverflowingOnBigintRanges() {
        PostgresSequenceUsage usage = new PostgresSequenceUsage(
                "public",
                "events_seq",
                BigInteger.valueOf(Long.MAX_VALUE).subtract(BigInteger.ONE),
                BigInteger.valueOf(Long.MAX_VALUE),
                BigInteger.valueOf(Long.MAX_VALUE),
                false,
                "public",
                "events",
                "id",
                "int8",
                1L,
                BigInteger.ONE,
                BigInteger.ONE,
                BigInteger.valueOf(Long.MIN_VALUE),
                BigInteger.ONE);
        // A long-based (lastValue * 100) computation would overflow here and produce a negative percentage.
        assertThat(usage.percentUsed()).isEqualTo(99);
        SchemaSnapshot postgres = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(VendorFindingKinds.POSTGRES_SEQUENCES, List.of(usage), false));
        assertThat(new PostgresSequenceExhaustionRule()
                        .evaluate(context(postgres))
                        .status())
                .isEqualTo(VIOLATION);
    }

    @Test
    void postgresSequenceRuleSkipsWhenTheSequenceViewIsUnsupported() {
        SchemaSnapshot old = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.notApplicable(
                        VendorFindingKinds.POSTGRES_SEQUENCES, "The pg_sequences view requires PostgreSQL 10"));
        DatabaseAdvisorRuleResultDto result = new PostgresSequenceExhaustionRule().evaluate(context(old));
        assertThat(result.status()).isEqualTo(SKIPPED);
        assertThat(result.sampleViolations().get(0)).contains("pg_sequences");
    }

    // --- DB-PG-003: NOT VALID constraints ---

    @Test
    void postgresUnvalidatedConstraintRuleReportsForeignKeyAndCheckConstraints() {
        SchemaSnapshot postgres = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(
                        VendorFindingKinds.POSTGRES_UNVALIDATED_CONSTRAINTS,
                        List.of(
                                new PostgresUnvalidatedConstraint(
                                        "public", "orders", "fk_orders_customer", "f", "FOREIGN KEY ..."),
                                new PostgresUnvalidatedConstraint(
                                        "public", "orders", "ck_total_positive", "c", "CHECK ...")),
                        false));
        DatabaseAdvisorRuleResultDto result = new PostgresUnvalidatedConstraintRule().evaluate(context(postgres));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations().get(0)).contains("foreign key constraint fk_orders_customer");
        assertThat(result.sampleViolations().get(1)).contains("check constraint ck_total_positive");
    }

    @Test
    void postgresUnvalidatedConstraintRuleSkipsWithoutAPostgresDatasource() {
        assertThat(new PostgresUnvalidatedConstraintRule()
                        .evaluate(context(schema("ds", Dialect.MYSQL, List.of())))
                        .status())
                .isEqualTo(SKIPPED);
    }

    // --- DB-MYSQL-001: non-transactional storage engines ---

    @Test
    void mySqlEngineRuleFlagsNonTransactionalEnginesOnMySqlAndMariaDb() {
        SchemaSnapshot mysql = vendorSchema(
                "mysql-ds",
                Dialect.MYSQL,
                VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_TABLES,
                        List.of(new MySqlTableInfo("app", "legacy_sessions", "MyISAM", "utf8mb4_0900_ai_ci", null)),
                        false));
        SchemaSnapshot mariadb = vendorSchema(
                "mariadb-ds",
                Dialect.MARIADB,
                VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_TABLES,
                        List.of(new MySqlTableInfo("app", "cache_entries", "Aria", "utf8mb4_general_ci", null)),
                        false));
        DatabaseAdvisorRuleResultDto result = new MySqlNonInnodbEngineRule().evaluate(context(List.of(mysql, mariadb)));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations().get(0)).contains("legacy_sessions").contains("MyISAM");
        assertThat(result.sampleViolations().get(1)).contains("cache_entries").contains("Aria");
    }

    @Test
    void mySqlEngineRuleLeavesDeliberateSpecialistEnginesAlone() {
        SchemaSnapshot mysql = vendorSchema(
                "ds",
                Dialect.MYSQL,
                VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_TABLES,
                        List.of(
                                new MySqlTableInfo("app", "orders", "InnoDB", "utf8mb4_0900_ai_ci", null),
                                new MySqlTableInfo("app", "metrics", "ROCKSDB", "utf8mb4_0900_ai_ci", null),
                                new MySqlTableInfo("app", "remote_view", "FEDERATED", "utf8mb4_0900_ai_ci", null)),
                        false));
        assertThat(new MySqlNonInnodbEngineRule().evaluate(context(mysql)).status())
                .isEqualTo(PASS);
    }

    @Test
    void mySqlEngineRuleIsMediumSeverity() {
        assertThat(new MySqlNonInnodbEngineRule().definition().severity()).isEqualTo(DatabaseAdvisorRuleSupport.MEDIUM);
    }

    // --- DB-MYSQL-002: legacy utf8mb3 ---

    @Test
    void mySqlCharsetRuleFlagsUtf8mb3TableDefaultsAndColumnsOnly() {
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_TABLES,
                        List.of(
                                new MySqlTableInfo("app", "legacy", "InnoDB", "utf8mb3_general_ci", null),
                                new MySqlTableInfo("app", "ascii_codes", "InnoDB", "latin1_swedish_ci", null)),
                        false))
                .add(VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_COLUMN_CHARSETS,
                        List.of(
                                new MySqlColumnCharset("app", "users", "bio", "utf8", "utf8_general_ci"),
                                new MySqlColumnCharset("app", "users", "country", "latin1", "latin1_swedish_ci")),
                        false))
                .build();
        SchemaSnapshot mysql = schema("ds", Dialect.MYSQL, List.of(), findings);
        DatabaseAdvisorRuleResultDto result = new MySqlNonUtf8mb4CharsetRule().evaluate(context(mysql));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.violationCount()).isEqualTo(2);
        assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("app.legacy"));
        assertThat(result.sampleViolations()).anyMatch(detail -> detail.contains("app.users.bio"));
        assertThat(result.sampleViolations()).noneMatch(detail -> detail.contains("latin1"));
    }

    // --- DB-MYSQL-003: AUTO_INCREMENT exhaustion ---

    @Test
    void mySqlAutoIncrementRuleIsSignednessAware() {
        BigInteger nearSignedIntLimit = BigInteger.valueOf(2_000_000_000L);
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_TABLES,
                        List.of(
                                new MySqlTableInfo("app", "orders", "InnoDB", "utf8mb4_0900_ai_ci", nearSignedIntLimit),
                                new MySqlTableInfo(
                                        "app", "events", "InnoDB", "utf8mb4_0900_ai_ci", nearSignedIntLimit)),
                        false))
                .add(VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_AUTO_INCREMENT_COLUMNS,
                        List.of(
                                new MySqlAutoIncrementColumn("app", "orders", "id", "int", "int(11)"),
                                new MySqlAutoIncrementColumn("app", "events", "id", "int", "int(10) unsigned")),
                        false))
                .build();
        DatabaseAdvisorRuleResultDto result = new MySqlAutoIncrementExhaustionRule()
                .evaluate(context(schema("ds", Dialect.MYSQL, List.of(), findings)));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.sampleViolations().get(0)).contains("app.orders.id").contains("int(11)");
    }

    @Test
    void mySqlAutoIncrementRuleHandlesBigintUnsignedWithoutOverflow() {
        BigInteger huge = new BigInteger("18000000000000000000");
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_TABLES,
                        List.of(new MySqlTableInfo("app", "events", "InnoDB", "utf8mb4_0900_ai_ci", huge)),
                        false))
                .add(VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_AUTO_INCREMENT_COLUMNS,
                        List.of(new MySqlAutoIncrementColumn("app", "events", "id", "bigint", "bigint unsigned")),
                        false))
                .build();
        DatabaseAdvisorRuleResultDto result = new MySqlAutoIncrementExhaustionRule()
                .evaluate(context(schema("ds", Dialect.MARIADB, List.of(), findings)));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("97%");
    }

    @Test
    void mySqlAutoIncrementRuleSkipsTablesWhoseCounterTheServerDidNotReport() {
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_TABLES,
                        List.of(new MySqlTableInfo("app", "orders", "InnoDB", "utf8mb4_0900_ai_ci", null)),
                        false))
                .add(VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_AUTO_INCREMENT_COLUMNS,
                        List.of(new MySqlAutoIncrementColumn("app", "orders", "id", "int", "int(11)")),
                        false))
                .build();
        assertThat(new MySqlAutoIncrementExhaustionRule()
                        .evaluate(context(schema("ds", Dialect.MYSQL, List.of(), findings)))
                        .status())
                .isEqualTo(SKIPPED);
    }

    @Test
    void mySqlCharsetRuleRecommendsADialectAppropriateCollation() {
        VendorFindings mysqlFindings = VendorFindings.builder()
                .add(VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_COLUMN_CHARSETS,
                        List.of(new MySqlColumnCharset("app", "users", "bio", "utf8mb3", "utf8mb3_general_ci")),
                        false))
                .build();
        DatabaseAdvisorRuleResultDto mysqlResult = new MySqlNonUtf8mb4CharsetRule()
                .evaluate(context(schema("ds", Dialect.MYSQL, List.of(), mysqlFindings)));
        assertThat(mysqlResult.sampleViolations().get(0)).contains("utf8mb4_0900_ai_ci");

        VendorFindings mariadbFindings = VendorFindings.builder()
                .add(VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_COLUMN_CHARSETS,
                        List.of(new MySqlColumnCharset("app", "users", "bio", "utf8mb3", "utf8mb3_general_ci")),
                        false))
                .build();
        DatabaseAdvisorRuleResultDto mariadbResult = new MySqlNonUtf8mb4CharsetRule()
                .evaluate(context(schema("ds", Dialect.MARIADB, List.of(), mariadbFindings)));
        assertThat(mariadbResult.sampleViolations().get(0)).contains("utf8mb4_uca1400_ai_ci");
    }

    // --- DB-PG-001 uniqueness-impact note ---

    @Test
    void postgresInvalidIndexRuleDoesNotInferLostUniquenessEnforcement() {
        SchemaSnapshot postgres = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(
                        VendorFindingKinds.POSTGRES_INVALID_INDEXES,
                        List.of(new PostgresInvalidIndex("sales", "orders", "uq_broken", false, true, true, true)),
                        false));
        DatabaseAdvisorRuleResultDto result = new PostgresInvalidIndexRule().evaluate(context(postgres));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("UNIQUE").contains("may still reject");
    }

    @Test
    void postgresInvalidIndexRuleOmitsTheUniquenessNoteForANonUniqueIndex() {
        SchemaSnapshot postgres = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(
                        VendorFindingKinds.POSTGRES_INVALID_INDEXES,
                        List.of(new PostgresInvalidIndex("sales", "orders", "ix_broken", false, true, true, false)),
                        false));
        DatabaseAdvisorRuleResultDto result = new PostgresInvalidIndexRule().evaluate(context(postgres));
        assertThat(result.sampleViolations().get(0)).doesNotContain("UNIQUE");
    }

    // --- DB-PG-004: PostgreSQL table lacking usable replica identity ---

    @Test
    void postgresReplicaIdentityRuleFlagsAPublishedTableWithNoPrimaryKeyAndDefaultIdentity() {
        TableModel auditLog = DatabaseAdvisorFixtures.table("audit_log", List.of(), List.of(), List.of(), List.of());
        SchemaSnapshot postgres = schema(
                "ds",
                Dialect.POSTGRESQL,
                List.of(auditLog),
                VendorFindings.builder()
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES,
                                List.of(new PostgresReplicaIdentityCandidate("public", "audit_log", "d", true, false)),
                                false))
                        .build());
        DatabaseAdvisorRuleResultDto result = new PostgresReplicaIdentityRule().evaluate(context(postgres));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0))
                .contains("public.audit_log")
                .contains("no primary key");
    }

    @Test
    void postgresReplicaIdentityRuleFlagsATableExplicitlySetToNothing() {
        TableModel orders = DatabaseAdvisorFixtures.table("orders", List.of(), List.of("id"), List.of(), List.of());
        SchemaSnapshot postgres = schema(
                "ds",
                Dialect.POSTGRESQL,
                List.of(orders),
                VendorFindings.builder()
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES,
                                List.of(new PostgresReplicaIdentityCandidate("public", "orders", "n", true, false)),
                                false))
                        .build());
        DatabaseAdvisorRuleResultDto result = new PostgresReplicaIdentityRule().evaluate(context(postgres));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("NOTHING");
    }

    @Test
    void postgresReplicaIdentityRulePassesWhenTheTableHasAPrimaryKeyAndDefaultIdentity() {
        TableModel orders = DatabaseAdvisorFixtures.table("orders", List.of(), List.of("id"), List.of(), List.of());
        SchemaSnapshot postgres = schema(
                "ds",
                Dialect.POSTGRESQL,
                List.of(orders),
                VendorFindings.builder()
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES,
                                List.of(new PostgresReplicaIdentityCandidate("public", "orders", "d", true, false)),
                                false))
                        .build());
        assertThat(new PostgresReplicaIdentityRule().evaluate(context(postgres)).status())
                .isEqualTo(PASS);
    }

    @Test
    void postgresReplicaIdentityRuleDoesNotInferAMissingPrimaryKeyWhenMetadataWasUnreadable() {
        TableModel auditLog = new TableModel(
                "app",
                "public",
                "audit_log",
                "TABLE",
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                false,
                new TableMetadata(true, false, true, true, false, List.of("permission denied")));
        SchemaSnapshot postgres = schema(
                "ds",
                Dialect.POSTGRESQL,
                List.of(auditLog),
                VendorFindings.builder()
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES,
                                List.of(new PostgresReplicaIdentityCandidate("public", "audit_log", "d", true, false)),
                                false))
                        .build());

        assertThat(new PostgresReplicaIdentityRule().evaluate(context(postgres)).status())
                .isEqualTo(SKIPPED);
    }

    @Test
    void postgresReplicaIdentityRuleSkipsWhenNoTableIsInScopeOfAnyPublication() {
        SchemaSnapshot postgres = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(
                        VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES, List.of(), false));
        assertThat(new PostgresReplicaIdentityRule().evaluate(context(postgres)).status())
                .isEqualTo(SKIPPED);
    }

    @Test
    void postgresReplicaIdentityRuleSkipsWithoutAPostgresDatasource() {
        assertThat(new PostgresReplicaIdentityRule()
                        .evaluate(context(schema("ds", Dialect.MYSQL, List.of())))
                        .status())
                .isEqualTo(SKIPPED);
    }

    // --- DB-ORACLE-001: unusable Oracle indexes ---

    @Test
    void oracleUnusableIndexRuleFlagsAnOrdinaryUnusableIndex() {
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_INDEX_DETAILS,
                        List.of(new OracleIndexDetail(
                                "APP", "ORDERS", "IX_CUSTOMER", "NORMAL", false, "UNUSABLE", "VISIBLE", false, false)),
                        false));
        DatabaseAdvisorRuleResultDto result = new OracleUnusableIndexRule().evaluate(context(oracle));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("IX_CUSTOMER").contains("APP.ORDERS");
    }

    @Test
    void oracleUnusableIndexRuleExcludesDomainIndexes() {
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_INDEX_DETAILS,
                        List.of(new OracleIndexDetail(
                                "APP", "DOCS", "IX_TEXT", "DOMAIN", false, "UNUSABLE", "VISIBLE", false, false)),
                        false));
        assertThat(new OracleUnusableIndexRule().evaluate(context(oracle)).status())
                .isEqualTo(SKIPPED);
    }

    @Test
    void oracleUnusableIndexRuleReportsAnUnusablePartitionByName() {
        SchemaSnapshot oracle = schema(
                "ds",
                Dialect.ORACLE,
                List.of(),
                VendorFindings.builder()
                        .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_INDEX_DETAILS, List.of(), false))
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.ORACLE_INDEX_PARTITION_STATUS,
                                List.of(new OracleIndexPartitionStatus(
                                        "APP", "EVENTS", "IX_EVENTS", "P_2024_01", false, "UNUSABLE")),
                                false))
                        .build());
        DatabaseAdvisorRuleResultDto result = new OracleUnusableIndexRule().evaluate(context(oracle));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0))
                .contains("partition P_2024_01")
                .contains("IX_EVENTS");
    }

    @Test
    void oracleUnusableIndexRulePassesWhenEveryIndexIsValid() {
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_INDEX_DETAILS,
                        List.of(new OracleIndexDetail(
                                "APP", "ORDERS", "IX_CUSTOMER", "NORMAL", false, "VALID", "VISIBLE", false, false)),
                        false));
        assertThat(new OracleUnusableIndexRule().evaluate(context(oracle)).status())
                .isEqualTo(PASS);
    }

    // --- DB-ORACLE-002: disabled/unvalidated Oracle constraints ---

    @Test
    void oracleInvalidConstraintRuleFlagsADisabledForeignKey() {
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_CONSTRAINTS,
                        List.of(new OracleConstraintDetail(
                                "APP", "ORDERS", "FK_ORDERS_CUSTOMER", "R", "DISABLED", "NOT VALIDATED", false, null)),
                        false));
        DatabaseAdvisorRuleResultDto result = new OracleInvalidConstraintRule().evaluate(context(oracle));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0))
                .contains("FK_ORDERS_CUSTOMER")
                .contains("DISABLED / NOT VALIDATED");
    }

    @Test
    void oracleInvalidConstraintRuleFlagsAnEnabledButUnvalidatedConstraint() {
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_CONSTRAINTS,
                        List.of(new OracleConstraintDetail(
                                "APP", "ORDERS", "FK_ORDERS_CUSTOMER", "R", "ENABLED", "NOT VALIDATED", false, null)),
                        false));
        DatabaseAdvisorRuleResultDto result = new OracleInvalidConstraintRule().evaluate(context(oracle));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0))
                .contains("ENABLED / NOT VALIDATED")
                .contains("New writes are checked");
    }

    @Test
    void oracleInvalidConstraintRuleIncludesDisabledSystemGeneratedNotNullCheckConstraint() {
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_CONSTRAINTS,
                        List.of(new OracleConstraintDetail(
                                "APP",
                                "ORDERS",
                                "SYS_C0012345",
                                "C",
                                "DISABLED",
                                "NOT VALIDATED",
                                true,
                                "\"CUSTOMER_ID\" IS NOT NULL")),
                        false));
        assertThat(new OracleInvalidConstraintRule().evaluate(context(oracle)).status())
                .isEqualTo(VIOLATION);
    }

    @Test
    void oracleInvalidConstraintRuleDoesNotExcludeAnUnnamedUserCheckConstraint() {
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_CONSTRAINTS,
                        List.of(new OracleConstraintDetail(
                                "APP",
                                "ORDERS",
                                "SYS_C0099999",
                                "C",
                                "DISABLED",
                                "NOT VALIDATED",
                                true,
                                "\"TOTAL\" > 0")),
                        false));
        assertThat(new OracleInvalidConstraintRule().evaluate(context(oracle)).status())
                .isEqualTo(VIOLATION);
    }

    @Test
    void oracleInvalidConstraintRulePassesWhenEnabledAndValidated() {
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_CONSTRAINTS,
                        List.of(new OracleConstraintDetail(
                                "APP", "ORDERS", "FK_ORDERS_CUSTOMER", "R", "ENABLED", "VALIDATED", false, null)),
                        false));
        assertThat(new OracleInvalidConstraintRule().evaluate(context(oracle)).status())
                .isEqualTo(PASS);
    }

    // --- DB-ORACLE-003: sequence/identity generator nearing exhaustion ---

    @Test
    void oracleSequenceExhaustionRuleFlagsANonCyclingSequenceNearItsMaxValue() {
        OracleSequenceUsage sequence = new OracleSequenceUsage(
                "APP",
                "ORDERS_SEQ",
                java.math.BigInteger.valueOf(950),
                java.math.BigInteger.valueOf(1000),
                java.math.BigInteger.ZERO,
                java.math.BigInteger.ONE,
                false,
                false);
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(VendorFindingKinds.ORACLE_SEQUENCES, List.of(sequence), false));
        DatabaseAdvisorRuleResultDto result = new OracleSequenceExhaustionRule().evaluate(context(oracle));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("ORDERS_SEQ").contains("95%");
    }

    @Test
    void oracleSequenceExhaustionRuleNamesTheIdentityColumnWhenKnown() {
        OracleSequenceUsage sequence = new OracleSequenceUsage(
                "APP",
                "ISEQ$$_74522",
                java.math.BigInteger.valueOf(950),
                java.math.BigInteger.valueOf(1000),
                java.math.BigInteger.ONE,
                java.math.BigInteger.ONE,
                false,
                false);
        OracleIdentityColumn identityColumn = new OracleIdentityColumn("APP", "ORDERS", "ID", "ISEQ$$_74522");
        SchemaSnapshot oracle = schema(
                "ds",
                Dialect.ORACLE,
                List.of(),
                VendorFindings.builder()
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.ORACLE_SEQUENCES, List.of(sequence), false))
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.ORACLE_IDENTITY_COLUMNS, List.of(identityColumn), false))
                        .build());
        DatabaseAdvisorRuleResultDto result = new OracleSequenceExhaustionRule().evaluate(context(oracle));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("APP.ORDERS.ID");
    }

    @Test
    void oracleSequenceExhaustionRuleIgnoresCyclingSequences() {
        OracleSequenceUsage sequence = new OracleSequenceUsage(
                "APP",
                "ORDERS_SEQ",
                java.math.BigInteger.valueOf(999),
                java.math.BigInteger.valueOf(1000),
                java.math.BigInteger.ONE,
                java.math.BigInteger.ONE,
                true,
                false);
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(VendorFindingKinds.ORACLE_SEQUENCES, List.of(sequence), false));
        assertThat(new OracleSequenceExhaustionRule().evaluate(context(oracle)).status())
                .isEqualTo(SKIPPED);
    }

    @Test
    void oracleSequenceExhaustionRuleExcludesSessionScalableAndShardedSequences() {
        OracleSequenceUsage sequence = new OracleSequenceUsage(
                "APP",
                "SCALABLE_SEQ",
                java.math.BigInteger.valueOf(999),
                java.math.BigInteger.valueOf(1000),
                java.math.BigInteger.ONE,
                java.math.BigInteger.ONE,
                false,
                true);
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(VendorFindingKinds.ORACLE_SEQUENCES, List.of(sequence), false));
        assertThat(new OracleSequenceExhaustionRule().evaluate(context(oracle)).status())
                .isEqualTo(SKIPPED);
    }

    @Test
    void postgresInvalidFlagsMustBeExplicitAndUnknownDoesNotMeanInvalid() {
        SchemaSnapshot postgres = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(
                        VendorFindingKinds.POSTGRES_INVALID_INDEXES,
                        List.of(new PostgresInvalidIndex("public", "t", "idx", (Boolean) null, null, null, null)),
                        false));
        assertThat(new PostgresInvalidIndexRule().evaluate(context(postgres)).status())
                .isEqualTo(SKIPPED);
    }

    @Test
    void postgresSequenceProgressUsesStartDirectionIncrementAndExactThreshold() {
        assertThat(pgSequence(679, 600, 0, 700, 1, null, null, false).percentUsed())
                .isEqualTo(79);
        assertThat(pgSequence(680, 600, 0, 700, 1, null, null, false).percentUsed())
                .isEqualTo(80);
        assertThat(pgSequence(-680, -600, -700, 0, -1, null, null, false).percentUsed())
                .isEqualTo(80);
        assertThat(pgSequence(670, 600, 0, 700, 10, null, null, false).percentUsed())
                .isEqualTo(70);
        assertThat(pgSequence(-10, -10, -100, -1, -1, null, null, false).percentUsed())
                .isZero();
        assertThat(pgSequence(700, 600, 0, 700, 0, null, null, false).percentUsed())
                .isEqualTo(-1);
        assertThat(pgSequence(600, 600, 0, 700, 1, BigInteger.valueOf(100), null, false)
                        .percentUsed())
                .isEqualTo(100);
    }

    @Test
    void postgresCyclingSequenceCanReachNarrowerOwningColumnBeforeWrap() {
        PostgresSequenceUsage sequence =
                pgSequence(80, 0, -1000, 1000, 1, BigInteger.valueOf(100), BigInteger.valueOf(-101), true);
        assertThat(new PostgresSequenceExhaustionRule()
                        .evaluate(context(vendorSchema(
                                "ds",
                                Dialect.POSTGRESQL,
                                VendorAugmentation.available(
                                        VendorFindingKinds.POSTGRES_SEQUENCES, List.of(sequence), false)))))
                .extracting(DatabaseAdvisorRuleResultDto::status)
                .isEqualTo(VIOLATION);
        assertThat(pgSequence(-81, 0, -1000, 1000, -1, BigInteger.valueOf(100), BigInteger.valueOf(-100), true)
                        .percentUsed())
                .isEqualTo(81);
    }

    @Test
    void postgresHiddenCounterRetainsDefinitionButSkipsHeadroom() {
        PostgresSequenceUsage sequence = new PostgresSequenceUsage(
                "public",
                "hidden",
                null,
                BigInteger.valueOf(1000),
                null,
                false,
                null,
                null,
                null,
                null,
                1L,
                BigInteger.ONE,
                BigInteger.ONE,
                null,
                BigInteger.valueOf(100));
        assertThat(sequence.incrementBy()).isEqualTo(1L);
        assertThat(sequence.percentUsed()).isEqualTo(-1);
        assertThat(new PostgresSequenceExhaustionRule()
                        .evaluate(context(vendorSchema(
                                "ds",
                                Dialect.POSTGRESQL,
                                VendorAugmentation.available(
                                        VendorFindingKinds.POSTGRES_SEQUENCES, List.of(sequence), false))))
                        .status())
                .isEqualTo(SKIPPED);
    }

    @Test
    void postgresNotEnforcedConstraintDoesNotClaimNewWritesAreChecked() {
        SchemaSnapshot postgres = vendorSchema(
                "ds",
                Dialect.POSTGRESQL,
                VendorAugmentation.available(
                        VendorFindingKinds.POSTGRES_UNVALIDATED_CONSTRAINTS,
                        List.of(new PostgresUnvalidatedConstraint("public", "t", "check_it", "c", "CHECK", false)),
                        false));
        DatabaseAdvisorRuleResultDto result = new PostgresUnvalidatedConstraintRule().evaluate(context(postgres));
        assertThat(result.severity()).isEqualTo(DatabaseAdvisorRuleSupport.MEDIUM);
        assertThat(result.sampleViolations().get(0))
                .contains("NOT ENFORCED")
                .doesNotContain("never been", "New writes are checked");
        assertThat(PostgresCatalogReader.unvalidatedConstraintsSql(new DatabaseVersion(17, 0, 0, "17")))
                .contains("true as is_enforced")
                .doesNotContain("c.conenforced");
        assertThat(PostgresCatalogReader.unvalidatedConstraintsSql(new DatabaseVersion(18, 0, 0, "18")))
                .contains("c.conenforced as is_enforced");
        assertThat(PostgresCatalogReader.unvalidatedConstraintsSql(DatabaseVersion.UNKNOWN))
                .contains("null::boolean as is_enforced");
    }

    @Test
    void publicationInsertOnlyAndFullIdentityAreNotFindingsButUnknownIdentityIsSkipped() {
        assertThat(replicaResult(new PostgresReplicaIdentityCandidate("public", "t", "n", false, false))
                        .status())
                .isEqualTo(PASS);
        assertThat(replicaResult(new PostgresReplicaIdentityCandidate("public", "t", "f", true, false))
                        .status())
                .isEqualTo(PASS);
        assertThat(replicaResult(new PostgresReplicaIdentityCandidate("public", "t", null, true, null))
                        .status())
                .isEqualTo(SKIPPED);
        assertThat(replicaResult(new PostgresReplicaIdentityCandidate("public", "t", "i", true, null))
                        .status())
                .isEqualTo(SKIPPED);
        assertThat(replicaResult(new PostgresReplicaIdentityCandidate("public", "t", "i", true, false))
                        .status())
                .isEqualTo(VIOLATION);
        assertThat(replicaResult(new PostgresReplicaIdentityCandidate("public", "t", "i", true, true))
                        .status())
                .isEqualTo(PASS);
    }

    @Test
    void missingMysqlCounterFamilyCannotPassAndCounterIdentityIsExact() {
        SchemaSnapshot missing = vendorSchema(
                "ds",
                Dialect.MYSQL,
                VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_AUTO_INCREMENT_COLUMNS,
                        List.of(new MySqlAutoIncrementColumn("app", "Orders", "id", "int", "int")),
                        false));
        assertThat(new MySqlAutoIncrementExhaustionRule()
                        .evaluate(context(missing))
                        .status())
                .isEqualTo(SKIPPED);
        SchemaSnapshot collision = schema(
                "ds",
                Dialect.MYSQL,
                List.of(),
                VendorFindings.builder()
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.MYSQL_TABLES,
                                List.of(new MySqlTableInfo("app", "orders", "InnoDB", null, BigInteger.TEN)),
                                false))
                        .build());
        assertThat(MySqlCatalogReader.nextAutoIncrement(collision, "app", "Orders"))
                .isNull();
    }

    @Test
    void mysqlCounterThresholdIsExactlyEightyPercent() {
        for (int counter : new int[] {101, 102}) {
            SchemaSnapshot mysql = schema(
                    "ds",
                    Dialect.MYSQL,
                    List.of(),
                    VendorFindings.builder()
                            .add(VendorAugmentation.available(
                                    VendorFindingKinds.MYSQL_TABLES,
                                    List.of(new MySqlTableInfo(
                                            "app", "t", "InnoDB", null, BigInteger.valueOf(counter))),
                                    false))
                            .add(VendorAugmentation.available(
                                    VendorFindingKinds.MYSQL_AUTO_INCREMENT_COLUMNS,
                                    List.of(new MySqlAutoIncrementColumn("app", "t", "id", "tinyint", "tinyint")),
                                    false))
                            .build());
            assertThat(new MySqlAutoIncrementExhaustionRule()
                            .evaluate(context(mysql))
                            .status())
                    .isEqualTo(counter == 101 ? PASS : VIOLATION);
        }
    }

    @Test
    void charsetTableEvidenceSurvivesMissingColumnFamily() {
        SchemaSnapshot tableOnly = vendorSchema(
                "ds",
                Dialect.MARIADB,
                VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_TABLES,
                        List.of(new MySqlTableInfo("app", "t", "InnoDB", "utf8mb3_general_ci", null)),
                        false));
        DatabaseAdvisorContext context = context(tableOnly);
        DatabaseAdvisorRuleResultDto result = new MySqlNonUtf8mb4CharsetRule().evaluate(context);
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0))
                .contains("11.4.5", "aliases", "comparison semantics")
                .doesNotContain("does not exist on MariaDB");
        assertThat(context.evaluationDiagnostics()).isNotEmpty();
    }

    @Test
    void mariaDbCounterPersistenceBoundaryAndAriaCrashSafetyAreAccurate() {
        assertThat(MySqlAutoIncrementExhaustionRule.persistenceNote(
                        Dialect.MARIADB, new DatabaseVersion(10, 2, 3, "10.2.3")))
                .contains("reconstructed after restart");
        assertThat(MySqlAutoIncrementExhaustionRule.persistenceNote(
                        Dialect.MARIADB, new DatabaseVersion(10, 2, 4, "10.2.4")))
                .contains("persistent", "not transactional");
        assertThat(MySqlAutoIncrementExhaustionRule.persistenceNote(
                        Dialect.MARIADB, new DatabaseVersion(10, 2, -1, "10.2")))
                .contains("depends on version");
        SchemaSnapshot aria = vendorSchema(
                "ds",
                Dialect.MARIADB,
                VendorAugmentation.available(
                        VendorFindingKinds.MYSQL_TABLES,
                        List.of(new MySqlTableInfo("app", "t", "Aria", null, null)),
                        false));
        assertThat(new MySqlNonInnodbEngineRule()
                        .evaluate(context(aria))
                        .sampleViolations()
                        .get(0))
                .contains("can provide crash safety")
                .doesNotContain("not transactional or crash-safe");
    }

    @Test
    void oracleUnknownAndNAIndexStatesAreNotUnusable() {
        for (String status : new String[] {null, "N/A", "OTHER"}) {
            SchemaSnapshot oracle = vendorSchema(
                    "ds",
                    Dialect.ORACLE,
                    VendorAugmentation.available(
                            VendorFindingKinds.ORACLE_INDEX_DETAILS,
                            List.of(new OracleIndexDetail(
                                    "APP", "T", "IDX", "NORMAL", false, status, "VISIBLE", false, false)),
                            false));
            assertThat(new OracleUnusableIndexRule().evaluate(context(oracle)).status())
                    .isEqualTo(SKIPPED);
        }
    }

    @Test
    void oracleMissingPartitionCatalogDoesNotConfirmUsability() {
        SchemaSnapshot oracle = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_INDEX_DETAILS,
                        List.of(new OracleIndexDetail(
                                "APP", "T", "IDX", "NORMAL", false, "N/A", "VISIBLE", false, true)),
                        false));
        DatabaseAdvisorContext context = context(oracle);
        assertThat(new OracleUnusableIndexRule().evaluate(context).status()).isEqualTo(SKIPPED);
        assertThat(context.evaluationDiagnostics()).isNotEmpty();
    }

    @Test
    void oracleUnknownConstraintStatesSkipAndDisableValidateDoesNotClaimUnrestrictedDml() {
        SchemaSnapshot unknown = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_CONSTRAINTS,
                        List.of(new OracleConstraintDetail("APP", "T", "C", "U", null, "OTHER", false, null)),
                        false));
        assertThat(new OracleInvalidConstraintRule().evaluate(context(unknown)).status())
                .isEqualTo(SKIPPED);
        SchemaSnapshot validated = vendorSchema(
                "ds",
                Dialect.ORACLE,
                VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_CONSTRAINTS,
                        List.of(new OracleConstraintDetail("APP", "T", "C", "U", "DISABLED", "VALIDATED", false, null)),
                        false));
        assertThat(new OracleInvalidConstraintRule()
                        .evaluate(context(validated))
                        .sampleViolations()
                        .get(0))
                .contains("can restrict DML")
                .doesNotContain("enforces nothing");
    }

    @Test
    void oracleSequenceHandlesDescendingThresholdAndNumberIdentityLimits() {
        OracleSequenceUsage descending = new OracleSequenceUsage(
                "APP",
                "S",
                BigInteger.valueOf(-800),
                BigInteger.ZERO,
                BigInteger.valueOf(-1000),
                BigInteger.valueOf(-1),
                false,
                false);
        assertThat(descending.percentUsed()).isEqualTo(80);
        assertThat(new OracleSequenceUsage(
                                "APP",
                                "S",
                                BigInteger.valueOf(-790),
                                BigInteger.ZERO,
                                BigInteger.valueOf(-1000),
                                BigInteger.valueOf(-1),
                                false,
                                false)
                        .percentUsed())
                .isEqualTo(79);
        BigInteger huge = BigInteger.TEN.pow(28).subtract(BigInteger.ONE);
        assertThat(new OracleSequenceUsage(
                                "APP",
                                "S",
                                huge.subtract(BigInteger.ONE),
                                huge,
                                BigInteger.ONE,
                                BigInteger.ONE,
                                false,
                                false)
                        .percentUsed())
                .isEqualTo(99);
        OracleIdentityColumn identity = new OracleIdentityColumn("APP", "T", "ID", "S", "NUMBER", 5, 0);
        assertThat(identity.capacity()).isEqualTo(BigInteger.valueOf(99999));
        assertThat(new OracleIdentityColumn("APP", "T", "ID", "S", "NUMBER", null, null).capacity())
                .isNull();
        assertThat(new OracleIdentityColumn("APP", "T", "ID", "S", "NUMBER", 5, null).capacity())
                .isNull();
        OracleSequenceUsage sequence = new OracleSequenceUsage(
                "APP",
                "S",
                BigInteger.valueOf(80000),
                BigInteger.TEN.pow(28).subtract(BigInteger.ONE),
                BigInteger.ONE,
                BigInteger.ONE,
                true,
                false);
        assertThat(sequence.percentUsed(identity)).isEqualTo(80);
        SchemaSnapshot oracle = schema(
                "ds",
                Dialect.ORACLE,
                List.of(),
                VendorFindings.builder()
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.ORACLE_SEQUENCES, List.of(sequence), false))
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.ORACLE_IDENTITY_COLUMNS, List.of(identity), false))
                        .build());
        DatabaseAdvisorRuleResultDto result = new OracleSequenceExhaustionRule().evaluate(context(oracle));
        assertThat(result.status()).isEqualTo(VIOLATION);
        assertThat(result.sampleViolations().get(0)).contains("APP.T.ID", "99999", "Cache reservation");
        assertThat(new OracleSequenceExhaustionRule().definition().recommendation())
                .contains("ALTER TABLE")
                .doesNotContain("restart the sequence after archiving");
    }

    @Test
    void oracleHiddenSequenceCounterIsUnknownAndOwnersAreSchemaQualified() {
        OracleSequenceUsage sequence = new OracleSequenceUsage(
                "APP", "S", null, BigInteger.valueOf(1000), BigInteger.ZERO, BigInteger.ONE, false, false);
        assertThat(sequence.percentUsed()).isEqualTo(-1);
        assertThat(new OracleSequenceExhaustionRule()
                        .evaluate(context(vendorSchema(
                                "ds",
                                Dialect.ORACLE,
                                VendorAugmentation.available(
                                        VendorFindingKinds.ORACLE_SEQUENCES, List.of(sequence), false))))
                        .status())
                .isEqualTo(SKIPPED);
    }

    @Test
    void oracleIdentityLinkDoesNotMatchAnotherSchemasSequenceWithTheSameName() {
        OracleSequenceUsage sequence = new OracleSequenceUsage(
                "APP",
                "S",
                BigInteger.valueOf(80000),
                BigInteger.TEN.pow(28),
                BigInteger.ONE,
                BigInteger.ONE,
                false,
                false);
        OracleIdentityColumn unrelated = new OracleIdentityColumn("OTHER", "T", "ID", "S", "NUMBER", 5, 0);
        SchemaSnapshot oracle = schema(
                "ds",
                Dialect.ORACLE,
                List.of(),
                VendorFindings.builder()
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.ORACLE_SEQUENCES, List.of(sequence), false))
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.ORACLE_IDENTITY_COLUMNS, List.of(unrelated), false))
                        .build());
        assertThat(new OracleSequenceExhaustionRule().evaluate(context(oracle)).status())
                .isEqualTo(PASS);
    }

    @Test
    void oracleIdentityRangeClampsBothEndpointsForAscendingAndDescendingSequences() {
        BigInteger broadBound = BigInteger.TEN.pow(27);
        OracleIdentityColumn identity = new OracleIdentityColumn("APP", "T", "ID", "S", "NUMBER", 5, 0);
        for (int direction : new int[] {1, -1}) {
            OracleSequenceUsage midpoint = new OracleSequenceUsage(
                    "APP",
                    "S",
                    BigInteger.ZERO,
                    broadBound,
                    broadBound.negate(),
                    BigInteger.valueOf(direction),
                    false,
                    false);
            OracleSequenceUsage belowThreshold = new OracleSequenceUsage(
                    "APP",
                    "S",
                    BigInteger.valueOf(59999L * direction),
                    broadBound,
                    broadBound.negate(),
                    BigInteger.valueOf(direction),
                    false,
                    false);
            OracleSequenceUsage atThreshold = new OracleSequenceUsage(
                    "APP",
                    "S",
                    BigInteger.valueOf(60000L * direction),
                    broadBound,
                    broadBound.negate(),
                    BigInteger.valueOf(direction),
                    false,
                    false);
            assertThat(midpoint.percentUsed(identity)).isEqualTo(50);
            assertThat(belowThreshold.percentUsed(identity)).isEqualTo(79);
            assertThat(atThreshold.percentUsed(identity)).isEqualTo(80);
        }
    }

    @Test
    void oracleIdentityRangeClampingDoesNotInventUnknownCounterOrPrecision() {
        BigInteger broadBound = BigInteger.TEN.pow(27);
        OracleSequenceUsage hidden = new OracleSequenceUsage(
                "APP", "S", null, broadBound, broadBound.negate(), BigInteger.ONE, false, false);
        OracleIdentityColumn known = new OracleIdentityColumn("APP", "T", "ID", "S", "NUMBER", 5, 0);
        assertThat(hidden.percentUsed(known)).isEqualTo(-1);
        OracleIdentityColumn unknown = new OracleIdentityColumn("APP", "T", "ID", "S", "NUMBER", 5, null);
        OracleSequenceUsage sequence = new OracleSequenceUsage(
                "APP", "S", BigInteger.ZERO, broadBound, broadBound.negate(), BigInteger.ONE, false, false);
        assertThat(sequence.percentUsed(unknown)).isEqualTo(sequence.percentUsed());
        SchemaSnapshot oracle = schema(
                "ds",
                Dialect.ORACLE,
                List.of(),
                VendorFindings.builder()
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.ORACLE_SEQUENCES, List.of(sequence), false))
                        .add(VendorAugmentation.available(
                                VendorFindingKinds.ORACLE_IDENTITY_COLUMNS, List.of(unknown), false))
                        .build());
        assertThat(new OracleSequenceExhaustionRule().evaluate(context(oracle)).status())
                .isEqualTo(SKIPPED);
    }

    private static PostgresSequenceUsage pgSequence(
            long frontier,
            long start,
            long min,
            long max,
            long increment,
            BigInteger columnMax,
            BigInteger columnMin,
            boolean cycle) {
        return new PostgresSequenceUsage(
                "public",
                "s",
                BigInteger.valueOf(frontier),
                BigInteger.valueOf(max),
                columnMax,
                cycle,
                "public",
                "t",
                "id",
                "int4",
                increment,
                BigInteger.valueOf(min),
                BigInteger.valueOf(start),
                columnMin,
                BigInteger.valueOf(100));
    }

    private static DatabaseAdvisorRuleResultDto replicaResult(PostgresReplicaIdentityCandidate candidate) {
        return new PostgresReplicaIdentityRule()
                .evaluate(context(vendorSchema(
                        "ds",
                        Dialect.POSTGRESQL,
                        VendorAugmentation.available(
                                VendorFindingKinds.POSTGRES_REPLICA_IDENTITY_CANDIDATES, List.of(candidate), false))));
    }
}
